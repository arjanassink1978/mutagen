package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.mutation.MutationLoopResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Orchestrates opening a MR/PR for a set of generated tests.
 *
 * <p>Generates the branch name, builds the description via {@link MrDescription},
 * and delegates to the provided {@link GitClient}.
 */
public class PrService {

    private static final Logger log = LoggerFactory.getLogger(PrService.class);

    private static final DateTimeFormatter BRANCH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GitClient gitClient;

    public PrService(GitClient gitClient) {
        this.gitClient = gitClient;
    }

    /**
     * Opens a MR/PR for the given tests and mutation results.
     *
     * @return the URL of the created MR/PR
     */
    public String openPullRequest(List<GeneratedTest> tests,
                                  MutationLoopResult loopResult,
                                  int threshold) {

        String branchName   = generateBranchName();
        String description  = MrDescription.build(tests, loopResult, threshold);

        log.info("Opening {} MR/PR on branch '{}'", gitClient.providerName(), branchName);
        String url = gitClient.openPullRequest(branchName, tests, description);
        log.info("MR/PR created: {}", url);

        return url;
    }

    // Visible for testing
    static String generateBranchName() {
        return "mutagen/tests-" + LocalDateTime.now().format(BRANCH_TIMESTAMP);
    }
}
