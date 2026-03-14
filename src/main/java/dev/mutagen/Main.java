package dev.mutagen;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.generator.TestGeneratorService;
import dev.mutagen.llm.client.LlmClient;
import dev.mutagen.llm.client.LlmClientFactory;
import dev.mutagen.mutation.MutationLoopResult;
import dev.mutagen.mutation.MutationLoopService;
import dev.mutagen.mutation.PitestRunner;
import dev.mutagen.output.MavenModuleWriter;
import dev.mutagen.parser.RepoScanner;
import dev.mutagen.model.ParseResult;
import dev.mutagen.skill.SkillLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(
        name = "mutagen",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "AI-powered API test generator for Spring Boot"
)
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Parameters(index = "0", description = "Path to the repository to scan")
    private Path repoPath;

    @Option(names = {"-o", "--output"}, description = "Output path for endpoints.json")
    private Path outputPath;

    @Command(name = "parse", description = "Run only the endpoint parser")
    public Integer parse() throws IOException {
        RepoScanner scanner = new RepoScanner();
        Path out = outputPath != null ? outputPath : repoPath.resolve("endpoints.json");
        ParseResult result = scanner.scanAndWrite(repoPath, out);
        return result.getEndpoints().isEmpty() ? 1 : 0;
    }

    @Command(name = "generate", description = "Parse controllers and generate RestAssured tests")
    public Integer generate() throws IOException {
        RepoScanner scanner = new RepoScanner();
        ParseResult result = scanner.scan(repoPath);

        LlmClient llmClient     = LlmClientFactory.fromEnvironment();
        SkillLoader skillLoader = new SkillLoader();
        TestGeneratorService service = new TestGeneratorService(llmClient, skillLoader);

        List<GeneratedTest> tests = service.generateAll(result);

        Path outputDir = outputPath != null ? outputPath : repoPath;
        new MavenModuleWriter().write(outputDir, tests);

        return tests.isEmpty() ? 1 : 0;
    }

    @Command(name = "mutate", description = "Generate tests, run Pitest mutation loop, and fill coverage gaps")
    public Integer mutate() throws IOException {
        RepoScanner scanner = new RepoScanner();
        ParseResult result = scanner.scan(repoPath);

        LlmClient llmClient = LlmClientFactory.fromEnvironment();
        SkillLoader skillLoader = new SkillLoader();
        TestGeneratorService service = new TestGeneratorService(llmClient, skillLoader);

        int threshold = Integer.parseInt(System.getenv().getOrDefault("MUTATION_THRESHOLD", "80"));
        int maxIterations = Integer.parseInt(System.getenv().getOrDefault("MUTATION_MAX_ITERATIONS", "3"));

        // Backend is started inside the loop service so that AuthProber can probe auth
        // BEFORE test generation — this guarantees the LLM gets verified payloads.
        PitestRunner runner = PitestRunner.detect(repoPath);
        MutationLoopService loopService = new MutationLoopService(llmClient, skillLoader, runner);

        MutationLoopResult loopResult = null;
        try {
            loopResult = loopService.run(result, service, null, repoPath, threshold, maxIterations);
        } finally {
            // Always clean up the target repo — even if the run threw an exception.
            // loopService.getLastKnownTests() returns whatever was last written, so we
            // can remove those files and restore the pom regardless of success/failure.
            PitestRunner.cleanupInjectedDependencies(repoPath);
            cleanupInjectedTestFiles(repoPath, loopService.getLastKnownTests());
        }

        if (loopResult.tests().isEmpty()) {
            log.warn("No tests generated — nothing to mutate");
            return 1;
        }

        log.info("Mutation loop done: initial={}, final={}, iterations={}, threshold={}, tokens={} in / {} out (total {})",
                String.format(Locale.US, "%.1f%%", loopResult.initialScore()),
                String.format(Locale.US, "%.1f%%", loopResult.finalScore()),
                loopResult.iterationsRun(),
                threshold,
                loopResult.totalInputTokens(),
                loopResult.totalOutputTokens(),
                loopResult.totalInputTokens() + loopResult.totalOutputTokens());

        if (loopResult.thresholdMet(threshold)) {
            log.info("Mutation score threshold met!");
        } else {
            log.warn("Mutation score below threshold — writing best effort tests");
        }

        // Write final tests to separate Maven module
        Path outputDir = outputPath != null ? outputPath : repoPath;
        new MavenModuleWriter().write(outputDir, loopResult.tests(), loopResult.backendPort());

        return loopResult.thresholdMet(threshold) ? 0 : 1;
    }

    /**
     * Deletes the test files that were written into the target project during the mutation loop.
     * The final tests live in the {@code rest-assured-tests} Maven module instead.
     */
    private void cleanupInjectedTestFiles(Path repoPath, List<GeneratedTest> tests) {
        for (GeneratedTest test : tests) {
            Path file = repoPath.resolve(test.getRelativeFilePath());
            try {
                Files.deleteIfExists(file);
                log.debug("Cleaned up {}", file);
            } catch (IOException e) {
                log.warn("Could not delete injected test file {}: {}", file, e.getMessage());
            }
        }
    }

    @Override
    public Integer call() throws Exception {
        return parse();
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
