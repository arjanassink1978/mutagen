package dev.mutagen.llm.model;

/** Provider-agnostic LLM request. */
public class LlmRequest {

    private final String systemPrompt;
    private final String userPrompt;
    private final int maxTokens;
    private final float temperature;

    private LlmRequest(Builder builder) {
        this.systemPrompt = builder.systemPrompt;
        this.userPrompt   = builder.userPrompt;
        this.maxTokens    = builder.maxTokens;
        this.temperature  = builder.temperature;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt()   { return userPrompt; }
    public int getMaxTokens()       { return maxTokens; }
    public float getTemperature()   { return temperature; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String systemPrompt = "";
        private String userPrompt;
        private int maxTokens    = 4096;
        private float temperature = 0.2f;

        public Builder systemPrompt(String s) { this.systemPrompt = s; return this; }
        public Builder userPrompt(String s)   { this.userPrompt = s; return this; }
        public Builder maxTokens(int n)       { this.maxTokens = n; return this; }
        public Builder temperature(float t)   { this.temperature = t; return this; }

        public LlmRequest build() {
            if (userPrompt == null || userPrompt.isBlank()) {
                throw new IllegalStateException("userPrompt is required");
            }
            return new LlmRequest(this);
        }
    }
}
