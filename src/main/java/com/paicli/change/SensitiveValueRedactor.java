package com.paicli.change;

import java.util.regex.Pattern;

/** Last-line defense for operator logs. Structured code should still avoid logging secrets at the source. */
public final class SensitiveValueRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|access[_-]?key|authorization|cookie|private-token)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern URI_USER_INFO = Pattern.compile("(https?://)[^/@\\s]+@", Pattern.CASE_INSENSITIVE);

    private SensitiveValueRedactor() { }

    public static String redact(String input) {
        if (input == null || input.isBlank()) return "";
        String value = BEARER.matcher(input).replaceAll("$1<redacted>");
        value = KEY_VALUE.matcher(value).replaceAll("$1$2<redacted>");
        value = URI_USER_INFO.matcher(value).replaceAll("$1<redacted>@");
        return value.substring(0, Math.min(value.length(), 1_024));
    }
}
