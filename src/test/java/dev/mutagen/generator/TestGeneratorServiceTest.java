package dev.mutagen.generator;

import dev.mutagen.llm.MockLlmClient;
import dev.mutagen.model.*;
import dev.mutagen.skill.Skill;
import dev.mutagen.skill.SkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TestGeneratorServiceTest {

    private MockLlmClient mockLlm;
    private SkillLoader skillLoader;
    private TestGeneratorService service;

    // Realistische gegenereerde testcode als mock response
    private static final String MOCK_GENERATED_TEST = """
            package com.example.controller;
            
            import io.restassured.RestAssured;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.boot.test.web.server.LocalServerPort;
            import static io.restassured.RestAssured.given;
            import static org.hamcrest.Matchers.*;
            
            @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
            class UserControllerIT {
            
                @LocalServerPort
                private int port;
            
                @BeforeEach
                void setUp() {
                    RestAssured.port = port;
                }
            
                @Test
                void getUsers_validRequest_returns200() {
                    given()
                        .when().get("/api/v1/users")
                        .then().statusCode(200);
                }
            
                @Test
                void getUserById_validId_returns200() {
                    given()
                        .pathParam("id", 1)
                        .when().get("/api/v1/users/{id}")
                        .then().statusCode(200);
                }
            
                @Test
                void getUserById_unknownId_returns404() {
                    given()
                        .pathParam("id", 9999)
                        .when().get("/api/v1/users/{id}")
                        .then().statusCode(404);
                }
            }
            """;

    @BeforeEach
    void setUp() {
        mockLlm     = new MockLlmClient();
        skillLoader = new SkillLoader();
        service     = new TestGeneratorService(mockLlm, skillLoader);
    }

    @Test
    void generateAll_singleController_returnsOneGeneratedTest() {
        mockLlm.thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResult();

        List<GeneratedTest> results = service.generateAll(parseResult);

        // generateAll now returns AbstractIT + one IT class per controller
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTestClassName()).isEqualTo("AbstractIT");
        assertThat(results.get(1).getControllerClass()).isEqualTo("UserController");
        assertThat(results.get(1).getTestClassName()).isEqualTo("UserControllerIT");
    }

    @Test
    void generateAll_callsLlmOncePerController() {
        // 1 call for AbstractIT + 2 for controllers = 3 total
        mockLlm.thenReturn(MOCK_GENERATED_TEST).thenReturn(MOCK_GENERATED_TEST).thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResultWithTwoControllers();

        service.generateAll(parseResult);

        assertThat(mockLlm.getCallCount()).isEqualTo(3);
    }

    @Test
    void generateAll_promptContainsEndpointInfo() {
        mockLlm.thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResult();

        service.generateAll(parseResult);

        String userPrompt = mockLlm.getLastRequest().getUserPrompt();
        assertThat(userPrompt).contains("UserController");
        assertThat(userPrompt).contains("/api/v1/users");
        assertThat(userPrompt).contains("GET");
    }

    @Test
    void generateAll_skillUsedAsSystemPrompt() {
        mockLlm.thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResult();

        service.generateAll(parseResult);

        String systemPrompt = mockLlm.getLastRequest().getSystemPrompt();
        assertThat(systemPrompt).contains("RestAssured");
        assertThat(systemPrompt).isNotBlank();
    }

    @Test
    void generateAll_existingTestCodeIncludedInPrompt() {
        mockLlm.thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResult();
        String existingCode = "class OrderControllerIT { @Test void test() {} }";

        service.generateAll(parseResult, existingCode);

        assertThat(mockLlm.getLastRequest().getUserPrompt()).contains("OrderControllerIT");
    }

    @Test
    void generateAll_derivesCorrectRelativeFilePath() {
        mockLlm.thenReturn(MOCK_GENERATED_TEST);
        ParseResult parseResult = buildParseResult();

        List<GeneratedTest> results = service.generateAll(parseResult);

        // Index 0 = AbstractIT, index 1 = UserControllerIT
        assertThat(results.get(1).getRelativeFilePath())
                .isEqualTo("src/test/java/com/example/controller/UserControllerIT.java");
    }

    @Test
    void generateAll_controllerFails_continuesWithNext() {
        // Call 1: AbstractIT (succeeds), call 2: controller 1 (fails), call 3: controller 2 (succeeds)
        mockLlm.thenReturn(MOCK_GENERATED_TEST)
               .thenAnswer(req -> { throw new RuntimeException("API timeout"); })
               .thenReturn(MOCK_GENERATED_TEST);

        ParseResult parseResult = buildParseResultWithTwoControllers();
        List<GeneratedTest> results = service.generateAll(parseResult);

        // AbstractIT + één van de twee controllers slaagt
        assertThat(results).hasSize(2);
    }

    @Test
    void sanitizeOutput_stripsMarkdownCodeFence() {
        String withFence = "```java\npackage com.example;\nclass Test {}\n```";
        String result = service.sanitizeOutput(withFence);

        assertThat(result).startsWith("package");
        assertThat(result).doesNotContain("```");
    }

    @Test
    void sanitizeOutput_stripsPreamble() {
        String withPreamble = "Here is your generated test:\n\npackage com.example;\nclass Test {}";
        String result = service.sanitizeOutput(withPreamble);

        assertThat(result).startsWith("package");
        assertThat(result).doesNotContain("Here is");
    }

    @Test
    void sanitizeOutput_cleanCode_unchanged() {
        String clean = "package com.example;\nclass Test {}";
        assertThat(service.sanitizeOutput(clean)).isEqualTo(clean);
    }

    // -------------------------------------------------------------------------

    private ParseResult buildParseResult() {
        ParseResult result = new ParseResult();
        result.setRepoPath("/repo");
        result.setFilesScanned(1);
        result.setControllersFound(1);

        EndpointInfo get = new EndpointInfo();
        get.setControllerClass("UserController");
        get.setControllerFile("/repo/src/main/java/com/example/controller/UserController.java");
        get.setMethodName("getUsers");
        get.setHttpMethod(HttpMethod.GET);
        get.setPath("/users");
        get.setFullPath("/api/v1/users");
        get.setResponseType("ResponseEntity<List<Object>>");

        EndpointInfo getById = new EndpointInfo();
        getById.setControllerClass("UserController");
        getById.setControllerFile("/repo/src/main/java/com/example/controller/UserController.java");
        getById.setMethodName("getUserById");
        getById.setHttpMethod(HttpMethod.GET);
        getById.setPath("/users/{id}");
        getById.setFullPath("/api/v1/users/{id}");
        getById.setResponseType("ResponseEntity<Object>");
        getById.getPathParams().add(new ParamInfo("id", "Long"));

        result.setEndpoints(List.of(get, getById));
        return result;
    }

    private ParseResult buildParseResultWithTwoControllers() {
        ParseResult result = buildParseResult();

        EndpointInfo orderEndpoint = new EndpointInfo();
        orderEndpoint.setControllerClass("OrderController");
        orderEndpoint.setControllerFile("/repo/src/main/java/com/example/controller/OrderController.java");
        orderEndpoint.setMethodName("getOrders");
        orderEndpoint.setHttpMethod(HttpMethod.GET);
        orderEndpoint.setPath("/orders");
        orderEndpoint.setFullPath("/api/v1/orders");
        orderEndpoint.setResponseType("ResponseEntity<List<Object>>");

        result.setEndpoints(List.of(result.getEndpoints().get(0), result.getEndpoints().get(1), orderEndpoint));
        return result;
    }
}
