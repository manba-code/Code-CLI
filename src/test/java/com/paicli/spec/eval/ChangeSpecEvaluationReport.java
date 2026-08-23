package com.paicli.spec.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class ChangeSpecEvaluationReport {
    private static final double SUCCESS_UPLIFT_THRESHOLD = 0.10d;
    private static final double SUCCESS_NON_INFERIORITY_MARGIN = 0.05d;
    private static final double FALSE_COMPLETION_REDUCTION_THRESHOLD = 0.30d;
    private static final double PENALIZED_TTA_REGRESSION_LIMIT = 0.15d;
    private static final double WILSON_Z_95 = 1.959963984540054d;

    private ChangeSpecEvaluationReport() {
    }

    static String toMarkdown(
            List<ChangeSpecEvaluationResult> results,
            String provider,
            String model,
            long seed,
            int repetitions,
            long censoredDurationMs,
            boolean costConfigured,
            String costCurrency
    ) {
        String currency = normalizeCurrency(costCurrency);
        StringBuilder report = new StringBuilder();
        report.append("# ChangeSpec V1 A/B/C 快速评测\n\n")
                .append("- 时间：").append(Instant.now()).append("\n")
                .append("- Provider / Model：").append(provider).append(" / ").append(model).append("\n")
                .append("- 每任务重复：").append(repetitions).append(" 次\n")
                .append("- 模式顺序 seed：").append(seed).append("\n")
                .append("- 未成功运行的惩罚 TTA 固定值：")
                .append(decimal(censoredDurationMs / 1000d)).append(" 秒（不是实际失败耗时）\n")
                .append("- 成本币种：").append(costConfigured ? currency : "未配置").append("\n")
                .append("- 人工总投入：NOT_MEASURED（自动 Pilot 不把自动确认冒充人工时间）\n")
                .append("- 客观成功：最终候选同时通过隐藏 Oracle 与允许修改范围检查\n")
                .append("- 结论状态：PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED\n\n");

        appendOutcomeSummary(report, results, costConfigured, currency);
        appendTimingSummary(report, results);
        appendTierSummary(report, results);
        appendValueAssessment(report, results);
        appendContrastSummary(report, results);
        appendPairingAudit(report, results);
        appendDetails(report, results);
        report.append("\n> B/C 的每条产品成本都计入同一份配对 Draft 的生成开销，以模拟独立产品运行；")
                .append("评测器实际只调用一次 Draft 并把同一 document/digest 交给 B/C，因此不能用逐行产品成本直接计算本次 API 账单。\n")
                .append("> NOT_EVALUABLE 表示没有可用改善空间、机会或分母；NOT_MEASURED 表示本实验未采集。两者都不能解释为‘没有价值’或‘已经通过’。\n")
                .append("> 快速样本只用于工程决策，不构成统计学上的普遍提效结论。\n");
        return report.toString();
    }

    private static void appendOutcomeSummary(
            StringBuilder report,
            List<ChangeSpecEvaluationResult> results,
            boolean costConfigured,
            String currency
    ) {
        report.append("## 结果质量总览\n\n")
                .append("| 组 | 客观成功率（95% CI） | 首次成功率 | 完成声明覆盖率 | 公开接受率 | 声明内虚假率 | 全运行虚假率 | Scope 越界率 | 修复机会/尝试率/条件成功率 | 平均 LLM 调用 | 平均 Token(in/out) | 平均产品成本 | 单位成功成本 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ChangeSpecEvaluationMode mode : ChangeSpecEvaluationMode.values()) {
            List<ChangeSpecEvaluationResult> group = group(results, mode, value -> true);
            long successes = count(group, ChangeSpecEvaluationResult::taskSuccess);
            long completions = count(group, ChangeSpecEvaluationResult::completionClaimed);
            long falseCompletions = count(group, ChangeSpecEvaluationResult::falseCompletion);
            report.append("| ").append(mode.displayName()).append(" | ")
                    .append(rateWithInterval(successes, group.size())).append(" | ")
                    .append(rate(count(group, ChangeSpecEvaluationResult::firstPassSuccess), group.size())).append(" | ")
                    .append(rate(completions, group.size())).append(" | ")
                    .append(acceptanceRate(group)).append(" | ")
                    .append(rate(falseCompletions, completions)).append(" | ")
                    .append(rate(falseCompletions, group.size())).append(" | ")
                    .append(rate(count(group, ChangeSpecEvaluationResult::scopeViolation), group.size())).append(" | ")
                    .append(repairOpportunity(group, mode)).append(" | ")
                    .append(decimal(group.stream().mapToInt(ChangeSpecEvaluationResult::llmCalls)
                            .average().orElse(0))).append(" | ")
                    .append(decimal(group.stream().mapToLong(ChangeSpecEvaluationResult::inputTokens)
                            .average().orElse(0))).append("/")
                    .append(decimal(group.stream().mapToLong(ChangeSpecEvaluationResult::outputTokens)
                            .average().orElse(0))).append(" | ")
                    .append(costConfigured
                            ? currency + " " + decimal(group.stream().mapToDouble(
                                    ChangeSpecEvaluationResult::estimatedCost).average().orElse(0))
                            : "未配置")
                    .append(" | ")
                    .append(unitSuccessCost(group, costConfigured, currency))
                    .append(" |\n");
        }
    }

    private static void appendTimingSummary(
            StringBuilder report,
            List<ChangeSpecEvaluationResult> results
    ) {
        report.append("\n## 时间口径总览\n\n")
                .append("| 组 | 产品耗时 P50 | 客观正确候选 TTA P50 | 可信产品决策 TTA P50 | 失败实际耗时 P50 | 惩罚 TTA P50 | 失败数 | Draft P50 | ReAct P50 | ReAct LLM 请求 P50 | ReAct 工具批次墙钟 P50 | 公开 Verifier P50 | 隐藏 Oracle P50 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ChangeSpecEvaluationMode mode : ChangeSpecEvaluationMode.values()) {
            List<ChangeSpecEvaluationResult> group = group(results, mode, value -> true);
            report.append("| ").append(mode.displayName()).append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::productDurationMs)).append(" | ")
                    .append(successDurationP50(group)).append(" | ")
                    .append(trustedDecisionP50(group, mode)).append(" | ")
                    .append(failureObservedP50(group)).append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::penalizedTimeToAcceptedChangeMs)).append(" | ")
                    .append(count(group, value -> !value.taskSuccess())).append("/").append(group.size()).append(" | ")
                    .append(mode.usesChangeSpec()
                            ? durationP50(group, ChangeSpecEvaluationResult::draftDurationMs) : "N/A")
                    .append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::reactExecutionMs)).append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::reactLlmRequestMs)).append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::reactToolExecutionMs)).append(" | ")
                    .append(mode.usesChangeSpec()
                            ? durationP50(group, ChangeSpecEvaluationResult::publicVerificationMs) : "N/A")
                    .append(" | ")
                    .append(durationP50(group, ChangeSpecEvaluationResult::hiddenOracleDurationMs)).append(" |\n");
        }
        report.append("\n- `客观正确候选 TTA`：仅对隐藏 Oracle 与 Scope 均通过的候选统计产品耗时加隐藏 Oracle 耗时。\n")
                .append("- `可信产品决策 TTA`：仅 B/C 有产品级 Verdict；A 没有等价可信决定，固定为 N/A。\n")
                .append("- `惩罚 TTA`：失败运行使用统一固定值，只用于历史工程评分；不得称为实际失败耗时或统计删失时间。\n")
                .append("- `ReAct LLM 请求`：累计每次模型请求的等待墙钟，失败请求也计入。\n")
                .append("- `ReAct 工具批次墙钟`：累计 Agent 等待各工具批次完成的墙钟；并行批次按整批等待时间统计，不把单项工具耗时相加。公开 Verifier 仍单列，不计入该列。\n");
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

    private static void appendValueAssessment(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        Predicate<ChangeSpecEvaluationResult> mediumHigh = value -> value.tier() != ChangeSpecEvaluationTier.SMALL;
        List<ChangeSpecEvaluationResult> a = group(results, ChangeSpecEvaluationMode.REACT, mediumHigh);
        List<ChangeSpecEvaluationResult> c = group(results, ChangeSpecEvaluationMode.SPEC_WITH_REPAIR, mediumHigh);

        GateAssessment nonInferiority = successNonInferiority(a, c);
        GateAssessment successUplift = successUplift(a, c);
        GateAssessment falseCompletion = falseCompletionImprovement(a, c);
        GateAssessment repair = repairValue(c);
        GateAssessment tta = penalizedTta(a, c);

        report.append("\n## RFC 首轮价值分维度结论（中型 + 高风险）\n\n")
                .append("| 维度 | 状态 | 结果与解释 |\n")
                .append("|---|---|---|\n");
        appendAssessment(report, "任务成功率非劣（暂定容忍 -5 个百分点）", nonInferiority);
        appendAssessment(report, "任务成功率增益（暂定至少 +10 个百分点）", successUplift);
        appendAssessment(report, "虚假完成改善（暂定相对下降至少 30%）", falseCompletion);
        appendAssessment(report, "Evidence 修复条件增益", repair);
        appendAssessment(report, "惩罚 TTA（暂定不得恶化超过 15%）", tta);
        appendAssessment(report, "完整人工投入", new GateAssessment(
                GateStatus.NOT_MEASURED,
                "自动 Pilot 未采集 Spec 确认、HITL、结果复核、返工与沟通总人时"));
        report.append("\n> 本报告不把不同维度合并成单一‘有价值/无价值’总分。")
                .append("成功率天花板、零缺陷下限、没有修复机会和未测人工时间必须分别解释。\n");
    }

    private static void appendContrastSummary(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        Predicate<ChangeSpecEvaluationResult> mediumHigh = value -> value.tier() != ChangeSpecEvaluationTier.SMALL;
        report.append("\n## A/B/C 配对作用拆分（中型 + 高风险）\n\n")
                .append("| 对照 | 解释 | 基线成功率 | 候选成功率 | 变化 | 候选胜/负/平 | 配对数 |\n")
                .append("|---|---|---:|---:|---:|---:|---:|\n");
        appendContrast(report, results, mediumHigh,
                ChangeSpecEvaluationMode.REACT, ChangeSpecEvaluationMode.SPEC_NO_REPAIR,
                "A→B", "契约 + 公开 Evidence Gate");
        appendContrast(report, results, mediumHigh,
                ChangeSpecEvaluationMode.SPEC_NO_REPAIR, ChangeSpecEvaluationMode.SPEC_WITH_REPAIR,
                "B→C", "一次 Evidence 修复");
        appendContrast(report, results, mediumHigh,
                ChangeSpecEvaluationMode.REACT, ChangeSpecEvaluationMode.SPEC_WITH_REPAIR,
                "A→C", "完整 ChangeSpec 产品路径");
    }

    private static void appendContrast(
            StringBuilder report,
            List<ChangeSpecEvaluationResult> results,
            Predicate<ChangeSpecEvaluationResult> predicate,
            ChangeSpecEvaluationMode baselineMode,
            ChangeSpecEvaluationMode candidateMode,
            String label,
            String explanation
    ) {
        List<ResultPair> pairs = pair(results, predicate, baselineMode, candidateMode);
        long baselineSuccesses = pairs.stream().filter(pair -> pair.baseline().taskSuccess()).count();
        long candidateSuccesses = pairs.stream().filter(pair -> pair.candidate().taskSuccess()).count();
        long wins = pairs.stream().filter(pair -> !pair.baseline().taskSuccess() && pair.candidate().taskSuccess()).count();
        long losses = pairs.stream().filter(pair -> pair.baseline().taskSuccess() && !pair.candidate().taskSuccess()).count();
        long ties = pairs.size() - wins - losses;
        String delta = pairs.isEmpty()
                ? "N/A"
                : signedDecimal((candidateSuccesses - baselineSuccesses) * 100d / pairs.size()) + " pp";
        report.append("| ").append(label).append(" | ").append(explanation).append(" | ")
                .append(rate(baselineSuccesses, pairs.size())).append(" | ")
                .append(rate(candidateSuccesses, pairs.size())).append(" | ")
                .append(delta).append(" | ")
                .append(wins).append("/").append(losses).append("/").append(ties).append(" | ")
                .append(pairs.size()).append(" |\n");
    }

    private static void appendPairingAudit(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        List<ResultPair> pairs = pair(results, value -> true,
                ChangeSpecEvaluationMode.SPEC_NO_REPAIR, ChangeSpecEvaluationMode.SPEC_WITH_REPAIR);
        long matched = pairs.stream()
                .filter(pair -> !pair.baseline().specDigest().isBlank()
                        && pair.baseline().specDigest().equals(pair.candidate().specDigest()))
                .count();
        report.append("\n## B/C 配对审计\n\n")
                .append("- digest 一致：").append(matched).append("/").append(pairs.size()).append(" 对。\n")
                .append("- B 仅关闭自动修复；公开 Verifier、Criterion、Verdict 与 C 保持同一生产链路。\n");
    }

    private static void appendDetails(StringBuilder report, List<ChangeSpecEvaluationResult> results) {
        report.append("\n## 逐次结果\n\n")
                .append("| 任务 | 层级 | 组 | 轮次 | 最终 | 首次 | 公开 Verdict | 诊断 | 修复机会 | 修复 | Token(in/out/cache) | 产品耗时 | ReAct LLM | ReAct 工具批次 | 隐藏 Oracle | 说明 |\n")
                .append("|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|\n");
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
                        .append(value.repairEligible() ? "YES" : "NO").append(" | ")
                        .append(value.repairCount()).append(" | ")
                        .append(value.inputTokens()).append("/").append(value.outputTokens()).append("/")
                        .append(value.cachedInputTokens()).append(" | ")
                        .append(decimal(value.productDurationMs() / 1000d)).append("s | ")
                        .append(decimal(value.reactLlmRequestMs() / 1000d)).append("s | ")
                        .append(decimal(value.reactToolExecutionMs() / 1000d)).append("s | ")
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

    private static GateAssessment successNonInferiority(
            List<ChangeSpecEvaluationResult> baseline,
            List<ChangeSpecEvaluationResult> candidate
    ) {
        if (baseline.isEmpty() || candidate.isEmpty()) {
            return new GateAssessment(GateStatus.NOT_MEASURED, "缺少 A 或 C 样本");
        }
        double baselineRate = successFraction(baseline);
        double candidateRate = successFraction(candidate);
        double delta = candidateRate - baselineRate;
        GateStatus status = delta >= -SUCCESS_NON_INFERIORITY_MARGIN ? GateStatus.PASS : GateStatus.FAIL;
        return new GateAssessment(status,
                "A=" + percent(baselineRate) + "，C=" + percent(candidateRate)
                        + "，变化=" + signedDecimal(delta * 100d) + " pp");
    }

    private static GateAssessment successUplift(
            List<ChangeSpecEvaluationResult> baseline,
            List<ChangeSpecEvaluationResult> candidate
    ) {
        if (baseline.isEmpty() || candidate.isEmpty()) {
            return new GateAssessment(GateStatus.NOT_MEASURED, "缺少 A 或 C 样本");
        }
        double baselineRate = successFraction(baseline);
        double candidateRate = successFraction(candidate);
        double delta = candidateRate - baselineRate;
        if (baselineRate >= 1d - SUCCESS_NON_INFERIORITY_MARGIN
                && candidateRate >= baselineRate - SUCCESS_NON_INFERIORITY_MARGIN) {
            return new GateAssessment(GateStatus.NOT_EVALUABLE,
                    "A=" + percent(baselineRate) + "，C=" + percent(candidateRate)
                            + "；基线接近天花板且 C 保持非劣，没有至少 10 pp 的可用改善空间");
        }
        return new GateAssessment(delta >= SUCCESS_UPLIFT_THRESHOLD ? GateStatus.PASS : GateStatus.FAIL,
                "A=" + percent(baselineRate) + "，C=" + percent(candidateRate)
                        + "，变化=" + signedDecimal(delta * 100d) + " pp");
    }

    private static GateAssessment falseCompletionImprovement(
            List<ChangeSpecEvaluationResult> baseline,
            List<ChangeSpecEvaluationResult> candidate
    ) {
        long baselineCompletions = count(baseline, ChangeSpecEvaluationResult::completionClaimed);
        long candidateCompletions = count(candidate, ChangeSpecEvaluationResult::completionClaimed);
        if (baselineCompletions == 0 || candidateCompletions == 0) {
            return new GateAssessment(GateStatus.NOT_EVALUABLE,
                    "A 或 C 没有完成声明，缺少可比较分母；需同时审阅完成声明覆盖率");
        }
        double baselineRate = fraction(count(baseline, ChangeSpecEvaluationResult::falseCompletion), baselineCompletions);
        double candidateRate = fraction(count(candidate, ChangeSpecEvaluationResult::falseCompletion), candidateCompletions);
        if (baselineRate == 0d) {
            return candidateRate == 0d
                    ? new GateAssessment(GateStatus.NOT_EVALUABLE,
                            "A/C 声明内虚假率均为 0%；C 保持非劣，但基线已触及下限")
                    : new GateAssessment(GateStatus.FAIL,
                            "A 声明内虚假率为 0%，C 回退到 " + percent(candidateRate));
        }
        double reduction = (baselineRate - candidateRate) / baselineRate;
        return new GateAssessment(
                reduction >= FALSE_COMPLETION_REDUCTION_THRESHOLD ? GateStatus.PASS : GateStatus.FAIL,
                "A=" + percent(baselineRate) + "，C=" + percent(candidateRate)
                        + "，相对下降=" + decimal(reduction * 100d) + "%");
    }

    private static GateAssessment repairValue(List<ChangeSpecEvaluationResult> candidate) {
        List<ChangeSpecEvaluationResult> eligible = candidate.stream()
                .filter(ChangeSpecEvaluationResult::repairEligible).toList();
        if (eligible.isEmpty()) {
            return new GateAssessment(GateStatus.NOT_EVALUABLE,
                    "repair_eligible_count=0；没有失败后进入 Evidence 修复的机会");
        }
        long recovered = count(eligible, value -> !value.firstPassSuccess() && value.taskSuccess());
        return new GateAssessment(recovered > 0 ? GateStatus.PASS : GateStatus.FAIL,
                "repair_eligible_count=" + eligible.size()
                        + "，修复后恢复=" + recovered
                        + "，条件成功率=" + rate(recovered, eligible.size()));
    }

    private static GateAssessment penalizedTta(
            List<ChangeSpecEvaluationResult> baseline,
            List<ChangeSpecEvaluationResult> candidate
    ) {
        if (baseline.isEmpty() || candidate.isEmpty()) {
            return new GateAssessment(GateStatus.NOT_MEASURED, "缺少 A 或 C 样本");
        }
        double baselineP50 = median(baseline.stream()
                .map(ChangeSpecEvaluationResult::penalizedTimeToAcceptedChangeMs).toList());
        double candidateP50 = median(candidate.stream()
                .map(ChangeSpecEvaluationResult::penalizedTimeToAcceptedChangeMs).toList());
        if (baselineP50 == 0d) {
            return new GateAssessment(GateStatus.NOT_EVALUABLE, "A 的惩罚 TTA P50 为 0，无法计算变化");
        }
        double change = (candidateP50 - baselineP50) / baselineP50;
        return new GateAssessment(change <= PENALIZED_TTA_REGRESSION_LIMIT ? GateStatus.PASS : GateStatus.FAIL,
                "A=" + decimal(baselineP50 / 1000d) + "s，C="
                        + decimal(candidateP50 / 1000d) + "s，变化="
                        + signedDecimal(change * 100d) + "%；该值是失败惩罚评分，不是实际失败耗时");
    }

    private static void appendAssessment(StringBuilder report, String dimension, GateAssessment assessment) {
        report.append("| ").append(dimension).append(" | ")
                .append(assessment.status()).append(" | ")
                .append(escape(assessment.detail())).append(" |\n");
    }

    private static String repairOpportunity(
            List<ChangeSpecEvaluationResult> group,
            ChangeSpecEvaluationMode mode
    ) {
        if (mode != ChangeSpecEvaluationMode.SPEC_WITH_REPAIR) return "N/A";
        List<ChangeSpecEvaluationResult> eligible = group.stream()
                .filter(ChangeSpecEvaluationResult::repairEligible).toList();
        long recovered = count(eligible, value -> !value.firstPassSuccess() && value.taskSuccess());
        return "eligible=" + eligible.size() + "/" + group.size()
                + "；attempt=" + (eligible.isEmpty() ? "N/A" : "100.00%")
                + "；conditional=" + (eligible.isEmpty() ? "N/A" : rate(recovered, eligible.size()));
    }

    private static String acceptanceRate(List<ChangeSpecEvaluationResult> group) {
        List<ChangeSpecEvaluationResult> applicable = group.stream()
                .filter(ChangeSpecEvaluationResult::acceptanceApplicable).toList();
        return applicable.isEmpty()
                ? "N/A"
                : rate(count(applicable, ChangeSpecEvaluationResult::acceptancePassed), applicable.size());
    }

    private static String unitSuccessCost(
            List<ChangeSpecEvaluationResult> group,
            boolean costConfigured,
            String currency
    ) {
        if (!costConfigured) return "未配置";
        long successes = count(group, ChangeSpecEvaluationResult::taskSuccess);
        if (successes == 0) return "N/A";
        double totalCost = group.stream().mapToDouble(ChangeSpecEvaluationResult::estimatedCost).sum();
        return currency + " " + decimal(totalCost / successes);
    }

    private static String successDurationP50(List<ChangeSpecEvaluationResult> group) {
        List<Long> successful = group.stream()
                .filter(ChangeSpecEvaluationResult::taskSuccess)
                .map(ChangeSpecEvaluationResult::observedOutcomeDurationMs)
                .toList();
        return successful.isEmpty() ? "N/A" : seconds(median(successful));
    }

    private static String trustedDecisionP50(
            List<ChangeSpecEvaluationResult> group,
            ChangeSpecEvaluationMode mode
    ) {
        if (!mode.usesChangeSpec()) return "N/A";
        List<Long> trusted = group.stream()
                .filter(ChangeSpecEvaluationResult::trustedProductDecisionAvailable)
                .map(ChangeSpecEvaluationResult::trustedProductDecisionMs)
                .toList();
        return trusted.isEmpty() ? "N/A" : seconds(median(trusted));
    }

    private static String failureObservedP50(List<ChangeSpecEvaluationResult> group) {
        List<Long> failed = group.stream()
                .filter(value -> !value.taskSuccess())
                .map(ChangeSpecEvaluationResult::observedOutcomeDurationMs)
                .toList();
        return failed.isEmpty() ? "N/A" : seconds(median(failed));
    }

    private static String durationP50(
            List<ChangeSpecEvaluationResult> group,
            java.util.function.Function<ChangeSpecEvaluationResult, Long> mapper
    ) {
        if (group.isEmpty()) return "N/A";
        return seconds(median(group.stream().map(mapper).toList()));
    }

    private static String seconds(double durationMs) {
        return decimal(durationMs / 1000d) + "s";
    }

    private static List<ChangeSpecEvaluationResult> group(
            List<ChangeSpecEvaluationResult> results,
            ChangeSpecEvaluationMode mode,
            Predicate<ChangeSpecEvaluationResult> predicate
    ) {
        return results.stream().filter(value -> value.mode() == mode).filter(predicate).toList();
    }

    private static List<ResultPair> pair(
            List<ChangeSpecEvaluationResult> results,
            Predicate<ChangeSpecEvaluationResult> predicate,
            ChangeSpecEvaluationMode baselineMode,
            ChangeSpecEvaluationMode candidateMode
    ) {
        List<ResultPair> pairs = new ArrayList<>();
        for (ChangeSpecEvaluationResult baseline : group(results, baselineMode, predicate)) {
            ChangeSpecEvaluationResult candidate = results.stream()
                    .filter(predicate)
                    .filter(value -> value.mode() == candidateMode)
                    .filter(value -> value.caseId().equals(baseline.caseId())
                            && value.repetition() == baseline.repetition())
                    .findFirst().orElse(null);
            if (candidate != null) pairs.add(new ResultPair(baseline, candidate));
        }
        return pairs;
    }

    private static long count(
            List<ChangeSpecEvaluationResult> results,
            Predicate<ChangeSpecEvaluationResult> predicate
    ) {
        return results.stream().filter(predicate).count();
    }

    private static double successFraction(List<ChangeSpecEvaluationResult> group) {
        return fraction(count(group, ChangeSpecEvaluationResult::taskSuccess), group.size());
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

    private static String rateWithInterval(long numerator, long denominator) {
        if (denominator == 0) return "N/A";
        double center = fraction(numerator, denominator);
        double z2 = WILSON_Z_95 * WILSON_Z_95;
        double adjustedCenter = (center + z2 / (2d * denominator)) / (1d + z2 / denominator);
        double adjustedHalfWidth = WILSON_Z_95 / (1d + z2 / denominator)
                * Math.sqrt(center * (1d - center) / denominator + z2 / (4d * denominator * denominator));
        double lower = Math.max(0d, adjustedCenter - adjustedHalfWidth);
        double upper = Math.min(1d, adjustedCenter + adjustedHalfWidth);
        return percent(center) + " [" + percent(lower) + ", " + percent(upper) + "]";
    }

    private static String rate(long numerator, long denominator) {
        return denominator == 0 ? "N/A" : percent(fraction(numerator, denominator));
    }

    private static double fraction(long numerator, long denominator) {
        return denominator == 0 ? 0d : numerator / (double) denominator;
    }

    private static String percent(double value) {
        return decimal(value * 100d) + "%";
    }

    private static String signedDecimal(double value) {
        return (value > 0d ? "+" : "") + decimal(value);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String normalizeCurrency(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : "USD";
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

    private enum GateStatus {
        PASS,
        FAIL,
        NOT_EVALUABLE,
        NOT_MEASURED
    }

    private record GateAssessment(GateStatus status, String detail) {
    }

    private record ResultPair(ChangeSpecEvaluationResult baseline, ChangeSpecEvaluationResult candidate) {
    }
}
