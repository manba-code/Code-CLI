package com.paicli.cli;

import com.paicli.spec.ChangeSpec;
import com.paicli.spec.ChangeSpecDocument;
import com.paicli.spec.SpecRunResult;
import com.paicli.spec.SpecVerifier;

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

    static String formatResult(SpecRunResult result) {
        StringBuilder out = new StringBuilder();
        for (SpecRunResult.VerificationAttempt attempt : result.verificationAttempts()) {
            out.append("🧪 ChangeSpec Verifier 结果");
            if (result.verificationAttempts().size() > 1) {
                out.append(" · attempt ")
                        .append(attempt.attempt())
                        .append(" (")
                        .append(lower(attempt.phase()))
                        .append(')');
            }
            out.append('\n');
            for (SpecVerifier.VerifierResult verifier : attempt.verifierResults()) {
                out.append("  ")
                        .append(verifier.status())
                        .append(' ')
                        .append(verifier.verifierId())
                        .append(" (")
                        .append(lower(verifier.type()))
                        .append(")\n")
                        .append("    ")
                        .append(verifier.detail())
                        .append('\n');
            }
        }

        out.append("📋 Acceptance Criterion 结果\n");
        for (SpecRunResult.CriterionResult criterion : result.criterionResults()) {
            out.append("  ")
                    .append(criterion.status())
                    .append(' ')
                    .append(criterion.criterionId())
                    .append(" (")
                    .append(lower(criterion.judge()))
                    .append(")\n")
                    .append("    ")
                    .append(criterion.reason())
                    .append('\n');
        }

        if (result.workspaceChanges() != null) {
            out.append("本轮 changed files: ")
                    .append(result.workspaceChanges().changedFiles().size())
                    .append('\n');
            for (String path : result.workspaceChanges().changedFiles()) {
                out.append("  - ").append(path).append('\n');
            }
            if (result.workspaceChanges().diffTruncated()) {
                out.append("⚠️ final diff 已按大小限制截断\n");
            }
        }
        if (result.verdict() != null) {
            out.append("🏁 最终 Verdict: ").append(result.verdict()).append('\n');
        }
        if (result.metrics().repairCount() > 0) {
            out.append("自动修复: ").append(result.metrics().repairCount()).append("/1\n");
        }
        if (result.artifacts().status() == SpecRunResult.PersistenceStatus.SAVED) {
            out.append("运行结果: ").append(result.artifacts().resultJson()).append('\n');
            out.append("变更证据: ").append(result.artifacts().changeDiff()).append('\n');
        } else if (result.artifacts().status() == SpecRunResult.PersistenceStatus.FAILED) {
            out.append("❌ ").append(result.artifacts().detail()).append('\n');
        }
        if (!result.detail().isBlank()
                && result.artifacts().status() != SpecRunResult.PersistenceStatus.FAILED) {
            out.append("详情: ").append(result.detail()).append('\n');
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
