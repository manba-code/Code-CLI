package com.paicli.cli;

import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.ChangeSpecDocument;
import com.paicli.spec.SpecRunResult;
import com.paicli.spec.WorkspaceChangeTracker;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSpecCliFormatterTest {

    @Test
    void formatsOnlyCompactReviewFields() {
        ChangeSpecDocument document = new ChangeSpecCodec().decode("""
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-TEST-001
                revision: 1
                title: 修复重试
                intent:
                  goal: 超时最多重试三次
                  non_goals:
                    - 不更换 HTTP Client
                scope:
                  mode: open
                  include: []
                  exclude:
                    - pom.xml
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 超时最多重试三次
                    oracle:
                      type: human
                      verifiers: []
                  - id: AC-SCOPE
                    kind: scope
                    statement: 修改不得超出声明的 Scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                ---

                # 背景

                这里不应出现在单屏摘要中。
                """);

        String summary = ChangeSpecCliFormatter.formatDraft(document);

        assertTrue(summary.contains("目标：超时最多重试三次"));
        assertTrue(summary.contains("非目标：不更换 HTTP Client"));
        assertTrue(summary.contains("exclude：pom.xml"));
        assertTrue(summary.contains("AC-1 [behavior/human]"));
        assertFalse(summary.contains("这里不应出现在单屏摘要中"));
    }

    @Test
    void formatsCriterionVerdictWorkspaceAndPersistenceFailure() {
        SpecRunResult result = new SpecRunResult(
                SpecRunResult.Status.FINISHED,
                new SpecRunResult.RunIdentity(
                        "RUN-1", "CHANGE-1", 1, "digest", Path.of(".paicli/specs/CHANGE-1-r1.md")),
                "done",
                new WorkspaceChangeTracker.WorkspaceChanges(List.of("src/App.java"), "diff", true),
                List.of(
                        new SpecRunResult.VerificationAttempt(
                                1,
                                SpecRunResult.VerificationPhase.INITIAL,
                                new WorkspaceChangeTracker.WorkspaceChanges(List.of("src/App.java"), "diff", true),
                                List.of()),
                        new SpecRunResult.VerificationAttempt(
                                2,
                                SpecRunResult.VerificationPhase.POST_REPAIR,
                                new WorkspaceChangeTracker.WorkspaceChanges(List.of("src/App.java"), "diff", true),
                                List.of())),
                List.of(
                        new SpecRunResult.CriterionResult(
                                "AC-1",
                                SpecRunResult.CriterionStatus.PASS,
                                List.of("verifier:attempt-2:VT-1"),
                                SpecRunResult.Judge.VERIFIER,
                                "检查通过"),
                        new SpecRunResult.CriterionResult(
                                "AC-2",
                                SpecRunResult.CriterionStatus.NOT_RUN,
                                List.of("human:AC-2"),
                                SpecRunResult.Judge.HUMAN,
                                "用户跳过")),
                List.of(),
                SpecRunResult.Verdict.NEEDS_HUMAN,
                new SpecRunResult.Metrics(
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        1,
                        0L,
                        SpecRunResult.LlmUsage.empty(),
                        SpecRunResult.LlmUsage.empty()),
                SpecRunResult.Artifacts.failed(Path.of(".paicli/runs/RUN-1"), "运行结果持久化失败"),
                "");

        String summary = ChangeSpecCliFormatter.formatResult(result);

        assertTrue(summary.contains("PASS AC-1 (verifier)"));
        assertTrue(summary.contains("NOT_RUN AC-2 (human)"));
        assertTrue(summary.contains("src/App.java"));
        assertTrue(summary.contains("final diff 已按大小限制截断"));
        assertTrue(summary.contains("最终 Verdict: NEEDS_HUMAN"));
        assertTrue(summary.contains("attempt 1 (initial)"));
        assertTrue(summary.contains("attempt 2 (post_repair)"));
        assertTrue(summary.contains("自动修复: 1/1"));
        assertTrue(summary.contains("运行结果持久化失败"));
    }
}
