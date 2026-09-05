package com.paicli.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChangeSpecModuleTest {
    private final ChangeSpecCodec codec = new ChangeSpecCodec();

    @TempDir
    Path projectRoot;

    @Test
    void persistsDraftAndLocksOnlyMatchingDigest() throws Exception {
        FileChangeSpecModule module = module();
        ChangeSpecModule.ChangeContext context = context(1);

        ChangeSpecModule.SpecDraft draft = module.generateDraft(context);

        assertTrue(Files.isRegularFile(draft.path()));
        assertEquals("CHANGE-ASYNC-1", draft.specId());
        assertThrows(SpecDigestConflictException.class,
                () -> module.lockConfirmed(draft, "stale-digest"));

        ChangeSpecModule.LockedSpec locked = module.lockConfirmed(draft, draft.specDigest());
        assertTrue(Files.isRegularFile(locked.path()));
        assertEquals(projectRoot.resolve(".paicli/specs/CHANGE-ASYNC-1-r1.md"), locked.path());
        assertThrows(FileAlreadyExistsException.class,
                () -> module.lockConfirmed(draft, draft.specDigest()));
    }

    @Test
    void tamperedPersistedDraftCannotBeApproved() throws Exception {
        FileChangeSpecModule module = module();
        ChangeSpecModule.SpecDraft draft = module.generateDraft(context(1));
        Files.writeString(draft.path(), validDocument("CHANGE-ASYNC-1", 1)
                .replace("title: 修复问题", "title: 被篡改"));

        IOException error = assertThrows(IOException.class,
                () -> module.lockConfirmed(draft, draft.specDigest()));

        assertTrue(error.getMessage().contains("已发生变化"));
        assertTrue(Files.notExists(projectRoot.resolve(".paicli/specs/CHANGE-ASYNC-1-r1.md")));
    }

    @Test
    void supplementRevisionGetsIndependentDraftFile() throws Exception {
        FileChangeSpecModule module = module();

        ChangeSpecModule.SpecDraft first = module.generateDraft(context(1));
        ChangeSpecModule.SpecDraft second = module.generateDraft(context(2));

        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertTrue(Files.isRegularFile(first.path()));
        assertTrue(Files.isRegularFile(second.path()));
    }

    @Test
    void refusesDraftReferenceOutsideManagedDirectory() {
        FileChangeSpecModule module = module();
        ChangeSpecModule.SpecDraft outside = new ChangeSpecModule.SpecDraft(
                projectRoot.resolve("outside.md"),
                "CHANGE-ASYNC-1",
                1,
                "digest",
                0L,
                null);

        IOException error = assertThrows(IOException.class,
                () -> module.lockConfirmed(outside, "digest"));

        assertTrue(error.getMessage().contains("不属于当前项目"));
    }

    private FileChangeSpecModule module() {
        return new FileChangeSpecModule(projectRoot, context -> SpecDraftSession.DraftGeneration.unmeasured(
                codec.decode(validDocument(context.specId(), context.revision()))));
    }

    private static ChangeSpecModule.ChangeContext context(int revision) {
        return new ChangeSpecModule.ChangeContext(
                "change_123456789abc",
                "CHANGE-ASYNC-1",
                revision,
                "修复问题",
                "",
                "");
    }

    private static String validDocument(String id, int revision) {
        return """
                ---
                schema: paicli/change-spec/v1
                id: %s
                revision: %d
                title: 修复问题
                intent:
                  goal: 修复问题
                  non_goals: []
                scope:
                  mode: open
                  include: []
                  exclude: []
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 问题已修复
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
                """.formatted(id, revision);
    }
}
