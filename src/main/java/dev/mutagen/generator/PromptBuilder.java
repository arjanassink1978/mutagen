package dev.mutagen.generator;

import dev.mutagen.auth.AuthContext;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.ParamInfo;
import dev.mutagen.model.RequestBodyInfo;

import java.util.List;

/**
 * Builds structured user prompts from endpoint data.
 *
 * <p>The skill (system prompt) describes HOW to generate.
 * This class describes WHAT to generate — always programmatic and consistent.
 */
public class PromptBuilder {

    /**
     * Builds the prompt for generating a single IT test class.
     * Auth setup is handled by AbstractIT — this prompt only needs to convey endpoints
     * and inform the LLM about which token field to use (userToken / adminToken / none).
     */
    public String buildTestGenerationPrompt(String controllerClass, String packageName,
                                             List<EndpointInfo> endpoints, String existingTestCode,
                                             AuthContext authContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate RestAssured integration tests for the following Spring Boot controller.\n\n");
        sb.append("## Controller\n");
        sb.append("Class: `").append(controllerClass).append("`\n");
        sb.append("Package: `").append(packageName).append("`\n\n");

        // Let the LLM know what tokens AbstractIT provides
        if (authContext.securityEnabled()) {
            sb.append("## Auth tokens (provided by AbstractIT base class)\n");
            sb.append("- `token` — regular user JWT (use for user-level endpoints)\n");
            sb.append("- `adminToken` — admin JWT if admin role exists (use for admin-only endpoints)\n");
            sb.append("If an endpoint requires auth, use `\"Bearer \" + token` or `\"Bearer \" + adminToken`.\n\n");
        }

        sb.append("## Endpoints (").append(endpoints.size()).append(" total)\n\n");
        endpoints.forEach(e -> sb.append(formatEndpoint(e)).append("\n"));

        if (existingTestCode != null && !existingTestCode.isBlank()) {
            sb.append("## Existing test style (follow these conventions)\n");
            sb.append("```java\n").append(truncate(existingTestCode, 1500)).append("\n```\n\n");
        }

        sb.append("## Instruction\n");
        sb.append("Generate the complete test class extending AbstractIT. ");
        sb.append("Start with `package ").append(packageName).append(";` and end with the last `}`.\n");
        sb.append("Do NOT add a @BeforeAll — RestAssured setup and auth are in AbstractIT.");
        return sb.toString();
    }

    public String buildMutationGapPrompt(String existingTestCode, String survivingMutants,
                                          int mutationScore, int targetScore,
                                          List<EndpointInfo> endpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current situation\n");
        sb.append("Mutation score: ").append(mutationScore).append("% (target: ").append(targetScore).append("%)\n\n");

        sb.append("## Available API endpoints (use these to write tests)\n");
        if (endpoints != null && !endpoints.isEmpty()) {
            endpoints.forEach(e -> sb.append(formatEndpoint(e)).append("\n"));
        } else {
            sb.append("(see existing test class for examples)\n");
        }
        sb.append("\n");

        sb.append("## Existing test class (summary)\n");
        sb.append(buildTestClassSummary(existingTestCode)).append("\n\n");

        sb.append("## Mutants to kill\n");
        sb.append("Each entry is prefixed with its status:\n");
        sb.append("- `[SURVIVED]` — the line WAS executed but no test assertion caught the mutation. ");
        sb.append("Add an assertion that verifies the exact value.\n");
        sb.append("- `[NO_COVERAGE]` — the line was NEVER executed by any test. ");
        sb.append("You MUST write a test that calls an API endpoint which triggers this code path.\n\n");
        sb.append("```\n").append(truncate(survivingMutants, 4000)).append("\n```\n\n");

        sb.append("## Instruction\n");
        sb.append("Write additional @Test methods that kill the surviving/uncovered mutants above.\n");
        sb.append("Use the listed API endpoints to reach the mutated code paths.\n");
        sb.append("For [NO_COVERAGE] mutants in methods that operate on existing resources (like/unlike/reply/delete): ");
        sb.append("first create the resource with a POST call and extract its ID, then call the target endpoint with that ID.\n");
        sb.append("Return ONLY the extra test methods, not a full class.\n");
        return sb.toString();
    }

    private String formatEndpointCompact(EndpointInfo e) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(e.getHttpMethod()).append(" ").append(e.getFullPath()).append("**");
        if (e.isRequiresAuth()) {
            String role = e.getRequiredRole();
            if (isAdminOnly(role)) sb.append(" (").append(role).append(" only — use adminToken)");
            else if (role != null) sb.append(" (role: ").append(role).append(" — use token)");
            else sb.append(" (auth — use token)");
        }
        if (e.getRequestBody() != null) {
            sb.append(" body: ").append(e.getRequestBody().getJavaType());
            if (!e.getRequestBody().getFields().isEmpty()) {
                sb.append(" {");
                sb.append(String.join(", ", e.getRequestBody().getFields().entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue())
                        .toList()));
                sb.append("}");
            }
        }
        if (!e.getPathParams().isEmpty()) {
            sb.append(" path:{").append(e.getPathParams().stream()
                    .map(p -> p.getName() + ":" + p.getJavaType()).collect(java.util.stream.Collectors.joining(",")))
                    .append("}");
        }
        if (!e.getQueryParams().isEmpty()) {
            sb.append(" query:{").append(e.getQueryParams().stream()
                    .map(p -> p.getName() + ":" + p.getJavaType()).collect(java.util.stream.Collectors.joining(",")))
                    .append("}");
        }
        if (!e.getResponseFields().isEmpty()) {
            sb.append(" response:{").append(e.getResponseFields().entrySet().stream()
                    .map(en -> en.getKey() + ":" + en.getValue()).collect(java.util.stream.Collectors.joining(",")))
                    .append("}");
        }
        return sb.toString();
    }

    /**
     * Produces a compact summary of a test class for use in the gap-fill prompt.
     * Includes: class declaration, fields (for variable names), setUp() body,
     * and a list of existing test method names (so LLM avoids duplicates and
     * knows what's already covered).
     */
    String buildTestClassSummary(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return "";

        StringBuilder sb = new StringBuilder();

        // 1. Extract package + class declaration line
        for (String line : sourceCode.split("\n")) {
            String t = line.strip();
            if (t.startsWith("package ") || t.startsWith("public class ") || t.startsWith("class ")) {
                sb.append(t).append("\n");
            }
        }

        // 2. Extract field declarations (lines with private/protected/String/int/long etc. before any @Test)
        boolean inTestMethod = false;
        int depth = 0;
        for (String line : sourceCode.split("\n")) {
            String t = line.strip();
            if (t.equals("@Test") || t.startsWith("@Test(")) { inTestMethod = true; }
            if (inTestMethod) {
                for (char c : t.toCharArray()) {
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
                if (depth <= 0) { inTestMethod = false; depth = 0; }
                continue;
            }
            // Field: line that looks like a field declaration (has type + name + ; but no method call)
            if ((t.startsWith("private ") || t.startsWith("protected ") || t.startsWith("static "))
                    && t.endsWith(";") && !t.contains("(")) {
                sb.append("  ").append(t).append("\n");
            }
        }

        // 3. Extract setUp() / @BeforeAll method body (contains base URL, auth calls etc.)
        java.util.regex.Matcher setUp = java.util.regex.Pattern
                .compile("@BeforeAll[\\s\\S]*?void\\s+\\w+\\s*\\([^)]*\\)[\\s\\S]*?\\{([\\s\\S]*?)\\n    \\}",
                        java.util.regex.Pattern.DOTALL)
                .matcher(sourceCode);
        if (setUp.find()) {
            sb.append("  @BeforeAll setUp() {\n").append(truncate(setUp.group(1).strip(), 500)).append("\n  }\n");
        }

        // 4. List existing @Test method names to avoid duplicates
        java.util.List<String> methodNames = new java.util.ArrayList<>();
        java.util.regex.Matcher methods = java.util.regex.Pattern
                .compile("void\\s+(\\w+)\\s*\\(")
                .matcher(sourceCode);
        while (methods.find()) {
            String name = methods.group(1);
            if (!name.equals("setUp") && !name.startsWith("set") && !name.startsWith("get")) {
                methodNames.add(name);
            }
        }
        if (!methodNames.isEmpty()) {
            sb.append("  // Existing test methods (do NOT duplicate these):\n");
            methodNames.forEach(n -> sb.append("  //   ").append(n).append("()\n"));
        }

        // 5. Include first @Test method body as API call pattern example
        // This shows the gap-fill LLM the exact RestAssured call style (param vs multiPart vs body)
        java.util.regex.Matcher firstTest = java.util.regex.Pattern
                .compile("@Test\\s+void\\s+\\w+\\s*\\(\\)[^{]*\\{([\\s\\S]*?)\\n    \\}")
                .matcher(sourceCode);
        if (firstTest.find()) {
            sb.append("  // Example API call pattern from existing test (follow this style):\n");
            String example = firstTest.group(0).replaceAll("(?m)^", "  ");
            sb.append(truncate(example, 600)).append("\n");
        }

        return sb.toString().strip();
    }

    private String formatEndpoint(EndpointInfo e) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(e.getHttpMethod()).append(" ").append(e.getFullPath()).append("\n");
        sb.append("Method: `").append(e.getMethodName()).append("`\n");
        String rt = e.getResponseType();
        sb.append("Response type: `").append(rt).append("`");
        if (rt != null && !rt.contains("ResponseEntity")) {
            sb.append(" ⚠ plain DTO — HTTP status will be **200** (not 201)");
        }
        sb.append("\n");
        if (e.isRequiresAuth()) {
            String role = e.getRequiredRole();
            if (isAdminOnly(role)) {
                sb.append("⚠ Requires role: ").append(role).append(" — use `\"Bearer \" + adminToken`\n");
            } else if (role != null) {
                sb.append("⚠ Requires role: ").append(role).append(" — use `\"Bearer \" + token`\n");
            } else {
                sb.append("⚠ Requires authentication — use `\"Bearer \" + token`\n");
            }
        }
        if (!e.getPathParams().isEmpty())   { sb.append("Path params:\n");   e.getPathParams().forEach(p -> sb.append(formatParam(p))); }
        if (!e.getQueryParams().isEmpty())  { sb.append("Query params:\n");  e.getQueryParams().forEach(p -> sb.append(formatParam(p))); }
        if (!e.getHeaderParams().isEmpty()) { sb.append("Header params:\n"); e.getHeaderParams().forEach(p -> sb.append(formatParam(p))); }
        if (e.getRequestBody() != null)     sb.append(formatRequestBody(e.getRequestBody()));
        if (!e.getConsumes().isEmpty())     sb.append("Consumes: ").append(String.join(", ", e.getConsumes())).append("\n");
        if (!e.getProduces().isEmpty())     sb.append("Produces: ").append(String.join(", ", e.getProduces())).append("\n");
        if (!e.getResponseFields().isEmpty()) {
            sb.append("Response fields:\n");
            e.getResponseFields().forEach((name, type) -> sb.append("  - `").append(name).append("`: ").append(type).append("\n"));
        }
        if (e.getMethodSource() != null && !e.getMethodSource().isBlank()) {
            sb.append("Source (first lines):\n```java\n").append(e.getMethodSource()).append("\n```\n");
        }
        return sb.toString();
    }

    private String formatParam(ParamInfo p) {
        StringBuilder sb = new StringBuilder("  - `").append(p.getName()).append("` (").append(p.getJavaType()).append(")");
        if (!p.isRequired()) sb.append(" optional");
        if (p.getDefaultValue() != null) sb.append(", default: `").append(p.getDefaultValue()).append("`");
        if (!p.getConstraints().isEmpty()) sb.append(", constraints: ").append(p.getConstraints());
        return sb.append("\n").toString();
    }

    private String formatRequestBody(RequestBodyInfo body) {
        StringBuilder sb = new StringBuilder("Request body: `").append(body.getJavaType()).append("`");
        if (body.isValidated()) sb.append(" (@Valid)");
        if (body.getQualifiedJavaType() != null && !body.getQualifiedJavaType().equals(body.getJavaType())) {
            sb.append(" (import: `").append(body.getQualifiedJavaType()).append("`)");
        }
        sb.append("\n");
        if (!body.getFields().isEmpty()) {
            sb.append("  Fields:\n");
            body.getFields().forEach((name, type) -> sb.append("  - `").append(name).append("`: ").append(type).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Returns true if the required role string implies admin-level access only
     * (i.e. does NOT also allow regular users). Examples:
     * "ADMIN" → true, "ADMIN" → true, "USER or ADMIN" → false, "USER" → false.
     */
    private boolean isAdminOnly(String role) {
        if (role == null) return false;
        String upper = role.toUpperCase();
        // Admin-only if it contains an admin-like role AND no user-like role
        boolean hasAdmin = upper.contains("ADMIN") || upper.contains("OWNER") || upper.contains("SUPER");
        boolean hasUser  = upper.contains("USER")  || upper.contains("MEMBER") || upper.contains("MOD");
        return hasAdmin && !hasUser;
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n... (truncated)";
    }
}
