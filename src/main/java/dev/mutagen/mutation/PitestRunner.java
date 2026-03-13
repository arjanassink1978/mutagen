package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs Pitest as a subprocess ({@code mvn pitest:mutationCoverage} or {@code ./gradlew pitest})
 * and returns the path to the produced {@code mutations.xml}.
 *
 * <p>Use {@link #detect(Path)} to auto-select the build tool, or construct directly with
 * {@link BuildTool#MAVEN} / {@link BuildTool#GRADLE}.
 */
public class PitestRunner implements MutationRunner {

    private static final Logger log = LoggerFactory.getLogger(PitestRunner.class);

    public enum BuildTool { MAVEN, GRADLE }

    private final BuildTool buildTool;

    public PitestRunner(BuildTool buildTool) {
        this.buildTool = buildTool;
    }

    /** Auto-detects the build tool by looking for {@code build.gradle} in the repo root. */
    public static PitestRunner detect(Path repoPath) {
        if (repoPath.resolve("build.gradle").toFile().exists()
                || repoPath.resolve("build.gradle.kts").toFile().exists()) {
            return new PitestRunner(BuildTool.GRADLE);
        }
        return new PitestRunner(BuildTool.MAVEN);
    }

    @Override
    public Path run(List<GeneratedTest> tests, Path repoPath) throws IOException {
        // Ensure parent project and dependencies are installed in local Maven repo
        installParentProject(repoPath);

        // Inject test dependencies (RestAssured, Pitest JUnit5 plugin) into target pom
        injectTestDependencies(repoPath);

        List<String> command = buildCommand(tests);
        log.info("Running Pitest: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(repoPath.toFile())
                .redirectErrorStream(true)
                .inheritIO();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new PitestException("Failed to start Pitest process: " + e.getMessage(), e);
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PitestException("Pitest process was interrupted", e);
        }

        if (exitCode != 0) {
            throw new PitestException("Pitest exited with code " + exitCode, exitCode);
        }

        Path report = repoPath.resolve("target/pit-reports/mutations.xml");
        if (!report.toFile().exists()) {
            throw new PitestException(
                    "mutations.xml not found after Pitest run — verify that pitest-maven is configured in the target project's pom.xml",
                    -1);
        }

        return report;
    }

    private static final String MUTAGEN_MARKER = "<!-- mutagen-injected -->";

    /**
     * Temporarily injects RestAssured + Pitest JUnit5 plugin dependencies into the target pom
     * so Pitest can compile and run the generated tests inline.
     */
    private void injectTestDependencies(Path repoPath) throws IOException {
        Path pomPath = repoPath.resolve("pom.xml");
        String pom = Files.readString(pomPath);

        if (pom.contains(MUTAGEN_MARKER)) {
            log.debug("Test dependencies already injected");
            return;
        }

        String deps = """
                %s
                <dependency>
                  <groupId>io.rest-assured</groupId>
                  <artifactId>rest-assured</artifactId>
                  <version>5.5.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>""".formatted(MUTAGEN_MARKER);

        pom = pom.replace("</dependencies>", deps);

        // Inject pitest-maven plugin with JUnit 5 support
        String pitestPlugin = """
                      %s
                      <plugin>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-maven</artifactId>
                        <version>1.17.4</version>
                        <dependencies>
                          <dependency>
                            <groupId>org.pitest</groupId>
                            <artifactId>pitest-junit5-plugin</artifactId>
                            <version>1.2.1</version>
                          </dependency>
                        </dependencies>
                      </plugin>
                    </plugins>""".formatted(MUTAGEN_MARKER);

        pom = pom.replace("</plugins>", pitestPlugin);

        Files.writeString(pomPath, pom);
        log.info("Injected test dependencies into {}", pomPath);
    }

    /**
     * Removes mutagen-injected dependencies from the target pom.
     * Tries {@code git checkout pom.xml} first; falls back to marker-based string removal.
     */
    public static void cleanupInjectedDependencies(Path repoPath) throws IOException {
        Path pomPath = repoPath.resolve("pom.xml");
        if (!pomPath.toFile().exists()) return;

        String pom = Files.readString(pomPath);
        if (!pom.contains(MUTAGEN_MARKER)) {
            log.debug("No injected dependencies found in {}", pomPath);
            return;
        }

        // Try git checkout first (cleanest)
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", "pom.xml")
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            int code = proc.waitFor();
            if (code == 0) {
                log.info("Restored pom.xml via git checkout in {}", repoPath);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("git checkout failed ({}), falling back to string removal", e.getMessage());
        }

        // Fallback: remove marker lines and the injected XML blocks
        // Dependencies block: <!-- mutagen-injected -->\n<dependency>...</dependency>\n</dependencies>
        String cleaned = pom;

        // Remove injected dependency block (marker + everything up to </dependencies>)
        cleaned = cleaned.replaceAll(
                "\\s*" + java.util.regex.Pattern.quote(MUTAGEN_MARKER) + "\\s*" +
                "<dependency>[\\s\\S]*?</dependency>\\s*</dependencies>",
                "\n  </dependencies>");

        // Remove injected plugin block (marker + plugin up to </plugins>)
        cleaned = cleaned.replaceAll(
                "\\s*" + java.util.regex.Pattern.quote(MUTAGEN_MARKER) + "\\s*" +
                "<plugin>[\\s\\S]*?</plugin>\\s*</plugins>",
                "\n          </plugins>");

        if (!cleaned.equals(pom)) {
            Files.writeString(pomPath, cleaned);
            log.info("Removed injected dependencies from {} via string replacement", pomPath);
        } else {
            log.warn("Could not remove injected marker from {}; manual cleanup may be needed", pomPath);
        }
    }

    /**
     * Runs {@code mvn install -q -DskipTests} from the parent pom directory
     * so that the application-under-test artifact is available in the local repo.
     */
    private void installParentProject(Path repoPath) throws IOException {
        // Walk up to find the parent pom directory (the test module's parent)
        Path parentDir = repoPath.getParent();
        if (parentDir == null || !parentDir.resolve("pom.xml").toFile().exists()) {
            log.debug("No parent pom found above {}; skipping install step", repoPath);
            return;
        }

        // Use -N (non-recursive) to install parent pom only, then build the target module
        String targetModule = repoPath.getFileName().toString();
        log.info("Installing parent project and module '{}': mvn install in {}", targetModule, parentDir);
        ProcessBuilder pb = new ProcessBuilder("mvn", "install", "-q", "-Dmaven.test.skip=true",
                "-Ddocker.skip=true", "-pl", targetModule, "-am")
                .directory(parentDir.toFile())
                .redirectErrorStream(true)
                .inheritIO();

        try {
            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                throw new PitestException("Parent project install failed (exit code " + exitCode + ")", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PitestException("Parent install was interrupted", e);
        }
    }

    // Visible for testing
    List<String> buildCommand(List<GeneratedTest> tests) {
        if (buildTool == BuildTool.GRADLE) {
            return List.of("./gradlew", "pitest");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.add("test-compile");
        cmd.add("org.pitest:pitest-maven:mutationCoverage");
        cmd.add("-DoutputFormats=XML");
        cmd.add("-DtimestampedReports=false");
        cmd.add("-DtargetClasses=" + deriveTargetClasses(tests));
        cmd.add("-DtargetTests="   + deriveTargetTests(tests));
        return cmd;
    }

    // Visible for testing
    String deriveTargetClasses(List<GeneratedTest> tests) {
        return tests.stream()
                .map(t -> t.getPackageName() + ".*")
                .distinct()
                .collect(Collectors.joining(","));
    }

    // Visible for testing
    String deriveTargetTests(List<GeneratedTest> tests) {
        return tests.stream()
                .map(t -> t.getPackageName() + "." + t.getTestClassName())
                .collect(Collectors.joining(","));
    }
}
