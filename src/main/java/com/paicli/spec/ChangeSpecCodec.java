package com.paicli.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
        yamlFactory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        this.yamlMapper = new ObjectMapper(yamlFactory)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleModule wireEnums = new SimpleModule("change-spec-wire-enums");
        addLowerCaseEnumSerializer(wireEnums, ChangeSpec.ScopeMode.class);
        addLowerCaseEnumSerializer(wireEnums, ChangeSpec.CriterionKind.class);
        addLowerCaseEnumSerializer(wireEnums, ChangeSpec.OracleType.class);
        addLowerCaseEnumSerializer(wireEnums, ChangeSpec.VerifierType.class);
        this.yamlMapper.registerModule(wireEnums);
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
                    List.of(formatYamlError(e)),
                    e);
        }
    }

    private static String formatYamlError(JsonProcessingException error) {
        StringBuilder message = new StringBuilder("ChangeSpec YAML 无法解析");
        String path = mappingPath(error);
        if (!path.isBlank()) {
            message.append("，字段 ").append(path);
        }
        message.append(": ");
        String mismatch = mismatchSummary(error);
        if (!mismatch.isBlank()) {
            message.append(mismatch).append("；");
        }
        message.append(error.getOriginalMessage());
        return message.toString();
    }

    private static String mappingPath(JsonProcessingException error) {
        if (!(error instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String mismatchSummary(JsonProcessingException error) {
        if (!(error instanceof MismatchedInputException mismatch)) {
            return "";
        }
        String expected = mismatch.getTargetType() == null
                ? "目标类型"
                : mismatch.getTargetType().getSimpleName();
        JsonParser parser = error.getProcessor() instanceof JsonParser value ? value : null;
        String actual = parser == null || parser.currentToken() == null
                ? "不兼容值"
                : switch (parser.currentToken()) {
                    case START_OBJECT -> "Object";
                    case START_ARRAY -> "Array";
                    case VALUE_STRING -> "String";
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "Number";
                    case VALUE_TRUE, VALUE_FALSE -> "Boolean";
                    case VALUE_NULL -> "Null";
                    default -> parser.currentToken().name();
                };
        return "期望 " + expected + "，实际为 " + actual;
    }

    /**
     * 将已校验的机器契约和 Markdown 正文编码为可锁定文档。
     * 编码前会重新计算摘要，拒绝保存被调用方拼装或篡改的文档对象。
     */
    public String encode(ChangeSpecDocument document) throws JsonProcessingException {
        Objects.requireNonNull(document, "document");
        ChangeSpec spec = Objects.requireNonNull(document.spec(), "document.spec");
        validate(spec);
        String actualDigest = digest(spec);
        if (!actualDigest.equals(document.specDigest())) {
            throw new ChangeSpecValidationException(List.of("specDigest 与机器契约不一致"));
        }
        String machineContract = encodeMachineContract(spec);
        String markdown = normalizeNewlines(document.markdownBody() == null ? "" : document.markdownBody());
        if (markdown.isEmpty()) {
            return machineContract + "\n";
        }
        return machineContract + "\n\n" + markdown;
    }

    String encodeMachineContract(ChangeSpecDocument document) throws JsonProcessingException {
        Objects.requireNonNull(document, "document");
        ChangeSpec spec = Objects.requireNonNull(document.spec(), "document.spec");
        validate(spec);
        if (!digest(spec).equals(document.specDigest())) {
            throw new ChangeSpecValidationException(List.of("specDigest 与机器契约不一致"));
        }
        return encodeMachineContract(spec);
    }

    private String encodeMachineContract(ChangeSpec spec) throws JsonProcessingException {
        String yaml = normalizeNewlines(yamlMapper.writeValueAsString(spec)).stripTrailing();
        if (!yaml.startsWith("---\n")) {
            yaml = "---\n" + yaml;
        }
        return yaml + "\n---";
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
        validateScopeContract(spec, errors);
        validateAllVerifiersReferenced(spec, errors);
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

    private void validateScopeContract(ChangeSpec spec, List<String> errors) {
        List<ChangeSpec.VerifierDefinition> scopeVerifiers = spec.verifiers().stream()
                .filter(Objects::nonNull)
                .filter(verifier -> verifier.type() == ChangeSpec.VerifierType.PATH_SCOPE)
                .toList();
        if (scopeVerifiers.size() != 1) {
            errors.add("每份 ChangeSpec 必须定义且只能定义一个 path_scope Verifier");
            return;
        }
        String scopeVerifierId = scopeVerifiers.get(0).id();
        List<ChangeSpec.AcceptanceCriterion> scopeCriteria = spec.acceptance().stream()
                .filter(Objects::nonNull)
                .filter(criterion -> criterion.kind() == ChangeSpec.CriterionKind.SCOPE)
                .toList();
        if (scopeCriteria.size() != 1) {
            errors.add("每份 ChangeSpec 必须定义且只能定义一个 kind: scope Criterion");
            return;
        }
        ChangeSpec.AcceptanceCriterion criterion = scopeCriteria.get(0);
        if (criterion.oracle() == null
                || criterion.oracle().type() != ChangeSpec.OracleType.DETERMINISTIC
                || criterion.oracle().verifiers().size() != 1
                || !criterion.oracle().verifiers().contains(scopeVerifierId)) {
            errors.add("kind: scope Criterion 必须以 deterministic Oracle 仅引用 path_scope Verifier "
                    + scopeVerifierId);
        }
    }

    private void validateAllVerifiersReferenced(ChangeSpec spec, List<String> errors) {
        Set<String> referenced = new HashSet<>();
        for (ChangeSpec.AcceptanceCriterion criterion : spec.acceptance()) {
            if (criterion != null && criterion.oracle() != null) {
                referenced.addAll(criterion.oracle().verifiers());
            }
        }
        for (ChangeSpec.VerifierDefinition verifier : spec.verifiers()) {
            if (verifier != null && !isBlank(verifier.id()) && !referenced.contains(verifier.id())) {
                errors.add("Verifier 未被 deterministic Criterion 引用: " + verifier.id());
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
                if (!isBlank(verifier.command()) || verifier.expect() != null) {
                    errors.add("verifiers[" + label + "] 的 path_scope 不能配置 command 或 expect");
                }
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
            if (!isBlank(expect.junitReportGlob()) && !isProjectRelativeGlob(expect.junitReportGlob())) {
                errors.add("verifiers[" + label
                        + "].expect.junit_report_glob 必须是项目内使用 / 的相对路径");
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

    private static <E extends Enum<E>> void addLowerCaseEnumSerializer(
            SimpleModule module,
            Class<E> enumType
    ) {
        module.addSerializer(enumType, new JsonSerializer<>() {
            @Override
            public void serialize(E value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.name().toLowerCase(Locale.ROOT));
            }
        });
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
