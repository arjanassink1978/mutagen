package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;

import java.util.List;

/**
 * Provider-agnostic interface for Git platform operations.
 *
 * <p>Implementations: {@link GitLabClient}, {@link GitHubClient}.
 * Use {@link GitClientFactory#fromEnvironment()} to get the right implementation.
 */
public interface GitClient {

    /**
     * Creates a new branch, commits all generated test files to it, and opens an MR/PR.
     *
     * @param branchName  the name of the new branch to create
     * @param tests       the generated test files to commit
     * @param description the MR/PR body (markdown)
     * @return the URL of the created MR/PR
     * @throws GitException if any API call fails
     */
    String openPullRequest(String branchName, List<GeneratedTest> tests, String description);

    /** Provider name for logging, e.g. {@code "gitlab"} or {@code "github"}. */
    String providerName();
}
