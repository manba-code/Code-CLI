package com.paicli.agent.eval;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class AgentEvaluationReport {
    private AgentEvaluationReport() {
    }

    static String toMarkdown(List<AgentEvaluationResult> results, String provider, String model,
                             long seed, int repetitions, boolean costConfigured) {
        StringBuilder report = new StringBuilder();
        report.append("# PaiCLI Agent A/B 质量评测\n\n")
                .append("- 时间：").append(Instant.now()).append("\n")
                .append("- Provider / Model：").append(provider).append(" / ").append(model).append("\n")
                .append("- 重复次数：").append(repetitions).append("\n")
                .append("- 随机种子：").append(seed).append("\n")
                .append("- 判定方式：运行结束后注入隐藏测试，并检查未授权文件变更\n\n");

        report.append("## 总览\n\n")
                .append("| 模式 | 通过/总数 | 成功率 | 平均检查通过率 | 平均 LLM 调用 | 平均输入 Token | 平均输出 Token | 平均耗时 | 估算成本 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (EvaluationMode mode : EvaluationMode.values()) {
            List<AgentEvaluationResult> group = results.stream().filter(r -> r.mode() == mode).toList();
            long passed = group.stream().filter(AgentEvaluationResult::passed).count();
            report.append("| ").append(mode.displayName()).append(" | ")
                    .append(passed).append("/").append(group.size()).append(" | ")
                    .append(percent(passed, group.size())).append(" | ")
                    .append(percent(group.stream().mapToInt(AgentEvaluationResult::passedChecks).sum(),
                            group.stream().mapToInt(AgentEvaluationResult::totalChecks).sum())).append(" | ")
                    .append(decimal(average(group, AgentEvaluationResult::llmCalls))).append(" | ")
                    .append(decimal(averageLong(group, AgentEvaluationResult::inputTokens))).append(" | ")
                    .append(decimal(averageLong(group, AgentEvaluationResult::outputTokens))).append(" | ")
                    .append(decimal(averageLong(group, AgentEvaluationResult::durationMillis) / 1000d)).append("s | ")
                    .append(costConfigured ? "$" + decimal(averageDouble(group, AgentEvaluationResult::estimatedCostUsd)) : "未配置")
                    .append(" |\n");
        }

        appendReviewerMetrics(report, results);
        report.append("\n## 逐次结果\n\n")
                .append("| 用例 | 模式 | 轮次 | 结果 | 检查 | 调用 | Token(in/out/cache) | 耗时 | Reviewer | 纠正 | 说明 |\n")
                .append("|---|---|---:|---|---:|---:|---:|---:|---|---:|---|\n");
        for (AgentEvaluationResult result : results) {
            String detail = result.error() == null
                    ? result.validationDetail()
                    : result.validationDetail() + "；运行异常=" + result.error();
            report.append("| ").append(escape(result.caseId())).append(" | ")
                    .append(result.mode().displayName()).append(" | ")
                    .append(result.repetition()).append(" | ")
                    .append(result.passed() ? "✅" : "❌").append(" | ")
                    .append(result.passedChecks()).append("/").append(result.totalChecks()).append(" | ")
                    .append(result.llmCalls()).append(" | ")
                    .append(result.inputTokens()).append("/").append(result.outputTokens()).append("/")
                    .append(result.cachedInputTokens()).append(" | ")
                    .append(decimal(result.durationMillis() / 1000d)).append("s | ")
                    .append(result.reviewDecision()).append(" | ")
                    .append(result.correctionRetries()).append(result.reviewRecovered() ? "(修复成功)" : "").append(" | ")
                    .append(escape(detail)).append(" |\n");
        }

        report.append("\n## 可审计产物\n\n");
        for (AgentEvaluationResult result : results) {
            report.append("- `").append(result.caseId()).append(" / ").append(result.mode().name())
                    .append(" / r").append(result.repetition()).append("`：`")
                    .append(result.workspace().toAbsolutePath()).append("`\n");
        }
        report.append("\n> 这是一组随机性评测，不是普通单元测试。比较结论应结合多轮成功率、成本和耗时，")
                .append("不要用单次结果宣称某种架构必然更优。\n");
        return report.toString();
    }

    private static void appendReviewerMetrics(StringBuilder report, List<AgentEvaluationResult> results) {
        List<AgentEvaluationResult> observed = results.stream()
                .filter(r -> r.mode() == EvaluationMode.MULTI_AGENT)
                .filter(r -> r.reviewDecision() != AgentEvaluationResult.ReviewDecision.NOT_OBSERVED)
                .toList();
        long trueAccept = observed.stream().filter(r -> r.passed()
                && r.reviewDecision() == AgentEvaluationResult.ReviewDecision.APPROVED).count();
        long falseAccept = observed.stream().filter(r -> !r.passed()
                && r.reviewDecision() == AgentEvaluationResult.ReviewDecision.APPROVED).count();
        long falseReject = observed.stream().filter(r -> r.passed()
                && r.reviewDecision() == AgentEvaluationResult.ReviewDecision.REJECTED).count();
        long trueReject = observed.stream().filter(r -> !r.passed()
                && r.reviewDecision() == AgentEvaluationResult.ReviewDecision.REJECTED).count();
        int retries = results.stream().filter(r -> r.mode() == EvaluationMode.MULTI_AGENT)
                .mapToInt(AgentEvaluationResult::correctionRetries).sum();
        long recoveries = results.stream().filter(r -> r.mode() == EvaluationMode.MULTI_AGENT)
                .filter(AgentEvaluationResult::reviewRecovered).count();

        report.append("\n## Reviewer 观察\n\n")
                .append("| 客观结果 | Reviewer批准 | Reviewer拒绝 |\n")
                .append("|---|---:|---:|\n")
                .append("| 隐藏验证通过 | ").append(trueAccept).append(" | ").append(falseReject).append(" |\n")
                .append("| 隐藏验证失败 | ").append(falseAccept).append(" | ").append(trueReject).append(" |\n\n")
                .append("- 误放行率：").append(percent(falseAccept, falseAccept + trueReject)).append("\n")
                .append("- 误拒率：").append(percent(falseReject, falseReject + trueAccept)).append("\n")
                .append("- 触发纠正执行：").append(retries).append(" 次\n")
                .append("- Reviewer反馈后最终恢复：").append(recoveries).append(" 次\n")
                .append("- 未形成明确批准/拒绝的 FAILED/BLOCKED 结果不进入混淆矩阵。\n");
    }

    private static double average(List<AgentEvaluationResult> values,
                                  java.util.function.ToIntFunction<AgentEvaluationResult> extractor) {
        return values.isEmpty() ? 0 : values.stream().mapToInt(extractor).average().orElse(0);
    }

    private static double averageLong(List<AgentEvaluationResult> values,
                                      java.util.function.ToLongFunction<AgentEvaluationResult> extractor) {
        return values.isEmpty() ? 0 : values.stream().mapToLong(extractor).average().orElse(0);
    }

    private static double averageDouble(List<AgentEvaluationResult> values,
                                        java.util.function.ToDoubleFunction<AgentEvaluationResult> extractor) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(extractor).average().orElse(0);
    }

    private static String percent(long numerator, long denominator) {
        return denominator == 0 ? "n/a" : decimal(numerator * 100d / denominator) + "%";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
