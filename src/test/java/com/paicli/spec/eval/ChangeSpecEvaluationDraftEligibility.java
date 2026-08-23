package com.paicli.spec.eval;

import com.paicli.spec.ChangeSpec;
import com.paicli.spec.ChangeSpecDocument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 评测专用的配对 Draft 资格检查，不改变生产 ChangeSpec 的任务命令白名单。 */
final class ChangeSpecEvaluationDraftEligibility {
    private static final String JUNIT_REPORT_GLOB = "target/surefire-reports/TEST-*.xml";

    private ChangeSpecEvaluationDraftEligibility() {
    }

    static List<String> validate(
            ChangeSpecEvaluationCase evaluationCase,
            ChangeSpecDocument document
    ) {
        List<String> errors = new ArrayList<>();
        Set<String> qualifiedCommandVerifierIds = new HashSet<>();
        for (ChangeSpec.VerifierDefinition verifier : document.spec().verifiers()) {
            if (verifier == null || verifier.type() != ChangeSpec.VerifierType.COMMAND) {
                continue;
            }
            if (evaluationCase.isAllowedVerifierCommand(verifier.command())) {
                validateEvidenceExpectation(evaluationCase, verifier, errors, qualifiedCommandVerifierIds);
            } else {
                errors.add("verifier[" + verifier.id() + "].command 不在评测任务允许列表: "
                        + verifier.command());
            }
        }
        if (qualifiedCommandVerifierIds.isEmpty()) {
            errors.add("评测 Draft 必须声明满足公开证据契约的 command Verifier: command="
                    + evaluationCase.publicVerifierCommand()
                    + ", junit_report_glob=" + JUNIT_REPORT_GLOB
                    + ", minimum_tests>=" + evaluationCase.minimumPublicTests());
        }

        for (ChangeSpec.AcceptanceCriterion criterion : document.spec().acceptance()) {
            if (criterion == null
                    || criterion.kind() == null
                    || criterion.kind() == ChangeSpec.CriterionKind.SCOPE
                    || criterion.oracle() == null
                    || criterion.oracle().type() != ChangeSpec.OracleType.DETERMINISTIC) {
                continue;
            }
            boolean referencesAllowedCommand = criterion.oracle().verifiers().stream()
                    .anyMatch(qualifiedCommandVerifierIds::contains);
            if (!referencesAllowedCommand) {
                errors.add("acceptance[" + criterion.id()
                        + "] 的非 scope deterministic Criterion 必须引用满足公开证据契约的 command Verifier");
            }
        }
        return List.copyOf(errors);
    }

    private static void validateEvidenceExpectation(
            ChangeSpecEvaluationCase evaluationCase,
            ChangeSpec.VerifierDefinition verifier,
            List<String> errors,
            Set<String> qualifiedCommandVerifierIds
    ) {
        ChangeSpec.CommandExpectation expect = verifier.expect();
        if (expect == null) {
            errors.add("verifier[" + verifier.id() + "].expect 缺失，无法证明公开证据覆盖");
            return;
        }
        boolean qualified = true;
        if (!Integer.valueOf(0).equals(expect.exitCode())) {
            errors.add("verifier[" + verifier.id() + "].expect.exit_code 必须为 0");
            qualified = false;
        }
        if (!JUNIT_REPORT_GLOB.equals(expect.junitReportGlob())) {
            errors.add("verifier[" + verifier.id() + "].expect.junit_report_glob 必须为 "
                    + JUNIT_REPORT_GLOB);
            qualified = false;
        }
        int minimum = expect.minimumTests() == null ? 0 : expect.minimumTests();
        if (minimum < evaluationCase.minimumPublicTests()) {
            errors.add("verifier[" + verifier.id() + "].expect.minimum_tests=" + minimum
                    + "，低于公开证据契约要求的 " + evaluationCase.minimumPublicTests());
            qualified = false;
        }
        if (qualified) qualifiedCommandVerifierIds.add(verifier.id());
    }
}
