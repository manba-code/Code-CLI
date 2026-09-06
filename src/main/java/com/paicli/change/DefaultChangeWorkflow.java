package com.paicli.change;

import com.paicli.spec.ChangeSpecModule;
import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.ChangeSpecDocument;
import com.paicli.config.PaiCliConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ChangeTask 生命周期的唯一写入口。状态变化与业务事件由 ChangeStore 原子提交。
 */
public final class DefaultChangeWorkflow implements ChangeWorkflow, ChangeExecutionControl {
    private final ChangeStore store;
    private final ChangeEventStore eventStore;
    private final ChangeSpecModule specModule;
    private final RiskEngine riskEngine;
    private final ExecutionRouter executionRouter;
    private final ChangeApprovalPolicy approvalPolicy;
    private final ChangeSpecCodec specCodec;
    private final Clock clock;
    private java.util.function.Consumer<ChangeTaskId> dispatch;
    private MockScmAdapter scm;
    private DeliveryHeadReader heads;
    private ChangeArtifactReader artifacts;

    public synchronized void connectArtifacts(ChangeArtifactReader artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts);
    }

    /** 单进程本地平台在启动 Worker 前装配；领域测试可保持显式逐阶段驱动。 */
    public synchronized void connect(java.util.function.Consumer<ChangeTaskId> dispatch,
                                     MockScmAdapter scm, DeliveryHeadReader heads) {
        if (this.dispatch != null) throw new IllegalStateException("ChangeWorkflow 已装配");
        this.dispatch = Objects.requireNonNull(dispatch);
        this.scm = Objects.requireNonNull(scm);
        this.heads = Objects.requireNonNull(heads);
    }

    /** 持久化状态是调度事实源；重启或投递/发布失败后可重复调用。 */
    public synchronized void advance(ChangeTaskId id) {
        if (dispatch == null) return;
        ChangeTask task = load(id);
        if (task.state() == ChangeState.PUBLISHING && task.deliveryApproval() != null
                && task.deliveryApproval().runId().isBlank()) {
            ApprovalRecord legacy = task.deliveryApproval();
            ChangeTask review = task.withDeliveryDecision(ChangeState.DELIVERY_REVIEW, null, clock.instant());
            var payload = ChangeJson.MAPPER.createObjectNode();
            payload.put("invalidatedApprovalId", legacy.id()).put("reason", "旧审批未绑定 run 与判断版本，升级后需重新确认");
            task = store.update(task.version(), review, event(review, "delivery.approval_invalidated", "SYSTEM",
                    "change-workflow", task.state(), review.state(), payload.toString()));
        }
        if (task.state() == ChangeState.READY) task = queueForExecution(id, task.version());
        if (task.state() == ChangeState.QUEUED) {
            dispatch.accept(id);
        } else if (task.run() != null && (task.state() == ChangeState.PUBLISHING
                || task.state() == ChangeState.FAILED || task.state() == ChangeState.DELIVERY_REVIEW)) {
            publish(task);
        }
    }

    synchronized void recordDispatchFailure(ChangeTaskId id, RuntimeException error) {
        ChangeTask task = load(id);
        String payload = "{\"error\":\"" + json(messageOf(error)) + "\"}";
        var events = eventStore.events(id);
        if (!events.isEmpty()) {
            ChangeEvent last = events.get(events.size() - 1);
            if (last.type().equals("dispatch.failed") && last.payloadJson().equals(payload)) return;
        }
        ChangeTask unchanged = task.transition(task.state(), clock.instant());
        store.update(task.version(), unchanged, event(unchanged, "dispatch.failed", "SYSTEM",
                "change-workflow", task.state(), task.state(), payload));
    }

    private void publish(ChangeTask task) {
        RunRef run = task.run();
        String conclusion = switch (task.deliveryVerdict()) {
            case PASSED -> "success";
            case NEEDS_HUMAN -> "pending";
            case FAILED, INCOMPLETE, SPEC_INVALID -> "failure";
        };
        // A verified result awaiting approval may not publish a success Check.
        if (conclusion.equals("success") && task.state() != ChangeState.PUBLISHING) {
            if (task.humanReview() == null) return; // Preserve legacy no-Check-before-approval behavior.
            conclusion = "pending";
        }
        requirePublishable(task, conclusion);
        DeliveryRef previous = scm.find(task).orElse(null);
        DeliveryRef delivery = scm.publish(task, conclusion);
        // SCM may have committed before a crash. Record the missing event exactly once on retry.
        String publicationKey = scm.publicationKey(task);
        boolean recorded = eventStore.events(task.id()).stream().anyMatch(e -> e.type().equals("pr.check_published")
                && e.payloadJson().contains("\"publicationKey\":\"" + publicationKey + "\""));
        if (previous == null || !recorded) {
            ChangeTask checked = task.transition(task.state(), clock.instant());
            task = store.update(task.version(), checked, event(checked, "pr.check_published", "SYSTEM",
                    "mock-scm", task.state(), checked.state(),
                    "{\"publicationKey\":\"" + publicationKey + "\",\"judgmentRevision\":" + task.judgmentRevision()
                            + ",\"runId\":\"" + json(run.runId()) + "\",\"pullRequestId\":\"" + json(delivery.pullRequestId())
                            + "\",\"headSha\":\"" + json(delivery.headSha())
                            + "\",\"conclusion\":\"" + conclusion + "\"}"));
        }
        if (conclusion.equals("success")) {
            ChangeTask completed = task.transition(ChangeState.COMPLETED, clock.instant());
            store.update(task.version(), completed, event(completed, "change.completed", "SYSTEM",
                    "change-workflow", task.state(), completed.state(), "{}"));
        }
    }

    private void requirePublishable(ChangeTask task, String conclusion) {
        SpecRef spec = task.spec();
        RunRef run = task.run();
        requireCurrentDeliveryIdentity(task, spec.digest(), run.headSha());
        ApprovalRecord approvedSpec = task.specApproval();
        if (!spec.locked() || approvedSpec == null
                || approvedSpec.decision() != ApprovalRecord.Decision.APPROVED
                || !approvedSpec.specDigest().equals(spec.digest())) {
            throw new ChangeValidationException("发布需要当前锁定 Spec 的有效审批");
        }
        requirePersistedDeliveryIdentity(task);
        if (conclusion.equals("success")) {
            if (run.status() != com.paicli.spec.SpecRunResult.Status.FINISHED
                    || task.deliveryVerdict() != com.paicli.spec.SpecRunResult.Verdict.PASSED) {
                throw new ChangeValidationException("只有 FINISHED/PASSED 可以发布 success");
            }
            if (task.risk() == null || task.route() == null) {
                throw new ChangeValidationException("发布缺少确定性风险路由");
            }
            if ((task.risk().level() == RiskLevel.HIGH || task.route().deliveryApprovalRequired())
                    && !task.deliveryApprovedFor(spec.digest(), run.headSha())) {
                throw new ChangeValidationException("发布 success 需要有效 Delivery Approval");
            }
            if (task.deliveryApproval() != null) {
                approvalPolicy.requireAllowed(task, task.risk().level(), task.deliveryApproval().approverId(),
                        ApprovalRecord.Stage.DELIVERY);
            }
        }
        requireVersion(load(task.id()), task.version());
    }

    private void requirePersistedDeliveryIdentity(ChangeTask task) {
        SpecRef spec = task.spec();
        RunRef run = task.run();
        try {
            if (artifacts == null) throw new ChangeValidationException("发布前可信 Artifact 校验未装配");
            var content = artifacts.read(new ChangeTaskView(task, eventStore.events(task.id())), null, null);
            ChangeSpecDocument locked = specCodec.decode(Files.readString(spec.lockedPath()));
            if (!locked.specDigest().equals(spec.digest()) || !locked.spec().id().equals(spec.specId())
                    || locked.spec().revision() != spec.revision()) {
                throw new ChangeConflictException("发布前锁定 Spec identity 已变化");
            }
            if (task.humanReview() != null && task.humanReview().latest() != null) {
                var judgment = task.humanReview().latest();
                if (!judgment.matches(spec, run)) throw new ChangeConflictException("交付判断身份已失效");
                var recomputed = DeliveryJudgmentReducer.reduce(task, locked.spec(), content.path("result"),
                        task.humanReview().entries(), judgment.revision(), judgment.createdAt());
                if (!judgment.equals(recomputed)) throw new ChangeConflictException("交付判断依据已变化，不能发布或审批");
            }
            if (!run.headSha().equals(heads.currentHead(task))) {
                throw new ChangeConflictException("发布前分支 headSha 已变化，需重新验证和审批");
            }
            var persisted = content.path("result");
            if (persisted == null || !persisted.isObject() || !persisted.path("runId").asText().equals(run.runId())
                    || !persisted.path("spec").path("digest").asText().equals(spec.digest())
                    || !persisted.path("status").asText().equals(run.status().name())
                    || !persisted.path("verdict").asText().equals(run.verdict().name())
                    || !persisted.path("verificationAttempts").isArray()
                    || !persisted.path("criterionResults").isArray()
                    || !content.path("codeDiff").isTextual()) {
                throw new ChangeValidationException("发布前 Verdict/Evidence 未持久化或与当前执行不匹配");
            }
        } catch (IOException e) {
            throw new ChangeValidationException("发布前无法读取锁定 Spec 或 Verdict/Evidence");
        }
    }

    public DefaultChangeWorkflow(
            ChangeStore store,
            ChangeEventStore eventStore,
            ChangeSpecModule specModule
    ) {
        this(store, eventStore, specModule, defaultConfig(), Clock.systemUTC());
    }

    public DefaultChangeWorkflow(
            ChangeStore store,
            ChangeEventStore eventStore,
            ChangeSpecModule specModule,
            Clock clock
    ) {
        this(store, eventStore, specModule, defaultConfig(), clock);
    }

    public DefaultChangeWorkflow(
            ChangeStore store,
            ChangeEventStore eventStore,
            ChangeSpecModule specModule,
            PaiCliConfig config,
            Clock clock
    ) {
        this(store, eventStore, specModule, new RiskEngine(), ExecutionRouter.fromConfig(config),
                ChangeApprovalPolicy.fromConfig(config), new ChangeSpecCodec(), clock);
    }

    DefaultChangeWorkflow(
            ChangeStore store,
            ChangeEventStore eventStore,
            ChangeSpecModule specModule,
            RiskEngine riskEngine,
            ExecutionRouter executionRouter,
            ChangeApprovalPolicy approvalPolicy,
            ChangeSpecCodec specCodec,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.specModule = Objects.requireNonNull(specModule, "specModule");
        this.riskEngine = Objects.requireNonNull(riskEngine, "riskEngine");
        this.executionRouter = Objects.requireNonNull(executionRouter, "executionRouter");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.specCodec = Objects.requireNonNull(specCodec, "specCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ChangeTaskId submit(ChangeRequest request) {
        Objects.requireNonNull(request, "request");
        var existing = store.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get().id();
        }

        Instant now = clock.instant();
        ChangeTaskId id = ChangeTaskId.create();
        DraftJob job = DraftJob.pending(new ChangeSpecModule.ChangeContext(id.value(),
                "CHANGE-" + id.value().substring("change_".length()).toUpperCase(java.util.Locale.ROOT),
                1, request.requirement(), request.projectContext(), request.referencedContext()), clock.millis());
        ChangeTask created = new ChangeTask(id, request.idempotencyKey(), 0L, ChangeState.DRAFTING_SPEC,
                request.source(), request.repository(), request.title(), request.requirement(), request.actorId(),
                request.projectContext(), request.referencedContext(), null, null, null, null, null, null, null,
                job, now, now);
        try {
            store.create(created, event(
                    created,
                    "change.created",
                    request.actorType(),
                    request.actorId(),
                    null,
                    ChangeState.DRAFTING_SPEC,
                    draftPayload(job)));
        } catch (ChangeConflictException conflict) {
            return store.findByIdempotencyKey(request.idempotencyKey())
                    .map(ChangeTask::id)
                    .orElseThrow(() -> conflict);
        }

        return id;
    }

    @Override
    public synchronized ChangeTaskView decide(ChangeTaskId id, ChangeDecision decision) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(decision, "decision");
        ChangeTask current = load(id);
        requireVersion(current, decision.expectedVersion());

        if (decision instanceof ChangeDecision.ApproveSpec approve) {
            requireState(current, ChangeState.SPEC_REVIEW);
            requireCurrentDigest(current, approve.expectedDigest());
            return approve(current, approve);
        }
        if (decision instanceof ChangeDecision.SupplementSpec supplement) {
            requireState(current, ChangeState.SPEC_REVIEW);
            requireCurrentDigest(current, supplement.expectedDigest());
            return supplement(current, supplement);
        }
        if (decision instanceof ChangeDecision.RejectSpec reject) {
            requireState(current, ChangeState.SPEC_REVIEW);
            requireCurrentDigest(current, reject.expectedDigest());
            return reject(current, reject);
        }
        if (decision instanceof ChangeDecision.ApproveDelivery approve) {
            requireState(current, ChangeState.DELIVERY_REVIEW);
            requireCurrentDeliveryIdentity(current, approve.expectedSpecDigest(), approve.expectedHeadSha());
            requireJudgmentIdentity(current, approve.expectedRunId(), approve.expectedJudgmentRevision());
            return approveDelivery(current, approve);
        }
        if (decision instanceof ChangeDecision.RejectDelivery reject) {
            requireState(current, ChangeState.DELIVERY_REVIEW);
            requireCurrentDeliveryIdentity(current, reject.expectedSpecDigest(), reject.expectedHeadSha());
            requireJudgmentIdentity(current, reject.expectedRunId(), reject.expectedJudgmentRevision());
            if (heads != null) requirePersistedDeliveryIdentity(current);
            return rejectDelivery(current, reject);
        }
        throw new IllegalArgumentException("不支持的 ChangeDecision: " + decision.getClass().getName());
    }

    @Override
    public synchronized ChangeTaskView get(ChangeTaskId id) {
        ChangeTask task = load(id);
        return new ChangeTaskView(task, eventStore.events(id), scm == null ? null : scm.find(task).orElse(null),
                scm == null ? java.util.List.of() : scm.history(id));
    }

    @Override
    public ChangeTask queueForExecution(ChangeTaskId id, long expectedVersion) {
        ChangeTask current = load(Objects.requireNonNull(id, "id"));
        requireVersion(current, expectedVersion);
        requireState(current, ChangeState.READY);
        if (current.spec() == null || !current.spec().locked()) {
            throw new ChangeConflictException("ChangeSpec 未锁定，不能进入执行队列");
        }
        if (current.risk() == null || current.route() == null) {
            throw new ChangeConflictException("ChangeTask 缺少确定性风险与 ExecutionRoute");
        }
        ChangeTask queued = current.withExecution(ChangeState.QUEUED, null, null, clock.instant());
        return store.update(current.version(), queued, event(
                queued,
                "execution.queued",
                "SYSTEM",
                "change-workflow",
                current.state(),
                queued.state(),
                "{}"));
    }

    @Override
    public ExecutionLease claimExecution(ChangeTaskId id) {
        ChangeTask current = load(Objects.requireNonNull(id, "id"));
        requireState(current, ChangeState.QUEUED);
        WorkerClaimRef claim = new WorkerClaimRef(claimId(), clock.instant());
        ChangeTask running = current.withExecution(ChangeState.RUNNING, claim, current.run(), clock.instant());
        ChangeTask saved = store.update(current.version(), running, event(
                running,
                "execution.claimed",
                "WORKER",
                claim.claimId(),
                current.state(),
                running.state(),
                "{\"claimId\":\"" + json(claim.claimId()) + "\"}"));
        return new StoreBackedExecutionLease(saved, claim.claimId());
    }

    @Override
    public void recoverExecution(ChangeTaskId id, String reason) {
        ChangeTask current = load(Objects.requireNonNull(id, "id"));
        if (current.state() == ChangeState.QUEUED || current.state().terminal()
                || current.state() == ChangeState.DELIVERY_REVIEW || current.state() == ChangeState.PUBLISHING) {
            return;
        }
        if (current.state() != ChangeState.RUNNING && current.state() != ChangeState.VERIFYING) {
            throw new ChangeConflictException("当前状态不能恢复 Worker 执行: " + current.state());
        }
        ChangeTask queued = current.withExecution(ChangeState.QUEUED, null, current.run(), clock.instant());
        store.update(current.version(), queued, event(
                queued,
                "execution.recovered",
                "SYSTEM",
                "worker-queue",
                current.state(),
                queued.state(),
                "{\"reason\":\"" + json(reason) + "\"}"));
    }

    @Override
    public void cancelExecution(ChangeTaskId id, String reason) {
        ChangeTask current = load(Objects.requireNonNull(id, "id"));
        if (current.state() == ChangeState.CANCELED) {
            return;
        }
        if (current.state() != ChangeState.QUEUED
                && current.state() != ChangeState.RUNNING
                && current.state() != ChangeState.VERIFYING) {
            throw new ChangeConflictException("当前状态不能取消 Worker 执行: " + current.state());
        }
        ChangeTask canceled = current.withExecution(ChangeState.CANCELED, null, current.run(), clock.instant());
        store.update(current.version(), canceled, event(
                canceled,
                "change.canceled",
                "SYSTEM",
                "worker-queue",
                current.state(),
                canceled.state(),
                "{\"reason\":\"" + json(reason) + "\"}"));
    }

    private ChangeTaskView approve(ChangeTask current, ChangeDecision.ApproveSpec decision) {
        SpecRef spec = Objects.requireNonNull(current.spec(), "current.spec");
        ChangeSpecDocument draftDocument = readDraft(spec);
        RiskAssessment assessment = riskEngine.assess(current, draftDocument.spec());
        approvalPolicy.requireAllowed(current, assessment.level(), decision.actorId());
        ExecutionRoute route = executionRouter.route(assessment);
        ChangeSpecModule.LockedSpec locked;
        try {
            locked = specModule.lockConfirmed(toDraft(spec), decision.expectedDigest());
        } catch (IOException e) {
            throw new IllegalStateException("锁定 ChangeSpec 失败: " + e.getMessage(), e);
        }
        assertLockedIdentity(spec, locked);
        Instant now = clock.instant();
        ApprovalRecord approval = new ApprovalRecord(
                approvalId(),
                ApprovalRecord.Stage.SPEC,
                ApprovalRecord.Decision.APPROVED,
                decision.actorId(),
                decision.reason(),
                spec.digest(),
                "",
                now);
        ChangeTask ready = current.approve(spec.lockAt(locked.path()), assessment, route, approval, now);
        store.update(current.version(), ready, event(
                ready,
                "spec.approved",
                decision.actorType(),
                decision.actorId(),
                current.state(),
                ready.state(),
                "{\"specDigest\":\"" + json(spec.digest())
                        + "\",\"riskLevel\":\"" + assessment.level().name()
                        + "\",\"riskScore\":" + assessment.score()
                        + ",\"provider\":\"" + json(route.provider())
                        + "\",\"model\":\"" + json(route.model()) + "\"}"));
        advance(current.id());
        return get(current.id());
    }

    private ChangeTaskView supplement(ChangeTask current, ChangeDecision.SupplementSpec decision) {
        String nextRequirement = current.requirement()
                + "\n\n用户补充要求：\n"
                + decision.supplement();
        SpecRef previous = Objects.requireNonNull(current.spec(), "current.spec");
        DraftJob job = DraftJob.pending(new ChangeSpecModule.ChangeContext(current.id().value(),
                previous.specId(), previous.revision() + 1, nextRequirement,
                current.projectContext(), current.referencedContext()), clock.millis());
        ChangeTask drafting = current.withDraftJob(job, ChangeState.DRAFTING_SPEC, clock.instant());
        store.update(current.version(), drafting, event(drafting, "spec.supplemented", decision.actorType(),
                decision.actorId(), current.state(), drafting.state(), draftPayload(job)));
        return get(current.id());
    }

    private ChangeTaskView reject(ChangeTask current, ChangeDecision.RejectSpec decision) {
        Instant now = clock.instant();
        ApprovalRecord approval = new ApprovalRecord(
                approvalId(),
                ApprovalRecord.Stage.SPEC,
                ApprovalRecord.Decision.REJECTED,
                decision.actorId(),
                decision.reason(),
                decision.expectedDigest(),
                "",
                now);
        ChangeTask rejected = current.rejectSpec(approval, now);
        store.update(current.version(), rejected, event(
                rejected,
                "spec.rejected",
                decision.actorType(),
                decision.actorId(),
                current.state(),
                rejected.state(),
                "{\"reason\":\"" + json(decision.reason()) + "\"}"));
        return get(current.id());
    }

    private ChangeTaskView approveDelivery(
            ChangeTask current,
            ChangeDecision.ApproveDelivery decision
    ) {
        RiskAssessment risk = Objects.requireNonNull(current.risk(), "current.risk");
        ExecutionRoute route = Objects.requireNonNull(current.route(), "current.route");
        if (current.deliveryVerdict() != com.paicli.spec.SpecRunResult.Verdict.PASSED
                || current.run().status() != com.paicli.spec.SpecRunResult.Status.FINISHED) {
            throw new ChangeValidationException("Delivery Approval 不能把 NEEDS_HUMAN 或失败 Verdict 改为 PASSED");
        }
        if (!route.deliveryApprovalRequired()) {
            throw new ChangeValidationException("当前 ExecutionRoute 不要求 Delivery Approval");
        }
        approvalPolicy.requireAllowed(current, risk.level(), decision.actorId(), ApprovalRecord.Stage.DELIVERY);
        if (heads != null) requirePersistedDeliveryIdentity(current);
        Instant now = clock.instant();
        ApprovalRecord approval = new ApprovalRecord(
                approvalId(),
                ApprovalRecord.Stage.DELIVERY,
                ApprovalRecord.Decision.APPROVED,
                decision.actorId(),
                decision.reason(),
                decision.expectedSpecDigest(),
                decision.expectedHeadSha(),
                now, current.run().runId(), current.judgmentRevision());
        ChangeTask publishing = current.withDeliveryDecision(ChangeState.PUBLISHING, approval, now);
        store.update(current.version(), publishing, event(
                publishing,
                "delivery.approved",
                decision.actorType(),
                decision.actorId(),
                current.state(),
                publishing.state(),
                ChangeJson.MAPPER.valueToTree(approval).toString()));
        advance(current.id());
        return get(current.id());
    }

    private ChangeTaskView rejectDelivery(
            ChangeTask current,
            ChangeDecision.RejectDelivery decision
    ) {
        Instant now = clock.instant();
        ApprovalRecord approval = new ApprovalRecord(
                approvalId(),
                ApprovalRecord.Stage.DELIVERY,
                ApprovalRecord.Decision.REJECTED,
                decision.actorId(),
                decision.reason(),
                decision.expectedSpecDigest(),
                decision.expectedHeadSha(),
                now, current.run().runId(), current.judgmentRevision());
        ChangeTask rejected = current.withDeliveryDecision(ChangeState.REJECTED, approval, now);
        store.update(current.version(), rejected, event(
                rejected,
                "delivery.rejected",
                decision.actorType(),
                decision.actorId(),
                current.state(),
                rejected.state(),
                ChangeJson.MAPPER.valueToTree(approval).toString()));
        return get(current.id());
    }

    private static void requireJudgmentIdentity(ChangeTask task, String runId, long revision) {
        if (task.run() == null || !task.run().runId().equals(runId) || task.judgmentRevision() != revision) {
            throw new ChangeConflictException("Run 或交付判断版本已过期，请刷新");
        }
    }

    @Override
    public synchronized ChangeTaskView recordHumanEvidence(ChangeTaskId id, HumanEvidenceSubmission input) {
        ChangeTask current = load(id);
        requireVersion(current, input.expectedVersion());
        requireCurrentDeliveryIdentity(current, input.expectedSpecDigest(), input.expectedHeadSha());
        requireJudgmentIdentity(current, input.expectedRunId(), input.expectedJudgmentRevision());
        if (!java.util.Set.of(ChangeState.DELIVERY_REVIEW, ChangeState.FAILED, ChangeState.PUBLISHING,
                ChangeState.COMPLETED).contains(current.state())) {
            throw new ChangeConflictException("当前状态不接受人工验收");
        }
        if (artifacts == null || heads == null) throw new ChangeValidationException("人工验收产物与 head 校验未装配");
        requirePersistedDeliveryIdentity(current);
        try {
            var content = artifacts.read(get(id), null, null);
            var spec = specCodec.decode(content.path("lockedSpec").asText()).spec();
            boolean human = spec.acceptance().stream().anyMatch(c -> c.id().equals(input.criterionId())
                    && c.oracle().type() == com.paicli.spec.ChangeSpec.OracleType.HUMAN);
            if (!human) throw new ChangeValidationException("只能补录锁定 Spec 中的 Human Criterion");
            java.util.Set<String> allowed = new java.util.HashSet<>();
            content.path("artifactRefs").forEach(ref -> allowed.add(ref.path("id").asText()));
            if (!allowed.containsAll(input.artifactRefs())) throw new ChangeValidationException("Artifact 引用必须来自当前任务已有产物");
            Instant now = clock.instant();
            var entries = new java.util.ArrayList<HumanReview.Entry>();
            var judgments = new java.util.ArrayList<HumanReview.Judgment>();
            if (current.humanReview() != null) {
                entries.addAll(current.humanReview().entries());
                judgments.addAll(current.humanReview().judgments());
            }
            if (judgments.isEmpty()) {
                judgments.add(new HumanReview.Judgment(0, current.spec().digest(), current.run().runId(),
                        current.run().headSha(), current.run().verdict(),
                        DeliveryJudgmentReducer.reduce(current, spec, content.path("result"), java.util.List.of(), 0, now).criterionResults(),
                        current.run().completedAt()));
            }
            long revision = current.judgmentRevision() + 1;
            var entry = new HumanReview.Entry("human_" + UUID.randomUUID(), id.value(), current.spec().digest(),
                    current.run().runId(), current.run().headSha(), revision, input.criterionId(), input.decision(),
                    input.reason(), input.artifactRefs(), input.actorId(), input.actorType(), now);
            entries.add(entry);
            var judgment = DeliveryJudgmentReducer.reduce(current, spec, content.path("result"), entries, revision, now);
            judgments.add(judgment);
            ChangeState next = switch (judgment.verdict()) {
                case PASSED -> current.route().deliveryApprovalRequired() || current.risk().level() == RiskLevel.HIGH
                        ? ChangeState.DELIVERY_REVIEW : ChangeState.PUBLISHING;
                case NEEDS_HUMAN -> ChangeState.DELIVERY_REVIEW;
                default -> ChangeState.FAILED;
            };
            ChangeTask updated = current.withHumanReview(new HumanReview(entries, judgments), next, now);
            var payload = ChangeJson.MAPPER.createObjectNode();
            payload.set("entry", ChangeJson.MAPPER.valueToTree(entry));
            payload.set("judgment", ChangeJson.MAPPER.valueToTree(judgment));
            payload.put("invalidatedApprovalId", current.deliveryApproval() == null ? "" : current.deliveryApproval().id());
            store.update(current.version(), updated, event(updated, "human.evidence_recorded", input.actorType(), input.actorId(),
                    current.state(), next, payload.toString()));
            // Publication is a separate, retryable workflow step; a saved observation is not a successful Check.
            return get(id);
        } catch (IOException e) {
            throw new ChangeValidationException("无法读取当前人工验收产物");
        }
    }

    private ChangeSpecDocument readDraft(SpecRef spec) {
        if (spec.draftPath() == null) {
            throw new ChangeConflictException("ChangeTask 没有可评估的 ChangeSpec Draft");
        }
        try {
            ChangeSpecDocument document = specCodec.decode(
                    Files.readString(spec.draftPath(), StandardCharsets.UTF_8));
            if (!spec.specId().equals(document.spec().id())
                    || spec.revision() != document.spec().revision()
                    || !spec.digest().equals(document.specDigest())) {
                throw new ChangeConflictException("ChangeSpec Draft identity 或 digest 已过期");
            }
            return document;
        } catch (IOException error) {
            throw new IllegalStateException("读取 ChangeSpec Draft 失败: " + error.getMessage(), error);
        }
    }

    /** Claim and completion use the same versioned transaction as business state and events. */
    public synchronized DraftJob claimDraft(ChangeTaskId id, long timeoutMs) {
        ChangeTask task = load(id);
        DraftJob job = task.draftJob();
        if (task.state() != ChangeState.DRAFTING_SPEC || job == null || !job.due(clock.millis())) return null;
        DraftJob claimed = job.claim(clock.millis(), timeoutMs);
        saveDraftJob(task, claimed, ChangeState.DRAFTING_SPEC, "spec.drafting_started", "draft-runner");
        return claimed;
    }

    public ChangeSpecModule.SpecDraft generateDraft(DraftJob claim) throws IOException {
        return specModule.generateDraft(claim.attemptInput());
    }

    public synchronized boolean draftClaimActive(ChangeTaskId id, DraftJob claim) {
        return matchesClaim(load(id), claim) && clock.millis() < claim.deadlineAt();
    }

    private boolean matchesClaim(ChangeTask task, DraftJob claim) {
        DraftJob current = task.draftJob();
        return task.state() == ChangeState.DRAFTING_SPEC && current != null
                && current.status() == DraftJob.Status.RUNNING
                && current.generation().equals(claim.generation()) && current.lease().equals(claim.lease());
    }

    public synchronized void completeDraft(ChangeTaskId id, DraftJob claim, ChangeSpecModule.SpecDraft draft) {
        ChangeTask task = load(id);
        if (!matchesClaim(task, claim) || clock.millis() >= claim.deadlineAt()) return;
        if (!claim.input().specId().equals(draft.specId()) || claim.input().revision() != draft.revision()) {
            failDraft(id, claim, false, "Draft identity 不匹配");
            return;
        }
        SpecRef spec = new SpecRef(draft.specId(), draft.revision(), draft.specDigest(), draft.path(), null);
        // Validate the persisted document before allowing review, not just the provider's metadata.
        readDraft(spec);
        ChangeTask review = task.withDraft(spec, claim.input().request(), clock.instant());
        DraftJob completed = claim.finish(DraftJob.Status.SUCCEEDED, 0, "");
        review = new ChangeTask(review.id(), review.idempotencyKey(), review.version(), review.state(),
                review.source(), review.repository(), review.title(), review.requirement(), review.requesterId(),
                review.projectContext(), review.referencedContext(), review.spec(), review.risk(), review.route(),
                review.specApproval(), review.deliveryApproval(), review.workerClaim(), review.run(), completed,
                review.createdAt(), review.updatedAt());
        var payload = ChangeJson.MAPPER.createObjectNode();
        payload.put("generation", claim.generation()).put("attempt", claim.attempts())
                .put("specDigest", spec.digest()).put("revision", spec.revision()).put("draftPath", spec.draftPath().toString());
        store.update(task.version(), review, event(review, "spec.draft_generated", "AGENT", "change-spec",
                task.state(), review.state(), payload.toString()));
    }

    public synchronized void failDraft(ChangeTaskId id, DraftJob claim, boolean retryable, String reason) {
        ChangeTask task = load(id);
        if (!matchesClaim(task, claim)) return;
        boolean retry = retryable && claim.attempts() < DraftJob.MAX_ATTEMPTS;
        DraftJob next = claim.finish(retry ? DraftJob.Status.RETRY_WAIT : DraftJob.Status.FAILED,
                retry ? clock.millis() + 1000L * (1L << (claim.attempts() - 1)) : 0, reason);
        saveDraftJob(task, next, retry ? ChangeState.DRAFTING_SPEC : ChangeState.FAILED,
                retry ? "spec.draft_retry_scheduled" : "spec.draft_failed", "draft-runner");
    }

    /** Called once at startup under the platform's exclusive data-directory lock. */
    public synchronized void recoverDrafts() {
        for (ChangeTask task : store.list()) {
            if (task.spec() != null && task.spec().locked()) continue;
            if (task.state() != ChangeState.CREATED && task.state() != ChangeState.DRAFTING_SPEC) continue;
            DraftJob job = task.draftJob();
            if (job == null) {
                // Additive migration of pre-M1 interrupted creation/supplement, using saved inputs only.
                job = DraftJob.pending(new ChangeSpecModule.ChangeContext(task.id().value(),
                        task.spec() == null ? nextSpecId(task) : task.spec().specId(),
                        task.spec() == null ? 1 : task.spec().revision() + 1, task.requirement(),
                        task.projectContext(), task.referencedContext()), clock.millis());
                saveDraftJob(task, job, ChangeState.DRAFTING_SPEC, "spec.draft_recovered", "draft-runner");
            } else if (job.status() == DraftJob.Status.RUNNING) {
                failDraft(task.id(), job, true, "服务重启，前次生成中断；已消耗的 attempt 保留");
            }
        }
    }

    @Override
    public synchronized ChangeTaskView cancelDraft(ChangeTaskId id, long version, String generation,
                                                   String actor, String actorType) {
        ChangeTask task = draftOperation(id, version, generation, actor);
        if (task.state() != ChangeState.DRAFTING_SPEC) throw new ChangeConflictException("当前状态不能取消 Draft");
        saveDraftJob(task, task.draftJob().finish(DraftJob.Status.CANCELED, 0, "用户取消"),
                ChangeState.CANCELED, "spec.draft_canceled", actor, actorType);
        return get(id);
    }

    @Override
    public synchronized ChangeTaskView retryDraft(ChangeTaskId id, long version, String generation,
                                                  String actor, String actorType) {
        ChangeTask task = draftOperation(id, version, generation, actor);
        if (task.state() != ChangeState.FAILED || task.draftJob().status() != DraftJob.Status.FAILED) {
            throw new ChangeConflictException("只有生成失败的 Draft 可以重试");
        }
        saveDraftJob(task, DraftJob.pending(task.draftJob().input(), clock.millis()),
                ChangeState.DRAFTING_SPEC, "spec.draft_retried", actor, actorType);
        return get(id);
    }

    private ChangeTask draftOperation(ChangeTaskId id, long version, String generation, String actor) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actorId 必填");
        ChangeTask task = load(id);
        requireVersion(task, version);
        if (task.draftJob() == null || !task.draftJob().generation().equals(generation)) {
            throw new ChangeConflictException("Draft generation 已过期，请刷新");
        }
        return task;
    }

    private void saveDraftJob(ChangeTask task, DraftJob job, ChangeState state, String type, String actor) {
        saveDraftJob(task, job, state, type, actor,
                type.equals("spec.draft_retried") || type.equals("spec.draft_canceled") ? "LEGACY" : "SYSTEM");
    }

    private void saveDraftJob(ChangeTask task, DraftJob job, ChangeState state, String type,
                              String actor, String actorType) {
        ChangeTask next = task.withDraftJob(job, state, clock.instant());
        store.update(task.version(), next, event(next, type, actorType,
                actor, task.state(), state, draftPayload(job)));
    }

    private static String draftPayload(DraftJob job) {
        var payload = ChangeJson.MAPPER.createObjectNode();
        payload.put("generation", job.generation()).put("attempt", job.attempts())
                .put("revision", job.input().revision()).put("status", job.status().name())
                .put("availableAt", job.availableAt()).put("deadlineAt", job.deadlineAt()).put("error", job.error());
        return payload.toString();
    }

    private ChangeTask load(ChangeTaskId id) {
        return store.find(id).orElseThrow(() -> new ChangeNotFoundException(id));
    }

    private static ChangeSpecModule.SpecDraft toDraft(SpecRef spec) {
        if (spec.draftPath() == null) {
            throw new IllegalStateException("ChangeTask 没有可审批的 Draft");
        }
        return new ChangeSpecModule.SpecDraft(
                spec.draftPath(), spec.specId(), spec.revision(), spec.digest(), 0L, null);
    }

    private static void requireVersion(ChangeTask current, long expected) {
        if (current.version() != expected) {
            throw new ChangeConflictException(
                    "ChangeTask version 已过期，expected=" + expected + ", actual=" + current.version());
        }
    }

    private static void requireState(ChangeTask current, ChangeState expected) {
        if (current.state() != expected) {
            throw new ChangeConflictException(
                    "当前状态不接受该决策，expected=" + expected + ", actual=" + current.state());
        }
    }

    private static void requireCurrentDigest(ChangeTask current, String expectedDigest) {
        SpecRef spec = current.spec();
        if (spec == null || !spec.digest().equals(expectedDigest)) {
            throw new ChangeConflictException("ChangeSpec digest 已过期");
        }
    }

    private static void requireCurrentDeliveryIdentity(
            ChangeTask current,
            String expectedSpecDigest,
            String expectedHeadSha
    ) {
        SpecRef spec = current.spec();
        RunRef run = current.run();
        if (spec == null || run == null
                || !spec.digest().equals(expectedSpecDigest)
                || !run.specDigest().equals(expectedSpecDigest)) {
            throw new ChangeConflictException("Delivery Approval 的 specDigest 已过期");
        }
        if (!run.headSha().equals(expectedHeadSha)) {
            throw new ChangeConflictException("Delivery Approval 的 headSha 已过期");
        }
    }

    private static void assertLockedIdentity(SpecRef draft, ChangeSpecModule.LockedSpec locked) {
        if (!draft.specId().equals(locked.specId())
                || draft.revision() != locked.revision()
                || !draft.digest().equals(locked.specDigest())) {
            throw new IllegalStateException("锁定的 ChangeSpec identity 与 Draft 不一致");
        }
    }

    private ChangeEvent event(
            ChangeTask task,
            String type,
            String actorType,
            String actorId,
            ChangeState previous,
            ChangeState next,
            String payload
    ) {
        return new ChangeEvent(0L, task.id(), type, actorType, actorId, previous, next, payload, clock.instant());
    }

    private static String nextSpecId(ChangeTask task) {
        return "CHANGE-" + task.id().value().substring("change_".length()).toUpperCase(java.util.Locale.ROOT);
    }

    private static String approvalId() {
        return "approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String claimId() {
        return "claim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String json(String value) {
        try {
            String encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value == null ? "" : value);
            return encoded.substring(1, encoded.length() - 1);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法编码 Change Event", e);
        }
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static PaiCliConfig defaultConfig() {
        return new PaiCliConfig();
    }

    private final class StoreBackedExecutionLease implements ExecutionLease {
        private ChangeTask current;
        private final String claimId;
        private boolean finished;

        private StoreBackedExecutionLease(ChangeTask current, String claimId) {
            this.current = current;
            this.claimId = claimId;
        }

        @Override
        public synchronized ChangeTask task() {
            return current;
        }

        @Override
        public synchronized void started(String workspaceId) {
            requireActiveClaim();
            requireState(current, ChangeState.RUNNING);
            update(ChangeState.RUNNING, current.run(), "execution.started",
                    "{\"workspaceId\":\"" + json(workspaceId) + "\"}");
        }

        @Override
        public synchronized void verificationStarted() {
            requireActiveClaim();
            requireState(current, ChangeState.RUNNING);
            update(ChangeState.VERIFYING, current.run(), "verification.started", "{}");
        }

        @Override
        public synchronized void complete(RunRef run) {
            requireActiveClaim();
            Objects.requireNonNull(run, "run");
            SpecRef spec = Objects.requireNonNull(current.spec(), "current.spec");
            if (!spec.digest().equals(run.specDigest())) {
                throw new ChangeConflictException("Spec Run digest 与锁定 ChangeSpec 不一致");
            }
            boolean acceptedForReview = run.status() == com.paicli.spec.SpecRunResult.Status.FINISHED
                    && (run.verdict() == com.paicli.spec.SpecRunResult.Verdict.PASSED
                    || run.verdict() == com.paicli.spec.SpecRunResult.Verdict.NEEDS_HUMAN);
            if (acceptedForReview && current.state() != ChangeState.VERIFYING) {
                throw new ChangeConflictException("成功执行必须先进入 VERIFYING");
            }
            boolean approvalRequired = run.verdict() == com.paicli.spec.SpecRunResult.Verdict.NEEDS_HUMAN
                    || current.risk() != null && current.risk().level() == RiskLevel.HIGH
                    || current.route() != null && current.route().deliveryApprovalRequired();
            ChangeState next = acceptedForReview
                    ? approvalRequired ? ChangeState.DELIVERY_REVIEW : ChangeState.PUBLISHING
                    : ChangeState.FAILED;
            String eventType = acceptedForReview ? "execution.completed" : "change.failed";
            update(next, run, eventType,
                    "{\"runId\":\"" + json(run.runId()) + "\",\"verdict\":\""
                            + run.verdict().name() + "\",\"headSha\":\"" + json(run.headSha()) + "\"}");
            finished = true;
        }

        @Override
        public synchronized void fail(String reason) {
            if (finished) {
                return;
            }
            requireActiveClaim();
            update(ChangeState.FAILED, current.run(), "change.failed",
                    "{\"reason\":\"" + json(reason) + "\"}");
            finished = true;
        }

        private void update(ChangeState next, RunRef run, String type, String payload) {
            ChangeTask previous = current;
            WorkerClaimRef nextClaim = next == ChangeState.RUNNING || next == ChangeState.VERIFYING
                    ? previous.workerClaim()
                    : null;
            ChangeTask changed = previous.withExecution(next, nextClaim, run, clock.instant());
            current = store.update(previous.version(), changed, event(
                    changed, type, "WORKER", claimId, previous.state(), next, payload));
        }

        private void requireActiveClaim() {
            if (finished) {
                throw new ChangeConflictException("Worker execution lease 已结束");
            }
            WorkerClaimRef claim = current.workerClaim();
            if (claim == null || !claimId.equals(claim.claimId())) {
                throw new ChangeConflictException("Worker execution claim 已失效");
            }
        }
    }
}
