package com.paicli.cli;

import com.paicli.spec.ChangeSpec;
import com.paicli.spec.ChangeSpecDocument;

import java.util.List;
import java.util.Locale;

final class ChangeSpecCliFormatter {
    private ChangeSpecCliFormatter() {
    }

    static String formatDraft(ChangeSpecDocument document) {
        ChangeSpec spec = document.spec();
        StringBuilder out = new StringBuilder();
        out.append("📐 ChangeSpec Draft · ")
                .append(spec.id())
                .append(" · r")
                .append(spec.revision())
                .append('\n');
        out.append("目标：").append(spec.intent().goal()).append('\n');
        appendList(out, "非目标", spec.intent().nonGoals());
        appendScope(out, spec.scope());
        out.append("验收条件：\n");
        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            out.append("  - ")
                    .append(criterion.id())
                    .append(" [")
                    .append(lower(criterion.kind()))
                    .append('/')
                    .append(lower(criterion.oracle().type()))
                    .append("] ")
                    .append(criterion.statement());
            if (!criterion.oracle().verifiers().isEmpty()) {
                out.append(" <- ").append(String.join(", ", criterion.oracle().verifiers()));
            }
            out.append('\n');
        }
        if (spec.verifiers().isEmpty()) {
            out.append("验证方式：无自动 Verifier\n");
        } else {
            out.append("验证方式：\n");
            for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
                out.append("  - ")
                        .append(verifier.id())
                        .append(" [")
                        .append(lower(verifier.type()))
                        .append(']');
                if (verifier.command() != null && !verifier.command().isBlank()) {
                    out.append(' ').append(verifier.command());
                }
                out.append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    private static void appendScope(StringBuilder out, ChangeSpec.Scope scope) {
        out.append("范围：").append(lower(scope.mode())).append('\n');
        appendList(out, "  include", scope.include());
        appendList(out, "  exclude", scope.exclude());
    }

    private static void appendList(StringBuilder out, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            out.append(label).append("：无\n");
            return;
        }
        out.append(label).append("：").append(String.join("、", values)).append('\n');
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
