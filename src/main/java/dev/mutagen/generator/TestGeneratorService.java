package dev.mutagen.generator;

import dev.mutagen.auth.AuthContext;
import dev.mutagen.auth.AuthSetupInfo;
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
        return generateAll(parseResult, null, AuthContext.noAuth());
    }

    public List<GeneratedTest> generateAll(ParseResult parseResult, String existingTestCode) {
        return generateAll(parseResult, existingTestCode, AuthContext.noAuth());
    }

    /**
     * Generates {@code AbstractIT} + one IT class per controller.
     *
     * @param existingTestCode optional fragment of an existing test for style matching
     * @param authContext      static analysis of the project's security configuration
     */
    public List<GeneratedTest> generateAll(ParseResult parseResult, String existingTestCode,
                                            AuthContext authContext) {
        Map<String, List<EndpointInfo>> byController = parseResult.getEndpoints()
                .stream()
                .collect(Collectors.groupingBy(EndpointInfo::getControllerClass));

        log.info("Starting test generation for {} controllers", byController.size());

        // Derive package from endpoints (used for AbstractIT)
        String packageName = parseResult.getEndpoints().stream()
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

        Skill testSkill = skillLoader.load(Skill.Type.RESTASSURED_TEST);
        List<GeneratedTest> results = new ArrayList<>();

        // Generate AbstractIT first
        log.info("Generating AbstractIT for package '{}'...", packageName);
        try {
            GeneratedTest abstractIT = generateAbstractIT(authContext, packageName);
            results.add(abstractIT);
            log.info("  ✓ AbstractIT generated ({} tokens)",
                    abstractIT.getInputTokens() + abstractIT.getOutputTokens());
        } catch (Exception e) {
            log.error("  ✗ AbstractIT generation failed: {}", e.getMessage());
        }

        int index = 1;
        for (Map.Entry<String, List<EndpointInfo>> entry : byController.entrySet()) {
            String controllerClass       = entry.getKey();
            List<EndpointInfo> endpoints = entry.getValue();

            log.info("[{}/{}] Generating for {} ({} endpoints)...",
                    index++, byController.size(), controllerClass, endpoints.size());
            try {
                GeneratedTest test = generateForController(
                        controllerClass, endpoints, testSkill, existingTestCode, authContext);
                results.add(test);
                log.info("  ✓ {} generated ({} tokens)",
                        test.getTestClassName(), test.getInputTokens() + test.getOutputTokens());
            } catch (Exception e) {
                log.error("  ✗ Generation failed for {}: {}", controllerClass, e.getMessage());
            }
        }

        log.info("Test generation complete: {}/{} controllers succeeded",
                results.size() - 1, byController.size());
        return results;
    }

    /**
     * Generates the {@code AbstractIT} base class using static auth context.
     * The generated class includes {@code @SpringBootTest(RANDOM_PORT)}, RestAssured setup,
     * and token acquisition logic (if auth is required).
     */
    public GeneratedTest generateAbstractIT(AuthContext authContext, String packageName) {
        Skill skill = skillLoader.load(Skill.Type.ABSTRACT_IT);

        String prompt = """
                Generate the abstract base class `AbstractIT` for package: %s

                IMPORTANT: Output ONLY the abstract base class — NO @Test methods, NO test logic.
                The class must be named `AbstractIT` and declared `abstract`.
                It must contain: @SpringBootTest(RANDOM_PORT), @TestInstance(PER_CLASS),
                @LocalServerPort int port, and a @BeforeAll setUp() that configures RestAssured
                and (if security is enabled) acquires auth tokens.

                For the setUp() method: use ONLY standard Java types and RestAssured.
                Do NOT import or reference any DTO or request classes from the application.
                Build JSON request bodies as plain strings (e.g. String.format("{...}", ...))
                or as java.util.Map with RestAssured's .body(map).
                Use private static inner classes if needed — never import application DTOs.

                Declare these protected fields so subclasses can reuse them:
                  protected String token;
                  protected String adminToken;
                  protected String testUsername;
                  protected String testPassword;
                Set testUsername and testPassword to the credentials used for signup/signin.
                Set token = ... after the signin call using .extract().path("token").
                Subclasses use testUsername/testPassword to create valid signin requests in their own tests.

                CRITICAL for the signup call: keep username and email SHORT.
                Use UUID.randomUUID().toString().substring(0, 8) as the unique suffix — NOT the full UUID.
                Example: String u = "t_" + UUID.randomUUID().toString().substring(0, 8);
                         // username = u  (~10 chars), email = u + "@example.com" (~22 chars)
                Full UUIDs are 36 chars; combined with prefix and domain they exceed @Size(max=50).

                """.formatted(packageName)
                + authContext.toPromptSection();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(skill.getContent())
                .userPrompt(prompt)
                .maxTokens(2048)
                .temperature(0.1f)
                .build();

        LlmResponse response = llmClient.complete(request);
        String sourceCode = sanitizeOutput(response.getContent());
        sourceCode = sanitizeAbstractIT(sourceCode);

        return new GeneratedTest("AbstractIT", "AbstractIT", packageName, sourceCode,
                List.of(), response.getInputTokens(), response.getOutputTokens(), response.getProvider());
    }

    public GeneratedTest generateForController(String controllerClass, List<EndpointInfo> endpoints,
                                                Skill skill, String existingTestCode,
                                                AuthContext authContext) {
        String packageName   = derivePackageName(endpoints);
        String testClassName = deriveTestClassName(controllerClass);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(skill.getContent())
                .userPrompt(promptBuilder.buildTestGenerationPrompt(
                        controllerClass, packageName, endpoints, existingTestCode, authContext))
                .maxTokens(4096)
                .temperature(0.1f)
                .build();

        LlmResponse response = llmClient.complete(request);
        String sourceCode = sanitizeOutput(response.getContent());
        // Use the actual class name from the generated source as filename to avoid mismatches
        String actualClassName = extractClassName(sourceCode);
        if (actualClassName != null) testClassName = actualClassName;
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
     * Ensures the generated AbstractIT actually declares `abstract class AbstractIT`.
     * If the LLM generated a wrong class name, rename it. If it forgot `abstract`, add it.
     */
    /**
     * Ensures the generated AbstractIT actually declares {@code abstract class AbstractIT}.
     * If the LLM generated a wrong class name, rename it. If it forgot {@code abstract}, add it.
     */
    /**
     * Ensures the generated AbstractIT actually declares {@code abstract class AbstractIT}.
     * If the LLM generated a wrong class name, rename it. If it forgot {@code abstract}, add it.
     */
    String sanitizeAbstractIT(String code) {
        if (!code.contains("class AbstractIT")) {
            // LLM wrote a wrong class name — replace with AbstractIT
            // Remove any "extends AbstractIT" that would make it a test subclass, not the base
            code = code.replaceAll("\\s+extends\\s+AbstractIT", "");
            // Rename the class declaration
            code = code.replaceFirst(
                    "public\\s+(?:abstract\\s+)?class\\s+\\w+",
                    "public abstract class AbstractIT");
        }
        // Ensure abstract keyword is present
        code = code.replace("public class AbstractIT", "public abstract class AbstractIT");

        // Normalize token field name: tests always use `token`, so rename userToken → token
        // Keep adminToken as-is; if both exist, `token` becomes an alias for the user token
        code = code.replace("protected String userToken", "protected String token");
        code = code.replace("this.userToken", "this.token");
        code = code.replace("userToken =", "token =");
        code = code.replaceAll("\\buserToken\\b", "token");

        // Replace bare UUID.randomUUID() (without .toString().substring) with a short version
        // to prevent email/username fields from exceeding @Size(max=50) constraints.
        code = code.replaceAll(
                "UUID\\.randomUUID\\(\\)(?!\\.toString\\(\\)\\.substring)",
                "UUID.randomUUID().toString().substring(0, 8)");

        // Strip any @Test methods the LLM may have generated — AbstractIT must have none
        code = removeTestMethods(code);

        // Remove @Test import if no @Test annotations remain
        if (!code.contains("@Test")) {
            code = code.replaceAll("import org\\.junit\\.jupiter\\.api\\.Test;\\s*\\n", "");
        }

        return code;
    }

    /**
     * Removes all @Test-annotated methods from the given Java source code.
     * Uses a simple line-by-line / brace-counting approach.
     */
    private String removeTestMethods(String code) {
        String[] lines = code.split("\n", -1);
        java.util.List<String> result = new java.util.ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            // Detect @Test annotation line — use exact match or @Test( to avoid matching @TestInstance
            if (trimmed.equals("@Test") || trimmed.startsWith("@Test(")) {
                // Skip annotation line(s) until we hit the method signature
                while (i < lines.length && !lines[i].contains("{")) {
                    i++;
                }
                // Now skip the method body by counting braces
                int depth = 0;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    i++;
                    if (depth == 0) break;
                }
                // Skip trailing blank line after method if present
                if (i < lines.length && lines[i].trim().isEmpty()) i++;
            } else {
                result.add(lines[i]);
                i++;
            }
        }
        return String.join("\n", result);
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

    /** Extracts the public class name from generated Java source, or null if not found. */
    private String extractClassName(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+(?:abstract\\s+)?class\\s+(\\w+)")
                .matcher(source);
        return m.find() ? m.group(1) : null;
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
