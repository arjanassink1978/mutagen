package dev.mutagen.llm.client;

import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;

/**
 * Provider-agnostic interface for all LLM communication.
 *
 * <p>Implementations: {@link dev.mutagen.llm.provider.AnthropicLlmClient},
 * {@link dev.mutagen.llm.provider.OpenAiLlmClient}.
 *
 * <p>Use {@link LlmClientFactory#fromEnvironment()} to get the right implementation.
 */
public interface LlmClient {

    /**
     * @throws LlmException if the API returns an error or is unreachable
     */
    LlmResponse complete(LlmRequest request);

    /** Provider name for logging and usage events, e.g. "anthropic", "openai". */
    String providerName();
}
