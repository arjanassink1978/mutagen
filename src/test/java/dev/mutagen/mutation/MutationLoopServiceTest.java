package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.llm.MockLlmClient;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.skill.SkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MutationLoopServiceTest {

    @TempDir
    Path tempDir;

    private MockLlmClient        mockLlm;
    private MockMutationRunner   mockRunner;
    private MutationLoopService  service;

    // Fixture mutations.xml with 1 killed, 2 survived, 1 no-coverage → score 25%
    private static final Path FIXTURE_XML =
            Path.of("src/test/resources/fixtures/mutations.xml");

    // A fixture with all mutants killed → score 100%
    private Path allKilledXml;

    @BeforeEach
    void setUp() throws IOException {
        mockLlm    = new MockLlmClient();
        mockRunner = new MockMutationRunner(FIXTURE_XML);

        // No-op BackendStarter so tests don't attempt to start a real server
        BackendStarter noopBackend = new BackendStarter(tempDir, 0) {
            @Override public int start() { return 0; }
            @Override public void stop() {}
        };
        service = new MutationLoopService(mockLlm, new SkillLoader(), mockRunner,
                repoPath -> noopBackend);

        allKilledXml = tempDir.resolve("all_killed.xml");
        Files.writeString(allKilledXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mutations>
                    <mutation detected='true' status='KILLED' numberOfTestsRun='3'>
                        <sourceFile>UserController.java</sourceFile>
                        <mutatedClass>com.example.controller.UserController</mutatedClass>
                        <mutatedMethod>getUser</mutatedMethod>
                        <mutatedMethodDesc>()V</mutatedMethodDesc>
                        <lineNumber>10</lineNumber>
                        <mutator>org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator</mutator>
                        <indexes><index>0</index></indexes>
                        <blocks><block>0</block></blocks>
                        <killingTest>com.example.controller.UserControllerIT/test()</killingTest>
                        <description>negated conditional</description>
                    </mutation>
                </mutations>
                """);
    }

    @Test
    void run_thresholdAlreadyMet_stopsAfterOneIteration() throws IOException {
        // Score is 25%, threshold is 0 → already met
        MutationLoopResult result = service.run(singleTest(), tempDir, 0, 3);

        assertThat(result.iterationsRun()).isEqualTo(1);
        assertThat(mockRunner.getCallCount()).isEqualTo(1);
        assertThat(mockLlm.getCallCount()).isEqualTo(0);
    }

    @Test
    void run_belowThreshold_callsLlmToFillGaps() throws IOException {
        // Iteration 1: score 25% (below 80%)
        // Iteration 2: score 100% (threshold met)
        mockRunner.thenReturn(FIXTURE_XML).thenReturn(allKilledXml);
        mockLlm.withDefaultResponse("@Test\nvoid mutation_extra() {}");

        MutationLoopResult result = service.run(singleTest(), tempDir, 80, 3);

        assertThat(mockLlm.getCallCount()).isGreaterThan(0);
        assertThat(result.iterationsRun()).isEqualTo(2);
    }

    @Test
    void run_maxIterationsReached_stopsLoop() throws IOException {
        // Always returns 25% (fixture) → never reaches threshold of 80%
        mockLlm.withDefaultResponse("@Test\nvoid extra() {}");

        MutationLoopResult result = service.run(singleTest(), tempDir, 80, 2);

        assertThat(result.iterationsRun()).isEqualTo(2);
        assertThat(mockRunner.getCallCount()).isEqualTo(2);
    }

    @Test
    void run_writesTestFilesToDisk() throws IOException {
        service.run(singleTest(), tempDir, 0, 1);

        Path written = tempDir.resolve(
                "src/test/java/com/example/controller/UserControllerIT.java");
        assertThat(written).exists();
        assertThat(Files.readString(written)).contains("UserControllerIT");
    }

    @Test
    void run_returnsInitialAndFinalScore() throws IOException {
        mockRunner.thenReturn(FIXTURE_XML).thenReturn(allKilledXml);
        mockLlm.withDefaultResponse("@Test\nvoid extra() {}");

        MutationLoopResult result = service.run(singleTest(), tempDir, 80, 3);

        assertThat(result.initialScore()).isEqualTo(25.0);
        assertThat(result.finalScore()).isEqualTo(100.0);
    }

    @Test
    void run_thresholdMet_returnsTrueFromHelper() throws IOException {
        mockRunner.thenReturn(allKilledXml);

        MutationLoopResult result = service.run(singleTest(), tempDir, 80, 3);

        assertThat(result.thresholdMet(80)).isTrue();
    }

    @Test
    void run_augmentedSourceContainsExtraMethods() throws IOException {
        mockRunner.thenReturn(FIXTURE_XML).thenReturn(allKilledXml);
        mockLlm.withDefaultResponse("""
                @Test
                void mutation_boundary_line38() {
                    given().when().get("/api/v1/users/18").then().statusCode(200);
                }
                """);

        MutationLoopResult result = service.run(singleTest(), tempDir, 80, 3);

        String augmentedSource = result.tests().get(0).getSourceCode();
        assertThat(augmentedSource).contains("mutation_boundary_line38");
        assertThat(augmentedSource).contains("mutation gap fill");
    }

    @Test
    void appendTestMethods_insertsBeforeLastBrace() {
        String existing = "package x;\nclass T {\n    @Test void a() {}\n}";
        String newMethods = "@Test\nvoid b() {}";

        String result = service.appendTestMethods(existing, newMethods);

        assertThat(result).endsWith("\n}");
        assertThat(result).contains("void b()");
        assertThat(result.indexOf("void b()"))
                .isGreaterThan(result.indexOf("void a()"));
    }

    @Test
    void appendTestMethods_stripsMarkdownFences() {
        String existing = "package x;\nclass T {}";
        String withFences = "```java\n@Test\nvoid b() {}\n```";

        String result = service.appendTestMethods(existing, withFences);

        assertThat(result).doesNotContain("```");
        assertThat(result).contains("@Test");
    }

    @Test
    void appendTestMethods_blankMethods_returnsOriginal() {
        String existing = "package x;\nclass T {}";
        assertThat(service.appendTestMethods(existing, "   ")).isEqualTo(existing);
    }

    // ------------------------------------------------------------------

    private List<GeneratedTest> singleTest() {
        return List.of(new GeneratedTest(
                "UserController",
                "UserControllerIT",
                "com.example.controller",
                """
                package com.example.controller;
                import org.junit.jupiter.api.Test;
                class UserControllerIT {
                    @Test
                    void getUser_returns200() {}
                }
                """,
                List.of(),
                100, 200, "mock"));
    }
}
