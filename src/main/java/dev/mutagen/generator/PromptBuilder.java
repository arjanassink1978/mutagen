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
                                          int mutationScore, int targetScore) {
        return """
                ## Current situation
                Mutation score: %d%% (target: %d%%)

                ## Existing test code
                ```java
                %s
                ```

                ## Surviving mutants (Pitest output)
                ```
                %s
                ```

                ## Instruction
                Write additional @Test methods that kill the surviving mutants above.
                Return ONLY the extra test methods, not a full class.
                """.formatted(mutationScore, targetScore,
                              truncate(existingTestCode, 2000),
                              truncate(survivingMutants, 1500));
    }

    private String formatEndpoint(EndpointInfo e) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(e.getHttpMethod()).append(" ").append(e.getFullPath()).append("\n");
        sb.append("Method: `").append(e.getMethodName()).append("`\n");
        sb.append("Response type: `").append(e.getResponseType()).append("`\n");
        if (e.isRequiresAuth()) sb.append("⚠ Requires authentication (`@PreAuthorize` or `@Secured`)\n");
        if (!e.getPathParams().isEmpty())   { sb.append("Path params:\n");   e.getPathParams().forEach(p -> sb.append(formatParam(p))); }
        if (!e.getQueryParams().isEmpty())  { sb.append("Query params:\n");  e.getQueryParams().forEach(p -> sb.append(formatParam(p))); }
        if (!e.getHeaderParams().isEmpty()) { sb.append("Header params:\n"); e.getHeaderParams().forEach(p -> sb.append(formatParam(p))); }
        if (e.getRequestBody() != null)     sb.append(formatRequestBody(e.getRequestBody()));
        if (!e.getProduces().isEmpty())     sb.append("Produces: ").append(String.join(", ", e.getProduces())).append("\n");
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

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n... (truncated)";
    }
}
