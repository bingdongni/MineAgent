package com.mineagent.api.llm.provider;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Structured LLM provider failure used by the agent loop to distinguish
 * transient failures from permanent request/configuration errors.
 */
public final class LLMProviderException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;
    private final Long retryAfterMillis;

    public LLMProviderException(String message, Integer statusCode,
                                boolean retryable, Throwable cause) {
        this(message, statusCode, retryable, null, cause);
    }

    public LLMProviderException(String message, Integer statusCode,
                                boolean retryable, Long retryAfterMillis,
                                Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Long retryAfterMillis() {
        return retryAfterMillis;
    }

    /** HTTP 408/429 and 5xx are transient; other 4xx errors need correction. */
    public static LLMProviderException http(String provider, int statusCode,
                                             String responseBody) {
        return http(provider, statusCode, responseBody, null);
    }

    public static LLMProviderException http(String provider, int statusCode,
                                             String responseBody,
                                             String retryAfterHeader) {
        boolean retryable = statusCode == 408 || statusCode == 429
                || statusCode >= 500 && statusCode <= 599;
        return new LLMProviderException(provider + " API error " + statusCode
                + ": " + truncate(responseBody, 1000), statusCode, retryable,
                retryable ? parseRetryAfter(retryAfterHeader) : null, null);
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

    private static Long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.min(60_000L, Math.max(0L, Math.multiplyExact(seconds, 1000L)));
        } catch (ArithmeticException | NumberFormatException ignored) {
            try {
                long millis = ZonedDateTime.parse(value.trim(),
                                DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis();
                // A remote header must not immobilize an agent loop indefinitely.
                return Math.min(60_000L, Math.max(0L, millis));
            } catch (RuntimeException malformedDate) {
                return null;
            }
        }
    }
}
