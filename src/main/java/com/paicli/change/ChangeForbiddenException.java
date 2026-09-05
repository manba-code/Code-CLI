package com.paicli.change;

/** 业务职责分离拒绝；API 映射为 403。 */
public final class ChangeForbiddenException extends ChangeConflictException {
    public ChangeForbiddenException(String message) { super(message); }
}
