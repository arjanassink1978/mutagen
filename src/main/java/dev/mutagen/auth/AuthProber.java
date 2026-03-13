package dev.mutagen.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.HttpMethod;
import dev.mutagen.model.RequestBodyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Probes the running backend to discover a working signup/signin flow and
 * returns an {@link AuthSetupInfo} with verified payloads and the token field name.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Find POST endpoints that look like signup and signin based on path keywords and DTO fields.</li>
 *   <li>Build a test body for signup from the parsed DTO fields.</li>
 *   <li>Try signup — if it fails, retry without optional-looking fields.</li>
 *   <li>Try signin and scan the response for a JWT-like string field.</li>
 * </ol>
 */
public class AuthProber {

    private static final Logger log = LoggerFactory.getLogger(AuthProber.class);

    private static final Set<String> SIGNUP_KEYWORDS = Set.of("signup", "sign-up", "register", "registration", "createuser", "create-user");
    private static final Set<String> SIGNIN_KEYWORDS = Set.of("signin", "sign-in", "login", "log-in", "authenticate", "token", "auth");

    /** Field names whose values must be unique per test run. */
    private static final Set<String> UNIQUE_FIELDS = Set.of("username", "email", "name", "login", "handle");

    /** Field names that are likely enum/role collections — skip them so the server assigns defaults. */
    private static final Set<String> SKIP_FIELDS = Set.of("roles", "role", "authorities", "permissions", "type", "status");

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public AuthProber(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http    = HttpClient.newHttpClient();
        this.mapper  = new ObjectMapper();
    }

    /**
     * Attempts to discover a working auth flow from the given endpoint list.
     *
     * <p>If {@code MUTAGEN_AUTH_USERNAME} and {@code MUTAGEN_AUTH_PASSWORD} environment variables
     * are set, the signup phase is skipped entirely and those credentials are used directly for
     * the signin probe. This is useful when signup is not publicly available or a test user
     * already exists. Optionally set {@code MUTAGEN_AUTH_SIGNIN_PATH} to override the detected
     * signin endpoint path.
     *
     * @return {@link AuthSetupInfo} — call {@link AuthSetupInfo#isAvailable()} to check success
     */
    public AuthSetupInfo probe(List<EndpointInfo> allEndpoints) {
        String fixedUsername = System.getenv("MUTAGEN_AUTH_USERNAME");
        String fixedPassword = System.getenv("MUTAGEN_AUTH_PASSWORD");
        String fixedSigninPath = System.getenv("MUTAGEN_AUTH_SIGNIN_PATH");

        if (fixedUsername != null && fixedPassword != null) {
            log.info("AuthProber: using provided credentials (MUTAGEN_AUTH_USERNAME / MUTAGEN_AUTH_PASSWORD)");
            return probeWithCredentials(fixedUsername, fixedPassword, fixedSigninPath, allEndpoints);
        }

        List<EndpointInfo> signupCandidates = findCandidates(allEndpoints, SIGNUP_KEYWORDS);
        List<EndpointInfo> signinCandidates = findCandidates(allEndpoints, SIGNIN_KEYWORDS);

        if (signinCandidates.isEmpty()) {
            log.info("AuthProber: no signin endpoint found — skipping auth probing");
            return new AuthSetupInfo(null, null, null, null, null);
        }

        String unique = "probe_" + UUID.randomUUID().toString().substring(0, 8);

        // Try signup first (optional — some APIs don't need it or it may not exist)
        String signupPath = null;
        String signupBody = null;
        for (EndpointInfo candidate : signupCandidates) {
            String path = candidate.getFullPath();
            String body = buildSignupBody(candidate, unique);
            int status  = post(path, body);
            if (status >= 200 && status < 300) {
                log.info("AuthProber: signup succeeded at {} ({})", path, status);
                signupPath = path;
                signupBody = body;
                break;
            } else {
                log.debug("AuthProber: signup candidate {} returned {}", path, status);
            }
        }

        // Try signin
        for (EndpointInfo candidate : signinCandidates) {
            String path = candidate.getFullPath();
            // Skip if it looks like a signup endpoint (path contains signup keywords)
            if (isSignupPath(path)) continue;

            String body   = buildSigninBody(candidate, unique);
            String result = postForBody(path, body);
            if (result == null) continue;

            String tokenField = detectTokenField(result);
            if (tokenField != null) {
                log.info("AuthProber: signin succeeded at {} — token field: '{}'", path, tokenField);
                String signupTemplate = signupBody != null ? toTemplate(signupBody, unique) : null;
                String signinTemplate = toTemplate(body, unique);
                return new AuthSetupInfo(signupPath, signupTemplate, path, signinTemplate, tokenField);
            } else {
                log.debug("AuthProber: signin at {} returned body without recognizable token field", path);
            }
        }

        log.warn("AuthProber: could not discover a working auth flow");
        return new AuthSetupInfo(null, null, null, null, null);
    }

    // ── Credentials override ─────────────────────────────────────────────────

    /**
     * Signs in with known credentials, skipping the signup phase entirely.
     * Used when {@code MUTAGEN_AUTH_USERNAME} / {@code MUTAGEN_AUTH_PASSWORD} are set.
     */
    private AuthSetupInfo probeWithCredentials(String username, String password,
                                                String signinPathOverride,
                                                List<EndpointInfo> allEndpoints) {
        List<EndpointInfo> signinCandidates = signinPathOverride != null
                ? List.of()   // we'll use the override path directly
                : findCandidates(allEndpoints, SIGNIN_KEYWORDS);

        // Build the paths to try: override first, then discovered candidates
        List<String> pathsToTry = new java.util.ArrayList<>();
        if (signinPathOverride != null) pathsToTry.add(signinPathOverride);
        signinCandidates.stream()
                .map(EndpointInfo::getFullPath)
                .filter(p -> !isSignupPath(p))
                .forEach(pathsToTry::add);

        if (pathsToTry.isEmpty()) {
            log.warn("AuthProber: credentials provided but no signin path found — "
                    + "set MUTAGEN_AUTH_SIGNIN_PATH to specify it");
            return new AuthSetupInfo(null, null, null, null, null);
        }

        // Build signin body using the provided credentials
        String usernameKey = username.contains("@") ? "email" : "username";
        String signinBodyTemplate = "{\"" + usernameKey + "\":\"UNIQUE_USER\",\"password\":\"UNIQUE_PASS\"}";
        String signinBody = signinBodyTemplate
                .replace("UNIQUE_USER", username)
                .replace("UNIQUE_PASS", password);

        for (String path : pathsToTry) {
            if (isSignupPath(path)) continue;
            String result = postForBody(path, signinBody);
            if (result == null) continue;

            String tokenField = detectTokenField(result);
            if (tokenField != null) {
                log.info("AuthProber: signin with provided credentials succeeded at {} — token field: '{}'", path, tokenField);
                // Build a template that uses the fixed credentials (no unique placeholder needed)
                String template = "{\"" + usernameKey + "\":\"" + username + "\",\"password\":\"" + password + "\"}";
                return new AuthSetupInfo(null, null, path, template, tokenField);
            }
        }

        log.warn("AuthProber: signin with provided credentials failed on all candidates");
        return new AuthSetupInfo(null, null, null, null, null);
    }

    // ── Candidate discovery ─────────────────────────────────────────────────

    private List<EndpointInfo> findCandidates(List<EndpointInfo> endpoints, Set<String> keywords) {
        return endpoints.stream()
                .filter(e -> e.getHttpMethod() == HttpMethod.POST)
                .filter(e -> pathMatchesAny(e.getFullPath(), keywords))
                .sorted(Comparator.comparingInt(e -> pathScore(e.getFullPath(), keywords)))
                .toList();
    }

    private boolean pathMatchesAny(String path, Set<String> keywords) {
        String lower = path.toLowerCase().replace("-", "").replace("_", "");
        return keywords.stream().anyMatch(k -> lower.contains(k.replace("-", "").replace("_", "")));
    }

    /** Lower score = better match (shorter, more specific path). */
    private int pathScore(String path, Set<String> keywords) {
        return path.length();
    }

    private boolean isSignupPath(String path) {
        return pathMatchesAny(path, SIGNUP_KEYWORDS);
    }

    // ── Body builders ────────────────────────────────────────────────────────

    private String buildSignupBody(EndpointInfo endpoint, String unique) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (endpoint.getRequestBody() != null) {
            Map<String, String> fields = endpoint.getRequestBody().getFields();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String name = entry.getKey();
                String type = entry.getValue().toLowerCase();
                if (SKIP_FIELDS.contains(name.toLowerCase())) continue;
                body.put(name, generateValue(name, type, unique));
            }
        } else {
            // Fallback: minimal body
            body.put("username", "user_" + unique);
            body.put("email",    "user_" + unique + "@example.com");
            body.put("password", "Test1234!");
        }
        return toJson(body);
    }

    private String buildSigninBody(EndpointInfo endpoint, String unique) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (endpoint.getRequestBody() != null) {
            Map<String, String> fields = endpoint.getRequestBody().getFields();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String name = entry.getKey();
                String type = entry.getValue().toLowerCase();
                if (SKIP_FIELDS.contains(name.toLowerCase())) continue;
                // For signin we only want credential-like fields
                if (isCredentialField(name)) {
                    body.put(name, generateValue(name, type, unique));
                }
            }
        }
        if (body.isEmpty()) {
            body.put("username", "user_" + unique);
            body.put("password", "Test1234!");
        }
        return toJson(body);
    }

    private Object generateValue(String fieldName, String fieldType, String unique) {
        String name = fieldName.toLowerCase();
        if (name.equals("email"))                      return "user_" + unique + "@example.com";
        if (UNIQUE_FIELDS.contains(name))              return "user_" + unique;
        if (name.contains("password") || name.equals("pass") || name.equals("pwd")) return "Test1234!";
        if (name.equals("name") || name.contains("firstname") || name.contains("lastname")) return "Test User";
        if (fieldType.contains("int") || fieldType.contains("long"))  return 1;
        if (fieldType.contains("bool"))                return true;
        return "test_" + name;
    }

    private boolean isCredentialField(String name) {
        String lower = name.toLowerCase();
        return lower.contains("user") || lower.contains("email") || lower.contains("login")
                || lower.contains("pass") || lower.contains("pwd") || lower.contains("credential");
    }

    // ── Token detection ──────────────────────────────────────────────────────

    /**
     * Scans JSON response body for a field that looks like a JWT or long token string.
     * Returns the field name, or null if none found.
     */
    private String detectTokenField(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            // First pass: look for known names
            for (String candidate : List.of("token", "accessToken", "access_token", "jwt", "idToken", "id_token", "authToken")) {
                JsonNode node = root.get(candidate);
                if (node != null && node.isTextual() && node.asText().length() > 20) {
                    return candidate;
                }
            }
            // Second pass: any string field with JWT-like content (contains two dots)
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().isTextual()) {
                    String value = entry.getValue().asText();
                    if (value.length() > 20 && value.chars().filter(c -> c == '.').count() == 2) {
                        return entry.getKey();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("AuthProber: could not parse signin response as JSON: {}", e.getMessage());
        }
        return null;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private int post(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            log.debug("AuthProber: POST {} failed: {}", path, e.getMessage());
            return -1;
        }
    }

    private String postForBody(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return (response.statusCode() >= 200 && response.statusCode() < 300) ? response.body() : null;
        } catch (Exception e) {
            log.debug("AuthProber: POST {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Replaces occurrences of the probed unique value with UNIQUE_USER / UNIQUE_EMAIL placeholders
     * so the returned body can be used as a template in the LLM prompt.
     */
    private String toTemplate(String body, String unique) {
        return body
                .replace(unique + "@example.com", "UNIQUE_EMAIL")
                .replace(unique, "UNIQUE_USER");
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize probe body", e);
        }
    }
}
