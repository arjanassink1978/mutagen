package dev.mutagen.mutation;

import dev.mutagen.auth.AuthAnalyzer;
import dev.mutagen.auth.AuthContext;
import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.generator.PromptBuilder;
import dev.mutagen.generator.TestGeneratorService;
import dev.mutagen.model.ParseResult;
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
     * Full pipeline: starts the backend, probes auth, generates tests, then runs the mutation loop.
     *
     * <p>Prefer this overload over passing pre-generated tests, because auth probing requires
     * the backend to be running and must happen <em>before</em> test generation.
     *
     * @param parseResult       scanned endpoint data
     * @param generatorService  test generator to invoke after auth probing
     * @param existingTestCode  optional existing test snippet for style matching
     * @param repoPath          root of the target Spring Boot project
     * @param threshold         target mutation score (0–100)
     * @param maxIterations     maximum number of pitest runs
     */
    public MutationLoopResult run(ParseResult parseResult,
                                   TestGeneratorService generatorService,
                                   String existingTestCode,
                                   Path repoPath,
                                   int threshold,
                                   int maxIterations) throws IOException {

        MutationReport        report      = null;
        double                initialScore = -1;
        int                   port         = 0;
        List<GeneratedTest>   current      = List.of();

        BackendStarter backend = backendFactory.create(repoPath);
        try {
            port = backend.start();
            log.info("Backend started on port {}", port);

            // Analyse the project's security config statically (no HTTP calls needed)
            AuthContext authContext = new AuthAnalyzer().analyze(repoPath);

            current = generatorService.generateAll(parseResult, existingTestCode, authContext);
            if (current.isEmpty()) {
                log.warn("No tests generated — aborting mutation loop");
                return new MutationLoopResult(current, 0, 0, 0, null, port);
            }

            // Stop the external backend before Pitest.
            // The inline tests use @SpringBootTest(RANDOM_PORT), so Spring starts its own
            // embedded server inside the Pitest JVM — the only way Pitest can instrument
            // the production code and actually track mutation coverage.
            backend.stop();
            log.info("Backend stopped — @SpringBootTest will start embedded server during Pitest");

            current = validateAndFix(current, repoPath, 0);

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                log.info("Mutation loop — iteration {}/{}", iteration, maxIterations);

                writeTestFiles(current, repoPath, 0);

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
                List<Mutant> noCoverage = report.getNoCoverageMutants();
                List<Mutant> toFill = combineMutants(survived, noCoverage);
                if (toFill.isEmpty()) break;

                log.info("  Asking LLM to fill {} mutant(s) ({} survived, {} no-coverage)...",
                        toFill.size(), survived.size(), Math.min(noCoverage.size(), toFill.size() - survived.size()));
                current = augmentTests(current, toFill, (int) score, threshold);
                current = validateAndFix(current, repoPath, port);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            backend.stop(); // ensure stopped on interrupt
            throw new IOException("Interrupted while waiting for backend", e);
        } catch (Exception e) {
            backend.stop(); // ensure stopped on any other exception
            throw e;
        }

        double finalScore = report != null ? report.getMutationScore() : 0;
        return new MutationLoopResult(current, initialScore, finalScore, maxIterations, report, 0);
    }

    /**
     * Runs the mutation loop and returns the final result.
     * No external backend is started — tests use {@code @SpringBootTest(RANDOM_PORT)}.
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

        // This overload has no auth probing — no external backend needed.
        // @SpringBootTest handles the embedded server during Pitest.
        try {
            current = validateAndFix(current, repoPath, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during test validation", e);
        }

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("Mutation loop — iteration {}/{}", iteration, maxIterations);

            writeTestFiles(current, repoPath, 0);

            Path reportPath = mutationRunner.run(current, repoPath);
            report          = reportParser.parse(reportPath);

            double score = report.getMutationScore();
            log.info("  Score: {}% (target: {}%, killed: {}, survived: {}, no-coverage: {})",
                    String.format("%.1f", score), threshold,
                    report.getKilledCount(), report.getSurvivedCount(), report.getNoCoverageCount());

            if (iteration == 1) initialScore = score;

            if (score >= threshold) {
                log.info("  Threshold met — stopping");
                return new MutationLoopResult(current, initialScore, score, iteration, report, 0);
            }

            if (iteration == maxIterations) break;

            List<Mutant> survived = report.getSurvivedMutants();
            List<Mutant> noCoverage = report.getNoCoverageMutants();
            List<Mutant> toFill = combineMutants(survived, noCoverage);
            if (toFill.isEmpty()) break;

            log.info("  Asking LLM to fill {} mutant(s) ({} survived, {} no-coverage)...",
                    toFill.size(), survived.size(), Math.min(noCoverage.size(), toFill.size() - survived.size()));
            current = augmentTests(current, toFill, (int) score, threshold);
            try {
                current = validateAndFix(current, repoPath, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during post-augment validation", e);
            }
        }

        double finalScore = report != null ? report.getMutationScore() : 0;
        return new MutationLoopResult(current, initialScore, finalScore, maxIterations, report, 0);
    }

    // ---------------------------------------------------------------

    /**
     * Runs the generated tests via {@code mvn test} and asks the LLM to fix any failures.
     * Tests use {@code @SpringBootTest(RANDOM_PORT)} so no external backend is needed.
     * Repeats up to 3 times until all tests pass.
     */
    private List<GeneratedTest> validateAndFix(List<GeneratedTest> tests,
                                                Path repoPath, int portUnused) throws IOException, InterruptedException {
        if (!repoPath.resolve("pom.xml").toFile().exists()) {
            log.debug("No pom.xml in {} — skipping pre-flight test validation", repoPath);
            writeTestFiles(tests, repoPath, 0);
            return tests;
        }

        final int MAX_FIX_ATTEMPTS = 3;
        List<GeneratedTest> current = new ArrayList<>(tests);

        for (int attempt = 1; attempt <= MAX_FIX_ATTEMPTS; attempt++) {
            writeTestFiles(current, repoPath, 0);
            Map<String, String> failures = runTestsAndCollectFailures(current, repoPath);
            if (failures.isEmpty()) {
                log.info("All tests pass — proceeding to mutation testing");
                return current;
            }
            log.info("  {} test class(es) failing (fix attempt {}/{})",
                    failures.size(), attempt, MAX_FIX_ATTEMPTS);
            current = stripAbstractITTestMethods(current, failures);
            current = fixFailingTests(current, failures);
        }
        writeTestFiles(current, repoPath, 0);
        Map<String, String> remaining = runTestsAndCollectFailures(current, repoPath);
        if (!remaining.isEmpty()) {
            throw new IOException(
                    "Tests still failing after " + MAX_FIX_ATTEMPTS + " fix attempts — aborting. " +
                    "Failing classes: " + String.join(", ", remaining.keySet()));
        }
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
     * If AbstractIT has @Test methods (e.g. added by augmentTests before the guard was in place,
     * or by LLM despite instructions), strip them — AbstractIT is infrastructure-only.
     */
    private List<GeneratedTest> stripAbstractITTestMethods(List<GeneratedTest> tests,
                                                             Map<String, String> failures) {
        boolean hasAbstractITError = failures.containsKey("AbstractIT")
                || failures.values().stream().anyMatch(v -> v.contains("AbstractIT"));
        if (!hasAbstractITError) return tests;

        return tests.stream().map(test -> {
            if (!"AbstractIT".equals(test.getTestClassName())) return test;
            String src = test.getSourceCode();
            if (!src.contains("@Test")) return test;
            String stripped = stripTestMethods(src);
            log.info("  Stripped rogue @Test methods from AbstractIT");
            return test.withSourceCode(stripped);
        }).toList();
    }

    /**
     * Asks the LLM to fix each failing test class given the failure summary.
     */
    private List<GeneratedTest> fixFailingTests(List<GeneratedTest> tests,
                                                  Map<String, String> failures) {
        Skill skill = skillLoader.load(Skill.Type.RESTASSURED_TEST);
        return tests.stream().map(test -> {
            // AbstractIT is infrastructure — never send it to the LLM for fixing
            if ("AbstractIT".equals(test.getTestClassName())) return test;

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
            // AbstractIT is infrastructure — never add @Test methods to it
            if ("AbstractIT".equals(test.getTestClassName())) return test;

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
     * Writes all generated test files (including AbstractIT) to the target project's test directory.
     * AbstractIT is generated with {@code @SpringBootTest(RANDOM_PORT)} so Spring starts inside
     * the Pitest JVM — the only way Pitest can instrument production code and track mutation coverage.
     */
    private void writeTestFiles(List<GeneratedTest> tests, Path repoPath, int portUnused)
            throws IOException {
        for (GeneratedTest test : tests) {
            Path target = repoPath.resolve(test.getRelativeFilePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, test.getSourceCode());
            log.debug("  Wrote {}", test.getRelativeFilePath());
        }
    }

    /**
     * A mutant is "relevant" to a test if they share a package prefix.
     * Controllers in {@code com.example.controller} are covered by a test in the same package.
     */
    /**
     * Combines survived and no-coverage mutants for the gap-fill prompt.
     * Survived mutants are always included; no-coverage mutants are capped at 20
     * per iteration to keep the LLM prompt manageable.
     */
    private List<Mutant> combineMutants(List<Mutant> survived, List<Mutant> noCoverage) {
        List<Mutant> result = new java.util.ArrayList<>(survived);
        int cap = Math.max(0, 20 - survived.size());
        result.addAll(noCoverage.stream().limit(cap).toList());
        return result;
    }

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

        // Deduplicate: skip methods whose name already appears in the existing source
        newMethods = deduplicateMethods(existingSource, newMethods);
        if (newMethods.isBlank()) return existingSource;

        int lastBrace = existingSource.lastIndexOf('}');
        if (lastBrace < 0) return existingSource + "\n\n" + newMethods;

        return existingSource.substring(0, lastBrace).stripTrailing()
                + "\n\n    // --- mutation gap fill ---\n\n"
                + newMethods
                + "\n}";
    }

    /**
     * Removes methods from {@code newMethods} whose name already exists in {@code existing}.
     * Matches on "void methodName(" to detect duplicates.
     */
    private String deduplicateMethods(String existing, String newMethods) {
        // Split newMethods into individual method blocks (split on @Test boundaries)
        java.util.regex.Pattern methodPattern =
                java.util.regex.Pattern.compile("@Test[\\s\\S]*?(?=@Test|\\z)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = methodPattern.matcher(newMethods);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            // Extract method name: "void someName("
            java.util.regex.Matcher nameMatcher =
                    java.util.regex.Pattern.compile("void\\s+(\\w+)\\s*\\(").matcher(block);
            if (nameMatcher.find()) {
                String name = nameMatcher.group(1);
                if (!existing.contains("void " + name + "(")) {
                    result.append(block).append("\n");
                }
            } else {
                result.append(block).append("\n");
            }
        }
        return result.toString().strip();
    }

    private int countTestMethods(String code) {
        int count = 0;
        int idx   = 0;
        while ((idx = code.indexOf("@Test", idx)) >= 0) { count++; idx++; }
        return count;
    }

    /**
     * Removes all {@code @Test}-annotated methods from a source file.
     * Used to strip rogue test methods from AbstractIT added by augmentTests.
     */
    private String stripTestMethods(String code) {
        String[] lines = code.split("\n", -1);
        java.util.List<String> result = new java.util.ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.equals("@Test") || trimmed.startsWith("@Test(")) {
                while (i < lines.length && !lines[i].contains("{")) i++;
                int depth = 0;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    i++;
                    if (depth == 0) break;
                }
                if (i < lines.length && lines[i].trim().isEmpty()) i++;
            } else {
                result.add(lines[i]);
                i++;
            }
        }
        String stripped = String.join("\n", result);
        // Remove @Test import if no @Test annotations remain
        if (!stripped.contains("@Test")) {
            stripped = stripped.replaceAll("import org\\.junit\\.jupiter\\.api\\.Test;\\s*\\n", "");
        }
        return stripped;
    }
}
