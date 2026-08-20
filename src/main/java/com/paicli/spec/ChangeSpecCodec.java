package com.paicli.spec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 解析 ChangeSpec Markdown，完成结构映射并生成稳定的机器契约摘要。
 */
public final class ChangeSpecCodec {
    private final ObjectMapper yamlMapper;
    private final ObjectMapper canonicalMapper;

    public ChangeSpecCodec() {
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.yamlMapper = new ObjectMapper(yamlFactory)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.canonicalMapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    public ChangeSpecDocument decode(String document) {
        DocumentParts parts = splitDocument(document);
        try {
            ChangeSpec spec = yamlMapper.readValue(parts.yaml(), ChangeSpec.class);
            validate(spec);
            return new ChangeSpecDocument(spec, parts.markdown(), digest(spec));
        } catch (JsonProcessingException e) {
            throw new ChangeSpecValidationException(
                    List.of("ChangeSpec YAML 无法解析: " + e.getOriginalMessage()),
                    e);
        }
    }

    private void validate(ChangeSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            throw new ChangeSpecValidationException(List.of("YAML 必须定义 ChangeSpec 对象"));
        }
        if (!"paicli/change-spec/v1".equals(spec.schema())) {
            errors.add("schema 必须是 paicli/change-spec/v1");
        }
        validateIdentityAndIntent(spec, errors);
        validateScope(spec.scope(), errors);
        validateAcceptance(spec.acceptance(), errors);
        validateUniqueIds(spec, errors);
        validateVerifiers(spec.verifiers(), errors);
        validateVerifierReferences(spec, errors);
        if (!errors.isEmpty()) {
            throw new ChangeSpecValidationException(errors);
        }
    }

    private void validateIdentityAndIntent(ChangeSpec spec, List<String> errors) {
        if (isBlank(spec.id())) {
            errors.add("id 不能为空");
        }
        if (spec.revision() < 1) {
            errors.add("revision 必须大于等于 1");
        }
        if (isBlank(spec.title())) {
            errors.add("title 不能为空");
        }
        if (spec.intent() == null || isBlank(spec.intent().goal())) {
            errors.add("intent.goal 不能为空");
        }
    }

    private void validateScope(ChangeSpec.Scope scope, List<String> errors) {
        if (scope == null || scope.mode() == null) {
            errors.add("scope.mode 必须是 open 或 bounded");
            return;
        }
        if (scope.mode() == ChangeSpec.ScopeMode.BOUNDED && scope.include().isEmpty()) {
            errors.add("scope.mode 为 bounded 时 include 不能为空");
        }
        validatePaths("scope.include", scope.include(), errors);
        validatePaths("scope.exclude", scope.exclude(), errors);
    }

    private void validatePaths(String field, List<String> paths, List<String> errors) {
        for (int i = 0; i < paths.size(); i++) {
            if (!isProjectRelativeGlob(paths.get(i))) {
                errors.add(field + "[" + i + "] 必须是项目内使用 / 的相对路径");
            }
        }
    }

    private boolean isProjectRelativeGlob(String path) {
        if (isBlank(path) || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) {
            return false;
        }
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private void validateVerifierReferences(ChangeSpec spec, List<String> errors) {
        Set<String> verifierIds = new HashSet<>();
        for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
            if (verifier != null && !isBlank(verifier.id())) {
                verifierIds.add(verifier.id());
            }
        }
        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            if (criterion == null || criterion.oracle() == null) {
                continue;
            }
            for (String verifierId : criterion.oracle().verifiers()) {
                if (!verifierIds.contains(verifierId)) {
                    errors.add("acceptance[" + criterion.id()
                            + "] 引用了不存在的 Verifier: " + verifierId);
                }
            }
        }
    }

    private void validateUniqueIds(ChangeSpec spec, List<String> errors) {
        Set<String> acceptanceIds = new HashSet<>();
        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            if (criterion != null && !isBlank(criterion.id()) && !acceptanceIds.add(criterion.id())) {
                errors.add("acceptance id 重复: " + criterion.id());
            }
        }

        Set<String> verifierIds = new HashSet<>();
        for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
            if (verifier != null && !isBlank(verifier.id()) && !verifierIds.add(verifier.id())) {
                errors.add("verifier id 重复: " + verifier.id());
            }
        }
    }

    private void validateAcceptance(
            List<ChangeSpec.AcceptanceCriterion> acceptance,
            List<String> errors
    ) {
        if (acceptance.isEmpty()) {
            errors.add("acceptance 至少需要一条 Criterion");
            return;
        }
        for (int i = 0; i < acceptance.size(); i++) {
            ChangeSpec.AcceptanceCriterion criterion = acceptance.get(i);
            if (criterion == null) {
                errors.add("acceptance[" + i + "] 不能为空");
                continue;
            }
            String label = isBlank(criterion.id()) ? String.valueOf(i) : criterion.id();
            if (isBlank(criterion.id())) {
                errors.add("acceptance[" + label + "].id 不能为空");
            }
            if (criterion.kind() == null) {
                errors.add("acceptance[" + label + "].kind 不能为空");
            }
            if (isBlank(criterion.statement())) {
                errors.add("acceptance[" + label + "].statement 不能为空");
            }
            validateOracle(label, criterion.oracle(), errors);
        }
    }

    private void validateOracle(String label, ChangeSpec.Oracle oracle, List<String> errors) {
        if (oracle == null || oracle.type() == null) {
            errors.add("acceptance[" + label + "].oracle.type 不能为空");
            return;
        }
        if (oracle.type() == ChangeSpec.OracleType.DETERMINISTIC && oracle.verifiers().isEmpty()) {
            errors.add("acceptance[" + label
                    + "] 的 deterministic oracle 必须引用至少一个 Verifier");
        }
        if (oracle.type() == ChangeSpec.OracleType.HUMAN && !oracle.verifiers().isEmpty()) {
            errors.add("acceptance[" + label + "] 的 human oracle 不能引用 Verifier");
        }
    }

    private void validateVerifiers(
            List<ChangeSpec.VerifierDefinition> verifiers,
            List<String> errors
    ) {
        for (int i = 0; i < verifiers.size(); i++) {
            ChangeSpec.VerifierDefinition verifier = verifiers.get(i);
            if (verifier == null) {
                errors.add("verifiers[" + i + "] 不能为空");
                continue;
            }
            String label = isBlank(verifier.id()) ? String.valueOf(i) : verifier.id();
            if (isBlank(verifier.id())) {
                errors.add("verifiers[" + label + "].id 不能为空");
            }
            if (verifier.type() == null) {
                errors.add("verifiers[" + label + "].type 不能为空");
                continue;
            }
            if (verifier.type() != ChangeSpec.VerifierType.COMMAND) {
                continue;
            }
            if (isBlank(verifier.command())) {
                errors.add("verifiers[" + label + "].command 不能为空");
            }
            ChangeSpec.CommandExpectation expect = verifier.expect();
            if (expect == null) {
                errors.add("verifiers[" + label + "].expect 不能为空");
                continue;
            }
            if (expect.exitCode() == null) {
                errors.add("verifiers[" + label + "].expect.exit_code 不能为空");
            }
            if (expect.minimumTests() != null && expect.minimumTests() <= 0) {
                errors.add("verifiers[" + label + "].expect.minimum_tests 必须大于 0");
            }
            if (expect.minimumTests() != null && isBlank(expect.junitReportGlob())) {
                errors.add("verifiers[" + label
                        + "].expect.junit_report_glob 在 minimum_tests 存在时不能为空");
            }
        }
    }

    private DocumentParts splitDocument(String document) {
        if (document == null) {
            throw invalidDocument("ChangeSpec 文档不能为空");
        }
        String normalized = normalizeNewlines(document);
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("---\n")) {
            throw invalidDocument("ChangeSpec 缺少 YAML front matter 起始标记");
        }
        int closingMarker = normalized.indexOf("\n---\n", 4);
        if (closingMarker < 0 && normalized.endsWith("\n---")) {
            closingMarker = normalized.length() - 4;
            return new DocumentParts(normalized.substring(4, closingMarker), "");
        }
        if (closingMarker < 0) {
            throw invalidDocument("ChangeSpec 缺少 YAML front matter 结束标记");
        }
        String yaml = normalized.substring(4, closingMarker);
        String markdown = normalized.substring(closingMarker + 5);
        if (markdown.startsWith("\n")) {
            markdown = markdown.substring(1);
        }
        return new DocumentParts(yaml, markdown);
    }

    private String digest(ChangeSpec spec) throws JsonProcessingException {
        byte[] canonical = canonicalMapper.writeValueAsBytes(spec);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ChangeSpecValidationException invalidDocument(String message) {
        return new ChangeSpecValidationException(List.of(message));
    }

    private record DocumentParts(String yaml, String markdown) {
    }
}
