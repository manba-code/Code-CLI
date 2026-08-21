package com.paicli.spec.eval;

import com.paicli.spec.ChangeSpecDocument;
import com.paicli.spec.SpecDraftSession;
import com.paicli.spec.SpecRunResult;

import java.nio.file.Path;

record ChangeSpecPairedDraft(
        ChangeSpecDocument document,
        SpecRunResult.LlmUsage usage,
        long durationMs,
        String error,
        Path diagnosticFile
) {
    ChangeSpecPairedDraft {
        usage = usage == null ? SpecRunResult.LlmUsage.empty() : usage;
        durationMs = Math.max(0L, durationMs);
        error = error == null ? "" : error;
    }

    boolean available() {
        return document != null && error.isBlank();
    }

    SpecDraftSession.DraftGeneration asGeneration() {
        if (!available()) throw new IllegalStateException("配对 Draft 不可用: " + error);
        return new SpecDraftSession.DraftGeneration(document, usage, durationMs);
    }
}
