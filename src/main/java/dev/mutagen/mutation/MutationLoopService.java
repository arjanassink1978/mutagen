package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.generator.PromptBuilder;
import dev.mutagen.llm.client.LlmClient;
import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;
import dev.mutagen.mutation.model.Mutant;
import dev.mutagen.mutation.model.MutationReport;
import dev.mutagen.skill.Skill;
import dev.mutagen.skill.SkillLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the mutation testing loop:
 * <ol>
 *   <li>Write test files to the target repo</li>
 *   <li>Run Pitest via {@link MutationRunner}</li>
 *   <li>If score &lt; threshold: ask LLM to fill mutation gaps</li>
 *   <li>Repeat up to {@code maxIterations}</li>
 * </ol>
 */
public class MutationLoopService {

    private static final Logger log = LoggerFactory.getLogger(MutationLoopService.class);

    @FunctionalInterface
    interface BackendStarterFactory {
        BackendStarter create(Path repoPath);
    }

    private final LlmClient              llmClient;
    private final SkillLoader            skillLoader;
    private final MutationRunner         mutationRunner;
    private final MutationReportParser   reportParser;
    private final PromptBuilder          promptBuilder;
    private final BackendStarterFactory  backendFactory;

    public MutationLoopService(LlmClient llmClient,
                                SkillLoader skillLoader,
                                MutationRunner mutationRunner) {
        this(llmClient, skillLoader, mutationRunner, BackendStarter::detect);
    }

    MutationLoopService(LlmClient llmClient,
                        SkillLoader skillLoader,
                        MutationRunner mutationRunner,
                        BackendStarterFactory backendFactory) {
        this.llmClient      = llmClient;
        this.skillLoader    = skillLoader;
        this.mutationRunner = mutationRunner;
        this.reportParser   = new MutationReportParser();
        this.promptBuilder  = new PromptBuilder();
        this.backendFactory = backendFactory;
    }

    /**
     * Runs the mutation loop and returns the final result.
     * Starts the target backend before running Pitest and stops it afterwards.
     *
     * @param tests         initial generated tests (in-memory; will be written to disk each iteration)
     * @param repoPath      root of the target Spring Boot project
     * @param threshold     target mutation score (0–100)
     * @param maxIterations maximum number of pitest runs
     */
    public MutationLoopResult run(List<GeneratedTest> tests,
                                  Path repoPath,
                                  int threshold,
                                  int maxIterations) throws IOException {

        List<GeneratedTest> current = new ArrayList<>(tests);
        MutationReport report       = null;
        double initialScore         = -1;
        int port                    = 0;

        BackendStarter backend = backendFactory.create(repoPath);
        try {
            port = backend.start();
            log.info("Backend started on port {}", port);

            current = validateAndFix(current, repoPath, port);

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                log.info("Mutation loop — iteration {}/{}", iteration, maxIterations);

                writeTestFiles(current, repoPath, port);

                Path reportPath = mutationRunner.run(current, repoPath);
                report          = reportParser.parse(reportPath);

                double score = report.getMutationScore();
                log.info("  Score: {}% (target: {}%, killed: {}, survived: {}, no-coverage: {})",
                        String.format("%.1f", score), threshold,
                        report.getKilledCount(), report.getSurvivedCount(), report.getNoCoverageCount());

                if (iteration == 1) initialScore = score;

                if (score >= threshold) {
                    log.info("  Threshold met — stopping");
                    return new MutationLoopResult(current, initialScore, score, iteration, report, port);
                }

                if (iteration == maxIterations) break;

                List<Mutant> survived = report.getSurvivedMutants();
                if (survived.isEmpty()) break;

                log.info("  Asking LLM to fill {} surviving mutant(s)...", survived.size());
                current = augmentTests(current, survived, (int) score, threshold);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for backend", e);
        } finally {
            backend.stop();
        }

        double finalScore = report != null ? report.getMutationScore() : 0;
        return new MutationLoopResult(current, initialScore, finalScore, maxIterations, report, port);
    }

    // ---------------------------------------------------------------

    /**
     * Runs the generated tests against the live backend and asks the LLM to fix any failures.
     * Repeats up to 3 times until all tests pass.
     */
    private List<GeneratedTest> validateAndFix(List<GeneratedTest> tests,
                                                Path repoPath, int port) throws IOException, InterruptedException {
        if (!repoPath.resolve("pom.xml").toFile().exists()) {
            log.debug("No pom.xml in {} — skipping pre-flight test validation", repoPath);
            writeTestFiles(tests, repoPath, port);
            return tests;
        }

        final int MAX_FIX_ATTEMPTS = 3;
        List<GeneratedTest> current = new ArrayList<>(tests);

        for (int attempt = 1; attempt <= MAX_FIX_ATTEMPTS; attempt++) {
            writeTestFiles(current, repoPath, port);
            Map<String, String> failures = runTestsAndCollectFailures(current, repoPath);
            if (failures.isEmpty()) {
                log.info("All tests pass — proceeding to mutation testing");
                return current;
            }
            log.info("  {} test class(es) failing (fix attempt {}/{})",
                    failures.size(), attempt, MAX_FIX_ATTEMPTS);
            current = fixFailingTests(current, failures);
        }
        log.warn("Tests still failing after fix attempts — proceeding to Pitest anyway");
        writeTestFiles(current, repoPath, port);
        return current;
    }

    /**
     * Runs {@code mvn test} for the given test classes and returns a map of
     * className → failure summary (non-empty means failures).
     */
    private Map<String, String> runTestsAndCollectFailures(List<GeneratedTest> tests,
                                                            Path repoPath) throws IOException, InterruptedException {
        String testClassArg = tests.stream()
                .map(GeneratedTest::getTestClassName)
                .collect(Collectors.joining(","));

        // Delete stale surefire reports so we only read results from this run
        Path surefireDir = repoPath.resolve("target/surefire-reports");
        if (Files.isDirectory(surefireDir)) {
            try (var existing = Files.list(surefireDir)) {
                existing.filter(p -> p.getFileName().toString().startsWith("TEST-"))
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }

        List<String> cmd = List.of("mvn", "test", "-Dtest=" + testClassArg,
                "-DfailIfNoTests=false", "-q");
        log.info("Running tests: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(repoPath.toFile())
                .redirectErrorStream(true);

        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode  = proc.waitFor();

        if (exitCode == 0) return Map.of();

        // Parse Surefire XML reports for failure details
        return parseSurefireFailures(repoPath, output);
    }

    private Map<String, String> parseSurefireFailures(Path repoPath, String mvnOutput) {
        Map<String, String> failures = new LinkedHashMap<>();
        Path surefireDir = repoPath.resolve("target/surefire-reports");
        if (!Files.isDirectory(surefireDir)) {
            // No reports yet (compile failure) — extract from mvn output directly
            failures.put("compile-error", truncate(mvnOutput, 2000));
            return failures;
        }

        try (var paths = Files.list(surefireDir)) {
            paths.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .forEach(xmlPath -> {
                        try {
                            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                            Document doc = factory.newDocumentBuilder().parse(xmlPath.toFile());
                            String fqcn = doc.getDocumentElement().getAttribute("name");
                            // Use simple class name as key (GeneratedTest.getTestClassName() returns simple name)
                            String className = fqcn.contains(".")
                                    ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;

                            NodeList testcases = doc.getElementsByTagName("testcase");
                            StringBuilder failureSummary = new StringBuilder();
                            for (int i = 0; i < testcases.getLength(); i++) {
                                Element tc = (Element) testcases.item(i);
                                NodeList failureNodes = tc.getElementsByTagName("failure");
                                NodeList errorNodes   = tc.getElementsByTagName("error");
                                NodeList relevant = failureNodes.getLength() > 0 ? failureNodes : errorNodes;
                                if (relevant.getLength() > 0) {
                                    failureSummary.append("FAIL: ").append(tc.getAttribute("name")).append("\n");
                                    String msg = ((Element) relevant.item(0)).getAttribute("message");
                                    if (msg != null && !msg.isBlank()) {
                                        failureSummary.append("  ").append(truncate(msg, 300)).append("\n");
                                    }
                                }
                            }
                            if (!failureSummary.isEmpty()) {
                                failures.put(className, failureSummary.toString());
                            }
                        } catch (Exception e) {
                            log.debug("Could not parse {}: {}", xmlPath, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list surefire-reports: {}", e.getMessage());
        }

        return failures;
    }

    /**
     * Asks the LLM to fix each failing test class given the failure summary.
     */
    private List<GeneratedTest> fixFailingTests(List<GeneratedTest> tests,
                                                  Map<String, String> failures) {
        Skill skill = skillLoader.load(Skill.Type.RESTASSURED_TEST);
        return tests.stream().map(test -> {
            String failureSummary = failures.get(test.getTestClassName());
            if (failureSummary == null) {
                // Check if it's a compile-error affecting all
                failureSummary = failures.get("compile-error");
            }
            if (failureSummary == null) return test;

            String prompt = """
                    The following RestAssured integration test class has failing tests.
                    The tests run against a LIVE backend. Fix ONLY the failing assertions based on what the backend actually returns.

                    ## Failing tests (with actual backend response)
                    %s

                    ## Current test code
                    ```java
                    %s
                    ```

                    ## Fix rules
                    - Adjust the `.statusCode(...)` assertion to match what the backend actually returns
                    - If a test expected 404 but backend returned 403 → expect 403 (authorization issue, not a bug in the test)
                    - If a test expected 400 but backend returned 500 → expect 500 (backend bug, adjust expectation)
                    - If a test expected 200/201 but backend returned 400 → the request body is wrong. Remove any fields that cause 400
                    - If backend returns 501 (NOT_IMPLEMENTED) → change `.statusCode(...)` to `anyOf(is(200), is(404), is(501))`
                    - If a test expected 400 but backend returned 404 → change to 404
                    - If a GET request returned 415 → add `.contentType(ContentType.JSON)` to the request
                    - Do NOT change tests that are already passing
                    - Return the complete fixed test class (same format: starts with `package`, ends with `}`)
                    """.formatted(failureSummary, test.getSourceCode());

            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(skill.getContent())
                    .userPrompt(prompt)
                    .maxTokens(4096)
                    .temperature(0.1f)
                    .build();

            try {
                LlmResponse response = llmClient.complete(request);
                String fixed = sanitizeOutput(response.getContent());
                log.info("  Fixed: {}", test.getTestClassName());
                return test.withSourceCode(fixed);
            } catch (Exception e) {
                log.warn("  Could not fix {}: {}", test.getTestClassName(), e.getMessage());
                return test;
            }
        }).toList();
    }

    private String sanitizeOutput(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence    = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int packageIndex = trimmed.indexOf("package ");
        if (packageIndex > 0) trimmed = trimmed.substring(packageIndex);
        return trimmed;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------

    private List<GeneratedTest> augmentTests(List<GeneratedTest> tests,
                                              List<Mutant> survived,
                                              int currentScore,
                                              int targetScore) {
        Skill skill = skillLoader.load(Skill.Type.MUTATION_GAP_ANALYSIS);

        return tests.stream().map(test -> {
            List<Mutant> relevant = survived.stream()
                    .filter(m -> isRelevant(m, test))
                    .toList();

            if (relevant.isEmpty()) return test;

            String survivingText = formatMutants(relevant);
            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(skill.getContent())
                    .userPrompt(promptBuilder.buildMutationGapPrompt(
                            test.getSourceCode(), survivingText, currentScore, targetScore))
                    .maxTokens(2048)
                    .temperature(0.1f)
                    .build();

            try {
                LlmResponse response = llmClient.complete(request);
                String extraMethods  = response.getContent().strip();
                int added = countTestMethods(extraMethods);
                log.info("  + {} @Test method(s) added for {}", added, test.getTestClassName());
                return test.withSourceCode(appendTestMethods(test.getSourceCode(), extraMethods));
            } catch (Exception e) {
                log.warn("  LLM augmentation failed for {}: {}", test.getTestClassName(), e.getMessage());
                return test;
            }
        }).toList();
    }

    /**
     * Writes test files for the inline Pitest run.
     *
     * <p>Tests generated by the LLM extend {@code AbstractIT} (suitable for the final output
     * module). For the inline Pitest run we need self-contained classes: Pitest forks its own
     * JVM and may not invoke {@code @BeforeAll} methods declared in abstract parent classes,
     * which would leave {@code RestAssured.port} unset and break all HTTP calls.
     *
     * <p>We therefore write a concrete, standalone version of each test class that inlines the
     * RestAssured setup instead of inheriting it.
     */
    private void writeTestFiles(List<GeneratedTest> tests, Path repoPath, int port)
            throws IOException {
        for (GeneratedTest test : tests) {
            String source = test.getSourceCode()
                    .replace("__BACKEND_PORT__", String.valueOf(port));
            source = inlineRestAssuredSetup(source, port);
            Path target = repoPath.resolve(test.getRelativeFilePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, source);
            log.debug("  Wrote {}", test.getRelativeFilePath());
        }
    }

    /**
     * Transforms a test class that extends {@code AbstractIT} into a self-contained class:
     * <ul>
     *   <li>Removes {@code extends AbstractIT}</li>
     *   <li>Adds {@code RestAssured.baseURI} and {@code RestAssured.port} setup to the first
     *       {@code @BeforeAll} method found (or adds a new one if none exists)</li>
     *   <li>Adds the required imports if not already present</li>
     * </ul>
     */
    private String inlineRestAssuredSetup(String source, int port) {
        if (!source.contains("extends AbstractIT")) {
            return source; // already self-contained
        }

        // Remove "extends AbstractIT"
        source = source.replaceAll("\\s+extends\\s+AbstractIT", "");

        String portSetup = String.format(
                "        RestAssured.baseURI = \"http://localhost\";%n" +
                "        RestAssured.port    = %d;%n", port);

        // Inject at the start of the first @BeforeAll method body
        if (source.contains("@BeforeAll")) {
            // Find the opening brace of the first @BeforeAll method and inject after it
            source = source.replaceFirst(
                    "(@BeforeAll\\s+static\\s+void\\s+\\w+\\s*\\(\\s*\\)\\s*\\{)",
                    "$1\n" + portSetup);
        } else {
            // No @BeforeAll exists — add one
            String packageName = derivePackage(source);
            String newMethod = String.format(
                    "%n    @BeforeAll%n    static void setUpRestAssured() {%n%s    }%n",
                    portSetup);
            // Insert before the first @Test
            source = source.replaceFirst("(\\s+@Test)", "\n" + newMethod + "$1");
        }

        // Ensure required imports are present
        source = ensureImport(source, "import io.restassured.RestAssured;");
        source = ensureImport(source, "import org.junit.jupiter.api.BeforeAll;");

        return source;
    }

    private String ensureImport(String source, String importLine) {
        if (source.contains(importLine)) return source;
        // Insert after the last existing import line
        int lastImport = source.lastIndexOf("\nimport ");
        if (lastImport == -1) return source;
        int insertAt = source.indexOf('\n', lastImport + 1);
        return source.substring(0, insertAt) + "\n" + importLine + source.substring(insertAt);
    }

    private String derivePackage(String sourceCode) {
        for (String line : sourceCode.split("\n")) {
            line = line.strip();
            if (line.startsWith("package ") && line.endsWith(";")) {
                return line.substring("package ".length(), line.length() - 1).strip();
            }
        }
        return "";
    }

    /**
     * A mutant is "relevant" to a test if they share a package prefix.
     * Controllers in {@code com.example.controller} are covered by a test in the same package.
     */
    private boolean isRelevant(Mutant mutant, GeneratedTest test) {
        if (mutant.getMutatedClass() == null) return false;
        String mutantPkg = mutant.getMutatedClass().contains(".")
                ? mutant.getMutatedClass().substring(0, mutant.getMutatedClass().lastIndexOf('.'))
                : mutant.getMutatedClass();
        return mutantPkg.startsWith(test.getPackageName())
                || test.getPackageName().startsWith(mutantPkg);
    }

    private String formatMutants(List<Mutant> mutants) {
        return mutants.stream()
                .map(m -> "[%s] %s#%s line %d — %s".formatted(
                        m.getMutatorShortName(),
                        m.getMutatedClass(),
                        m.getMutatedMethod(),
                        m.getLineNumber(),
                        m.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    // Visible for testing
    String appendTestMethods(String existingSource, String newMethods) {
        if (newMethods.isBlank()) return existingSource;

        // Strip markdown fences if the LLM added them despite instructions
        if (newMethods.startsWith("```")) {
            int first = newMethods.indexOf('\n');
            int last  = newMethods.lastIndexOf("```");
            if (first > 0 && last > first) {
                newMethods = newMethods.substring(first + 1, last).strip();
            }
        }

        int lastBrace = existingSource.lastIndexOf('}');
        if (lastBrace < 0) return existingSource + "\n\n" + newMethods;

        return existingSource.substring(0, lastBrace).stripTrailing()
                + "\n\n    // --- mutation gap fill ---\n\n"
                + newMethods
                + "\n}";
    }

    private int countTestMethods(String code) {
        int count = 0;
        int idx   = 0;
        while ((idx = code.indexOf("@Test", idx)) >= 0) { count++; idx++; }
        return count;
    }
}
