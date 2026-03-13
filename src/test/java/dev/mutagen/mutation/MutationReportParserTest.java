package dev.mutagen.mutation;

import dev.mutagen.mutation.model.Mutant;
import dev.mutagen.mutation.model.MutationReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

class MutationReportParserTest {

    private final MutationReportParser parser = new MutationReportParser();

    @Test
    void parse_countsAllMutants() throws IOException {
        MutationReport report = parseFixture();
        // Fixture: 1 KILLED + 2 SURVIVED + 1 NO_COVERAGE = 4
        assertThat(report.getTotalCount()).isEqualTo(4);
    }

    @Test
    void parse_correctKilledCount() throws IOException {
        assertThat(parseFixture().getKilledCount()).isEqualTo(1);
    }

    @Test
    void parse_correctSurvivedCount() throws IOException {
        assertThat(parseFixture().getSurvivedCount()).isEqualTo(2);
    }

    @Test
    void parse_correctNoCoverageCount() throws IOException {
        assertThat(parseFixture().getNoCoverageCount()).isEqualTo(1);
    }

    @Test
    void parse_mutationScore_is25Percent() throws IOException {
        // 1 killed / (1 + 2 + 1) total = 25%
        assertThat(parseFixture().getMutationScore()).isEqualTo(25.0);
    }

    @Test
    void parse_survivedMutants_haveCorrectFields() throws IOException {
        MutationReport report = parseFixture();
        Mutant survived = report.getSurvivedMutants().stream()
                .filter(m -> "getUserById".equals(m.getMutatedMethod()))
                .findFirst()
                .orElseThrow();

        assertThat(survived.getMutatedClass()).isEqualTo("com.example.controller.UserController");
        assertThat(survived.getLineNumber()).isEqualTo(38);
        assertThat(survived.getMutatorShortName()).isEqualTo("ConditionalsBoundaryMutator");
        assertThat(survived.getDescription()).isEqualTo("changed conditional boundary");
    }

    @Test
    void parse_killedMutant_isSurvivedReturnsFalse() throws IOException {
        MutationReport report = parseFixture();
        Mutant killed = report.getMutations().stream()
                .filter(m -> m.getStatusEnum() == Mutant.Status.KILLED)
                .findFirst()
                .orElseThrow();

        assertThat(killed.isSurvived()).isFalse();
        assertThat(killed.getMutatedMethod()).isEqualTo("createUser");
    }

    // ------------------------------------------------------------------

    private MutationReport parseFixture() throws IOException {
        return parser.parse(Paths.get("src/test/resources/fixtures/mutations.xml"));
    }
}
