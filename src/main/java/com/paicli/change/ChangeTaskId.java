package com.paicli.change;

import java.util.Objects;
import java.util.UUID;

public record ChangeTaskId(String value) {
    public ChangeTaskId {
        value = Objects.requireNonNull(value, "value").trim();
        if (!value.matches("change_[a-z0-9]{12}")) {
            throw new IllegalArgumentException("ChangeTaskId 格式必须是 change_ + 12 位小写字母或数字");
        }
    }

    public static ChangeTaskId create() {
        return new ChangeTaskId("change_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }
}
