package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（Worker 默认 2 个，按压力扩容至 4 个）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link WorkerPool.Lease} 独占租借，批次结束后缩容回默认容量
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;
    private static final int MIN_WORKERS = 2;
    private static final int MAX_WORKERS = 4;

    // LlmClient、ToolRegistry、MemoryManager 由调用方提供并共享；编排器只借用，不负责关闭。
    private final LlmClient llmClient;
    // Planner、共享 Reviewer 和 WorkerPool 由当前编排器创建并拥有，close() 时统一释放。
    private final SubAgent planner;
    private final WorkerPool workerPool;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";
    private com.paicli.skill.SkillRegistry skillRegistry;
    private com.paicli.skill.SkillContextBuffer skillContextBuffer;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 不可变的步骤状态快照。并行任务不原地修改对象，而是通过 {@link #updateStep}
     * 用新快照替换旧值，便于依赖调度只根据明确的终态做下一批筛选。
     * package-private 供同包测试验证依赖关系和状态转换。
     */
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String result,
                                  StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workerPool = new WorkerPool(MIN_WORKERS, MAX_WORKERS, this::createWorker);
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        this.memoryManager = memoryManager;
    }

    /**
     * 更新所有现存角色的外部上下文来源。后续按压力创建的 Worker/Reviewer 会通过
     * {@link #configureSubAgent(SubAgent)} 继承当前配置。
     */
    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        ensureOpen();
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        configureSubAgent(planner);
        workerPool.forEachWorker(this::configureSubAgent);
        configureSubAgent(reviewer);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 但共享同一 SkillContextBuffer——简化实现，避免角色级 buffer 隔离的工程开销。
     * 任务书 §3.6 描述的"角色独立 buffer"作为可观察的优化项暂未启用。
     */
    public void setSkillSystem(com.paicli.skill.SkillRegistry skillRegistry,
                               com.paicli.skill.SkillContextBuffer skillContextBuffer) {
        ensureOpen();
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        configureSubAgent(planner);
        workerPool.forEachWorker(this::configureSubAgent);
        configureSubAgent(reviewer);
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        ensureOpen();
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);
        planner.clearHistory();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        if (planResult.type() == AgentMessage.Type.ERROR) {
            return "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "❌ 规划失败：规划者未能生成有效计划";
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
        }

        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps) + "\n");

        // 3. 执行阶段：每轮只选择依赖全部完成的步骤，形成一个 DAG 批次屏障。
        // 当前批次全部结束后才重新筛选下一批，避免下游步骤提前读取尚未完成的结果。
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                String context = buildStepContext(steps, step);

                // Lease 的作用域覆盖执行、审查和重试；退出作用域后才清理历史并归还 Worker。
                try (WorkerPool.Lease lease = workerPool.acquire()) {
                    runStep(step, steps, retryCount, lease.worker(), reviewer, context, out);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("等待 Worker 时被中断"));
                    out.println("❌ 步骤 [" + step.id() + "] 等待 Worker 时被中断\n");
                }
            } else {
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按计划顺序 flush。
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（最多 " + workerPool.maxWorkers() + " 个并发 Worker）\n");
                runBatchParallel(executable, steps, retryCount);
            }
        }

        // 5. 处理因前置失败而无法执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 "tasks" 字段（兼容 Plan-and-Execute 的格式）
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 第二遍：建立依赖
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    // 替换步骤的依赖
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）。
     *
     * <p>失败依赖不会被视为完成，因此其下游步骤会继续保持 PENDING，最终由编排器
     * 统一标记为因前置失败而跳过。</p>
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        // 先构造本轮状态快照，避免筛选过程中反复遍历步骤列表。
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        // 只有“尚未执行且全部依赖成功完成”的步骤能进入当前批次。
        // FAILED 依赖不会放行下游，因此失败链路最终仍保持 PENDING 并被统一报告为跳过。
        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 串行化并行步骤对共享步骤列表的替换操作。列表本身是 ArrayList，不能让多个
     * Worker 线程同时遍历定位并写入。
     */
    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 避免多个审查任务竞争同一个 conversationHistory。流式输出写入步骤本地的
     * ByteArrayOutputStream；所有任务完成后按 batch 中的计划顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        // 线程数按当前批次大小收敛，但绝不超过 WorkerPool 的最大租借数。
        int parallelism = Math.min(batch.size(), workerPool.maxWorkers());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "paicli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            // 每个步骤写自己的缓冲区，避免多个线程直接争用终端输出流。
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

            // 当前批次的步骤互相独立；这里只读取上一批已完成的直接依赖结果。
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                // 并行步骤不能共享 Reviewer 的 conversationHistory，因此每步创建独立实例。
                // Lease 则保证每个步骤在完整执行周期内独占一个 Worker。
                try (SubAgent localReviewer = createReviewer("reviewer-" + step.id());
                     WorkerPool.Lease lease = workerPool.acquire()) {
                    runStep(step, steps, retryCount, lease.worker(), localReviewer, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    stepOut.flush();
                }
                return null;
            }));
        }

        try {
            // Future 等待形成批次屏障：所有同层步骤结束后，外层 run() 才会筛选下一层依赖。
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Batch wait interrupted");
                } catch (ExecutionException e) {
                    log.error("Parallel step task failed", e.getCause());
                }
            }
        } finally {
            // 批次线程池不跨批次复用；WorkerPool 单独保留最小容量，并关闭本批次扩出的实例。
            executor.shutdownNow();
            workerPool.trimToMinimum();
        }

        // batch 继承原计划顺序；这里按该顺序 flush，而不是按线程完成先后输出。
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。同一步骤
     * 的重试继续使用当前 Worker，使其保留本步骤内的执行历史；方法返回后 Lease 才会
     * 清理 Worker 历史。Reviewer 每次审查后立即清理历史，避免重试审查受上次结论污染。
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());

        // 依赖结果显式拼入本步骤消息；不依赖 Worker 之前是否执行过某个前置步骤。
        AgentMessage result = worker.executeWithContext(taskMsg, context, out);
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);

        // Reviewer 的上一轮结论不应成为下一轮审查证据，只保留角色系统提示词。
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            // 审查服务不可用不回滚已经成功的 Worker 结果，按降级策略保留当前产物。
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("⚠️ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，保留当前执行结果\n");
            updateStep(steps, step.id(), step.withResult(result.content()));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            // 重试继续使用同一个 Worker，让它能看到本步骤之前的尝试；Lease 归还时再统一清理。
            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);

            // 每次审查都是独立判断，避免首次拒绝结论污染重试结果。
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                // 重试结果已经产生但审查服务异常：停止继续消耗重试次数，保留最新结果。
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        // 即使达到重试上限仍保留最后一次可用产物；approved 只决定最终提示文案。
        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            out.println("⚠️ 步骤 [" + step.id() + "] 超过最大重试次数，保留当前结果\n");
        }
    }

    /**
     * 只把当前步骤的直接依赖结果显式注入任务文本。
     *
     * <p>Worker 虽然共享 ToolRegistry，但不共享 conversationHistory；跨步骤信息通过这里
     * 传递，而不是依赖某个 Worker 恰好执行过前置步骤。结果预览受长度限制，避免依赖链
     * 无界放大上下文。</p>
     */
    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    // 依赖结果只作为提示上下文，限制预览长度以免 DAG 层数增加时上下文持续膨胀。
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 创建拥有独立会话历史的 Worker。共享的是 LLM 客户端和工具注册表，不是对话上下文。
     */
    private SubAgent createWorker(int workerNumber) {
        SubAgent worker = new SubAgent("worker-" + workerNumber, AgentRole.WORKER, llmClient, toolRegistry);
        configureSubAgent(worker);
        return worker;
    }

    /**
     * 创建 Reviewer；并行路径按步骤创建独立实例，串行路径复用编排器持有的 Reviewer。
     */
    private SubAgent createReviewer(String name) {
        SubAgent agent = new SubAgent(name, AgentRole.REVIEWER, llmClient, toolRegistry);
        configureSubAgent(agent);
        return agent;
    }

    /**
     * 为预热实例和动态扩容实例应用一致的外部上下文与 Skill 配置。
     */
    private void configureSubAgent(SubAgent agent) {
        agent.setExternalContextSupplier(externalContextSupplier);
        agent.setSkillRegistry(skillRegistry);
        agent.setSkillContextBuffer(skillContextBuffer);
    }

    WorkerPool.Stats workerPoolStats() {
        return workerPool.stats();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AgentOrchestrator 已关闭");
        }
    }

    /**
     * 释放当前 Team 任务拥有的角色状态和 Worker 池。共享的 LlmClient、ToolRegistry、
     * MemoryManager 仍由上层 CLI/TUI 生命周期管理。
     */
    @Override
    public void close() {
        // compareAndSet 让显式 close 与 try-with-resources 的重复关闭保持幂等。
        if (closed.compareAndSet(false, true)) {
            // 这里只释放编排器拥有的角色状态；共享依赖仍由 CLI/TUI 的更外层生命周期管理。
            planner.close();
            workerPool.close();
            reviewer.close();
        }
    }
}
