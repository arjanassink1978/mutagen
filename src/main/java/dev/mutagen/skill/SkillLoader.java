package dev.mutagen.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads skills by type with user-override support.
 *
 * <p>Priority order (first match wins):
 * <ol>
 *   <li>User override — file in directory set via {@code MUTAGEN_SKILLS_PATH}</li>
 *   <li>Built-in skill — bundled in the JAR under {@code resources/skills/}</li>
 * </ol>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final Map<Skill.Type, String> SKILL_FILES = Map.of(
            Skill.Type.RESTASSURED_TEST,      "restassured-test.md",
            Skill.Type.MUTATION_GAP_ANALYSIS, "mutation-gap-analysis.md",
            Skill.Type.TEST_IMPROVEMENT,      "test-improvement.md"
    );

    private final Optional<Path> customSkillsPath;
    private final Map<Skill.Type, Skill> cache = new ConcurrentHashMap<>();

    public SkillLoader() {
        this.customSkillsPath = Optional.empty();
    }

    public SkillLoader(Path customSkillsPath) {
        this.customSkillsPath = Optional.ofNullable(customSkillsPath);
    }

    /** Creates a loader using {@code MUTAGEN_SKILLS_PATH} env var if set. */
    public static SkillLoader fromEnvironment() {
        String skillsPath = System.getenv("MUTAGEN_SKILLS_PATH");
        if (skillsPath != null && !skillsPath.isBlank()) {
            Path path = Path.of(skillsPath);
            if (Files.isDirectory(path)) {
                log.info("Custom skills directory: {}", path.toAbsolutePath());
                return new SkillLoader(path);
            }
            log.warn("MUTAGEN_SKILLS_PATH '{}' is not a valid directory, using built-in skills", skillsPath);
        }
        return new SkillLoader();
    }

    public Skill load(Skill.Type type) {
        return cache.computeIfAbsent(type, this::loadUncached);
    }

    public Skill loadFromPath(Path path, String name) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return new Skill(Skill.Type.CUSTOM, name, content, path.toString());
    }

    private Skill loadUncached(Skill.Type type) {
        String filename = SKILL_FILES.get(type);
        if (filename == null) {
            throw new SkillNotFoundException("No filename configured for skill type: " + type);
        }

        if (customSkillsPath.isPresent()) {
            Path customFile = customSkillsPath.get().resolve(filename);
            if (Files.exists(customFile)) {
                try {
                    String content = Files.readString(customFile, StandardCharsets.UTF_8);
                    log.info("Loaded custom skill: {}", customFile);
                    return new Skill(type, filename, content, customFile.toString());
                } catch (IOException e) {
                    log.warn("Could not read custom skill {}, falling back to built-in", customFile);
                }
            }
        }

        String classpathResource = "/skills/" + filename;
        try (InputStream is = SkillLoader.class.getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new SkillNotFoundException("Built-in skill not found: " + classpathResource);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Loaded built-in skill: {}", classpathResource);
            return new Skill(type, filename, content, "classpath:" + classpathResource);
        } catch (IOException e) {
            throw new SkillNotFoundException("Error loading skill " + classpathResource + ": " + e.getMessage(), e);
        }
    }
}
