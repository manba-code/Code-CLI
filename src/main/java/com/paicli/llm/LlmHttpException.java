package com.paicli.llm;

import java.io.IOException;

/** HTTP status retained for bounded background retry without parsing provider response bodies. */
public final class LlmHttpException extends IOException {
    private final int status;
    public LlmHttpException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public boolean retryable() { return status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599; }
}
