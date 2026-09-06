package com.paicli.change;

import com.paicli.config.PaiCliConfig;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.spec.*;
import com.paicli.tool.CommandExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit offline composition: never loads credentials, provider clients, MCP or remote SCM. */
public final class OfflineChangeDemo {
    private OfflineChangeDemo() { }

    public static ChangePlatform create(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        Path repository = root.resolve("fixture-repository");
        if (!Files.exists(repository)) {
            Files.createDirectories(repository.resolve("payment"));
            Files.writeString(repository.resolve("payment/RefundPolicy.java"), resource("RefundPolicy.java"));
            git(repository, "init", "-b", "main");
            git(repository, "add", "payment/RefundPolicy.java");
            git(repository, "-c", "user.name=PaiChange Demo", "-c", "user.email=demo@paichange.local",
                    "commit", "--no-gpg-sign", "-m", "Offline refund fixture");
        }
        Path data = Files.createDirectories(root.resolve("platform"));
        Path fixtures = Files.createDirectories(data.resolve("fixtures"));
        Path fixture = fixtures.resolve("offline-refund.json");
        if (!Files.exists(fixture)) Files.writeString(fixture, ChangeJson.MAPPER.writeValueAsString(Map.of(
                "idempotencyKey", "offline-refund-v1", "sourceType", "mock_gitlab_issue", "externalId", "REFUND-DEMO",
                "title", "退款超时边界修复", "description", "退款超过 24 小时进入人工审核，24 小时整不进入；不能影响自动取消流程。",
                "requester", "developer", "repository", Map.of("path", repository.toString(), "baseRef", "main"))));
        PaiCliConfig config = new PaiCliConfig(); // Do not call load(): no personal provider configuration.
        // Explicit local-demo exception: one API key represents one operator, so no second approver exists.
        config.getPaiChange().setForbidRequesterSelfApprovalForMediumAndHigh(false);
        var route = new PaiCliConfig.PaiChangeRouteConfig();
        route.setProvider("offline-demo"); route.setModel("deterministic-fixture");
        route.setRepairEnabled(true); route.setDeliveryApprovalRequired(true);
        route.setToolPolicy(Boolean.getBoolean("paichange.demo.tool.approvals") ? "LOCKED_DOWN" : "STANDARD");
        config.getPaiChange().setRoutes(Map.of("LOW", route, "MEDIUM", route, "HIGH", route));
        FileChangeSpecModule specs = new FileChangeSpecModule(data, context -> {
            String source = resource("spec.md").replace("SPEC_ID", context.specId())
                    .replace("SPEC_REVISION", Integer.toString(context.revision())) + "\n确认需求与补充记录：\n" + context.request() + "\n";
            return new SpecDraftSession.DraftGeneration(new ChangeSpecCodec().decode(source), SpecRunResult.LlmUsage.empty(), 0);
        });
        return new ChangePlatform(data, fixtures, specs, config,
                new GitWorktreeWorkspaceProvisioner(root.resolve("workspaces")), runtime(), DeliveryHeadReader.localGit(), true);
    }

    private static ChangeWorkerRuntimeFactory runtime() {
        return new ChangeWorkerRuntimeFactory() {
            @Override
            public SpecExecutionEngine create(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace) {
                return create(task, workspace, ChangeWorkerRuntimeContext.none());
            }

            @Override
            public SpecExecutionEngine create(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace,
                                              ChangeWorkerRuntimeContext context) {
                com.paicli.tool.ToolRegistry tools = context != null && context.toolGovernanceEnabled()
                    ? new GovernedToolRegistry(context.toolApprovals(), task, workspace.workspaceRoot(), context.isolation())
                    : new com.paicli.tool.ToolRegistry();
            tools.setProjectPath(workspace.workspaceRoot().toString());
            SpecDraftSession unused = new SpecDraftSession(request -> { throw new IllegalStateException("Worker cannot draft"); },
                    document -> { throw new IllegalStateException("Offline demo has no terminal review"); });
            return new SpecRunCoordinator(workspace.workspaceRoot(), workspace.evidenceRoot().resolve("runs"),
                    unused, request -> request,
                    (phase, input, spec) -> {
                        try {
                            boolean repair = phase == SpecRunCoordinator.ReActPhase.REPAIR;
                            String source = resource("RefundPolicy.java").replace("hours > 48", repair ? "hours > 24" : "hours >= 24");
                            String output = tools.executeTool("write_file", ChangeJson.MAPPER.createObjectNode()
                                    .put("path", "payment/RefundPolicy.java").put("content", source).toString());
                            if (output.startsWith("[HITL]") || output.startsWith("🛡️")) {
                                return SpecRunCoordinator.ReActExecutionResult.failed(output);
                            }
                            return SpecRunCoordinator.ReActExecutionResult.completed("离线模拟 " + phase + "：已写入退款策略");
                        } catch (IOException error) { throw new IllegalStateException(error); }
                    }, new SpecVerifier(workspace.workspaceRoot(), command -> {
                        if (tools instanceof GovernedToolRegistry governed) {
                            return governed.executeCommandForVerification(command);
                        }
                        return verify(workspace.workspaceRoot(), command);
                    }),
                    (criterion, changes) -> SpecRunCoordinator.HumanJudgment.skipped("Offline fixture has no Human Criterion"),
                    new SpecRunCoordinator.RunOptions(task.route().repairEnabled()
                            ? SpecRunCoordinator.RepairPolicy.ENABLED : SpecRunCoordinator.RepairPolicy.DISABLED,
                            attempt -> { }, identity -> {
                                if (tools instanceof GovernedToolRegistry governed) {
                                    governed.approvalHandler().bindRunIdentity(identity);
                                }
                            }));
            }
        };
    }

    private static CommandExecutionResult verify(Path workspace, String command) {
        if (!command.equals("java payment/RefundPolicy.java")) return CommandExecutionResult.startError(command, "非演示命令，拒绝执行");
        Path output = null;
        try {
            output = Files.createTempFile("paichange-demo-verifier-", ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "payment/RefundPolicy.java").directory(workspace.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return CommandExecutionResult.startError(command, "本地 Java 验证超时");
                return CommandExecutionResult.completed(command, process.exitValue(), Files.readString(output));
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return CommandExecutionResult.startError(command, "本地验证失败");
        } finally {
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    public static void startAndBlock(int port) throws Exception {
        String key = RuntimeApiServer.configuredApiKey();
        if (key == null || key.isBlank()) throw new IllegalArgumentException("离线演示仍需 PAICLI_RUNTIME_API_KEY");
        String configured = System.getProperty("paichange.demo.dir");
        Path root = configured == null || configured.isBlank() ? Files.createTempDirectory("paichange-offline-") : Path.of(configured);
        try (ChangePlatform platform = create(root);
             RuntimeThreadStore threads = new RuntimeThreadStore(root.resolve("demo-threads.db"));
             RuntimeApiServer server = new RuntimeApiServer(threads, input -> "离线演示模式：threads 不调用模型。",
                     port, key, platform.handler())) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.close(); platform.close(); threads.close(); }, "offline-demo-shutdown"));
            platform.start(); server.start();
            System.out.println("PaiChange 离线模拟执行 / Mock SCM: http://127.0.0.1:" + server.port() + "/changes");
            System.out.println("演示数据目录: " + root.toAbsolutePath());
            new CountDownLatch(1).await();
        }
    }

    private static String resource(String name) throws IOException {
        try (var input = OfflineChangeDemo.class.getResourceAsStream("/paichange-demo/" + name)) {
            if (input == null) throw new IOException("缺少离线 fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("离线 fixture Git 初始化失败");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
