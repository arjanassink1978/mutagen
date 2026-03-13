package dev.mutagen.mutation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.ArrayList;
import java.util.List;

/** Parsed representation of Pitest's {@code mutations.xml} report. */
@JacksonXmlRootElement(localName = "mutations")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MutationReport {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "mutation")
    private List<Mutant> mutations = new ArrayList<>();

    // ---------------------------------------------------------------

    public List<Mutant> getMutations()       { return mutations; }
    public void setMutations(List<Mutant> m) { this.mutations = m; }

    public List<Mutant> getSurvivedMutants() {
        return mutations.stream().filter(Mutant::isSurvived).toList();
    }

    public int getKilledCount() {
        return (int) mutations.stream()
                .filter(m -> m.getStatusEnum() == Mutant.Status.KILLED)
                .count();
    }

    public int getSurvivedCount() {
        return (int) mutations.stream().filter(Mutant::isSurvived).count();
    }

    public int getNoCoverageCount() {
        return (int) mutations.stream()
                .filter(m -> m.getStatusEnum() == Mutant.Status.NO_COVERAGE)
                .count();
    }

    public int getTotalCount() { return mutations.size(); }

    /**
     * Mutation score as 0–100, consistent with Pitest's definition:
     * {@code killed / (killed + survived + no_coverage)}.
     * Returns 100 if there are no mutants.
     */
    public double getMutationScore() {
        int denominator = getKilledCount() + getSurvivedCount() + getNoCoverageCount();
        if (denominator == 0) return 100.0;
        return (getKilledCount() * 100.0) / denominator;
    }
}
