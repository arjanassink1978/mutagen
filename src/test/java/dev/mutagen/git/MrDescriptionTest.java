package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.HttpMethod;
import dev.mutagen.mutation.MutationLoopResult;
import dev.mutagen.mutation.model.MutationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MrDescriptionTest {

    @Test
    void build_containsTestClassName() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 2), 80);
        assertThat(description).contains("UserControllerIT");
        assertThat(description).contains("OrderControllerIT");
    }

    @Test
    void build_containsPackageName() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 2), 80);
        assertThat(description).contains("com.example.controller");
    }

    @Test
    void build_containsMutationScores() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 2), 80);
        assertThat(description).contains("25.0%");
        assertThat(description).contains("85.0%");
        assertThat(description).contains("80%");
    }

    @Test
    void build_containsEndpointCount() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 2), 80);
        // 2 endpoints on UserControllerIT + 1 on OrderControllerIT = 3
        assertThat(description).contains("3 endpoints");
    }

    @Test
    void build_containsTestClassCount() {
        String description = MrDescription.build(twoTests(), loopResult(50.0, 90.0, 1), 80);
        assertThat(description).contains("2 test classes");
    }

    @Test
    void build_singleTestClass_singularForm() {
        String description = MrDescription.build(List.of(userTest()), loopResult(50.0, 90.0, 1), 80);
        assertThat(description).contains("1 test class");
        assertThat(description).doesNotContain("1 test classes");
    }

    @Test
    void build_multipleIterations_mentionsIterations() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 3), 80);
        assertThat(description).contains("3 iteration");
    }

    @Test
    void build_oneIteration_noIterationMention() {
        String description = MrDescription.build(twoTests(), loopResult(85.0, 85.0, 1), 80);
        assertThat(description).doesNotContain("iteration");
    }

    @Test
    void build_negativeInitialScore_showsNa() {
        String description = MrDescription.build(twoTests(), loopResult(-1, 85.0, 1), 80);
        assertThat(description).contains("n/a");
    }

    @Test
    void build_isValidMarkdown() {
        String description = MrDescription.build(twoTests(), loopResult(25.0, 85.0, 2), 80);
        assertThat(description).contains("##");
        assertThat(description).contains("---");
        assertThat(description).contains("| |");
    }

    // ------------------------------------------------------------------

    private MutationLoopResult loopResult(double initial, double finalScore, int iterations) {
        return new MutationLoopResult(twoTests(), initial, finalScore, iterations, new MutationReport(), 0);
    }

    private List<GeneratedTest> twoTests() {
        return List.of(userTest(), orderTest());
    }

    private GeneratedTest userTest() {
        EndpointInfo e1 = endpoint(HttpMethod.GET, "/api/v1/users");
        EndpointInfo e2 = endpoint(HttpMethod.POST, "/api/v1/users");
        return new GeneratedTest("UserController", "UserControllerIT",
                "com.example.controller", "class UserControllerIT {}",
                List.of(e1, e2), 100, 200, "mock");
    }

    private GeneratedTest orderTest() {
        EndpointInfo e = endpoint(HttpMethod.GET, "/api/v1/orders");
        return new GeneratedTest("OrderController", "OrderControllerIT",
                "com.example.controller", "class OrderControllerIT {}",
                List.of(e), 100, 200, "mock");
    }

    private EndpointInfo endpoint(HttpMethod method, String path) {
        EndpointInfo e = new EndpointInfo();
        e.setHttpMethod(method);
        e.setFullPath(path);
        return e;
    }
}
