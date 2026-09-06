package com.paicli.change;

import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalType;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Authorization and one-time bootstrap policy around the persistent directory. */
public final class ProjectMemberService {
    private final ProjectMembershipDirectory directory;
    private final ChangeAuthorizer authorizer;
    private final String bootstrapAdminSubject;

    public ProjectMemberService(ProjectMembershipDirectory directory, ChangeAuthorizer authorizer,
                                String bootstrapAdminSubject) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.bootstrapAdminSubject = bootstrapAdminSubject == null ? "" : bootstrapAdminSubject.trim();
    }

    public List<ProjectMember> list(String projectId, Principal actor) {
        requireHuman(actor);
        authorizer.require(actor, projectId, ChangePermission.MANAGE_MEMBERS);
        return directory.projectMembers(projectId);
    }

    public List<ProjectMemberAudit> audit(String projectId, Principal actor) {
        requireHuman(actor);
        authorizer.require(actor, projectId, ChangePermission.MANAGE_MEMBERS);
        return directory.audit(projectId);
    }

    public ProjectMemberAuditExport exportAudit(String projectId, Principal actor) {
        List<ProjectMemberAudit> entries = audit(projectId, actor);
        if (entries.size() > 100_000) throw new ChangeValidationException("成员审计导出超过 100000 条上限");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (ProjectMemberAudit entry : entries) {
                byte[] line = ChangeJson.MAPPER.writeValueAsBytes(entry);
                if ((long) out.size() + line.length + 1 > 32L * 1024 * 1024) {
                    throw new ChangeValidationException("成员审计导出超过 32 MiB 上限");
                }
                out.write(line);
                out.write('\n');
            }
            byte[] content = out.toByteArray();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            return new ProjectMemberAuditExport(content, entries.size(), hash);
        } catch (ChangeValidationException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("成员审计导出失败", e); }
    }

    public ProjectMember put(String projectId, String subjectId, PrincipalType type, Set<ProjectRole> roles,
                             long expectedVersion, Principal actor) {
        requireHuman(actor);
        if (bootstrapEligible(projectId, actor)) {
            if (expectedVersion != 0 || !actor.subjectId().equals(subjectId) || type != PrincipalType.HUMAN
                    || !roles.equals(Set.of(ProjectRole.PROJECT_ADMIN))) {
                throw new ChangeForbiddenException(
                        "首次初始化只能由配置的 bootstrap admin 将自己设为唯一 PROJECT_ADMIN");
            }
            return directory.bootstrap(projectId, actor);
        }
        authorizer.require(actor, projectId, ChangePermission.MANAGE_MEMBERS);
        return directory.put(projectId, subjectId, type, roles, expectedVersion, actor);
    }

    public ProjectMember remove(String projectId, String subjectId, long expectedVersion, Principal actor) {
        requireHuman(actor);
        authorizer.require(actor, projectId, ChangePermission.MANAGE_MEMBERS);
        return directory.remove(projectId, subjectId, expectedVersion, actor);
    }

    private boolean bootstrapEligible(String projectId, Principal actor) {
        return !bootstrapAdminSubject.isEmpty() && bootstrapAdminSubject.equals(actor.subjectId())
                && !directory.hasMembers(projectId);
    }

    private static void requireHuman(Principal actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() == PrincipalType.SERVICE) {
            throw new ChangeForbiddenException("SERVICE 主体不能管理项目成员");
        }
    }
}
