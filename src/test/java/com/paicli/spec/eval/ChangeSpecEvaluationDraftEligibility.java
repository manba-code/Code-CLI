package com.paicli.spec.eval;

import com.paicli.spec.ChangeSpec;
import com.paicli.spec.ChangeSpecDocument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 评测专用的配对 Draft 资格检查，不改变生产 ChangeSpec 的任务命令白名单。 */
final class ChangeSpecEvaluationDraftEligibility {
    private ChangeSpecEvaluationDraftEligibility() {
    }

    static List<String> validate(
            ChangeSpecEvaluationCase evaluationCase,
            ChangeSpecDocument document
    ) {
        List<String> errors = new ArrayList<>();
        Set<String> allowedCommandVerifierIds = new HashSet<>();
        for (ChangeSpec.VerifierDefinition verifier : document.spec().verifiers()) {
            if (verifier == null || verifier.type() != ChangeSpec.VerifierType.COMMAND) {
                continue;
            }
            if (evaluationCase.isAllowedVerifierCommand(verifier.command())) {
                allowedCommandVerifierIds.add(verifier.id());
            } else {
                errors.add("verifier[" + verifier.id() + "].command 不在评测任务允许列表: "
                        + verifier.command());
            }
        }
        if (allowedCommandVerifierIds.isEmpty()) {
            errors.add("评测 Draft 必须声明任务允许的 command Verifier: "
                    + evaluationCase.publicVerifierCommand());
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
                    .anyMatch(allowedCommandVerifierIds::contains);
            if (!referencesAllowedCommand) {
                errors.add("acceptance[" + criterion.id()
                        + "] 的非 scope deterministic Criterion 必须引用任务允许的 command Verifier");
            }
        }
        return List.copyOf(errors);
    }
}
