package com.paicli.spec;

/**
 * ChangeSpec 文件的解析结果。Digest 只绑定机器契约，不绑定 Markdown 说明。
 */
public record ChangeSpecDocument(
        ChangeSpec spec,
        String markdownBody,
        String specDigest
) {
}
