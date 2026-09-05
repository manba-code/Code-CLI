package com.paicli.change;

import com.paicli.spec.SpecRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ChangeArtifactReaderTest {
    @TempDir Path root;

    @Test void readsOnlyAssociatedRevisionsAndPersistedRun() throws Exception {
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
        var reader = new ChangeArtifactReader(root);
        var first = workflow.get(ChangeTestSupport.submitAndDraft(workflow, ChangeTestSupport.request(root, "read", false)));
        workflow.decide(first.task().id(), new ChangeDecision.SupplementSpec(first.task().version(),
                first.task().spec().digest(), "lead", "Keep cancellation"));
        var second = ChangeTestSupport.draft(workflow, first.task().id());
        var artifact = reader.read(second, 1, 2);
        assertEquals(2, artifact.path("revisions").size());
        assertTrue(artifact.path("revisionDiff").asText().contains("+revision: 2"));
        assertEquals(2, artifact.path("verifiers").size());
        assertTrue(artifact.path("lockedSpec").isNull());
        assertThrows(ChangeNotFoundException.class, () -> reader.read(second, 1, 9));
        var ready = workflow.decide(second.task().id(), new ChangeDecision.ApproveSpec(second.task().version(),
                second.task().spec().digest(), "lead", "checked")).task();
        var finished = ChangeTestSupport.finish(workflow, ready, root.resolve("runs"), SpecRunResult.Verdict.PASSED);
        var saved = reader.read(workflow.get(finished.id()), null, null);
        assertFalse(saved.path("lockedSpec").asText().isBlank());
        assertEquals("test diff", saved.path("codeDiff").asText());
        assertEquals("PASSED", saved.path("result").path("verdict").asText());
        Files.writeString(finished.run().evidencePath().resolve("result.json"), "{}");
        assertThrows(ChangeConflictException.class, () -> reader.read(workflow.get(finished.id()), null, null));
    }

    @Test void refusesSymlinkFileAndAncestorAndOutOfRootAssociation() throws Exception {
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root.resolve("trusted")));
        var reader = new ChangeArtifactReader(root.resolve("trusted"));
        var view = workflow.get(ChangeTestSupport.submitAndDraft(workflow, ChangeTestSupport.request(root, "links", false)));
        Path draft = view.task().spec().draftPath();
        Path outside = root.resolve("outside.md");
        Files.move(draft, outside);
        Files.createSymbolicLink(draft, outside);
        assertThrows(ChangeForbiddenException.class, () -> reader.read(view, null, null));
        Files.delete(draft);
        Files.move(outside, draft);
        Path parent = draft.getParent(), moved = root.resolve("moved");
        Files.move(parent, moved);
        Files.createSymbolicLink(parent, moved);
        assertThrows(ChangeForbiddenException.class, () -> reader.read(view, null, null));
        var otherReader = new ChangeArtifactReader(root.resolve("unrelated"));
        assertThrows(ChangeForbiddenException.class, () -> otherReader.read(view, null, null));
    }

    @Test void refusesTamperedRevisionAndMissingOrOversizedArtifacts() throws Exception {
        var store = new InMemoryChangeStore();
        var workflow = new DefaultChangeWorkflow(store, store, ChangeTestSupport.specs(root));
        var reader = new ChangeArtifactReader(root);
        var view = workflow.get(ChangeTestSupport.submitAndDraft(workflow, ChangeTestSupport.request(root, "tamper", false)));
        Path draft = view.task().spec().draftPath();
        String original = Files.readString(draft);
        Files.writeString(draft, original.replace("Write the verified output", "Changed goal"));
        assertThrows(ChangeConflictException.class, () -> reader.read(view, null, null));
        Files.writeString(draft, "x".repeat(4 * 1024 * 1024 + 1));
        assertThrows(ChangeValidationException.class, () -> reader.read(view, null, null));
        Files.delete(draft);
        assertThrows(NoSuchFileException.class, () -> reader.read(view, null, null));
    }
}
