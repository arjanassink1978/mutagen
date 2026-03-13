package dev.mutagen.llm.client;

public class LlmException extends RuntimeException {

    public enum ErrorType {
        AUTHENTICATION,
        RATE_LIMIT,
        QUOTA_EXCEEDED,
        CONTEXT_TOO_LARGE,
        PROVIDER_ERROR,
        NETWORK_ERROR,
        UNKNOWN
    }

    private final ErrorType errorType;

    public LlmException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }

    public LlmException(String message, ErrorType errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() { return errorType; }

    public boolean isRetryable() {
        return errorType == ErrorType.RATE_LIMIT || errorType == ErrorType.NETWORK_ERROR;
    }
}
