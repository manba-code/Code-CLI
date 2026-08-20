package com.paicli.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSpecCodecTest {

    private final ChangeSpecCodec codec = new ChangeSpecCodec();

    @Test
    void decodesValidDocumentAndComputesDigest() {
        ChangeSpecDocument document = codec.decode(validDocument());

        assertEquals("CHANGE-001", document.spec().id());
        assertEquals("修复登录重试", document.spec().title());
        assertEquals("超时错误最多重试三次", document.spec().intent().goal());
        assertEquals(ChangeSpec.ScopeMode.BOUNDED, document.spec().scope().mode());
        assertEquals(2, document.spec().acceptance().size());
        assertEquals(2, document.spec().verifiers().size());
        assertEquals("# 背景\n\n登录请求可能无限重试。\n", document.markdownBody());
        assertTrue(document.specDigest().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsUnsupportedSchema() {
        String input = validDocument().replace("paicli/change-spec/v1", "paicli/change-spec/v2");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals("schema 必须是 paicli/change-spec/v1", error.errors().get(0));
    }

    @Test
    void rejectsMissingIdentityAndIntent() {
        String input = validDocument()
                .replace("id: CHANGE-001", "id: ' '")
                .replace("revision: 1", "revision: 0")
                .replace("title: 修复登录重试", "title: ' '")
                .replace("goal: 超时错误最多重试三次", "goal: ' '");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                List.of(
                        "id 不能为空",
                        "revision 必须大于等于 1",
                        "title 不能为空",
                        "intent.goal 不能为空"),
                error.errors());
    }

    @Test
    void rejectsBoundedScopeWithoutIncludePaths() {
        String input = validDocument().replace(
                "include:\n    - src/main/java/auth/**",
                "include: []");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals("scope.mode 为 bounded 时 include 不能为空", error.errors().get(0));
    }

    @Test
    void rejectsScopePathsThatEscapeTheProject() {
        String input = validDocument().replace(
                "src/main/java/auth/**",
                "../outside/**");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                "scope.include[0] 必须是项目内使用 / 的相对路径",
                error.errors().get(0));
    }

    @Test
    void rejectsAcceptanceThatReferencesUnknownVerifier() {
        String input = validDocument().replace(
                "verifiers: [VT-TEST]",
                "verifiers: [VT-MISSING]");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                "acceptance[AC-1] 引用了不存在的 Verifier: VT-MISSING",
                error.errors().get(0));
    }

    @Test
    void requiresJUnitReportWhenMinimumTestsIsConfigured() {
        String input = validDocument().replace(
                "      junit_report_glob: target/surefire-reports/TEST-*.xml\n",
                "");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                "verifiers[VT-TEST].expect.junit_report_glob 在 minimum_tests 存在时不能为空",
                error.errors().get(0));
    }

    @Test
    void rejectsDuplicateAcceptanceAndVerifierIds() {
        String input = validDocument()
                .replace("AC-2", "AC-1")
                .replace("VT-SCOPE", "VT-TEST");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                List.of(
                        "acceptance id 重复: AC-1",
                        "verifier id 重复: VT-TEST"),
                error.errors());
    }

    @Test
    void rejectsIncompleteDeterministicAcceptance() {
        String input = validDocument()
                .replace(
                        "statement: 超时错误最多重试三次",
                        "statement: ' '")
                .replace(
                        "verifiers: [VT-TEST]",
                        "verifiers: []");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                List.of(
                        "acceptance[AC-1].statement 不能为空",
                        "acceptance[AC-1] 的 deterministic oracle 必须引用至少一个 Verifier"),
                error.errors());
    }

    @Test
    void rejectsIncompleteCommandVerifier() {
        String input = validDocument()
                .replace(
                        "command: mvn -q -DskipTests=false test",
                        "command: ' '")
                .replace(
                        "minimum_tests: 1",
                        "minimum_tests: 0");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertEquals(
                List.of(
                        "verifiers[VT-TEST].command 不能为空",
                        "verifiers[VT-TEST].expect.minimum_tests 必须大于 0"),
                error.errors());
    }

    @Test
    void decodesDocumentWithoutMarkdownBody() {
        String input = validDocument();
        int closingMarker = input.indexOf("\n---\n");
        String yamlOnly = input.substring(0, closingMarker + 4);

        ChangeSpecDocument document = codec.decode(yamlOnly);

        assertEquals("", document.markdownBody());
    }

    @Test
    void reportsUnknownYamlFieldsAsValidationErrors() {
        String input = validDocument().replace(
                "title: 修复登录重试",
                "title: 修复登录重试\nunknown_field: true");

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.decode(input));

        assertTrue(error.errors().get(0).contains("ChangeSpec YAML 无法解析"));
    }

    @Test
    void digestBindsOnlyTheMachineContract() {
        ChangeSpecDocument original = codec.decode(validDocument());
        ChangeSpecDocument markdownChanged = codec.decode(
                validDocument().replace("登录请求可能无限重试。", "不同的背景说明。"));
        ChangeSpecDocument yamlFormattingChanged = codec.decode(
                validDocument().replace(
                        "schema: paicli/change-spec/v1",
                        "# YAML 注释不属于机器事实\nschema: paicli/change-spec/v1"));
        ChangeSpecDocument contractChanged = codec.decode(
                validDocument().replace("title: 修复登录重试", "title: 调整登录重试"));

        assertEquals(original.specDigest(), markdownChanged.specDigest());
        assertEquals(original.specDigest(), yamlFormattingChanged.specDigest());
        assertNotEquals(original.specDigest(), contractChanged.specDigest());
    }

    @Test
    void encodesValidatedDocumentAndPreservesIdentity() throws Exception {
        ChangeSpecDocument original = codec.decode(validDocument());

        String encoded = codec.encode(original);
        ChangeSpecDocument decoded = codec.decode(encoded);

        assertTrue(encoded.contains("mode: bounded"), encoded);
        assertTrue(encoded.contains("kind: behavior"), encoded);
        assertTrue(encoded.contains("type: path_scope"), encoded);
        assertEquals(original.spec(), decoded.spec());
        assertEquals(original.markdownBody(), decoded.markdownBody());
        assertEquals(original.specDigest(), decoded.specDigest());
    }

    @Test
    void rejectsEncodingWhenDigestDoesNotMatchMachineContract() {
        ChangeSpecDocument decoded = codec.decode(validDocument());
        ChangeSpecDocument tampered = new ChangeSpecDocument(
                decoded.spec(),
                decoded.markdownBody(),
                "0".repeat(64));

        ChangeSpecValidationException error = assertThrows(
                ChangeSpecValidationException.class,
                () -> codec.encode(tampered));

        assertEquals("specDigest 与机器契约不一致", error.errors().get(0));
    }

    private static String validDocument() {
        return """
                ---
                schema: paicli/change-spec/v1
                id: CHANGE-001
                revision: 1
                title: 修复登录重试
                intent:
                  goal: 超时错误最多重试三次
                  non_goals:
                    - 不更换 HTTP Client
                scope:
                  mode: bounded
                  include:
                    - src/main/java/auth/**
                  exclude:
                    - pom.xml
                acceptance:
                  - id: AC-1
                    kind: behavior
                    statement: 超时错误最多重试三次
                    oracle:
                      type: deterministic
                      verifiers: [VT-TEST]
                  - id: AC-2
                    kind: scope
                    statement: 修改不得超出允许范围
                    oracle:
                      type: deterministic
                      verifiers: [VT-SCOPE]
                verifiers:
                  - id: VT-SCOPE
                    type: path_scope
                  - id: VT-TEST
                    type: command
                    command: mvn -q -DskipTests=false test
                    expect:
                      exit_code: 0
                      junit_report_glob: target/surefire-reports/TEST-*.xml
                      minimum_tests: 1
                ---

                # 背景

                登录请求可能无限重试。
                """;
    }
}
