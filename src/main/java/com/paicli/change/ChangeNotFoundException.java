package com.paicli.change;

public final class ChangeNotFoundException extends RuntimeException {
    public ChangeNotFoundException(ChangeTaskId id) {
        super("ChangeTask 不存在: " + id.value());
    }
}
