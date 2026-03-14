package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs mutation testing against the target repository.
 *
 * <p>The only production implementation is {@link PitestRunner}.
 * In tests, use {@code MockMutationRunner}.
 */
public interface MutationRunner {

    /**
     * Executes mutation testing for the given tests in the target repo.
     *
     * @param tests    the generated test classes (must already be written to disk)
     * @param repoPath root of the target Spring Boot project
     * @return path to the produced {@code mutations.xml} report
     * @throws IOException     if the subprocess fails or the report is not produced
     * @throws PitestException if the mutation tool exits with a non-zero status
     */
    Path run(List<GeneratedTest> tests, Path repoPath) throws IOException;

    /**
     * Prepares the target repo for test compilation and execution.
     * Called once before the first {@code mvn test} run so that any required
     * test dependencies (e.g. RestAssured) are on the classpath.
     *
     * <p>Default implementation is a no-op.
     */
    default void prepareRepo(Path repoPath) throws IOException {}
}
