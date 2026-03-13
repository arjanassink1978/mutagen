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
 * {@link LlmClient} implementation for OpenAI (GPT-4o etc.).
 *
 * <p>Also supports Azure OpenAI via {@code OPENAI_BASE_URL}.
 * Configure via: {@code OPENAI_API_KEY}, {@code OPENAI_MODEL}, {@code OPENAI_BASE_URL}.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_MODEL    = "gpt-4.1-turbo";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLlmClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public OpenAiLlmClient(String apiKey, String model, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("OPENAI_API_KEY is not set", LlmException.ErrorType.AUTHENTICATION);
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
            log.debug("OpenAI request: model={}, maxTokens={}", model, request.getMaxTokens());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(3))
                    .build();

            return parseResponse(httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()));

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Network error calling OpenAI API: " + e.getMessage(),
                    LlmException.ErrorType.NETWORK_ERROR, e);
        }
    }

    @Override
    public String providerName() { return "openai"; }

    private String buildRequestBody(LlmRequest request) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());
        ArrayNode messages = body.putArray("messages");
        if (!request.getSystemPrompt().isBlank()) {
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", request.getUserPrompt());
        return objectMapper.writeValueAsString(body);
    }

    private LlmResponse parseResponse(HttpResponse<String> httpResponse) throws Exception {
        int status = httpResponse.statusCode();
        JsonNode json = objectMapper.readTree(httpResponse.body());

        if (status != 200) {
            String errorMsg  = json.path("error").path("message").asText(httpResponse.body());
            String errorCode = json.path("error").path("code").asText("");
            LlmException.ErrorType type = switch (status) {
                case 401 -> LlmException.ErrorType.AUTHENTICATION;
                case 429 -> errorCode.contains("quota") ? LlmException.ErrorType.QUOTA_EXCEEDED : LlmException.ErrorType.RATE_LIMIT;
                case 400 -> LlmException.ErrorType.CONTEXT_TOO_LARGE;
                default  -> LlmException.ErrorType.PROVIDER_ERROR;
            };
            throw new LlmException("OpenAI error [%d]: %s".formatted(status, errorMsg), type);
        }

        return new LlmResponse(
                json.path("choices").get(0).path("message").path("content").asText(),
                json.path("usage").path("prompt_tokens").asInt(0),
                json.path("usage").path("completion_tokens").asInt(0),
                json.path("model").asText(model),
                "openai"
        );
    }
}
