package com.paicli.change;

import com.paicli.spec.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class ChangeTestSupport {
    static ChangeTaskId submitAndDraft(ChangeWorkflow workflow, ChangeRequest request) {
        ChangeTaskId id = workflow.submit(request);
        draft(workflow, id);
        return id;
    }

    static ChangeTaskView draft(ChangeWorkflow workflow, ChangeTaskId id) {
        DefaultChangeWorkflow concrete = (DefaultChangeWorkflow) workflow;
        DraftJob claim = concrete.claimDraft(id, DraftJob.TIMEOUT_MS);
        if (claim != null) {
            try { concrete.completeDraft(id, claim, concrete.generateDraft(claim)); }
            catch (java.io.IOException | RuntimeException e) { concrete.failDraft(id, claim, false, e.getMessage()); }
        }
        return workflow.get(id);
    }

    static FileChangeSpecModule specs(Path root) {
        return new FileChangeSpecModule(root, context -> new SpecDraftSession.DraftGeneration(
                new ChangeSpecCodec().decode(document(context.specId(), context.revision())),
                SpecRunResult.LlmUsage.empty(), 0L));
    }

    static String document(String id, int revision) {
        return """
                ---
                schema: paicli/change-spec/v1
                id: %s
                revision: %d
                title: Fix local output
                intent:
                  goal: Write the verified output
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: output is correct
                    oracle:
                      type: deterministic
                      verifiers: [VT-CMD]
                  - id: AC-SCOPE
                    kind: scope
                    statement: Changes stay in scope
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-CMD
                    type: command
                    command: verify-output
                    expect:
                      exit_code: 0
                  - id: VT-SCOPE
                    type: path_scope
                ---
                """.formatted(id, revision);
    }

    static ChangeRequest request(Path repository, String key, boolean high) {
        return new ChangeRequest(key, new WorkItemRef("mock_gitlab_issue", key, "",
                high ? List.of("high-risk") : List.of(), "normal"),
                new RepositoryRef(repository.toString(), "main"), "Local change", "Fix output",
                "requester", "", "");
    }

    static ChangeTask approveSpec(DefaultChangeWorkflow workflow, ChangeRequest request) {
        ChangeTask review = workflow.get(ChangeTestSupport.submitAndDraft(workflow, request)).task();
        return workflow.decide(review.id(), new ChangeDecision.ApproveSpec(review.version(),
                review.spec().digest(), "lead", "reviewed")).task();
    }

    static ChangeTask finish(DefaultChangeWorkflow workflow, ChangeTask ready, Path root,
                             SpecRunResult.Verdict verdict) throws Exception {
        if (ready.state() == ChangeState.READY) workflow.queueForExecution(ready.id(), ready.version());
        var lease = workflow.claimExecution(ready.id());
        lease.verificationStarted();
        Path evidence = Files.createDirectories(root.resolve(ready.id().value()));
        var status = verdict == SpecRunResult.Verdict.INCOMPLETE
                ? SpecRunResult.Status.REACT_FAILED : SpecRunResult.Status.FINISHED;
        Files.writeString(evidence.resolve("result.json"), ChangeJson.MAPPER.writeValueAsString(Map.of(
                "runId", "run-" + ready.id().value(), "spec", Map.of("digest", ready.spec().digest()),
                "verdict", verdict.name(), "status", status.name(),
                "verificationAttempts", List.of(), "criterionResults", List.of())));
        Files.writeString(evidence.resolve("change.diff"), "test diff");
        lease.complete(new RunRef("run-" + ready.id().value(), ready.spec().digest(), status, verdict,
                "workspace-" + ready.id().value(), "paichange/" + ready.id().value(), "head-current",
                evidence, Instant.now()));
        return workflow.get(ready.id()).task();
    }

    static ChangeDecision.ApproveDelivery approveDelivery(ChangeTask task) {
        return new ChangeDecision.ApproveDelivery(task.version(), task.spec().digest(), task.run().headSha(), task.run().runId(), task.judgmentRevision(),
                        "delivery-lead", "reviewed");
    }
}
