package com.paicli.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class ScmPublicationKey {
    private ScmPublicationKey() { }

    static String compute(ChangeTask task) {
        try {
            String identity = ChangeJson.MAPPER.writeValueAsString(List.of(task.id().value(), task.run().specDigest(),
                    task.run().headSha(), task.run().runId(), task.judgmentRevision(),
                    task.deliveryApproval() == null ? "" : task.deliveryApproval().id()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 SCM 发布身份失败", e);
        }
    }
}
