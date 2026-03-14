package dev.mutagen.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an {@code authorization.md} file and parses it into an {@link AuthConfig}.
 *
 * <p>Expected format:
 * <pre>
 * ## Auth endpoints
 * - POST /api/auth/signup — register new user ...
 * - POST /api/auth/signin — sign in ...
 *
 * ## Pre-seeded users
 * - Regular user: username=user, password=user1234, roles=[ROLE_USER]
 * - Admin user:   username=admin, password=admin1234, roles=[ROLE_ADMIN]
 * </pre>
 */
public class AuthConfigReader {

    private static final Logger log = LoggerFactory.getLogger(AuthConfigReader.class);

    private static final Pattern SIGNIN_PATH  = Pattern.compile("POST\\s+(/\\S+signin\\S*)");
    private static final Pattern SIGNUP_PATH  = Pattern.compile("POST\\s+(/\\S+signup\\S*)");
    private static final Pattern REGULAR_USER = Pattern.compile(
            "(?i)regular\\s+user.*?username\\s*=\\s*(\\S+?)\\s*,\\s*password\\s*=\\s*(\\S+?)(?:[,\\s]|$)");
    private static final Pattern ADMIN_USER   = Pattern.compile(
            "(?i)admin\\s+user.*?username\\s*=\\s*(\\S+?)\\s*,\\s*password\\s*=\\s*(\\S+?)(?:[,\\s]|$)");

    public AuthConfig read(Path authFile) throws IOException {
        String content = Files.readString(authFile);

        String signinPath  = find(SIGNIN_PATH,  content);
        String signupPath  = find(SIGNUP_PATH,  content);
        AuthConfig.Credential regularUser = findCredential(REGULAR_USER, content);
        AuthConfig.Credential adminUser   = findCredential(ADMIN_USER,   content);

        if (signinPath == null) {
            throw new IllegalArgumentException(
                    "authorization.md must contain a line like: POST /api/auth/signin ...");
        }
        if (regularUser == null) {
            throw new IllegalArgumentException(
                    "authorization.md must contain a 'Regular user: username=X, password=Y' line");
        }

        log.info("Auth config loaded: signin={}, regularUser={}, adminUser={}",
                signinPath, regularUser.username(), adminUser != null ? adminUser.username() : "none");

        return new AuthConfig(signinPath, signupPath, regularUser, adminUser);
    }

    private static String find(Pattern p, String content) {
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static AuthConfig.Credential findCredential(Pattern p, String content) {
        Matcher m = p.matcher(content);
        if (!m.find()) return null;
        return new AuthConfig.Credential(m.group(1), m.group(2));
    }
}
