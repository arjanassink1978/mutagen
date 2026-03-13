package dev.mutagen.llm;

import dev.mutagen.llm.client.LlmClient;
import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Test double for {@link LlmClient}. Supports queued responses and dynamic answers.
 *
 * <pre>{@code
 * MockLlmClient mock = new MockLlmClient()
 *     .thenReturn("first response")
 *     .thenReturn("second response");
 *
 * mock.thenAnswer(req -> req.getUserPrompt().contains("User") ? "UserIT code" : "other");
 *
 * assertThat(mock.getCallCount()).isEqualTo(1);
 * assertThat(mock.getLastRequest().getUserPrompt()).contains("@GetMapping");
 * }</pre>
 */
public class MockLlmClient implements LlmClient {

    private final Deque<Function<LlmRequest, String>> responseQueue = new ArrayDeque<>();
    private final List<LlmRequest> recordedRequests = new ArrayList<>();
    private String defaultResponse = "// Mock generated test";

    public MockLlmClient thenReturn(String response) {
        responseQueue.add(req -> response);
        return this;
    }

    public MockLlmClient thenAnswer(Function<LlmRequest, String> answer) {
        responseQueue.add(answer);
        return this;
    }

    public MockLlmClient withDefaultResponse(String response) {
        this.defaultResponse = response;
        return this;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        recordedRequests.add(request);
        Function<LlmRequest, String> responder = responseQueue.isEmpty()
                ? req -> defaultResponse
                : responseQueue.poll();
        return new LlmResponse(responder.apply(request), 100, 200, "mock-model", "mock");
    }

    @Override
    public String providerName() { return "mock"; }

    public int getCallCount()               { return recordedRequests.size(); }
    public LlmRequest getLastRequest()      { return recordedRequests.getLast(); }
    public LlmRequest getRequest(int index) { return recordedRequests.get(index); }
    public List<LlmRequest> getAllRequests() { return List.copyOf(recordedRequests); }
    public void reset()                     { recordedRequests.clear(); responseQueue.clear(); }
}
