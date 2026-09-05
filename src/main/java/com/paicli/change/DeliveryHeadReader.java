package com.paicli.change;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 发布前只读核对 Worker 封存分支，不执行远程 SCM 操作。 */
@FunctionalInterface
public interface DeliveryHeadReader {
    String currentHead(ChangeTask task) throws IOException;

    static DeliveryHeadReader localGit() {
        return task -> {
            Process process = new ProcessBuilder("git", "-C", task.repository().repository(),
                    "rev-parse", "--verify", "refs/heads/" + task.run().branch() + "^{commit}")
                    .redirectErrorStream(true).start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("读取交付分支超时");
                if (process.exitValue() != 0) throw new ChangeConflictException("交付分支已不存在或无法解析");
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读取交付分支被中断", e);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(child -> child.destroyForcibly());
                    process.destroyForcibly();
                }
            }
        };
    }
}
