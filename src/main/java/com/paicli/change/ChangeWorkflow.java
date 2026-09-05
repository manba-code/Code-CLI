package com.paicli.change;

public interface ChangeWorkflow {
    ChangeTaskId submit(ChangeRequest request);

    ChangeTaskView decide(ChangeTaskId id, ChangeDecision decision);

    ChangeTaskView cancelDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration, String actorId);

    ChangeTaskView retryDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration, String actorId);

    ChangeTaskView recordHumanEvidence(ChangeTaskId id, HumanEvidenceSubmission submission);

    ChangeTaskView get(ChangeTaskId id);
}
