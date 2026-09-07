package com.paicli.change;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared credential-safe Git push primitive for configured SCM adapters. */
final class GitBranchPusher {
    private GitBranchPusher() { }

    static void push(Path repository, String remote, String branch, String headSha,
                     String httpUser, String token, String provider) throws Exception {
        String localHead = git(repository, "读取本地任务分支失败", "rev-parse", "--verify",
                "refs/heads/" + branch + "^{commit}").trim();
        if (!headSha.equals(localHead)) throw new ChangeConflictException("本地任务分支 headSha 已变化");
        String remoteUrl = git(repository, "读取 Git remote 失败", "remote", "get-url", remote).trim();
        ProcessBuilder builder = new ProcessBuilder("git", "-C", repository.toString(), "push", "--porcelain",
                remote, "refs/heads/" + branch + ":refs/heads/" + branch);
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            if (URI.create(remoteUrl).getUserInfo() != null) {
                throw new ChangeValidationException(provider + " remote URL 不得内嵌凭据");
            }
            String basic = Base64.getEncoder().encodeToString((httpUser + ":" + token)
                    .getBytes(StandardCharsets.UTF_8));
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "http.extraHeader");
            builder.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        run(builder, "推送 " + provider + " 任务分支失败");
    }

    static String git(Path repository, String error, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        return run(new ProcessBuilder(command), error);
    }

    private static String run(ProcessBuilder builder, String error) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException(error + "：命令超时");
            }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) throw new IOException(error + " (exit=" + process.exitValue() + ")");
            return new String(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(error + "：命令被中断", e);
        }
    }
}
