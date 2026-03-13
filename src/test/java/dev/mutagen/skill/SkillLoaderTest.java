package dev.mutagen.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SkillLoaderTest {

    @Test
    void load_builtinSkill_returnsContent() {
        SkillLoader loader = new SkillLoader();
        Skill skill = loader.load(Skill.Type.RESTASSURED_TEST);

        assertThat(skill).isNotNull();
        assertThat(skill.getContent()).isNotBlank();
        assertThat(skill.getContent()).contains("RestAssured");
        assertThat(skill.getSource()).startsWith("classpath:");
    }

    @Test
    void load_mutationSkill_returnsContent() {
        SkillLoader loader = new SkillLoader();
        Skill skill = loader.load(Skill.Type.MUTATION_GAP_ANALYSIS);

        assertThat(skill.getContent()).contains("mutant");
        assertThat(skill.getType()).isEqualTo(Skill.Type.MUTATION_GAP_ANALYSIS);
    }

    @Test
    void load_cachedOnSecondCall() {
        SkillLoader loader = new SkillLoader();
        Skill first  = loader.load(Skill.Type.RESTASSURED_TEST);
        Skill second = loader.load(Skill.Type.RESTASSURED_TEST);

        assertThat(first).isSameAs(second);
    }

    @Test
    void load_customOverride_prefersCustomFile(@TempDir Path tempDir) throws IOException {
        String customContent = "# Custom override skill\nGeef alleen Java terug.";
        Files.writeString(tempDir.resolve("restassured-test.md"), customContent);

        SkillLoader loader = new SkillLoader(tempDir);
        Skill skill = loader.load(Skill.Type.RESTASSURED_TEST);

        assertThat(skill.getContent()).isEqualTo(customContent);
        assertThat(skill.getSource()).contains(tempDir.toString());
    }

    @Test
    void load_customOverrideMissing_fallsBackToBuiltin(@TempDir Path tempDir) {
        // tempDir is leeg — geen override aanwezig
        SkillLoader loader = new SkillLoader(tempDir);
        Skill skill = loader.load(Skill.Type.RESTASSURED_TEST);

        assertThat(skill.getSource()).startsWith("classpath:");
        assertThat(skill.getContent()).contains("RestAssured");
    }

    @Test
    void loadFromPath_loadsArbitraryFile(@TempDir Path tempDir) throws IOException {
        Path customFile = tempDir.resolve("my-skill.md");
        Files.writeString(customFile, "# Mijn custom skill");

        SkillLoader loader = new SkillLoader();
        Skill skill = loader.loadFromPath(customFile, "my-skill");

        assertThat(skill.getName()).isEqualTo("my-skill");
        assertThat(skill.getContent()).isEqualTo("# Mijn custom skill");
        assertThat(skill.getType()).isEqualTo(Skill.Type.CUSTOM);
    }
}
