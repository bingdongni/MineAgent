package com.mineagent.api.llm.provider;

/**
 * Structured LLM provider failure used by the agent loop to distinguish
 * transient failures from permanent request/configuration errors.
 */
public final class LLMProviderException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;

    public LLMProviderException(String message, Integer statusCode,
                                boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    /** HTTP 408/429 and 5xx are transient; other 4xx errors need correction. */
    public static LLMProviderException http(String provider, int statusCode,
                                             String responseBody) {
        boolean retryable = statusCode == 408 || statusCode == 429
                || statusCode >= 500 && statusCode <= 599;
        return new LLMProviderException(provider + " API error " + statusCode
                + ": " + truncate(responseBody, 1000), statusCode, retryable, null);
    }

    public static LLMProviderException transport(String provider, Throwable cause) {
        String detail = cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage();
        return new LLMProviderException(provider + " API transport failure: " + detail,
                null, true, cause);
    }

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
