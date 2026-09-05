package com.paicli.change;

import com.paicli.spec.*;
import java.nio.file.*;
import java.util.*;

/** Offline fixtures with real locked documents and persisted, explicitly controlled verification. */
final class HumanEvidenceTestSupport {
    static FileChangeSpecModule specs(Path root) {
        return new FileChangeSpecModule(root, context -> new SpecDraftSession.DraftGeneration(
                new ChangeSpecCodec().decode(document(context.specId(), context.revision())), SpecRunResult.LlmUsage.empty(), 0));
    }
    static String document(String id, int revision) {
        return ChangeTestSupport.document(id, revision).replace("verifiers:\n  - id: VT-CMD", """
                  - id: AC-H1
                    kind: behavior
                    statement: Review the displayed output
                    oracle:
                      type: human
                  - id: AC-H2
                    kind: behavior
                    statement: Review readability
                    oracle:
                      type: human
                verifiers:
                  - id: VT-CMD""");
    }
    static ChangeTask finished(DefaultChangeWorkflow workflow, Path root, String key, boolean high,
                               SpecRunResult.Verdict verdict, String deterministic) throws Exception {
        var ready = ChangeTestSupport.approveSpec(workflow, ChangeTestSupport.request(root, key, high));
        var task = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), verdict);
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) ChangeJson.MAPPER.readTree(Files.readString(task.run().evidencePath().resolve("result.json")));
        result.set("criterionResults", ChangeJson.MAPPER.valueToTree(List.of(
                Map.of("criterionId", "AC-1", "status", deterministic.equals("ERROR") ? "INCONCLUSIVE" : deterministic, "judge", "verifier", "evidenceIds", List.of("EV-1")),
                Map.of("criterionId", "AC-SCOPE", "status", "PASS", "judge", "verifier", "evidenceIds", List.of("EV-2")),
                Map.of("criterionId", "AC-H1", "status", "NOT_RUN", "judge", "human"),
                Map.of("criterionId", "AC-H2", "status", "NOT_RUN", "judge", "human"))));
        result.set("verificationAttempts", ChangeJson.MAPPER.valueToTree(List.of(Map.of("attempt", 1, "verifierResults", List.of(
                Map.of("verifierId", "VT-CMD", "status", deterministic, "evidenceId", "EV-1"),
                Map.of("verifierId", "VT-SCOPE", "status", "PASS", "evidenceId", "EV-2"))))));
        Files.writeString(task.run().evidencePath().resolve("result.json"), result.toString());
        return task;
    }
    static HumanEvidenceSubmission input(ChangeTask task, String criterion, SpecRunResult.HumanDecision decision) {
        return new HumanEvidenceSubmission(task.version(), task.spec().digest(), task.run().runId(), task.run().headSha(),
                task.judgmentRevision(), criterion, decision, "Observed <img src=x onerror=alert(1)> safely", List.of("code-diff", "evidence:EV-1"), "reviewer");
    }
    static ChangeTask record(DefaultChangeWorkflow workflow, ChangeTask task, String criterion, SpecRunResult.HumanDecision decision) {
        return workflow.recordHumanEvidence(task.id(), input(task, criterion, decision)).task();
    }
}
