package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.mutation.model.MutationReport;

import java.util.List;

/**
 * The outcome of a {@link MutationLoopService#run} call.
 *
 * @param tests         the (potentially augmented) generated tests after the loop
 * @param initialScore  mutation score before any LLM gap-fill (first pitest run)
 * @param finalScore    mutation score after the last pitest run
 * @param iterationsRun number of pitest runs executed (≥ 1)
 * @param lastReport    the last parsed {@link MutationReport}
 * @param backendPort   the port the backend was running on during the mutation loop
 */
public record MutationLoopResult(
        List<GeneratedTest> tests,
        double initialScore,
        double finalScore,
        int iterationsRun,
        MutationReport lastReport,
        int backendPort
) {
    public boolean thresholdMet(int threshold) {
        return finalScore >= threshold;
    }
}
