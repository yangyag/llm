package com.llm.app.auth.exception;

public class TooManyLoginAttemptsException extends RuntimeException {
    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("too many login attempts; try again later");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
