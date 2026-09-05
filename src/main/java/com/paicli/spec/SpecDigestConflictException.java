package com.paicli.spec;

import java.io.IOException;

public final class SpecDigestConflictException extends IOException {
    public SpecDigestConflictException(String expected, String actual) {
        super("ChangeSpec digest 已过期，expected=" + expected + ", actual=" + actual);
    }
}
