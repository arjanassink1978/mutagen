package dev.mutagen.git;

import dev.mutagen.generator.GeneratedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubClientTest {

    @Mock GHRepository      mockRepo;
    @Mock GHRef             mockRef;
    @Mock GHRef.GHObject    mockRefObject;
    @Mock GHContentBuilder  mockContentBuilder;
    @Mock GHPullRequest     mockPr;

    private GitHubClient client;

    @BeforeEach
    void setUp() throws IOException {
        when(mockRepo.getDefaultBranch()).thenReturn("main");
        when(mockRepo.getRef("heads/main")).thenReturn(mockRef);
        when(mockRef.getObject()).thenReturn(mockRefObject);
        when(mockRefObject.getSha()).thenReturn("abc123def456");
        when(mockRepo.createContent()).thenReturn(mockContentBuilder);
        when(mockContentBuilder.branch(anyString())).thenReturn(mockContentBuilder);
        when(mockContentBuilder.path(anyString())).thenReturn(mockContentBuilder);
        when(mockContentBuilder.content(any(byte[].class))).thenReturn(mockContentBuilder);
        when(mockContentBuilder.message(anyString())).thenReturn(mockContentBuilder);
        when(mockContentBuilder.commit()).thenReturn(mock(GHContentUpdateResponse.class));
        when(mockRepo.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockPr);
        when(mockPr.getHtmlUrl()).thenReturn(new URL("https://github.com/owner/repo/pull/7"));

        client = new GitHubClient(mockRepo);
    }

    @Test
    void openPullRequest_returnsPrUrl() throws IOException {
        String url = client.openPullRequest("mutagen/tests-20240101", singleTest(), "desc");
        assertThat(url).isEqualTo("https://github.com/owner/repo/pull/7");
    }

    @Test
    void openPullRequest_createsBranchFromDefaultSha() throws IOException {
        client.openPullRequest("mutagen/tests", singleTest(), "desc");
        verify(mockRepo).createRef("refs/heads/mutagen/tests", "abc123def456");
    }

    @Test
    void openPullRequest_commitsEachTestFile() throws IOException {
        client.openPullRequest("mutagen/tests", twoTests(), "desc");
        verify(mockContentBuilder, times(2)).commit();
    }

    @Test
    void openPullRequest_commitPathMatchesRelativePath() throws IOException {
        client.openPullRequest("branch", singleTest(), "desc");
        verify(mockContentBuilder).path("src/test/java/com/example/controller/UserControllerIT.java");
    }

    @Test
    void openPullRequest_commitBranchIsCorrect() throws IOException {
        client.openPullRequest("my-branch", singleTest(), "desc");
        verify(mockContentBuilder, atLeastOnce()).branch("my-branch");
    }

    @Test
    void openPullRequest_prDescriptionPassedThrough() throws IOException {
        client.openPullRequest("branch", singleTest(), "my custom description");
        verify(mockRepo).createPullRequest(anyString(), anyString(), anyString(),
                eq("my custom description"));
    }

    @Test
    void openPullRequest_prTargetBranchIsDefault() throws IOException {
        client.openPullRequest("feature", singleTest(), "desc");
        verify(mockRepo).createPullRequest(anyString(), anyString(), eq("main"), anyString());
    }

    @Test
    void openPullRequest_ioException_throwsGitException() throws IOException {
        when(mockRepo.createRef(anyString(), anyString()))
                .thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> client.openPullRequest("branch", singleTest(), "desc"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("GitHub API error");
    }

    @Test
    void providerName_isGithub() {
        assertThat(client.providerName()).isEqualTo("github");
    }

    // ------------------------------------------------------------------

    private List<GeneratedTest> singleTest() {
        return List.of(new GeneratedTest(
                "UserController", "UserControllerIT", "com.example.controller",
                "package com.example.controller;\nclass UserControllerIT {}",
                List.of(), 100, 200, "mock"));
    }

    private List<GeneratedTest> twoTests() {
        return List.of(
                new GeneratedTest("UserController", "UserControllerIT", "com.example.controller",
                        "package com.example.controller;\nclass UserControllerIT {}",
                        List.of(), 0, 0, "mock"),
                new GeneratedTest("OrderController", "OrderControllerIT", "com.example.controller",
                        "package com.example.controller;\nclass OrderControllerIT {}",
                        List.of(), 0, 0, "mock"));
    }
}
