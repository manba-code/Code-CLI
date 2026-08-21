package com.paicli.spec.eval;

import com.paicli.spec.SpecDraftGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 只为显式 ChangeSpec 评测保存被拒绝的模型 Draft，不进入生产运行产物。 */
final class ChangeSpecDraftDiagnostic implements SpecDraftGenerator.DraftAttemptListener {
    private static final int MAX_OUTPUT_CHARS = 8 * 1024;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)"),
            Pattern.compile("(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)"),
            Pattern.compile("(?i)(\\bbearer\\s+)(\\S+)"));

    private final List<Attempt> attempts = new ArrayList<>();

    @Override
    public void onRejected(int attempt, String rawDraft, List<String> errors) {
        attempts.add(new Attempt(
                attempt,
                rawDraft == null ? "" : rawDraft,
                errors == null ? List.of() : List.copyOf(errors)));
    }

    Path write(Path runRoot, String caseId, int repetition, String finalError) throws IOException {
        Path relative = Path.of("draft-attempts", safeName(caseId) + "-r" + repetition + ".md");
        Path file = runRoot.resolve(relative).normalize();
        if (!file.startsWith(runRoot)) {
            throw new IOException("Draft 诊断路径逃逸评测目录");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, render(caseId, repetition, finalError), StandardCharsets.UTF_8);
        return relative;
    }

    private String render(String caseId, int repetition, String finalError) {
        StringBuilder out = new StringBuilder("# ChangeSpec Draft diagnostic\n\n")
                .append("- case: `").append(sanitize(caseId)).append("`\n")
                .append("- repetition: ").append(repetition).append('\n')
                .append("- final error: ").append(sanitize(finalError)).append("\n");
        for (Attempt attempt : attempts) {
            OutputSummary summary = summarize(attempt.rawDraft());
            out.append("\n## Attempt ").append(attempt.number()).append("\n\n")
                    .append("- original chars: ").append(attempt.rawDraft().length()).append('\n')
                    .append("- truncated: ").append(summary.truncated()).append("\n\n")
                    .append("### Validation errors\n\n");
            for (String error : attempt.errors()) {
                out.append("- ").append(sanitize(error)).append('\n');
            }
            out.append("\n### Sanitized model output\n\n");
            if (summary.text().isBlank()) {
                out.append("    (empty)\n");
            } else {
                summary.text().lines().forEach(line -> out.append("    ").append(line).append('\n'));
            }
        }
        return out.toString();
    }

    private static OutputSummary summarize(String text) {
        String sanitized = sanitize(text);
        if (sanitized.length() <= MAX_OUTPUT_CHARS) {
            return new OutputSummary(sanitized, false);
        }
        int head = 4 * 1024;
        String marker = "\n... draft output truncated ...\n";
        int tail = MAX_OUTPUT_CHARS - head - marker.length();
        return new OutputSummary(
                sanitized.substring(0, head) + marker + sanitized.substring(sanitized.length() - tail),
                true);
    }

    private static String sanitize(String text) {
        String sanitized = ANSI.matcher(text == null ? "" : text).replaceAll("");
        for (Pattern pattern : SECRET_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("$1***");
        }
        return sanitized;
    }

    private static String safeName(String value) {
        String safe = value == null ? "case" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? "case" : safe;
    }

    private record Attempt(int number, String rawDraft, List<String> errors) {
    }

    private record OutputSummary(String text, boolean truncated) {
    }
}
