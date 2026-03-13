package dev.mutagen.llm.client;

import dev.mutagen.llm.provider.AnthropicLlmClient;
import dev.mutagen.llm.provider.OpenAiLlmClient;
import dev.mutagen.llm.provider.ProxyLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the appropriate {@link LlmClient} based on environment variables.
 *
 * <p>Priority order (first match wins):
 * <ol>
 *   <li>{@code TESTGEN_API_KEY} → {@link ProxyLlmClient} (hosted/SaaS)</li>
 *   <li>{@code ANTHROPIC_API_KEY} → {@link AnthropicLlmClient}</li>
 *   <li>{@code OPENAI_API_KEY} → {@link OpenAiLlmClient}</li>
 * </ol>
 *
 * <p>Optional overrides: {@code ANTHROPIC_MODEL}, {@code ANTHROPIC_BASE_URL},
 * {@code OPENAI_MODEL}, {@code OPENAI_BASE_URL}, {@code TESTGEN_BASE_URL}.
 */
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    private LlmClientFactory() {}

    /**
     * @throws LlmException if no provider is configured
     */
    public static LlmClient fromEnvironment() {
        String testgenKey = env("TESTGEN_API_KEY");
        if (testgenKey != null) {
            String baseUrl = envOrDefault("TESTGEN_BASE_URL", "https://app.mutagen.dev");
            log.info("LLM provider: hosted proxy ({})", baseUrl);
            return new ProxyLlmClient(testgenKey, baseUrl);
        }

        String anthropicKey = env("ANTHROPIC_API_KEY");
        if (anthropicKey != null) {
            String model   = envOrDefault("ANTHROPIC_MODEL", "claude-sonnet-4-20250514");
            String baseUrl = envOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
            log.info("LLM provider: Anthropic Claude (model={})", model);
            return new AnthropicLlmClient(anthropicKey, model, baseUrl);
        }

        String openaiKey = env("OPENAI_API_KEY");
        if (openaiKey != null) {
            String model   = envOrDefault("OPENAI_MODEL", "gpt-4o");
            String baseUrl = envOrDefault("OPENAI_BASE_URL", "https://api.openai.com");
            log.warn("Mutagen is optimized for Claude (Anthropic). Results may vary with OpenAI.");
            log.info("LLM provider: OpenAI (model={})", model);
            return new OpenAiLlmClient(openaiKey, model, baseUrl);
        }

        throw new LlmException(
                "No LLM provider configured. Set one of: TESTGEN_API_KEY, ANTHROPIC_API_KEY, OPENAI_API_KEY",
                LlmException.ErrorType.AUTHENTICATION
        );
    }

    public static String describeConfiguration() {
        if (env("TESTGEN_API_KEY") != null)   return "hosted proxy";
        if (env("ANTHROPIC_API_KEY") != null) return "Anthropic Claude (" + envOrDefault("ANTHROPIC_MODEL", "claude-sonnet-4-20250514") + ")";
        if (env("OPENAI_API_KEY") != null)    return "OpenAI (" + envOrDefault("OPENAI_MODEL", "gpt-4o") + ")";
        return "not configured";
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
