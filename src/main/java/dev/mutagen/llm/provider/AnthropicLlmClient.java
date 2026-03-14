package dev.mutagen.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mutagen.llm.client.LlmClient;
import dev.mutagen.llm.client.LlmException;
import dev.mutagen.llm.model.LlmRequest;
import dev.mutagen.llm.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link LlmClient} implementation for Anthropic Claude.
 *
 * <p>Uses the Anthropic Messages API directly via {@code java.net.http}.
 * Configure via: {@code ANTHROPIC_API_KEY}, {@code ANTHROPIC_MODEL}, {@code ANTHROPIC_BASE_URL}.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String DEFAULT_BASE_URL  = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL     = "claude-sonnet-4-20250514";
    private static final String API_VERSION       = "2023-06-01";
    private static final String BETA_PROMPT_CACHE = "prompt-caching-2024-07-31";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicLlmClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public AnthropicLlmClient(String apiKey, String model, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("ANTHROPIC_API_KEY is not set", LlmException.ErrorType.AUTHENTICATION);
        }
        this.apiKey       = apiKey;
        this.model        = model;
        this.baseUrl      = baseUrl;
        this.httpClient   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            String body = buildRequestBody(request);
            log.debug("Anthropic request: model={}, maxTokens={}", model, request.getMaxTokens());

            HttpRequest.Builder httpBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .timeout(Duration.ofMinutes(3));
            if (request.isCacheSystemPrompt()) {
                httpBuilder.header("anthropic-beta", BETA_PROMPT_CACHE);
            }
            HttpRequest httpRequest = httpBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            return parseResponse(httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()));

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Network error calling Anthropic API: " + e.getMessage(),
                    LlmException.ErrorType.NETWORK_ERROR, e);
        }
    }

    @Override
    public String providerName() { return "anthropic"; }

    private String buildRequestBody(LlmRequest request) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        if (!request.getSystemPrompt().isBlank()) {
            if (request.isCacheSystemPrompt()) {
                // Array format required for cache_control
                ArrayNode systemArray = body.putArray("system");
                ObjectNode systemBlock = systemArray.addObject();
                systemBlock.put("type", "text");
                systemBlock.put("text", request.getSystemPrompt());
                ObjectNode cacheControl = systemBlock.putObject("cache_control");
                cacheControl.put("type", "ephemeral");
            } else {
                body.put("system", request.getSystemPrompt());
            }
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", request.getUserPrompt());
        return objectMapper.writeValueAsString(body);
    }

    private LlmResponse parseResponse(HttpResponse<String> httpResponse) throws Exception {
        int status = httpResponse.statusCode();
        JsonNode json = objectMapper.readTree(httpResponse.body());

        if (status != 200) {
            String errorType = json.path("error").path("type").asText("unknown");
            String errorMsg  = json.path("error").path("message").asText(httpResponse.body());
            LlmException.ErrorType type = switch (status) {
                case 401 -> LlmException.ErrorType.AUTHENTICATION;
                case 429 -> errorMsg.contains("quota") ? LlmException.ErrorType.QUOTA_EXCEEDED : LlmException.ErrorType.RATE_LIMIT;
                case 400 -> LlmException.ErrorType.CONTEXT_TOO_LARGE;
                default  -> LlmException.ErrorType.PROVIDER_ERROR;
            };
            throw new LlmException("Anthropic error [%d] %s: %s".formatted(status, errorType, errorMsg), type);
        }

        return new LlmResponse(
                json.path("content").get(0).path("text").asText(),
                json.path("usage").path("input_tokens").asInt(0),
                json.path("usage").path("output_tokens").asInt(0),
                json.path("model").asText(model),
                "anthropic"
        );
    }
}
