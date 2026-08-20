package com.paicli.spec;

import java.util.List;

/**
 * ChangeSpec 结构完整但不满足契约规则。
 */
public final class ChangeSpecValidationException extends IllegalArgumentException {
    private final List<String> errors;

    public ChangeSpecValidationException(List<String> errors) {
        this(errors, null);
    }

    public ChangeSpecValidationException(List<String> errors, Throwable cause) {
        super("ChangeSpec 无效: " + String.join("；", errors), cause);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
