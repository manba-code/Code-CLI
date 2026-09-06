package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.PaiCliConfig;
import com.paicli.tool.CommandExecutionResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Docker CLI implementation of the M6a per-task process boundary. */
public final class DockerWorkerIsolation implements WorkerIsolation {
    static final String MANAGED_LABEL = "com.paicli.paichange.managed=true";
    static final String OWNER_LABEL = "com.paicli.paichange.owner";
    static final String EGRESS_LABEL = "com.paicli.paichange.egress";
    static final String EGRESS_PROJECT_LABEL = "com.paicli.paichange.project";
    static final String EGRESS_POLICY_LABEL = "com.paicli.paichange.egress-policy";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final Config config;
    private final DockerCommandRunner runner;
    private final EphemeralSecretProvider secrets;
    private final Clock clock;
    private final Set<SessionImpl> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static WorkerIsolation fromConfig(Path controlRoot, PaiCliConfig config) {
        return fromConfig(controlRoot, config, EphemeralSecretProvider.none(), false);
    }

    public static WorkerIsolation fromConfig(Path controlRoot, PaiCliConfig config,
                                             EphemeralSecretProvider secretProvider) {
        EphemeralSecretProvider provider = Objects.requireNonNull(secretProvider, "secretProvider");
        return fromConfig(controlRoot, config, provider, provider != EphemeralSecretProvider.none());
    }

    private static WorkerIsolation fromConfig(Path controlRoot, PaiCliConfig config,
                                              EphemeralSecretProvider secretProvider, boolean secretsEnabled) {
        Config resolved = Config.from(controlRoot, config, secretsEnabled);
        return resolved.enabled() ? new DockerWorkerIsolation(resolved, new ProcessDockerCommandRunner(),
                secretProvider, Clock.systemUTC()) : WorkerIsolation.none();
    }

    DockerWorkerIsolation(Config config, DockerCommandRunner runner,
                          EphemeralSecretProvider secrets, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
        config.validate();
    }

    @Override
    public void recoverOrphans() throws Exception {
        requireDocker();
        DockerResult listed = runner.run(List.of(config.dockerBinary(), "ps", "-aq", "--filter",
                "label=" + OWNER_LABEL + "=" + config.owner()), null, CONTROL_TIMEOUT);
        requireSuccess(listed, "列出遗留 PaiChange 容器失败");
        for (String id : listed.output().split("\\R")) {
            String normalized = id.trim();
            if (normalized.isEmpty()) continue;
            DockerResult removed = runner.run(List.of(config.dockerBinary(), "rm", "-f", normalized),
                    null, CONTROL_TIMEOUT);
            requireSuccess(removed, "清理遗留 PaiChange 容器失败");
        }
    }

    @Override
    public Session open(ChangeTask task, WorkspaceProvisioner.WorkspaceLease workspace) throws Exception {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        requireDocker();
        requireLocalImage();
        Path root = workspace.workspaceRoot().toRealPath();
        Path repository = workspace.repositoryRoot().toRealPath();
        Path evidence = workspace.evidenceRoot().toRealPath();
        Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || root.toString().contains(",") || root.toString().contains("\n")) {
            throw new IllegalArgumentException("Docker workspace 必须是无逗号/换行的现有目录");
        }
        if (root.getParent() == null || root.equals(userHome) || repository.startsWith(root) || evidence.startsWith(root)) {
            throw new IllegalArgumentException("Docker 只允许挂载单个任务工作区，不能覆盖仓库、Evidence 或宿主私有目录");
        }
        Egress egress = config.egress(task);
        if (egress.enabled()) verifyEgressNetwork(task, egress);
        EphemeralSecretProvider.SecretLease lease = secrets.acquire(task);
        if (lease == null) lease = EphemeralSecretProvider.SecretLease.empty();
        if (lease.expired(clock.instant())) {
            lease.close();
            throw new IllegalStateException("任务 Secret lease 在容器启动前已失效");
        }

        String name = containerName(task.id());
        List<String> create = new ArrayList<>(List.of(
                config.dockerBinary(), "create", "--name", name,
                "--label", MANAGED_LABEL,
                "--label", OWNER_LABEL + "=" + config.owner(),
                "--label", "com.paicli.paichange.change=" + task.id().value(),
                "--pull=never",
                "--init", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true",
                "--pids-limit=" + config.pidsLimit(),
                "--memory=" + config.memoryMb() + "m",
                "--cpus=" + decimal(config.cpus()),
                "--user", config.user(),
                "--workdir", "/workspace",
                // Docker bind mounts are writable by default. The --mount grammar accepts
                // key=value fields (or the bare "readonly" flag), but not a bare "rw" field.
                "--mount", "type=bind,src=" + root + ",dst=/workspace",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864",
                "--tmpfs", "/run/paichange-secrets:rw,noexec,nosuid,nodev,uid=" + config.uid()
                        + ",gid=" + config.gid() + ",mode=0700,size=1048576"));
        if (egress.enabled()) {
            create.addAll(List.of("--network", egress.network(),
                    "--env", "HTTP_PROXY=" + egress.proxyUrl(),
                    "--env", "HTTPS_PROXY=" + egress.proxyUrl(),
                    "--env", "NO_PROXY=localhost,127.0.0.1,::1",
                    "--env", "http_proxy=" + egress.proxyUrl(),
                    "--env", "https_proxy=" + egress.proxyUrl(),
                    "--env", "no_proxy=localhost,127.0.0.1,::1"));
        } else {
            create.addAll(List.of("--network", "none"));
        }
        create.addAll(List.of(config.image(), "sh", "-c",
                "trap 'exit 0' TERM INT; while :; do sleep 3600; done"));

        boolean created = false;
        try {
            DockerResult createResult = runner.run(create, null, CONTROL_TIMEOUT);
            requireSuccess(createResult, "创建隔离 Worker 容器失败");
            created = true;
            requireSuccess(runner.run(List.of(config.dockerBinary(), "start", name), null, CONTROL_TIMEOUT),
                    "启动隔离 Worker 容器失败");
            SessionImpl session = new SessionImpl(name, task, egress, lease);
            session.installSecrets();
            active.add(session);
            return session;
        } catch (Exception failure) {
            lease.close();
            if (created) runner.run(List.of(config.dockerBinary(), "rm", "-f", name), null, CONTROL_TIMEOUT);
            throw failure;
        }
    }

    @Override
    public Capabilities capabilities() {
        boolean egress = config.egressByProject().values().stream().anyMatch(Egress::enabled);
        return new Capabilities(true, "docker", config.image(), config.cpus(), config.memoryMb(),
                config.pidsLimit(), config.taskTimeoutSeconds(), egress ? "default-deny+project-proxy" : "none",
                config.secretInjectionEnabled());
    }

    @Override
    public void close() {
        for (SessionImpl session : List.copyOf(active)) session.abort("平台关闭");
    }

    private void requireDocker() throws Exception {
        DockerResult result = runner.run(List.of(config.dockerBinary(), "version", "--format", "{{.Server.Version}}"),
                null, CONTROL_TIMEOUT);
        requireSuccess(result, "Docker Engine 不可用；M6a 不允许降级到宿主执行");
    }

    private void requireLocalImage() throws Exception {
        DockerResult result = runner.run(List.of(config.dockerBinary(), "image", "inspect", config.image()),
                null, CONTROL_TIMEOUT);
        requireSuccess(result, "M6a Docker 镜像未在本机预置；平台不会自动 pull 或 build");
    }

    private void verifyEgressNetwork(ChangeTask task, Egress egress) throws Exception {
        if (Set.of("bridge", "host", "default", "none").contains(egress.network().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("显式放行不得使用 Docker 默认/host 网络");
        }
        DockerResult inspected = runner.run(List.of(config.dockerBinary(), "network", "inspect", "--format",
                "{{.Internal}}|{{index .Labels \"" + EGRESS_LABEL + "\"}}|{{index .Labels \""
                        + EGRESS_PROJECT_LABEL + "\"}}|{{index .Labels \"" + EGRESS_POLICY_LABEL
                        + "\"}}", egress.network()), null, CONTROL_TIMEOUT);
        requireSuccess(inspected, "项目 egress 网络不存在或不可检查");
        String expected = "true|true|" + ChangeProject.id(task.repository()) + "|" + egress.policyDigest();
        if (!expected.equals(inspected.output().trim())) {
            throw new IllegalStateException("Docker egress 网络不是 internal 网络，或缺少匹配的 PaiChange 项目/策略标记");
        }
    }

    private static void requireSuccess(DockerResult result, String message) {
        if (result == null || result.timedOut() || result.exitCode() != 0) {
            String detail = result == null ? "无结果" : compact(result.output());
            throw new IllegalStateException(message + (detail.isBlank() ? "" : ": " + detail));
        }
    }

    private String containerName(ChangeTaskId id) {
        String safe = id.value().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return ("paichange-" + config.owner().substring(0, 10) + "-" + safe);
    }

    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private final class SessionImpl implements Session {
        private final String container;
        private final ChangeTask task;
        private final Egress egress;
        private final EphemeralSecretProvider.SecretLease secretLease;
        private final Instant deadline;
        private final Map<String, String> secretPaths;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SessionImpl(String container, ChangeTask task, Egress egress,
                            EphemeralSecretProvider.SecretLease secretLease) {
            this.container = container;
            this.task = task;
            this.egress = egress;
            this.secretLease = secretLease;
            this.deadline = clock.instant().plusSeconds(config.taskTimeoutSeconds());
            java.util.LinkedHashMap<String, String> paths = new java.util.LinkedHashMap<>();
            secretLease.filesByEnvironmentVariable().keySet().stream().sorted().forEach(name ->
                    paths.put(name, "/run/paichange-secrets/" + name.toLowerCase(Locale.ROOT)));
            this.secretPaths = Map.copyOf(paths);
        }

        private void installSecrets() throws Exception {
            for (Map.Entry<String, byte[]> entry : secretLease.filesByEnvironmentVariable().entrySet()) {
                if (secretLease.expired(clock.instant())) throw new IllegalStateException("任务 Secret lease 已失效");
                String path = secretPaths.get(entry.getKey());
                DockerResult result = runner.run(List.of(config.dockerBinary(), "exec", "-i", container,
                        "sh", "-c", "umask 077 && cat > '" + path + "'"), entry.getValue(), CONTROL_TIMEOUT);
                requireSuccess(result, "向容器注入短期 Secret 失败");
            }
        }

        @Override public boolean isolated() { return true; }
        @Override public Duration taskTimeout() { return Duration.ofSeconds(config.taskTimeoutSeconds()); }

        @Override
        public CommandExecutionResult executeCommand(String command, Duration timeout) {
            String normalized = command == null ? "" : command.trim();
            if (closed.get()) return CommandExecutionResult.canceled(normalized, "隔离容器已关闭");
            if (secretLease.expired(clock.instant())) {
                abort("Secret lease 失效");
                return CommandExecutionResult.canceled(normalized, "Secret lease 已失效，容器已清理");
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                abort("任务超时");
                return CommandExecutionResult.timedOut(normalized, "任务总时限已到");
            }
            Duration effective = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? remaining : (timeout.compareTo(remaining) < 0 ? timeout : remaining);
            Duration secretRemaining = Duration.between(clock.instant(), secretLease.expiresAt());
            if (secretRemaining.compareTo(effective) < 0) effective = secretRemaining;
            List<String> exec = new ArrayList<>(List.of(config.dockerBinary(), "exec", "--workdir", "/workspace"));
            secretPaths.forEach((name, path) -> exec.addAll(List.of("--env", name + "=" + path)));
            exec.addAll(List.of(container, "sh", "-c", normalized));
            try {
                DockerResult result = runner.run(exec, null, effective);
                if (result.timedOut()) {
                    abort("命令或任务超时");
                    return CommandExecutionResult.timedOut(normalized, result.output());
                }
                if (result.interrupted()) {
                    abort("命令被取消");
                    return CommandExecutionResult.canceled(normalized, "Worker 执行被取消，容器已清理");
                }
                if (closed.get()) {
                    return CommandExecutionResult.canceled(normalized, "隔离容器已关闭，命令进程树已清理");
                }
                return CommandExecutionResult.completed(normalized, result.exitCode(), result.output());
            } catch (Exception error) {
                if (closed.get()) {
                    return CommandExecutionResult.canceled(normalized, "隔离容器已关闭，命令进程树已清理");
                }
                abort("Docker exec 失败");
                return CommandExecutionResult.startError(normalized, compact(error.getMessage()));
            }
        }

        @Override
        public NetworkDecision networkDecision(String toolName, String argumentsJson) {
            if (!isNetworkTool(toolName)) return NetworkDecision.allow();
            if (!egress.enabled()) return NetworkDecision.deny("M6a 默认拒绝网络出口；项目未声明 proxy-only egress");
            if (!egress.allowedTools().contains(toolName)) {
                return NetworkDecision.deny("项目 egress 未放行工具 " + toolName);
            }
            if ("web_fetch".equals(toolName)) {
                try {
                    JsonNode args = ChangeJson.MAPPER.readTree(argumentsJson);
                    String host = URI.create(args.path("url").asText()).getHost();
                    if (host == null || !egress.allowedHosts().contains(host.toLowerCase(Locale.ROOT))) {
                        return NetworkDecision.deny("目标主机不在项目 egress allowlist");
                    }
                } catch (Exception e) {
                    return NetworkDecision.deny("无法验证联网工具目标主机");
                }
            }
            return NetworkDecision.allow();
        }

        @Override
        public void ensureHealthy() throws Exception {
            if (closed.get()) throw new IllegalStateException("隔离 Worker 容器已提前退出或被清理");
            if (secretLease.expired(clock.instant())) {
                abort("Secret lease 失效");
                throw new IllegalStateException("任务 Secret lease 已失效，隔离容器已清理");
            }
            DockerResult result = runner.run(List.of(config.dockerBinary(), "inspect", "--format",
                    "{{.State.Running}}", container), null, CONTROL_TIMEOUT);
            requireSuccess(result, "无法确认隔离 Worker 容器状态");
            if (!"true".equals(result.output().trim())) throw new IllegalStateException("隔离 Worker 容器已提前退出");
        }

        @Override
        public void abort(String reason) {
            if (!closed.compareAndSet(false, true)) return;
            try { runner.run(List.of(config.dockerBinary(), "rm", "-f", container), null, CONTROL_TIMEOUT); }
            catch (Exception ignored) { }
            secretLease.close();
            active.remove(this);
        }

        @Override public void close() { abort("任务完成"); }
    }

    private static boolean isNetworkTool(String name) {
        return "web_search".equals(name) || "web_fetch".equals(name)
                || (name != null && name.startsWith("mcp__"));
    }

    record Config(boolean enabled, String dockerBinary, String image, double cpus, int memoryMb,
                  int pidsLimit, int taskTimeoutSeconds, String user, String owner,
                  Map<String, Egress> egressByProject, boolean secretInjectionEnabled) {
        static Config from(Path controlRoot, PaiCliConfig rootConfig) {
            return from(controlRoot, rootConfig, false);
        }

        static Config from(Path controlRoot, PaiCliConfig rootConfig, boolean secretInjectionEnabled) {
            PaiCliConfig.DockerIsolationConfig value = rootConfig.getPaiChange().getDockerIsolation();
            boolean enabled = booleanSetting("paichange.docker.enabled", "PAICHANGE_DOCKER_ENABLED", value.isEnabled());
            String image = textSetting("paichange.docker.image", "PAICHANGE_DOCKER_IMAGE", value.getImage());
            Map<String, Egress> egress = new java.util.LinkedHashMap<>();
            value.getProjectEgress().forEach((project, item) -> egress.put(project,
                    Egress.from(item)));
            return new Config(enabled, "docker", image, value.getCpus(), value.getMemoryMb(), value.getPidsLimit(),
                    value.getTaskTimeoutSeconds(), value.getUser().isBlank() ? "65532:65532" : value.getUser().trim(),
                    sha256(controlRoot.toAbsolutePath().normalize().toString()).substring(0, 20), Map.copyOf(egress),
                    secretInjectionEnabled);
        }

        void validate() {
            if (!enabled) return;
            if (image == null || image.isBlank()) throw new IllegalArgumentException("启用 M6a 必须配置 Docker image");
            if (!image.matches("(?:[A-Za-z0-9][A-Za-z0-9._/:\\-]*@)?sha256:[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException("M6a Docker image 必须固定到 sha256 digest");
            }
            if (cpus < 0.1 || cpus > 16) throw new IllegalArgumentException("Docker CPU 限制必须为 0.1..16");
            if (memoryMb < 128 || memoryMb > 32768) throw new IllegalArgumentException("Docker 内存限制必须为 128..32768 MiB");
            if (pidsLimit < 16 || pidsLimit > 1024) throw new IllegalArgumentException("Docker pids 限制必须为 16..1024");
            if (taskTimeoutSeconds < 30 || taskTimeoutSeconds > 3600) throw new IllegalArgumentException("任务超时必须为 30..3600 秒");
            if (user == null || !user.matches("[1-9][0-9]*:[1-9][0-9]*")) {
                throw new IllegalArgumentException("Docker Worker 必须配置非 root 数字 uid:gid");
            }
            egressByProject.forEach((project, egress) -> egress.validate(project));
        }

        Egress egress(ChangeTask task) {
            return egressByProject.getOrDefault(ChangeProject.id(task.repository()), Egress.none());
        }

        String uid() { return user.substring(0, user.indexOf(':')); }
        String gid() { return user.substring(user.indexOf(':') + 1); }

        private static boolean booleanSetting(String property, String environment, boolean fallback) {
            String value = textSetting(property, environment, "");
            return value.isBlank() ? fallback : Boolean.parseBoolean(value);
        }

        private static String textSetting(String property, String environment, String fallback) {
            String value = System.getProperty(property);
            if (value == null || value.isBlank()) value = System.getenv(environment);
            return value == null || value.isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
        }
    }

    record Egress(String network, String proxyUrl, Set<String> allowedHosts, Set<String> allowedTools) {
        static Egress from(PaiCliConfig.DockerEgressConfig value) {
            Set<String> hosts = new HashSet<>();
            value.getAllowedHosts().forEach(host -> hosts.add(host.trim().toLowerCase(Locale.ROOT)));
            Set<String> tools = new HashSet<>();
            value.getAllowedTools().forEach(tool -> tools.add(tool.trim()));
            return new Egress(value.getNetwork().trim(), value.getProxyUrl().trim(), Set.copyOf(hosts), Set.copyOf(tools));
        }
        static Egress none() { return new Egress("", "", Set.of(), Set.of()); }
        boolean enabled() { return !network.isBlank(); }
        String policyDigest() {
            String material = proxyUrl + "\n"
                    + allowedHosts.stream().sorted().collect(java.util.stream.Collectors.joining(",")) + "\n"
                    + allowedTools.stream().sorted().collect(java.util.stream.Collectors.joining(","));
            return DockerWorkerIsolation.sha256(material);
        }
        void validate(String project) {
            if (project == null || project.isBlank()) throw new IllegalArgumentException("egress projectId 不能为空");
            if (!enabled()) {
                if (!proxyUrl.isBlank() || !allowedHosts.isEmpty() || !allowedTools.isEmpty())
                    throw new IllegalArgumentException("未配置 network 时不能声明 egress 放行");
                return;
            }
            if (!network.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("egress network 名称无效");
            }
            if (Set.of("bridge", "host", "default", "none").contains(network.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("显式放行不得使用 Docker 默认/host 网络");
            }
            URI proxy;
            try { proxy = URI.create(proxyUrl); }
            catch (Exception e) { throw new IllegalArgumentException("egress proxyUrl 无效", e); }
            if (!("http".equals(proxy.getScheme()) || "https".equals(proxy.getScheme()))
                    || proxy.getHost() == null || proxy.getUserInfo() != null || proxy.getQuery() != null
                    || proxy.getFragment() != null
                    || (proxy.getPath() != null && !proxy.getPath().isBlank() && !"/".equals(proxy.getPath()))) {
                throw new IllegalArgumentException("egress proxyUrl 必须是无凭据、query、fragment 或业务路径的 HTTP(S) 地址");
            }
            if (allowedHosts.stream().anyMatch(host -> host.isBlank() || host.equals("*")
                    || !host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                    || host.matches("[0-9.]+")
                    || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("169.254.169.254")
                    || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan")
                    || host.endsWith(".home") || host.endsWith(".internal"))) {
                throw new IllegalArgumentException("egress allowlist 只接受显式公网域名，不得包含通配、IP、宿主或元数据地址");
            }
            if (allowedTools.stream().anyMatch(tool -> !isNetworkTool(tool))) {
                throw new IllegalArgumentException("egress allowedTools 只接受 web/MCP 工具");
            }
        }
    }

    interface DockerCommandRunner {
        DockerResult run(List<String> command, byte[] stdin, Duration timeout) throws Exception;
    }

    record DockerResult(int exitCode, String output, boolean timedOut, boolean interrupted) { }

    static final class ProcessDockerCommandRunner implements DockerCommandRunner {
        @Override
        public DockerResult run(List<String> command, byte[] stdin, Duration timeout) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (stdin != null) {
                try (OutputStream output = process.getOutputStream()) { output.write(stdin); }
            } else {
                process.getOutputStream().close();
            }
            ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "paichange-docker-output"); thread.setDaemon(true); return thread;
            });
            Future<String> output = reader.submit(() -> readOutput(process));
            try {
                boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) {
                    terminate(process); output.cancel(true);
                    return new DockerResult(-1, "Docker CLI 超时", true, false);
                }
                String text;
                try { text = output.get(2, TimeUnit.SECONDS); }
                catch (TimeoutException e) { output.cancel(true); text = "Docker CLI 输出读取超时"; }
                return new DockerResult(process.exitValue(), text, false, false);
            } catch (InterruptedException e) {
                terminate(process); output.cancel(true); Thread.currentThread().interrupt();
                return new DockerResult(-1, "Docker CLI 被中断", false, true);
            } finally {
                reader.shutdownNow();
            }
        }

        private static String readOutput(Process process) throws Exception {
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096]; int read;
                while ((read = reader.read(buffer)) >= 0 && out.length() < MAX_OUTPUT_BYTES) {
                    out.append(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - out.length()));
                }
            }
            return out.toString();
        }

        private static void terminate(Process process) {
            try {
                List<ProcessHandle> children = process.descendants().toList();
                for (int i = children.size() - 1; i >= 0; i--) children.get(i).destroyForcibly();
            } catch (RuntimeException ignored) {
                // Some restricted macOS environments deny process-tree inspection. The task container is
                // independently removed with `docker rm -f`, which is the authoritative cleanup boundary.
            }
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
