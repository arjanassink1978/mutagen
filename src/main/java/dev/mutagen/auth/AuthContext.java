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

    /**
     * Creates an AuthContext backed by a user-supplied {@link AuthConfig}.
     * The {@link #toPromptSection()} of the returned instance outputs a simple,
     * human-readable description of the credentials rather than raw source snippets.
     */
    public static AuthContext fromConfig(AuthConfig config) {
        // We encode the config into authControllerSource as a synthetic prompt string.
        // This keeps the rest of the code (PromptBuilder, TestGeneratorService) unchanged.
        String prompt = buildConfigPromptSection(config);
        return new AuthContext(true, null, prompt, List.of(), null);
    }

    private static String buildConfigPromptSection(AuthConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Auth setup (pre-seeded credentials — do NOT call signup)\n");
        sb.append("The following users already exist in the database.\n\n");
        sb.append("Signin endpoint: POST ").append(config.signinPath())
          .append("  (JSON body: {\"username\":\"...\",\"password\":\"...\"})\n");
        sb.append("The response contains a `token` field with the JWT.\n\n");
        sb.append("Credentials:\n");
        sb.append("- Regular user: username=").append(config.regularUser().username())
          .append(", password=").append(config.regularUser().password())
          .append(" → store in `this.token`\n");
        if (config.hasAdmin()) {
            sb.append("- Admin user:   username=").append(config.adminUser().username())
              .append(", password=").append(config.adminUser().password())
              .append(" → store in `this.adminToken`\n");
        } else {
            sb.append("- No separate admin user — set `this.adminToken = this.token`\n");
        }
        sb.append("\nAlso set: `this.testUsername = \"").append(config.regularUser().username())
          .append("\"; this.testPassword = \"").append(config.regularUser().password()).append("\";`\n");
        return sb.toString();
    }

    public boolean hasAuthEndpoints() {
        return authControllerSource != null && !authControllerSource.isBlank();
    }

    /** Concatenates all available source snippets for inclusion in a prompt. */
    public String toPromptSection() {
        if (!securityEnabled) {
            return "## Security\nNo security — endpoints are publicly accessible. No tokens needed. Leave `token` and `adminToken` null.";
        }

        // Config-based auth: authControllerSource holds the pre-built prompt string (no source snippets)
        if (securityConfigSource == null && authControllerSource != null && authControllerSource.startsWith("## Auth setup")) {
            return authControllerSource;
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
