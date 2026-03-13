package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.model.EndpointInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PitestRunnerTest {

    private final PitestRunner runner = new PitestRunner(PitestRunner.BuildTool.MAVEN);

    @Test
    void buildCommand_containsPitestGoal() {
        List<String> cmd = runner.buildCommand(singleTest());
        assertThat(cmd).contains("org.pitest:pitest-maven:mutationCoverage");
    }

    @Test
    void buildCommand_containsXmlOutputFormat() {
        List<String> cmd = runner.buildCommand(singleTest());
        assertThat(cmd).contains("-DoutputFormats=XML");
    }

    @Test
    void buildCommand_containsTargetClasses() {
        List<String> cmd = runner.buildCommand(singleTest());
        assertThat(String.join(" ", cmd)).contains("-DtargetClasses=com.example.controller.*");
    }

    @Test
    void buildCommand_containsTargetTests() {
        List<String> cmd = runner.buildCommand(singleTest());
        assertThat(String.join(" ", cmd))
                .contains("-DtargetTests=com.example.controller.UserControllerIT");
    }

    @Test
    void buildCommand_multipleTests_joinsWithComma() {
        List<GeneratedTest> tests = List.of(
                makeTest("UserControllerIT", "com.example.controller"),
                makeTest("OrderControllerIT", "com.example.controller")
        );
        String joined = String.join(" ", runner.buildCommand(tests));
        assertThat(joined).contains("UserControllerIT,com.example.controller.OrderControllerIT");
    }

    @Test
    void buildCommand_multiplePackages_deduplicatesTargetClasses() {
        List<GeneratedTest> tests = List.of(
                makeTest("UserControllerIT", "com.example.controller"),
                makeTest("AdminControllerIT", "com.example.controller")
        );
        String targetClasses = runner.deriveTargetClasses(tests);
        // same package → only one entry
        assertThat(targetClasses).isEqualTo("com.example.controller.*");
    }

    @Test
    void detect_mavenProject_returnsMavenRunner() {
        // tmp dir has no build.gradle → Maven
        PitestRunner detected = PitestRunner.detect(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
        assertThat(detected.buildCommand(singleTest()))
                .contains("org.pitest:pitest-maven:mutationCoverage");
    }

    @Test
    void gradleBuildCommand_containsGradleTask() {
        PitestRunner gradle = new PitestRunner(PitestRunner.BuildTool.GRADLE);
        List<String> cmd = gradle.buildCommand(singleTest());
        assertThat(cmd).containsExactly("./gradlew", "pitest");
    }

    // ------------------------------------------------------------------

    private List<GeneratedTest> singleTest() {
        return List.of(makeTest("UserControllerIT", "com.example.controller"));
    }

    private GeneratedTest makeTest(String className, String packageName) {
        return new GeneratedTest(
                className.replace("IT", ""),
                className,
                packageName,
                "package " + packageName + ";\nclass " + className + " {}",
                List.of(),
                0, 0, "mock");
    }
}
