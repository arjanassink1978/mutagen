package dev.mutagen.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Statically analyses a Spring Boot project's source code to extract the security
 * configuration needed for generating the {@code AbstractIT} base class.
 *
 * <p>No HTTP calls are made — everything is derived from the Java source files.
 */
public class AuthAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AuthAnalyzer.class);

    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("(?:public|abstract|final)\\s+(?:class|interface)\\s+(\\w+)");

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("import\\s+[\\w.]+\\.(\\w+);");

    public AuthContext analyze(Path repoPath) {
        Path srcMain = repoPath.resolve("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            log.debug("No src/main/java in {} — skipping auth analysis", repoPath);
            return AuthContext.noAuth();
        }

        boolean securityEnabled = hasSpringSecurity(repoPath);
        if (!securityEnabled) {
            log.info("No Spring Security detected — generating tests without auth setup");
            return AuthContext.noAuth();
        }

        log.info("Spring Security detected — analysing auth configuration");

        List<Path> javaFiles = listJavaFiles(srcMain);

        String securityConfig  = findSecurityConfig(javaFiles);
        String authController  = findAuthController(javaFiles);
        List<String> authDtos  = authController != null
                ? findDtosForSource(authController, javaFiles) : List.of();
        String jwtFilter       = findJwtFilter(javaFiles);

        return new AuthContext(true, securityConfig, authController, authDtos, jwtFilter);
    }

    // -----------------------------------------------------------------------

    private boolean hasSpringSecurity(Path repoPath) {
        Path pom = repoPath.resolve("pom.xml");
        if (!Files.exists(pom)) return false;
        try {
            String content = Files.readString(pom);
            return content.contains("spring-security")
                    || content.contains("spring-boot-starter-security");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Finds the class that defines a {@code SecurityFilterChain} bean or extends
     * {@code WebSecurityConfigurerAdapter}.
     */
    private String findSecurityConfig(List<Path> javaFiles) {
        for (Path file : javaFiles) {
            try {
                String src = Files.readString(file);
                if ((src.contains("SecurityFilterChain") && src.contains("@Bean"))
                        || src.contains("WebSecurityConfigurerAdapter")
                        || src.contains("@EnableWebSecurity")) {
                    log.debug("Found security config: {}", file.getFileName());
                    return src;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Finds the controller that handles authentication (signup / signin / login / token).
     * Matches on {@code @RequestMapping} / {@code @PostMapping} path values.
     */
    private String findAuthController(List<Path> javaFiles) {
        Pattern mappingPattern = Pattern.compile(
                "@(?:Request|Post|Get)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

        for (Path file : javaFiles) {
            try {
                String src = Files.readString(file);
                if (!src.contains("@RestController") && !src.contains("@Controller")) continue;

                Matcher m = mappingPattern.matcher(src);
                while (m.find()) {
                    String path = m.group(1).toLowerCase();
                    if (path.contains("auth") || path.contains("login")
                            || path.contains("signup") || path.contains("signin")
                            || path.contains("register") || path.contains("token")) {
                        log.debug("Found auth controller: {}", file.getFileName());
                        return src;
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Finds the source of DTO classes referenced in the given source snippet.
     * Looks for imported simple class names that have fields typical of request DTOs
     * (i.e. not controllers or services).
     */
    private List<String> findDtosForSource(String source, List<Path> javaFiles) {
        List<String> dtoSources = new ArrayList<>();

        // Collect simple class names that appear as parameter types or return types
        Matcher importMatcher = IMPORT_PATTERN.matcher(source);
        List<String> importedSimpleNames = new ArrayList<>();
        while (importMatcher.find()) {
            importedSimpleNames.add(importMatcher.group(1));
        }

        for (Path file : javaFiles) {
            String fileName = file.getFileName().toString().replace(".java", "");
            if (!importedSimpleNames.contains(fileName)) continue;
            try {
                String src = Files.readString(file);
                // Only include plain data classes (records, or classes without Spring annotations)
                if (src.contains("@RestController") || src.contains("@Service")
                        || src.contains("@Repository") || src.contains("@Component")) continue;
                if (src.contains("@Entity") && !src.contains("record ")) continue;

                log.debug("Found auth DTO: {}", file.getFileName());
                dtoSources.add(src);
            } catch (IOException ignored) {}
        }
        return dtoSources;
    }

    /**
     * Finds a JWT filter — a class that extends {@code OncePerRequestFilter} and references
     * JWT-related terms.
     */
    private String findJwtFilter(List<Path> javaFiles) {
        for (Path file : javaFiles) {
            try {
                String src = Files.readString(file);
                if (src.contains("OncePerRequestFilter")
                        && (src.contains("jwt") || src.contains("Jwt") || src.contains("JWT")
                            || src.contains("Bearer") || src.contains("token"))) {
                    log.debug("Found JWT filter: {}", file.getFileName());
                    return src;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private List<Path> listJavaFiles(Path srcMain) {
        try (Stream<Path> stream = Files.walk(srcMain)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list Java files in {}: {}", srcMain, e.getMessage());
            return List.of();
        }
    }
}
