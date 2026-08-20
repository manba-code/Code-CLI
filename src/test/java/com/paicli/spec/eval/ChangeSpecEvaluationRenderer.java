package com.paicli.spec.eval;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.PrintStream;
import java.util.List;

final class ChangeSpecEvaluationRenderer implements Renderer {
    private final PrintStream out;

    ChangeSpecEvaluationRenderer(PrintStream out) {
        this.out = out;
    }

    @Override public void start() { }
    @Override public void close() { }
    @Override public PrintStream stream() { return out; }
    @Override public boolean rendersReasoning() { return false; }
    @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls != null) {
            toolCalls.forEach(call -> out.println("[tool] " + call.function().name()));
        }
    }
    @Override public void appendDiff(String filePath, String before, String after) {
        out.println("[diff] " + filePath);
    }
    @Override public void updateStatus(StatusInfo status) { }
    @Override public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("自动评测不提供交互式审批");
    }
    @Override public int openPalette(String title, List<String> items) { return -1; }
}
