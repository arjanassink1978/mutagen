package dev.mutagen.auth;

import java.util.List;

/**
 * Result of static source-code analysis for Spring Security configuration.
 * All fields are raw Java source snippets to be included verbatim in the LLM prompt.
 */
public record AuthContext(
        /** True when spring-security is on the classpath. */
        boolean securityEnabled,
        /** Source of the {@code SecurityFilterChain} / {@code WebSecurityConfigurerAdapter} class, or null. */
        String securityConfigSource,
        /** Source of the auth controller (login / signup endpoints), or null. */
        String authControllerSource,
        /** Sources of DTOs referenced in the auth controller. */
        List<String> authDtoSources,
        /** Source of the JWT filter class, or null. */
        String jwtFilterSource
) {
    public static AuthContext noAuth() {
        return new AuthContext(false, null, null, List.of(), null);
    }

    public boolean hasAuthEndpoints() {
        return authControllerSource != null && !authControllerSource.isBlank();
    }

    /** Concatenates all available source snippets for inclusion in a prompt. */
    public String toPromptSection() {
        if (!securityEnabled) {
            return "## Security\nNo Spring Security detected — endpoints are publicly accessible.";
        }

        var sb = new StringBuilder("## Security configuration\n\n");

        if (securityConfigSource != null) {
            sb.append("### SecurityFilterChain\n```java\n").append(trunc(securityConfigSource, 2000)).append("\n```\n\n");
        }
        if (authControllerSource != null) {
            sb.append("### Auth controller\n```java\n").append(trunc(authControllerSource, 2000)).append("\n```\n\n");
        }
        for (int i = 0; i < authDtoSources.size(); i++) {
            sb.append("### Auth DTO ").append(i + 1).append("\n```java\n")
              .append(trunc(authDtoSources.get(i), 800)).append("\n```\n\n");
        }
        if (jwtFilterSource != null) {
            sb.append("### JWT filter\n```java\n").append(trunc(jwtFilterSource, 1500)).append("\n```\n\n");
        }

        String envUser  = System.getenv("MUTAGEN_AUTH_USERNAME");
        String envPass  = System.getenv("MUTAGEN_AUTH_PASSWORD");
        String envPath  = System.getenv("MUTAGEN_AUTH_SIGNIN_PATH");
        if (envUser != null && envPass != null) {
            sb.append("### Provided credentials (env vars)\n");
            sb.append("- MUTAGEN_AUTH_SIGNIN_PATH=").append(envPath != null ? envPath : "(detect from source)").append("\n");
            sb.append("- MUTAGEN_AUTH_USERNAME=").append(envUser).append("\n");
            sb.append("- MUTAGEN_AUTH_PASSWORD=").append(envPass).append("\n");
            sb.append("Skip the signup step — use these credentials directly in signin.\n\n");
        }

        return sb.toString();
    }

    private static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}
