package dev.mutagen.generator;

import dev.mutagen.llm.client.LlmClient;
import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.ParseResult;
import dev.mutagen.skill.Skill;
import dev.mutagen.skill.SkillLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates RestAssured integration tests for all controllers in a {@link ParseResult}.
 *
 * <p>Each controller is sent as a separate LLM request. Failed controllers are logged
 * and skipped — generation continues for remaining controllers.
 */
public class TestGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorService.class);

    private final LlmClient llmClient;
    private final SkillLoader skillLoader;
    private final PromptBuilder promptBuilder;

    public TestGeneratorService(LlmClient llmClient, SkillLoader skillLoader) {
        this.llmClient     = llmClient;
        this.skillLoader   = skillLoader;
        this.promptBuilder = new PromptBuilder();
    }

    public List<GeneratedTest> generateAll(ParseResult parseResult) {
        return generateAll(parseResult, null);
    }

    /**
     * @param existingTestCode optional fragment of an existing test in the repo,
     *                         used to match the team's testing style
     */
    public List<GeneratedTest> generateAll(ParseResult parseResult, String existingTestCode) {
        Map<String, List<EndpointInfo>> byController = parseResult.getEndpoints()
                .stream()
                .collect(Collectors.groupingBy(EndpointInfo::getControllerClass));

        log.info("Starting test generation for {} controllers", byController.size());

        // Detect auth endpoints (sign-in / sign-up) to pass along when a controller needs auth
        List<EndpointInfo> authEndpoints = parseResult.getEndpoints().stream()
                .filter(e -> {
                    String path = e.getFullPath().toLowerCase();
                    String method = e.getHttpMethod().name();
                    return "POST".equals(method) && (path.contains("signin") || path.contains("login")
                            || path.contains("signup") || path.contains("register"));
                })
                .toList();
        if (!authEndpoints.isEmpty()) {
            log.info("Detected {} auth endpoint(s) for token acquisition", authEndpoints.size());
        }

        Skill skill = skillLoader.load(Skill.Type.RESTASSURED_TEST);
        List<GeneratedTest> results = new ArrayList<>();
        int index = 1;

        for (Map.Entry<String, List<EndpointInfo>> entry : byController.entrySet()) {
            String controllerClass       = entry.getKey();
            List<EndpointInfo> endpoints = entry.getValue();

            log.info("[{}/{}] Generating for {} ({} endpoints)...",
                    index++, byController.size(), controllerClass, endpoints.size());
            try {
                GeneratedTest test = generateForController(controllerClass, endpoints, skill, existingTestCode, authEndpoints);
                results.add(test);
                log.info("  ✓ {} generated ({} tokens)", test.getTestClassName(), test.getInputTokens() + test.getOutputTokens());
            } catch (Exception e) {
                log.error("  ✗ Generation failed for {}: {}", controllerClass, e.getMessage());
            }
        }

        log.info("Test generation complete: {}/{} controllers succeeded", results.size(), byController.size());
        return results;
    }

    public GeneratedTest generateForController(String controllerClass, List<EndpointInfo> endpoints,
                                                Skill skill, String existingTestCode) {
        return generateForController(controllerClass, endpoints, skill, existingTestCode, List.of());
    }

    public GeneratedTest generateForController(String controllerClass, List<EndpointInfo> endpoints,
                                                Skill skill, String existingTestCode,
                                                List<EndpointInfo> authEndpoints) {
        String packageName   = derivePackageName(endpoints);
        String testClassName = deriveTestClassName(controllerClass);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(skill.getContent())
                .userPrompt(promptBuilder.buildTestGenerationPrompt(
                        controllerClass, packageName, endpoints, existingTestCode, authEndpoints))
                .maxTokens(4096)
                .temperature(0.1f)
                .build();

        LlmResponse response = llmClient.complete(request);
        String sourceCode = sanitizeOutput(response.getContent());
        validateJavaOutput(sourceCode, testClassName);

        return new GeneratedTest(controllerClass, testClassName, packageName, sourceCode,
                endpoints, response.getInputTokens(), response.getOutputTokens(), response.getProvider());
    }

    /** Strips markdown code fences and preamble text that LLMs sometimes add despite instructions. */
    String sanitizeOutput(String raw) {
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
        trimmed = fixMockMvcAuthCalls(trimmed);
        trimmed = fixHamcrestInMatcher(trimmed);
        trimmed = fixMissingCollectionImports(trimmed);
        return trimmed;
    }

    /**
     * Post-processes generated test code to fix common LLM mistakes:
     * - Removes .auth().xxx() chains (not always supported)
     * - Removes spring-mock-mvc imports when standalone RestAssured is used
     */
    String fixMockMvcAuthCalls(String code) {
        code = code.replaceAll("\\.auth\\(\\)\\.[a-zA-Z]+\\([^)]*\\)", "");
        if (code.contains("import io.restassured.RestAssured")) {
            code = code.replaceAll("import io\\.restassured\\.module\\.mockmvc[^;]*;\\s*\n", "");
        }
        return code;
    }

    /**
     * Ensures {@code java.util.Set} and {@code java.util.List} are imported when used in the code
     * but not yet declared — prevents compile failures in Pitest's test-compile phase.
     */
    String fixMissingCollectionImports(String code) {
        // Find the insertion point: after the last existing import line
        int lastImport = code.lastIndexOf("\nimport ");
        if (lastImport < 0) return code;
        int insertAfter = code.indexOf('\n', lastImport + 1); // end of that last import line
        if (insertAfter < 0) return code;

        StringBuilder extra = new StringBuilder();
        if (code.contains("Set<") && !code.contains("import java.util.Set") && !code.contains("import java.util.*;")) {
            extra.append("\nimport java.util.Set;");
        }
        if (code.contains("List<") && !code.contains("import java.util.List") && !code.contains("import java.util.*;")) {
            extra.append("\nimport java.util.List;");
        }
        if (extra.isEmpty()) return code;
        return code.substring(0, insertAfter) + extra + code.substring(insertAfter);
    }

    /**
     * Hamcrest's {@code in(Collection)} doesn't accept varargs ints.
     * Replaces {@code .statusCode(in(X, Y))} with {@code .statusCode(anyOf(is(X), is(Y)))}.
     */
    String fixHamcrestInMatcher(String code) {
        return code.replaceAll(
                "\\.statusCode\\(in\\((\\d+),\\s*(\\d+)\\)\\)",
                ".statusCode(anyOf(is($1), is($2)))");
    }

    private void validateJavaOutput(String code, String expectedClassName) {
        if (!code.startsWith("package "))       log.warn("Generated code does not start with 'package'");
        if (!code.contains("@Test"))            log.warn("Generated code contains no @Test annotations for {}", expectedClassName);
        if (!code.contains(expectedClassName))  log.warn("Expected class name '{}' not found in generated code", expectedClassName);
    }

    private String deriveTestClassName(String controllerClass) {
        return controllerClass + "IT";
    }

    private String derivePackageName(List<EndpointInfo> endpoints) {
        return endpoints.stream()
                .map(EndpointInfo::getControllerFile)
                .filter(f -> f != null && f.contains("src/main/java/"))
                .map(f -> {
                    int start = f.indexOf("src/main/java/") + "src/main/java/".length();
                    int end   = f.lastIndexOf('/');
                    return (start < end) ? f.substring(start, end).replace('/', '.') : null;
                })
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse("com.example");
    }
}
