# PaiCLI Change Delivery

This context describes the language used to govern one code-change request from intake through an executable and verifiable delivery contract.

## Language

**ChangeTask**:
The durable business record for one requested code change, including its current lifecycle state, approved contract, decisions, and delivery outcome.
_Avoid_: Conversation, Runtime Task, Worker Job

**Work Item**:
The external issue or ticket from which a ChangeTask originates.
_Avoid_: ChangeTask, Prompt

**Change Decision**:
An actor's version-bound decision that advances or rejects a ChangeTask at a review stage.
_Avoid_: Tool Approval, Chat Reply

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

**Spec Approval**:
A decision bound to one exact ChangeSpec Draft digest that permits the contract to be locked for execution.
_Avoid_: Tool Approval, Delivery Approval

**Delivery Approval**:
A decision bound to the verified ChangeSpec digest and final code identity that permits publishing the delivery result.
_Avoid_: Spec Approval, Tool Approval

**Change Event**:
An immutable fact recording a meaningful ChangeTask lifecycle transition or decision.
_Avoid_: Debug Log, Agent Transcript

**Worker Job**:
A technical execution request that references a ChangeTask by identity and does not own its business lifecycle.
_Avoid_: ChangeTask, Work Item

**Execution Route**:
The persisted provider/model, repair and approval choices derived from deterministic RiskEngine output. The tool policy profile currently records intent; it does not enforce an organization tool allowlist.
_Avoid_: LLM Risk Verdict

**Mock Delivery**:
A local SQLite pull-request and head-bound Check record. Only the ChangeWorkflow can decide its conclusion and advance a passed delivery to COMPLETED after publication persists.
_Avoid_: Real Pull Request, Branch Protection


**Change Artifacts**:
Read-only Draft revisions, locked contract, final code diff and persisted verification results resolved through a ChangeTask's saved associations. They are untrusted display content, not paths selected by an HTTP caller or a new source of lifecycle decisions.
_Avoid_: Arbitrary file browser, Agent completion claim

**Offline Demonstration**:
An explicitly enabled local composition with deterministic Draft/ReAct substitutes and a fixed refund fixture, reusing the actual ChangeWorkflow, Worker, Git worktree, SpecExecutionEngine, Verifier, controlled repair and SQLite. Mock SCM writes remain local. It demonstrates workflow mechanics, not real-model effectiveness.
_Avoid_: Production execution, Real SCM integration
