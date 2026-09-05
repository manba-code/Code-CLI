package com.paicli.change;

import java.util.List;
import java.util.Objects;

/** RiskEngine 的确定性输出；reasons 顺序稳定，可直接用于审计。 */
public record RiskAssessment(RiskLevel level, int score, List<String> reasons) {
    public RiskAssessment {
        level = Objects.requireNonNull(level, "level");
        if (score < 0) {
            throw new IllegalArgumentException("score 不能为负数");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
