package com.paicli.cli;

import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.ChangeSpecDocument;
import org.junit.jupiter.api.Test;

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
}
