package dev.mutagen.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link LlmClient} implementation for the hosted Mutagen proxy.
 *
 * <p>Used when {@code TESTGEN_API_KEY} is set. The proxy handles auth,
 * quota enforcement, and billing before forwarding to the underlying model.
 * Configure via: {@code TESTGEN_API_KEY}, {@code TESTGEN_BASE_URL}.
 */
public class ProxyLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyLlmClient.class);
    private static final String DEFAULT_BASE_URL = "https://app.mutagen.dev";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ProxyLlmClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public ProxyLlmClient(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("TESTGEN_API_KEY is not set", LlmException.ErrorType.AUTHENTICATION);
        }
        this.apiKey       = apiKey;
        this.baseUrl      = baseUrl;
        this.httpClient   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("system", request.getSystemPrompt());
            body.put("user", request.getUserPrompt());
            body.put("max_tokens", request.getMaxTokens());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/complete"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "mutagen/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofMinutes(3))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = httpResponse.statusCode();
            JsonNode json = objectMapper.readTree(httpResponse.body());

            if (status != 200) {
                String errorMsg = json.path("error").asText(httpResponse.body());
                LlmException.ErrorType type = switch (status) {
                    case 401 -> LlmException.ErrorType.AUTHENTICATION;
                    case 402 -> LlmException.ErrorType.QUOTA_EXCEEDED;
                    case 429 -> LlmException.ErrorType.RATE_LIMIT;
                    default  -> LlmException.ErrorType.PROVIDER_ERROR;
                };
                throw new LlmException("Proxy error [%d]: %s".formatted(status, errorMsg), type);
            }

            return new LlmResponse(
                    json.path("content").asText(),
                    json.path("input_tokens").asInt(0),
                    json.path("output_tokens").asInt(0),
                    json.path("model").asText("unknown"),
                    "proxy"
            );

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Network error calling proxy: " + e.getMessage(),
                    LlmException.ErrorType.NETWORK_ERROR, e);
        }
    }

    @Override
    public String providerName() { return "proxy"; }
}
