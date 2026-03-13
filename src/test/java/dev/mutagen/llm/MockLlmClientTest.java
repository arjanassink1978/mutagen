package dev.mutagen.llm;

import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MockLlmClientTest {

    @Test
    void thenReturn_returnsConfiguredResponse() {
        MockLlmClient mock = new MockLlmClient();
        mock.thenReturn("gegenereerde test code");

        LlmResponse response = mock.complete(buildRequest("geef me een test"));

        assertThat(response.getContent()).isEqualTo("gegenereerde test code");
        assertThat(response.getProvider()).isEqualTo("mock");
    }

    @Test
    void thenReturn_multipleResponses_returnedInOrder() {
        MockLlmClient mock = new MockLlmClient()
                .thenReturn("eerste response")
                .thenReturn("tweede response");

        assertThat(mock.complete(buildRequest("vraag 1")).getContent()).isEqualTo("eerste response");
        assertThat(mock.complete(buildRequest("vraag 2")).getContent()).isEqualTo("tweede response");
    }

    @Test
    void thenReturn_queueEmpty_returnsDefaultResponse() {
        MockLlmClient mock = new MockLlmClient()
                .withDefaultResponse("standaard");

        assertThat(mock.complete(buildRequest("wat dan ook")).getContent()).isEqualTo("standaard");
        assertThat(mock.complete(buildRequest("nog een")).getContent()).isEqualTo("standaard");
    }

    @Test
    void thenAnswer_dynamicResponseBasedOnRequest() {
        MockLlmClient mock = new MockLlmClient()
                .thenAnswer(req -> req.getUserPrompt().contains("UserController")
                        ? "UserControllerIT code"
                        : "andere code");

        assertThat(mock.complete(buildRequest("genereer test voor UserController")).getContent())
                .isEqualTo("UserControllerIT code");
    }

    @Test
    void recordsAllRequests() {
        MockLlmClient mock = new MockLlmClient();

        mock.complete(buildRequest("eerste vraag"));
        mock.complete(buildRequest("tweede vraag"));

        assertThat(mock.getCallCount()).isEqualTo(2);
        assertThat(mock.getRequest(0).getUserPrompt()).isEqualTo("eerste vraag");
        assertThat(mock.getLastRequest().getUserPrompt()).isEqualTo("tweede vraag");
    }

    @Test
    void reset_clearsStateCompletely() {
        MockLlmClient mock = new MockLlmClient().thenReturn("iets");
        mock.complete(buildRequest("vraag"));

        mock.reset();

        assertThat(mock.getCallCount()).isEqualTo(0);
    }

    private LlmRequest buildRequest(String userPrompt) {
        return LlmRequest.builder()
                .systemPrompt("je bent een test generator")
                .userPrompt(userPrompt)
                .build();
    }
}
