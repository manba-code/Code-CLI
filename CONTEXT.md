# PaiCLI Change Delivery

This context describes the language used to turn one code-change request into an executable and verifiable delivery contract.

## Language

**ChangeSpec**:
The immutable contract for one confirmed code change, containing its intent, scope, acceptance criteria, and deterministic verification instructions.
_Avoid_: Requirement document, Plan, Task list

**Spec Run**:
One execution attempt bound to an exact ChangeSpec digest, including implementation, verification, and at most one evidence-driven repair.
_Avoid_: Spec, Session

**Acceptance Criterion**:
One atomic condition that must pass before the code change can be accepted.
_Avoid_: Preference, Suggestion, Checklist item

**Evidence**:
An observable fact produced from the workspace or a verification action and used to judge an Acceptance Criterion.
_Avoid_: Agent claim, Reasoning, Full execution log

**Verifier**:
A deterministic check that turns Evidence into PASS, FAIL, or ERROR.
_Avoid_: Reviewer, Judge

**Criterion Result**:
The evidence-backed result for one Acceptance Criterion: PASS, FAIL, INCONCLUSIVE, or NOT_RUN.
_Avoid_: Verdict

**Verdict**:
The final result reduced from every Criterion Result: SPEC_INVALID, FAILED, INCOMPLETE, NEEDS_HUMAN, or PASSED.
_Avoid_: Agent response, Reviewer opinion
