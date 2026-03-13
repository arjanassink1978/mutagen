package dev.mutagen.git;

import org.gitlab4j.api.GitLabApi;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Creates the appropriate {@link GitClient} based on environment variables.
 *
 * <p>Priority order (first match wins):
 * <ol>
 *   <li>{@code GITLAB_TOKEN} → {@link GitLabClient}</li>
 *   <li>{@code GITHUB_TOKEN} → {@link GitHubClient}</li>
 * </ol>
 *
 * <p>GitLab also requires {@code CI_PROJECT_ID}.
 * {@code CI_SERVER_URL} defaults to {@code https://gitlab.com}.
 * GitHub also requires {@code GITHUB_REPOSITORY} ({@code owner/repo}).
 */
public class GitClientFactory {

    private static final Logger log = LoggerFactory.getLogger(GitClientFactory.class);

    private GitClientFactory() {}

    /**
     * @throws GitException if no Git platform is configured or required env vars are missing
     */
    public static GitClient fromEnvironment() {
        String gitlabToken = env("GITLAB_TOKEN");
        if (gitlabToken != null) {
            return createGitLabClient(gitlabToken);
        }

        String githubToken = env("GITHUB_TOKEN");
        if (githubToken != null) {
            return createGitHubClient(githubToken);
        }

        throw new GitException(
                "No Git platform configured. Set GITLAB_TOKEN (+ CI_PROJECT_ID) " +
                "or GITHUB_TOKEN (+ GITHUB_REPOSITORY).");
    }

    // ---------------------------------------------------------------

    private static GitLabClient createGitLabClient(String token) {
        String serverUrl  = envOrDefault("CI_SERVER_URL", "https://gitlab.com");
        String projectId  = env("CI_PROJECT_ID");
        if (projectId == null) {
            throw new GitException(
                    "GITLAB_TOKEN is set but CI_PROJECT_ID is missing. " +
                    "Set CI_PROJECT_ID to your project's numeric ID or 'namespace/project' path.");
        }
        log.info("Git platform: GitLab ({}, project={})", serverUrl, projectId);
        GitLabApi api = new GitLabApi(serverUrl, token);
        return new GitLabClient(api, projectId);
    }

    private static GitHubClient createGitHubClient(String token) {
        String repoName = env("GITHUB_REPOSITORY");
        if (repoName == null) {
            throw new GitException(
                    "GITHUB_TOKEN is set but GITHUB_REPOSITORY is missing. " +
                    "Set GITHUB_REPOSITORY to 'owner/repo'.");
        }
        log.info("Git platform: GitHub ({})", repoName);
        try {
            var github = new GitHubBuilder().withOAuthToken(token).build();
            return new GitHubClient(github.getRepository(repoName));
        } catch (IOException e) {
            throw new GitException("Failed to connect to GitHub: " + e.getMessage(), e);
        }
    }

    private static String env(String name) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : null;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String val = env(name);
        return val != null ? val : defaultValue;
    }
}
