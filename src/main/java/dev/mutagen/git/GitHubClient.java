package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link GitClient} implementation for GitHub.
 *
 * <p>Reads configuration from environment variables:
 * <ul>
 *   <li>{@code GITHUB_TOKEN} — personal access token or {@code GITHUB_TOKEN} Actions secret</li>
 *   <li>{@code GITHUB_REPOSITORY} — {@code owner/repo} format (auto-set in GitHub Actions)</li>
 * </ul>
 *
 * <p>Inject a {@link GHRepository} directly (e.g. in tests):
 * {@code new GitHubClient(mockRepo)}.
 */
public class GitHubClient implements GitClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final GHRepository repository;

    public GitHubClient(GHRepository repository) {
        this.repository = repository;
    }

    @Override
    public String openPullRequest(String branchName, List<GeneratedTest> tests, String description) {
        try {
            String defaultBranch = repository.getDefaultBranch();
            String sha = repository.getRef("heads/" + defaultBranch).getObject().getSha();

            log.info("GitHub: creating branch '{}' from '{}'", branchName, defaultBranch);
            repository.createRef("refs/heads/" + branchName, sha);

            log.info("GitHub: committing {} test file(s)", tests.size());
            for (GeneratedTest test : tests) {
                commitFile(branchName, test);
            }

            log.info("GitHub: opening PR");
            var pr = repository.createPullRequest(
                    "test: AI-generated integration tests [mutagen]",
                    branchName,
                    defaultBranch,
                    description);

            String url = pr.getHtmlUrl().toString();
            log.info("GitHub PR created: {}", url);
            return url;

        } catch (IOException e) {
            throw new GitException("GitHub API error: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() { return "github"; }

    // ---------------------------------------------------------------

    private void commitFile(String branchName, GeneratedTest test) throws IOException {
        repository.createContent()
                .branch(branchName)
                .path(test.getRelativeFilePath())
                .content(test.getSourceCode().getBytes(StandardCharsets.UTF_8))
                .message("test: add " + test.getTestClassName() + " [mutagen]")
                .commit();
        log.debug("  Committed {}", test.getRelativeFilePath());
    }
}
