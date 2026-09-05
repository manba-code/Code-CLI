package com.paicli.change;

/** 请求合法，但不能满足交付业务不变量；API 映射为 422。 */
public final class ChangeValidationException extends RuntimeException {
    public ChangeValidationException(String message) { super(message); }
}
