package dev.mutagen.auth;

/**
 * User-provided authentication configuration parsed from an {@code authorization.md} file.
 *
 * <p>Contains only the credentials needed for AbstractIT setup — access rules are derived
 * separately from the Spring Security source (AuthAnalyzer) when needed.
 */
public record AuthConfig(
        /** Path of the sign-in endpoint, e.g. {@code /api/auth/signin}. */
        String signinPath,
        /** Path of the sign-up endpoint, e.g. {@code /api/auth/signup}. May be null. */
        String signupPath,
        /** Credentials for a regular (non-admin) user. */
        Credential regularUser,
        /** Credentials for an admin user. May be null if no admin user exists. */
        Credential adminUser
) {
    public record Credential(String username, String password) {}

    public boolean hasAdmin() {
        return adminUser != null;
    }
}
