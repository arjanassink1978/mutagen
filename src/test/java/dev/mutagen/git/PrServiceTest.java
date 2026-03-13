package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.HttpMethod;
import dev.mutagen.mutation.MutationLoopResult;
import dev.mutagen.mutation.model.MutationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrServiceTest {

    @Mock GitClient mockGitClient;

    @Test
    void openPullRequest_delegatesToGitClient() {
        when(mockGitClient.openPullRequest(anyString(), anyList(), anyString()))
                .thenReturn("https://gitlab.com/mr/1");
        when(mockGitClient.providerName()).thenReturn("gitlab");

        PrService service = new PrService(mockGitClient);
        String url = service.openPullRequest(twoTests(), loopResult(), 80);

        assertThat(url).isEqualTo("https://gitlab.com/mr/1");
    }

    @Test
    void openPullRequest_branchNameStartsWithMutagen() {
        when(mockGitClient.openPullRequest(anyString(), anyList(), anyString()))
                .thenReturn("https://example.com/pr/1");
        when(mockGitClient.providerName()).thenReturn("github");

        ArgumentCaptor<String> branchCaptor = ArgumentCaptor.forClass(String.class);
        new PrService(mockGitClient).openPullRequest(twoTests(), loopResult(), 80);

        verify(mockGitClient).openPullRequest(branchCaptor.capture(), anyList(), anyString());
        assertThat(branchCaptor.getValue()).startsWith("mutagen/tests-");
    }

    @Test
    void openPullRequest_descriptionContainsMutationScores() {
        when(mockGitClient.openPullRequest(anyString(), anyList(), anyString()))
                .thenReturn("https://example.com/pr/1");
        when(mockGitClient.providerName()).thenReturn("github");

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        new PrService(mockGitClient).openPullRequest(twoTests(), loopResult(), 80);

        verify(mockGitClient).openPullRequest(anyString(), anyList(), descCaptor.capture());
        String desc = descCaptor.getValue();
        assertThat(desc).contains("25.0%");  // initial score
        assertThat(desc).contains("85.0%");  // final score
        assertThat(desc).contains("80%");    // threshold
    }

    @Test
    void openPullRequest_testsPassedThroughToGitClient() {
        when(mockGitClient.openPullRequest(anyString(), anyList(), anyString()))
                .thenReturn("https://example.com/pr/1");
        when(mockGitClient.providerName()).thenReturn("gitlab");

        List<GeneratedTest> tests = twoTests();
        ArgumentCaptor<List<GeneratedTest>> testsCaptor = ArgumentCaptor.forClass(List.class);

        new PrService(mockGitClient).openPullRequest(tests, loopResult(), 80);

        verify(mockGitClient).openPullRequest(anyString(), testsCaptor.capture(), anyString());
        assertThat(testsCaptor.getValue()).hasSize(2);
    }

    @Test
    void generateBranchName_matchesExpectedPattern() {
        String name = PrService.generateBranchName();
        assertThat(name).matches("mutagen/tests-\\d{8}-\\d{6}");
    }

    // ------------------------------------------------------------------

    private MutationLoopResult loopResult() {
        return new MutationLoopResult(twoTests(), 25.0, 85.0, 2, new MutationReport());
    }

    private List<GeneratedTest> twoTests() {
        EndpointInfo e = new EndpointInfo();
        e.setHttpMethod(HttpMethod.GET);
        e.setFullPath("/api/v1/users");

        return List.of(
                new GeneratedTest("UserController", "UserControllerIT", "com.example.controller",
                        "class UserControllerIT {}", List.of(e), 0, 0, "mock"),
                new GeneratedTest("OrderController", "OrderControllerIT", "com.example.controller",
                        "class OrderControllerIT {}", List.of(), 0, 0, "mock"));
    }
}
