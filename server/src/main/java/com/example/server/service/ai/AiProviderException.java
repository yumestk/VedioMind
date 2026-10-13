package com.example.server.service.ai;

public class AiProviderException extends IllegalStateException {

    private final boolean retryable;
    private final long retryAfterMillis;

    public AiProviderException(
            String message,
            Throwable cause,
            boolean retryable,
            long retryAfterMillis
    ) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public static AiProviderException forStatus(
            String message,
            Throwable cause,
            int statusCode,
            long retryAfterMillis
    ) {
        return new AiProviderException(
                message,
                cause,
                statusCode <= 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500,
                retryAfterMillis
        );
    }
}
