package com.paicli.spec.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class ChangeSpecEvaluationReport {
    private ChangeSpecEvaluationReport() {
    }

    static String toMarkdown(
            List<ChangeSpecEvaluationResult> results,
            String provider,
            String model,
            long seed,
            int repetitions,
            long censoredDurationMs,
            boolean costConfigured
    ) {
        StringBuilder report = new StringBuilder();
        report.append("# ChangeSpec V1 A/B/C 快速评测\n\n")
                .append("- 时间：").append(Instant.now()).append("\n")
                .append("- Provider / Model：").append(provider).append(" / ").append(model).append("\n")
                .append("- 每任务重复：").append(repetitions).append(" 次\n")
                .append("- 模式顺序 seed：").append(seed).append("\n")
                .append("- 未成功运行的 time_to_accepted_change 截断值：")
                .append(decimal(censoredDurationMs / 1000d)).append(" 秒\n")
                .append("- 人工介入时间：N/A（自动 Pilot 不把自动确认冒充人工时间）\n")
                .append("- 客观成功：最终候选同时通过隐藏 Oracle 与允许修改范围检查\n\n");

        report.append("## 总览\n\n")
                .append("| 组 | 任务成功率 | 首次成功率 | 公开接受率 | 虚假完成率 | Scope 越界率 | TTA P50 | 平均 LLM 调用 | 平均 Token(in/out) | 平均成本 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ChangeSpecEvaluationMode mode : ChangeSpecEvaluationMode.values()) {
            List<ChangeSpecEvaluationResult> group = group(results, mode, value -> true);
            report.append("| ").append(mode.displayName()).append(" | ")
                    .append(rate(count(group, ChangeSpecEvaluationResult::taskSuccess), group.size())).append(" | ")
                    .append(rate(count(group, ChangeSpecEvaluationResult::firstPassSuccess), group.size())).append(" | ")
                    .append(acceptanceRate(group)).append(" | ")
                    .append(falseCompletionRate(group)).append(" | ")
                    .append(rate(count(group, ChangeSpecEvaluationResult::scopeViolation), group.size())).append(" | ")
                    .append(decimal(median(group.stream()
                            .map(ChangeSpecEvaluationResult::timeToAcceptedChangeMs).toList()) / 1000d))
                    .append("s | ")
                    .append(decimal(group.stream().mapToInt(ChangeSpecEvaluationResult::llmCalls)
                            .average().orElse(0))).append(" | ")
                    .append(decimal(group.stream().mapToLong(ChangeSpecEvaluationResult::inputTokens)
                            .average().orElse(0))).append("/")
                    .append(decimal(group.stream().mapToLong(ChangeSpecEvaluationResult::outputTokens)
                            .average().orElse(0))).append(" | ")
                    .append(costConfigured
                            ? "$" + decimal(group.stream().mapToDouble(
                                    ChangeSpecEvaluationResult::estimatedCostUsd).average().orElse(0))
                            : "未配置")
                    .append(" |\n");
        }

        appendTierSummary(report, results);
        appendValueGate(report, results);
        appendPairingAudit(report, results);
        appendDetails(report, results);
        report.append("\n> B/C 的每条产品成本都计入同一份配对 Draft 的生成开销，以模拟独立产品运行；")
                .append("评测器实际只调用一次 Draft 并把同一 document/digest 交给 B/C，因此不能用逐行成本直接计算本次 API 账单。\n")
                .append("> 快速样本只用于工程决策，不构成统计学上的普遍提效结论。\n");
        return report.toString();
    }

    private static void appendTierSummary(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        report.append("\n## 分层成功率\n\n")
                .append("| 层级 | A | B | C |\n")
                .append("|---|---:|---:|---:|\n");
        for (ChangeSpecEvaluationTier tier : ChangeSpecEvaluationTier.values()) {
            report.append("| ").append(tier.displayName()).append(" | ");
            for (int index = 0; index < ChangeSpecEvaluationMode.values().length; index++) {
                ChangeSpecEvaluationMode mode = ChangeSpecEvaluationMode.values()[index];
                List<ChangeSpecEvaluationResult> group = group(results, mode, value -> value.tier() == tier);
                report.append(rate(count(group, ChangeSpecEvaluationResult::taskSuccess), group.size()));
                report.append(index == ChangeSpecEvaluationMode.values().length - 1 ? " |\n" : " | ");
            }
        }
    }

    private static void appendValueGate(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        Predicate<ChangeSpecEvaluationResult> mediumHigh = value -> value.tier() != ChangeSpecEvaluationTier.SMALL;
        List<ChangeSpecEvaluationResult> a = group(results, ChangeSpecEvaluationMode.REACT, mediumHigh);
        List<ChangeSpecEvaluationResult> c = group(results, ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, mediumHigh);
        double aSuccess = fraction(count(a, ChangeSpecEvaluationResult::taskSuccess), a.size());
        double cSuccess = fraction(count(c, ChangeSpecEvaluationResult::taskSuccess), c.size());
        double successDeltaPoints = (cSuccess - aSuccess) * 100d;
        Double falseCompletionReduction = relativeFalseCompletionReduction(a, c);
        double aP50 = median(a.stream().map(ChangeSpecEvaluationResult::timeToAcceptedChangeMs).toList());
        double cP50 = median(c.stream().map(ChangeSpecEvaluationResult::timeToAcceptedChangeMs).toList());
        double p50Change = aP50 == 0 ? Double.NaN : (cP50 - aP50) / aP50 * 100d;

        report.append("\n## RFC 首轮价值门槛（中型 + 高风险）\n\n")
                .append("- C 相对 A 的任务成功率变化：")
                .append(decimal(successDeltaPoints)).append(" 个百分点；门槛为至少 +10。\n")
                .append("- C 相对 A 的虚假完成率下降：")
                .append(falseCompletionReduction == null
                        ? "不可计算（A 没有可用分母或基线为 0）"
                        : decimal(falseCompletionReduction * 100d) + "%")
                .append("；门槛为至少 30%。\n")
                .append("- C 相对 A 的 TTA P50 变化：")
                .append(Double.isNaN(p50Change) ? "不可计算" : decimal(p50Change) + "%")
                .append("；不得恶化超过 15%。\n")
                .append("- human_intervention_time：N/A；因此本自动 Pilot 不能单独得出‘满足完整提效门槛’的结论。\n");
    }

    private static void appendPairingAudit(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        int pairs = 0;
        int matched = 0;
        for (ChangeSpecEvaluationResult b : results.stream()
                .filter(value -> value.mode() == ChangeSpecEvaluationMode.SPEC_NO_REPAIR).toList()) {
            ChangeSpecEvaluationResult c = results.stream()
                    .filter(value -> value.mode() == ChangeSpecEvaluationMode.SPEC_WITH_REPAIR)
                    .filter(value -> value.caseId().equals(b.caseId()) && value.repetition() == b.repetition())
                    .findFirst().orElse(null);
            if (c == null) continue;
            pairs++;
            if (!b.specDigest().isBlank() && b.specDigest().equals(c.specDigest())) matched++;
        }
        report.append("\n## B/C 配对审计\n\n")
                .append("- digest 一致：").append(matched).append("/").append(pairs).append(" 对。\n")
                .append("- B 仅关闭自动修复；公开 Verifier、Criterion、Verdict 与 C 保持同一生产链路。\n");
    }

    private static void appendDetails(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        report.append("\n## 逐次结果\n\n")
                .append("| 任务 | 层级 | 组 | 轮次 | 最终 | 首次 | 公开 Verdict | 诊断 | 修复 | Token(in/out/cache) | 产品耗时 | 隐藏 Oracle | 说明 |\n")
                .append("|---|---|---|---:|---|---|---|---|---:|---:|---:|---:|---|\n");
        results.stream()
                .sorted(Comparator.comparing(ChangeSpecEvaluationResult::caseId)
                        .thenComparingInt(ChangeSpecEvaluationResult::repetition)
                        .thenComparing(ChangeSpecEvaluationResult::mode))
                .forEach(value -> report.append("| ").append(escape(value.caseId())).append(" | ")
                        .append(value.tier().displayName()).append(" | ")
                        .append(value.mode().name()).append(" | ")
                        .append(value.repetition()).append(" | ")
                        .append(value.taskSuccess() ? "PASS" : "FAIL").append(" | ")
                        .append(value.firstPassSuccess() ? "PASS" : "FAIL").append(" | ")
                        .append(escape(value.publicVerdict())).append(" | ")
                        .append(escape(value.diagnosticClassification())).append(" | ")
                        .append(value.repairCount()).append(" | ")
                        .append(value.inputTokens()).append("/").append(value.outputTokens()).append("/")
                        .append(value.cachedInputTokens()).append(" | ")
                        .append(decimal(value.productDurationMs() / 1000d)).append("s | ")
                        .append(decimal(value.hiddenOracleDurationMs() / 1000d)).append("s | ")
                        .append(escape(join(value.detail(), value.error()))).append(" |\n"));

        report.append("\n## 可审计产物\n\n");
        for (ChangeSpecEvaluationResult value : results) {
            report.append("- `").append(value.caseId()).append(" / ").append(value.mode().name())
                    .append(" / r").append(value.repetition()).append("`：`")
                    .append(value.workspace().toAbsolutePath()).append('`');
            if (value.draftDiagnostic() != null) {
                report.append("；[Draft 诊断](<")
                        .append(value.draftDiagnostic().toString().replace('\\', '/'))
                        .append(">)");
            }
            report.append('\n');
        }
    }

    private static List<ChangeSpecEvaluationResult> group(
            List<ChangeSpecEvaluationResult> results,
            ChangeSpecEvaluationMode mode,
            Predicate<ChangeSpecEvaluationResult> predicate
    ) {
        return results.stream().filter(value -> value.mode() == mode).filter(predicate).toList();
    }

    private static long count(
            List<ChangeSpecEvaluationResult> results,
            Predicate<ChangeSpecEvaluationResult> predicate
    ) {
        return results.stream().filter(predicate).count();
    }

    private static String acceptanceRate(List<ChangeSpecEvaluationResult> group) {
        List<ChangeSpecEvaluationResult> applicable = group.stream()
                .filter(ChangeSpecEvaluationResult::acceptanceApplicable).toList();
        return applicable.isEmpty()
                ? "N/A"
                : rate(count(applicable, ChangeSpecEvaluationResult::acceptancePassed), applicable.size());
    }

    private static String falseCompletionRate(List<ChangeSpecEvaluationResult> group) {
        long completions = count(group, ChangeSpecEvaluationResult::completionClaimed);
        return completions == 0 ? "N/A" : rate(count(group, ChangeSpecEvaluationResult::falseCompletion), completions);
    }

    private static Double relativeFalseCompletionReduction(
            List<ChangeSpecEvaluationResult> baseline,
            List<ChangeSpecEvaluationResult> candidate
    ) {
        long baselineCompletions = count(baseline, ChangeSpecEvaluationResult::completionClaimed);
        long candidateCompletions = count(candidate, ChangeSpecEvaluationResult::completionClaimed);
        if (baselineCompletions == 0 || candidateCompletions == 0) return null;
        double baselineRate = fraction(count(baseline, ChangeSpecEvaluationResult::falseCompletion), baselineCompletions);
        if (baselineRate == 0) return null;
        double candidateRate = fraction(count(candidate, ChangeSpecEvaluationResult::falseCompletion), candidateCompletions);
        return (baselineRate - candidateRate) / baselineRate;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) return 0d;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2d;
    }

    private static String rate(long numerator, long denominator) {
        return denominator == 0 ? "N/A" : decimal(fraction(numerator, denominator) * 100d) + "%";
    }

    private static double fraction(long numerator, long denominator) {
        return denominator == 0 ? 0d : numerator / (double) denominator;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "；" + second;
    }
}
