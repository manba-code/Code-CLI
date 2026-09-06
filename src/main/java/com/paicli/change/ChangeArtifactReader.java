package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.spec.ChangeSpecCodec;
import com.paicli.spec.ChangeSpecDocument;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.Constants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read only, bounded artifacts resolved exclusively from persisted ChangeTask associations. */
public final class ChangeArtifactReader {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private record Root(Path configured, Path real) { }
    private final List<Root> roots;
    private final EvidenceStore integrity;

    public ChangeArtifactReader(Path... roots) throws IOException {
        this(null, roots);
    }

    public ChangeArtifactReader(EvidenceStore integrity, Path... roots) throws IOException {
        this.integrity = integrity;
        List<Root> trusted = new ArrayList<>();
        for (Path root : roots) {
            Files.createDirectories(root);
            trusted.add(new Root(root.toAbsolutePath().normalize(), root.toRealPath()));
        }
        this.roots = List.copyOf(trusted);
    }

    public ObjectNode read(ChangeTaskView view, Integer from, Integer to) throws IOException {
        ChangeTask task = view.task();
        ObjectNode out = ChangeJson.MAPPER.createObjectNode();
        out.put("changeId", task.id().value());
        out.put("version", task.version());
        var revisions = out.putArray("revisions");
        out.putNull("draft"); out.putNull("lockedSpec"); out.putNull("revisionDiff");
        out.putNull("codeDiff"); out.putNull("result");
        out.put("evidenceIntegrity", integrity == null ? "NOT_MEASURED" : "NOT_APPLICABLE");
        out.putNull("evidenceManifestSha256");
        out.putArray("verifiers"); out.putArray("criteria");
        out.putArray("criterionResults"); out.putArray("verificationAttempts"); out.putArray("evidence");
        if (task.spec() != null) {
            SpecRef spec = task.spec();
            if (!spec.specId().matches("[A-Za-z0-9_-]+")) throw new ChangeForbiddenException("非法 Spec 文件身份");
            Set<String> associatedDigests = new HashSet<>();
            Map<Integer, Path> associatedPaths = new HashMap<>();
            for (ChangeEvent event : view.events()) {
                if (event.type().equals("spec.draft_generated")) {
                    JsonNode payload = ChangeJson.MAPPER.readTree(event.payloadJson());
                    associatedDigests.add(payload.path("specDigest").asText());
                    if (payload.hasNonNull("draftPath") && payload.has("revision")) {
                        associatedPaths.put(payload.path("revision").asInt(), Path.of(payload.path("draftPath").asText()));
                    }
                }
            }
            Map<Integer, String> bodies = new LinkedHashMap<>();
            // Older revisions belong to this task's saved Draft family and must also match its event digest.
            for (int revision = 1; revision <= spec.revision(); revision++) {
                Path file = revision == spec.revision() ? spec.draftPath()
                        : associatedPaths.getOrDefault(revision, spec.draftPath().getParent().resolve(spec.specId() + "-r" + revision + ".md"));
                String body = text(file);
                ChangeSpecDocument document = new ChangeSpecCodec().decode(body);
                if (!spec.specId().equals(document.spec().id()) || revision != document.spec().revision()
                        || !associatedDigests.contains(document.specDigest())) {
                    throw new ChangeConflictException("Draft 正文与任务关联不一致，请重新查询");
                }
                bodies.put(revision, body);
                revisions.addObject().put("revision", revision).put("digest", document.specDigest()).put("body", body);
                if (revision == spec.revision()) {
                    if (!spec.digest().equals(document.specDigest())) throw new ChangeConflictException("Draft digest 已变化");
                    out.put("draft", body);
                    out.set("verifiers", ChangeJson.MAPPER.valueToTree(document.spec().verifiers()));
                    out.set("criteria", ChangeJson.MAPPER.valueToTree(document.spec().acceptance()));
                }
            }
            if (spec.lockedPath() != null) {
                String locked = text(spec.lockedPath());
                ChangeSpecDocument document = new ChangeSpecCodec().decode(locked);
                if (!spec.digest().equals(document.specDigest()) || !spec.specId().equals(document.spec().id())
                        || spec.revision() != document.spec().revision()) throw new ChangeConflictException("锁定 Spec 身份已变化");
                out.put("lockedSpec", locked);
            }
            int left = from == null ? Math.max(1, spec.revision() - 1) : from;
            int right = to == null ? spec.revision() : to;
            if (!bodies.containsKey(left) || !bodies.containsKey(right)) throw new ChangeNotFoundException(task.id());
            out.put("fromRevision", left); out.put("toRevision", right);
            out.put("revisionDiff", diff(bodies.get(left), bodies.get(right), left, right));
        } else if (from != null || to != null) {
            throw new ChangeNotFoundException(task.id());
        }
        if (task.run() != null) {
            RunRef run = task.run();
            if (integrity != null) {
                integrity.verify(task.id(), run.runId(), run.evidencePath());
                out.put("evidenceIntegrity", "VERIFIED");
                out.put("evidenceManifestSha256", integrity.manifestSha256(task.id(), run.runId()));
            }
            JsonNode result;
            try { result = ChangeJson.MAPPER.readTree(text(run.evidencePath().resolve("result.json"))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IOException("持久化 Evidence JSON 损坏", e); }
            if (result == null || !result.isObject() || !result.path("criterionResults").isArray()
                    || !result.path("verificationAttempts").isArray() || !run.runId().equals(result.path("runId").asText())
                    || !run.specDigest().equals(result.path("spec").path("digest").asText())
                    || !run.status().name().equals(result.path("status").asText())
                    || !run.verdict().name().equals(result.path("verdict").asText())) {
                throw new ChangeConflictException("Evidence 身份与任务不一致");
            }
            out.set("result", result);
            out.set("criterionResults", result.path("criterionResults"));
            out.set("verificationAttempts", result.path("verificationAttempts"));
            var evidence = out.putArray("evidence");
            for (JsonNode attempt : result.path("verificationAttempts")) {
                for (JsonNode verifier : attempt.path("verifierResults")) evidence.add(verifier);
            }
            for (JsonNode human : result.path("humanEvidence")) evidence.add(human);
            out.put("codeDiff", text(run.evidencePath().resolve("change.diff")));
        }
        var refs = out.putArray("artifactRefs");
        if (task.spec() != null && task.spec().locked()) refs.addObject().put("id", "locked-spec").put("label", "锁定 Spec");
        if (task.run() != null) {
            refs.addObject().put("id", "run-result").put("label", "原始 Run / Verdict");
            refs.addObject().put("id", "code-diff").put("label", "当前运行代码 diff");
            for (JsonNode evidence : out.path("evidence")) {
                String id = evidence.path("evidenceId").asText();
                if (!id.isBlank()) refs.addObject().put("id", "evidence:" + id).put("label", id);
            }
        }
        out.set("humanReview", ChangeJson.MAPPER.valueToTree(task.humanReview()));
        return out;
    }

    private String text(Path path) throws IOException {
        if (path == null) throw new IOException("Artifact 关联缺失");
        // Resolve parent aliases (e.g. macOS /var) only when outside the configured trust anchor.
        Path candidate = path.toAbsolutePath().normalize();
        Root anchor = roots.stream().filter(r -> candidate.startsWith(r.configured()) || candidate.startsWith(r.real()))
                .findFirst().orElse(null);
        if (anchor == null) throw new ChangeForbiddenException("Artifact 不属于平台产物目录");
        if (!anchor.configured().toRealPath().equals(anchor.real())) throw new ChangeForbiddenException("Artifact 根目录已变化");
        Path root = anchor.real();
        Path resolved = candidate.startsWith(root) ? candidate : root.resolve(anchor.configured().relativize(candidate));
        Path cursor = root;
        for (Path part : root.relativize(resolved)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new ChangeForbiddenException("Artifact 不允许符号链接");
        }
        if (!resolved.toRealPath().startsWith(root)) throw new ChangeForbiddenException("Artifact 路径逃逸");
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artifact 不是普通文件");
        try (var input = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ChangeValidationException("Artifact 超过 4 MiB 读取上限");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String diff(String before, String after, int left, int right) throws IOException {
        RawText a = new RawText(Constants.encode(before)), b = new RawText(Constants.encode(after));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--- revision " + left + "\n+++ revision " + right + "\n").getBytes(StandardCharsets.UTF_8));
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.format(new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b), a, b);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
