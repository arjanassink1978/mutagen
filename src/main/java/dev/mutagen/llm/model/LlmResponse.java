package dev.mutagen.llm.model;

/** Provider-agnostic LLM response, including token usage for billing/logging. */
public class LlmResponse {

    private final String content;
    private final int inputTokens;
    private final int outputTokens;
    private final String model;
    private final String provider;

    public LlmResponse(String content, int inputTokens, int outputTokens,
                       String model, String provider) {
        this.content      = content;
        this.inputTokens  = inputTokens;
        this.outputTokens = outputTokens;
        this.model        = model;
        this.provider     = provider;
    }

    public String getContent()      { return content; }
    public int getInputTokens()     { return inputTokens; }
    public int getOutputTokens()    { return outputTokens; }
    public int getTotalTokens()     { return inputTokens + outputTokens; }
    public String getModel()        { return model; }
    public String getProvider()     { return provider; }

    @Override
    public String toString() {
        return "LlmResponse[provider=%s, model=%s, tokens=%d+%d]"
                .formatted(provider, model, inputTokens, outputTokens);
    }
}
