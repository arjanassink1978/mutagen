package dev.mutagen.mutation;

import dev.mutagen.mutation.model.Mutant;
import dev.mutagen.mutation.model.MutationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MutationReportTest {

    @Test
    void getMutationScore_allKilled_returns100() {
        MutationReport report = buildReport(3, 0, 0);
        assertThat(report.getMutationScore()).isEqualTo(100.0);
    }

    @Test
    void getMutationScore_noneKilled_returns0() {
        MutationReport report = buildReport(0, 3, 0);
        assertThat(report.getMutationScore()).isEqualTo(0.0);
    }

    @Test
    void getMutationScore_mixed_calculatesCorrectly() {
        // 1 killed, 2 survived, 1 no_coverage → 1/4 = 25%
        MutationReport report = buildReport(1, 2, 1);
        assertThat(report.getMutationScore()).isEqualTo(25.0);
    }

    @Test
    void getMutationScore_noMutants_returns100() {
        MutationReport report = new MutationReport();
        assertThat(report.getMutationScore()).isEqualTo(100.0);
    }

    @Test
    void getSurvivedMutants_filtersCorrectly() {
        MutationReport report = buildReport(2, 3, 1);
        assertThat(report.getSurvivedMutants()).hasSize(3);
        assertThat(report.getSurvivedMutants())
                .allMatch(Mutant::isSurvived);
    }

    @Test
    void getKilledCount_returnsCorrectCount() {
        MutationReport report = buildReport(4, 1, 0);
        assertThat(report.getKilledCount()).isEqualTo(4);
    }

    @Test
    void getNoCoverageCount_returnsCorrectCount() {
        MutationReport report = buildReport(1, 1, 5);
        assertThat(report.getNoCoverageCount()).isEqualTo(5);
    }

    @Test
    void getTotalCount_sumsAllStatuses() {
        MutationReport report = buildReport(2, 3, 1);
        assertThat(report.getTotalCount()).isEqualTo(6);
    }

    @Test
    void mutant_isSurvived_onlyForSurvivedStatus() {
        assertThat(mutantWithStatus("SURVIVED").isSurvived()).isTrue();
        assertThat(mutantWithStatus("KILLED").isSurvived()).isFalse();
        assertThat(mutantWithStatus("NO_COVERAGE").isSurvived()).isFalse();
        assertThat(mutantWithStatus("TIMED_OUT").isSurvived()).isFalse();
    }

    @Test
    void mutant_getMutatorShortName_stripsPackage() {
        Mutant m = new Mutant();
        m.setMutator("org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator");
        assertThat(m.getMutatorShortName()).isEqualTo("ConditionalsBoundaryMutator");
    }

    @Test
    void mutant_getMutatorShortName_noPackage_returnsAsIs() {
        Mutant m = new Mutant();
        m.setMutator("ConditionalsBoundaryMutator");
        assertThat(m.getMutatorShortName()).isEqualTo("ConditionalsBoundaryMutator");
    }

    @Test
    void mutant_getMutatorShortName_nullMutator_returnsEmpty() {
        assertThat(new Mutant().getMutatorShortName()).isEqualTo("");
    }

    // ------------------------------------------------------------------

    private MutationReport buildReport(int killed, int survived, int noCoverage) {
        MutationReport report = new MutationReport();
        List<Mutant> mutants = new java.util.ArrayList<>();
        for (int i = 0; i < killed;     i++) mutants.add(mutantWithStatus("KILLED"));
        for (int i = 0; i < survived;   i++) mutants.add(mutantWithStatus("SURVIVED"));
        for (int i = 0; i < noCoverage; i++) mutants.add(mutantWithStatus("NO_COVERAGE"));
        report.setMutations(mutants);
        return report;
    }

    private Mutant mutantWithStatus(String status) {
        Mutant m = new Mutant();
        m.setStatus(status);
        m.setMutatedClass("com.example.controller.UserController");
        m.setMutatedMethod("getUser");
        m.setLineNumber(42);
        m.setDescription("test mutation");
        m.setMutator("org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator");
        return m;
    }
}
