package com.paicli.change;

public interface ChangeWorkflow {
    ChangeTaskId submit(ChangeRequest request);

    ChangeTaskView decide(ChangeTaskId id, ChangeDecision decision);

    default ChangeTaskView cancelDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration, String actorId) {
        return cancelDraft(id, expectedVersion, expectedGeneration, actorId, "LEGACY");
    }

    ChangeTaskView cancelDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration,
                               String actorId, String actorType);

    default ChangeTaskView retryDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration, String actorId) {
        return retryDraft(id, expectedVersion, expectedGeneration, actorId, "LEGACY");
    }

    ChangeTaskView retryDraft(ChangeTaskId id, long expectedVersion, String expectedGeneration,
                              String actorId, String actorType);

    ChangeTaskView recordHumanEvidence(ChangeTaskId id, HumanEvidenceSubmission submission);

    ChangeTaskView get(ChangeTaskId id);
}
