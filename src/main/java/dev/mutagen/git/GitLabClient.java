package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.MergeRequestApi;
import org.gitlab4j.api.models.MergeRequestParams;
import org.gitlab4j.api.models.RepositoryFile;
import org.gitlab4j.models.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link GitClient} implementation for GitLab.
 *
 * <p>Reads project configuration from environment variables:
 * <ul>
 *   <li>{@code GITLAB_TOKEN} — personal access token or CI job token</li>
 *   <li>{@code CI_PROJECT_ID} — project numeric ID or {@code namespace/project} path</li>
 *   <li>{@code CI_SERVER_URL} — GitLab instance URL (default: {@code https://gitlab.com})</li>
 * </ul>
 *
 * <p>Inject a {@link GitLabApi} directly (e.g. in tests):
 * {@code new GitLabClient(mockApi, "my-group/my-project")}.
 */
public class GitLabClient implements GitClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

    private final GitLabApi gitLabApi;
    private final Object    projectId;

    public GitLabClient(GitLabApi gitLabApi, Object projectId) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
    }

    @Override
    public String openPullRequest(String branchName, List<GeneratedTest> tests, String description) {
        try {
            String defaultBranch = getDefaultBranch();
            log.info("GitLab: creating branch '{}' from '{}'", branchName, defaultBranch);
            gitLabApi.getRepositoryApi().createBranch(projectId, branchName, defaultBranch);

            log.info("GitLab: committing {} test file(s)", tests.size());
            for (GeneratedTest test : tests) {
                commitFile(branchName, test);
            }

            log.info("GitLab: opening MR");
            return createMergeRequest(branchName, defaultBranch, description);

        } catch (GitLabApiException e) {
            throw new GitException("GitLab API error: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() { return "gitlab"; }

    // ---------------------------------------------------------------

    private String getDefaultBranch() throws GitLabApiException {
        return gitLabApi.getProjectApi().getProject(projectId).getDefaultBranch();
    }

    private void commitFile(String branchName, GeneratedTest test) throws GitLabApiException {
        RepositoryFile file = new RepositoryFile();
        file.setFilePath(test.getRelativeFilePath());
        file.setContent(new String(
                java.util.Base64.getEncoder().encode(
                        test.getSourceCode().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
        file.setEncoding(Constants.Encoding.BASE64);

        String commitMessage = "test: add " + test.getTestClassName() + " [mutagen]";
        gitLabApi.getRepositoryFileApi().createFile(projectId, file, branchName, commitMessage);
        log.debug("  Committed {}", test.getRelativeFilePath());
    }

    private String createMergeRequest(String sourceBranch, String targetBranch,
                                       String description) throws GitLabApiException {
        MergeRequestParams params = new MergeRequestParams()
                .withSourceBranch(sourceBranch)
                .withTargetBranch(targetBranch)
                .withTitle("test: AI-generated integration tests [mutagen]")
                .withDescription(description)
                .withRemoveSourceBranch(false);

        var mr = gitLabApi.getMergeRequestApi().createMergeRequest(projectId, params);
        String url = mr.getWebUrl();
        log.info("GitLab MR created: {}", url);
        return url;
    }
}
