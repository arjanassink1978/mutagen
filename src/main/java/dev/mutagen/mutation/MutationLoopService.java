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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

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

    /**
     * Tracks the most recently generated tests across both {@code run()} overloads.
     * Updated every time {@code current} changes so that callers can read it even
     * after an exception — e.g. to clean up files that were written to the target repo.
     */
    private volatile List<GeneratedTest> lastKnownTests = List.of();

    /** Returns the last list of tests that were generated/modified in the most recent run. */
    public List<GeneratedTest> getLastKnownTests() { return lastKnownTests; }

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
        AtomicLong            totalIn      = new AtomicLong();
        AtomicLong            totalOut     = new AtomicLong();

        BackendStarter backend = backendFactory.create(repoPath);
        try {
            port = backend.start();
            log.info("Backend started on port {}", port);

            // Analyse the project's security config statically (no HTTP calls needed)
            AuthContext authContext = new AuthAnalyzer().analyze(repoPath);

            current = generatorService.generateAll(parseResult, existingTestCode, authContext);
            lastKnownTests = current;
            if (current.isEmpty()) {
                log.warn("No tests generated — aborting mutation loop");
                return new MutationLoopResult(current, 0, 0, 0, null, port, 0, 0);
            }
            // Accumulate tokens from initial test generation
            current.forEach(t -> { totalIn.addAndGet(t.getInputTokens()); totalOut.addAndGet(t.getOutputTokens()); });

            // Stop the external backend before Pitest.
            // The inline tests use @SpringBootTest(RANDOM_PORT), so Spring starts its own
            // embedded server inside the Pitest JVM — the only way Pitest can instrument
            // the production code and actually track mutation coverage.
            backend.stop();
            log.info("Backend stopped — @SpringBootTest will start embedded server during Pitest");

            // Inject test dependencies (RestAssured, Pitest plugin) before mvn test so that
            // the validateAndFix compile step can find io.restassured on the classpath.
            mutationRunner.prepareRepo(repoPath);

            current = validateAndFix(current, repoPath, 0, totalIn, totalOut);
            lastKnownTests = current;

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
                    return new MutationLoopResult(current, initialScore, score, iteration, report, port, totalIn.get(), totalOut.get());
                }

                if (iteration == maxIterations) break;

                List<Mutant> survived = report.getSurvivedMutants();
                List<Mutant> noCoverage = report.getNoCoverageMutants();
                List<Mutant> toFill = combineMutants(survived, noCoverage);
                if (toFill.isEmpty()) break;

                log.info("  Asking LLM to fill {} mutant(s) ({} survived, {} no-coverage)...",
                        toFill.size(), survived.size(), Math.min(noCoverage.size(), toFill.size() - survived.size()));
                List<GeneratedTest> beforeAugment = new ArrayList<>(current);
                current = augmentTests(current, toFill, (int) score, threshold, repoPath, totalIn, totalOut);
                current = validateAndFix(current, repoPath, port, totalIn, totalOut, beforeAugment);
                lastKnownTests = current;
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
        return new MutationLoopResult(current, initialScore, finalScore, maxIterations, report, 0, totalIn.get(), totalOut.get());
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
        AtomicLong totalIn          = new AtomicLong();
        AtomicLong totalOut         = new AtomicLong();
        // Accumulate tokens from the pre-generated tests passed in
        tests.forEach(t -> { totalIn.addAndGet(t.getInputTokens()); totalOut.addAndGet(t.getOutputTokens()); });

        // This overload has no auth probing — no external backend needed.
        // @SpringBootTest handles the embedded server during Pitest.
        try {
            // Inject test dependencies before mvn test so RestAssured is on the classpath.
            mutationRunner.prepareRepo(repoPath);
            current = validateAndFix(current, repoPath, 0, totalIn, totalOut);
            lastKnownTests = current;
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
                return new MutationLoopResult(current, initialScore, score, iteration, report, 0, totalIn.get(), totalOut.get());
            }

            if (iteration == maxIterations) break;

            List<Mutant> survived = report.getSurvivedMutants();
            List<Mutant> noCoverage = report.getNoCoverageMutants();
            List<Mutant> toFill = combineMutants(survived, noCoverage);
            if (toFill.isEmpty()) break;

            log.info("  Asking LLM to fill {} mutant(s) ({} survived, {} no-coverage)...",
                    toFill.size(), survived.size(), Math.min(noCoverage.size(), toFill.size() - survived.size()));
            current = augmentTests(current, toFill, (int) score, threshold, repoPath, totalIn, totalOut);
            try {
                current = validateAndFix(current, repoPath, 0, totalIn, totalOut);
                lastKnownTests = current;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during post-augment validation", e);
            }
        }

        double finalScore = report != null ? report.getMutationScore() : 0;
        return new MutationLoopResult(current, initialScore, finalScore, maxIterations, report, 0, totalIn.get(), totalOut.get());
    }

    // ---------------------------------------------------------------

    /**
     * Runs the generated tests via {@code mvn test} and asks the LLM to fix any failures.
     * Tests use {@code @SpringBootTest(RANDOM_PORT)} so no external backend is needed.
     * Repeats up to 3 times until all tests pass.
     */
    private List<GeneratedTest> validateAndFix(List<GeneratedTest> tests,
                                                Path repoPath, int portUnused,
                                                AtomicLong totalIn, AtomicLong totalOut) throws IOException, InterruptedException {
        return validateAndFix(tests, repoPath, portUnused, totalIn, totalOut, null);
    }

    /**
     * Runs tests and asks the LLM to fix failures, up to MAX_FIX_ATTEMPTS.
     * If still failing after all attempts, per-class fallback to {@code fallbackTests} (if provided).
     * If no fallback, throws IOException.
     */
    private List<GeneratedTest> validateAndFix(List<GeneratedTest> tests,
                                                Path repoPath, int portUnused,
                                                AtomicLong totalIn, AtomicLong totalOut,
                                                @Nullable List<GeneratedTest> fallbackTests) throws IOException, InterruptedException {
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
            current = fixFailingTests(current, failures, totalIn, totalOut);
        }
        writeTestFiles(current, repoPath, 0);
        Map<String, String> remaining = runTestsAndCollectFailures(current, repoPath);
        if (remaining.isEmpty()) return current;

        // Last resort: revert each still-failing class to the pre-augmentation fallback version.
        // This preserves gap-fill progress for classes that DID fix correctly.
        if (fallbackTests != null) {
            List<GeneratedTest> reverted = current.stream().map(test -> {
                if (!remaining.containsKey(test.getTestClassName())) return test;
                return fallbackTests.stream()
                        .filter(f -> f.getTestClassName().equals(test.getTestClassName()))
                        .findFirst()
                        .map(f -> {
                            log.warn("  Reverted {} to pre-augmentation state after {} failed fix attempts",
                                    test.getTestClassName(), MAX_FIX_ATTEMPTS);
                            return f;
                        })
                        .orElse(test);
            }).toList();
            writeTestFiles(reverted, repoPath, 0);
            Map<String, String> afterRevert = runTestsAndCollectFailures(reverted, repoPath);
            if (afterRevert.isEmpty()) {
                log.info("All tests pass after reverting {} class(es) to pre-augmentation state",
                        remaining.size());
                return reverted;
            }
            // Even the original tests are broken — give up on those classes entirely
            log.warn("  Pre-augmentation tests also failing for {} — removing those classes",
                    afterRevert.keySet());
            List<GeneratedTest> withoutBroken = reverted.stream()
                    .filter(t -> !afterRevert.containsKey(t.getTestClassName()))
                    .toList();
            writeTestFiles(withoutBroken, repoPath, 0);
            return withoutBroken;
        }

        throw new IOException(
                "Tests still failing after " + MAX_FIX_ATTEMPTS + " fix attempts — aborting. " +
                "Failing classes: " + String.join(", ", remaining.keySet()));
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

        // Include test-compile explicitly to force a fresh compile even when Maven's
        // incremental build thinks the .class files are up-to-date (stale .class from a
        // previous run can hide compile errors that Pitest's own test-compile would catch).
        List<String> cmd = List.of("mvn", "test-compile", "test", "-Dtest=" + testClassArg,
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
        Map<String, String> failures = parseSurefireFailures(repoPath, output);

        // If the build failed but no surefire XML files were written (compile error before tests ran),
        // parseSurefireFailures may return an empty map. Force a compile-error entry in that case
        // so validateAndFix knows to send the error to the LLM for fixing.
        if (failures.isEmpty()) {
            log.debug("Non-zero exit ({}) but no surefire failures parsed — treating as compile error", exitCode);
            return Map.of("compile-error", truncate(output, 2000));
        }
        return failures;
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
                                                  Map<String, String> failures,
                                                  AtomicLong totalIn, AtomicLong totalOut) {
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
                    - If a test expected 200/201 but backend returned 400 → the request body or parameter format is wrong. Two possible fixes:
                      (a) If the endpoint uses `@RequestParam` (query/form params), switch from `.body(json)` to `.param("field", value)` or `.formParam("field", value)` — do NOT send a JSON body
                      (b) If the endpoint uses `@RequestBody`, fix the JSON body fields
                    - CRITICAL: Never change `.statusCode(201)` to `.statusCode(400)` on a setup step that extracts an ID — if the POST still returns 400, fix the request instead
                    - If backend returns 501 (NOT_IMPLEMENTED) → change `.statusCode(...)` to `anyOf(is(200), is(404), is(501))`
                    - If a test expected 400 but backend returned 404 → change to 404
                    - If a GET request returned 415 → remove `.contentType(ContentType.JSON)` from the GET request (GET + Content-Type causes 415 on some servers)
                    - If the error is `cannot find symbol: method X()` → either add the missing helper method, or inline its logic directly in the test. Do NOT leave a call to a method that is not defined
                    - If the `Actual` in a failure shows nested objects like `<[SomeClass{field=value}]>` instead of plain strings, navigate to the specific field via JSON path: e.g. change `.body("items", hasItem("foo"))` to `.body("items.name", hasItem("foo"))` (use the actual field name that holds the expected value)
                    - Do NOT change tests that are already passing
                    - Return the COMPLETE fixed test class (same format: starts with `package`, ends with closing `}` of the class — never truncate)
                    """.formatted(failureSummary, test.getSourceCode());

            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(skill.getContent())
                    .cacheSystemPrompt(true)
                    .userPrompt(prompt)
                    .maxTokens(4096)
                    .temperature(0.1f)
                    .build();

            try {
                LlmResponse response = llmClient.complete(request);
                totalIn.addAndGet(response.getInputTokens());
                totalOut.addAndGet(response.getOutputTokens());
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
                                              int targetScore,
                                              Path repoPath,
                                              AtomicLong totalIn, AtomicLong totalOut) {
        Skill skill = skillLoader.load(Skill.Type.MUTATION_GAP_ANALYSIS);

        return tests.stream().map(test -> {
            // AbstractIT is infrastructure — never add @Test methods to it
            if ("AbstractIT".equals(test.getTestClassName())) return test;

            List<Mutant> relevant = survived.stream()
                    .filter(m -> isRelevant(m, test))
                    .toList();

            if (relevant.isEmpty()) {
                log.debug("  No relevant mutants for {} (pkg={})", test.getTestClassName(), test.getPackageName());
                return test;
            }

            String survivingText = formatMutants(relevant, repoPath);
            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(skill.getContent())
                    .cacheSystemPrompt(true)
                    .userPrompt(promptBuilder.buildMutationGapPrompt(
                            test.getSourceCode(), survivingText, currentScore, targetScore, test.getEndpoints()))
                    .maxTokens(4096)
                    .temperature(0.1f)
                    .build();

            try {
                LlmResponse response = llmClient.complete(request);
                totalIn.addAndGet(response.getInputTokens());
                totalOut.addAndGet(response.getOutputTokens());
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
     * Survived mutants are always included; no-coverage mutants fill up to a total of 40.
     */
    private List<Mutant> combineMutants(List<Mutant> survived, List<Mutant> noCoverage) {
        List<Mutant> result = new java.util.ArrayList<>(survived);
        int cap = Math.max(0, 80 - survived.size());
        // Sort no-coverage mutants: group by class so the LLM gets clustered context,
        // making it easier to write one test that covers multiple mutants in the same class.
        List<Mutant> sortedNoCoverage = noCoverage.stream()
                .sorted(java.util.Comparator.comparing(m -> m.getMutatedClass() != null ? m.getMutatedClass() : ""))
                .toList();
        result.addAll(sortedNoCoverage.stream().limit(cap).toList());
        return result;
    }

    /**
     * A mutant is "relevant" to a test if they share a common root package of at least 2 segments.
     * This ensures mutants in {@code com.example.service} are matched to tests in
     * {@code com.example.controller} (both share {@code com.example}).
     */
    private boolean isRelevant(Mutant mutant, GeneratedTest test) {
        if (mutant.getMutatedClass() == null) return false;
        String mutantPkg = mutant.getMutatedClass().contains(".")
                ? mutant.getMutatedClass().substring(0, mutant.getMutatedClass().lastIndexOf('.'))
                : mutant.getMutatedClass();
        String testPkg = test.getPackageName();
        // Exact prefix match (original behaviour)
        if (mutantPkg.startsWith(testPkg) || testPkg.startsWith(mutantPkg)) return true;
        // Shared root: compare up to min(2, depth) package segments
        String mutantRoot = rootPackage(mutantPkg, 3);
        String testRoot   = rootPackage(testPkg, 3);
        return !mutantRoot.isEmpty() && mutantRoot.equals(testRoot);
    }

    /** Returns the first {@code depth} segments of a package name, e.g. "com.example.foo" → "com.example" (depth=2). */
    private String rootPackage(String pkg, int depth) {
        String[] parts = pkg.split("\\.");
        if (parts.length <= depth) return pkg;
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < depth; i++) sb.append('.').append(parts[i]);
        return sb.toString();
    }

    private String formatMutants(List<Mutant> mutants, Path repoPath) {
        return mutants.stream()
                .map(m -> formatMutant(m, repoPath))
                .collect(Collectors.joining("\n"));
    }

    private String formatMutant(Mutant m, Path repoPath) {
        String statusTag = m.getStatusEnum() == Mutant.Status.NO_COVERAGE ? "NO_COVERAGE" : "SURVIVED";
        String header = "[%s][%s] %s#%s line %d — %s".formatted(
                statusTag,
                m.getMutatorShortName(),
                m.getMutatedClass(),
                m.getMutatedMethod(),
                m.getLineNumber(),
                m.getDescription());
        String snippet = readSourceSnippet(m, repoPath);
        return snippet.isBlank() ? header : header + "\n  Code: " + snippet;
    }

    /**
     * Reads a few lines of source code around the mutant's line number from the target project,
     * giving the LLM concrete context about what expression was mutated.
     */
    private String readSourceSnippet(Mutant m, Path repoPath) {
        if (m.getMutatedClass() == null || m.getLineNumber() <= 0) return "";
        // Derive source path: com.example.Foo → src/main/java/com/example/Foo.java
        String relativePath = "src/main/java/"
                + m.getMutatedClass().replace('.', '/') + ".java";
        Path sourceFile = repoPath.resolve(relativePath);
        if (!Files.exists(sourceFile)) return "";
        try {
            List<String> lines = Files.readAllLines(sourceFile);
            int target = m.getLineNumber() - 1; // 0-based
            if (target < 0 || target >= lines.size()) return "";
            // Show 1 line before + the mutated line + 1 line after for context
            int from = Math.max(0, target - 1);
            int to   = Math.min(lines.size() - 1, target + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i <= to; i++) {
                String marker = (i == target) ? " >> " : "    ";
                sb.append(marker).append(lines.get(i).strip());
                if (i < to) sb.append(" | ");
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
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
