package com.paicli.spec;

import com.paicli.tool.CommandExecutionResult;

import java.util.List;
import java.util.regex.Pattern;

/** ChangeSpec Evidence 共用的身份、脱敏、截断和修复摘要规则。 */
final class SpecEvidenceFormatter {
    static final int MAX_COMMAND_OUTPUT_CHARS = 8 * 1024;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)"),
            Pattern.compile("(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)"),
            Pattern.compile("(?i)(\\bbearer\\s+)(\\S+)"));

    private SpecEvidenceFormatter() {
    }

    static String evidenceId(int attempt, String verifierId) {
        return "verifier:attempt-" + attempt + ":" + verifierId;
    }

    static OutputSummary summarizeOutput(String output) {
        String sanitized = sanitizeText(output);
        if (sanitized.length() <= MAX_COMMAND_OUTPUT_CHARS) {
            return new OutputSummary(sanitized, false);
        }
        int head = 2 * 1024;
        String marker = "\n... command output truncated ...\n";
        int tail = MAX_COMMAND_OUTPUT_CHARS - head - marker.length();
        return new OutputSummary(
                sanitized.substring(0, head)
                        + marker
                        + sanitized.substring(sanitized.length() - tail),
                true);
    }

    static String sanitizeText(String text) {
        String sanitized = ANSI.matcher(text == null ? "" : text).replaceAll("");
        for (Pattern pattern : SECRET_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("$1***");
        }
        return sanitized;
    }

    static String repairSummary(SpecVerifier.VerifierResult verifier) {
        StringBuilder out = new StringBuilder();
        out.append("verifierId: ").append(verifier.verifierId()).append('\n');
        out.append("type: ").append(verifier.type().name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        out.append("status: ").append(verifier.status()).append('\n');
        out.append("detail: ").append(sanitizeText(verifier.detail())).append('\n');
        if (verifier.commandResult() != null) {
            CommandExecutionResult command = verifier.commandResult();
            OutputSummary summary = summarizeOutput(command.output());
            out.append("command: ").append(sanitizeText(command.command())).append('\n');
            out.append("commandStatus: ").append(command.status()).append('\n');
            out.append("exitCode: ").append(command.exitCode()).append('\n');
            if (!summary.text().isBlank()) {
                out.append("outputSummary: |-\n");
                summary.text().lines().forEach(line -> out.append("  ").append(line).append('\n'));
            }
            out.append("outputTruncated: ").append(summary.truncated()).append('\n');
        }
        if (verifier.junitSummary() != null) {
            SpecVerifier.JUnitSummary junit = verifier.junitSummary();
            out.append("junit: tests=").append(junit.tests())
                    .append(", executed=").append(junit.executedTests())
                    .append(", failures=").append(junit.failures())
                    .append(", errors=").append(junit.errors())
                    .append(", skipped=").append(junit.skipped())
                    .append('\n');
        }
        return out.toString().stripTrailing();
    }

    record OutputSummary(String text, boolean truncated) {
    }
}
