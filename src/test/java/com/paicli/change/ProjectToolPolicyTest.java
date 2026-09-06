package com.paicli.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectToolPolicyTest {
    @TempDir Path root;

    @Test
    void everyProfileHasAllowApprovalAndDenyCases() {
        ProjectToolPolicy policy = ProjectToolPolicy.defaults("project", Instant.EPOCH);

        assertEquals(ProjectToolPolicy.Effect.ALLOW, decision(policy, "STANDARD", "read_file", "{\"path\":\"README.md\"}"));
        assertEquals(ProjectToolPolicy.Effect.REQUIRE_APPROVAL, decision(policy, "STANDARD", "web_fetch", "{\"url\":\"https://example.test\"}"));
        assertEquals(ProjectToolPolicy.Effect.DENY, decision(policy, "STANDARD", "revert_turn", "{}"));

        assertEquals(ProjectToolPolicy.Effect.ALLOW, decision(policy, "RESTRICTED", "grep_code", "{\"pattern\":\"x\"}"));
        assertEquals(ProjectToolPolicy.Effect.REQUIRE_APPROVAL, decision(policy, "RESTRICTED", "write_file", "{\"path\":\"x\",\"content\":\"y\"}"));
        assertEquals(ProjectToolPolicy.Effect.DENY, decision(policy, "RESTRICTED", "web_search", "{\"query\":\"x\"}"));

        assertEquals(ProjectToolPolicy.Effect.ALLOW, decision(policy, "LOCKED_DOWN", "list_dir", "{\"path\":\".\"}"));
        assertEquals(ProjectToolPolicy.Effect.REQUIRE_APPROVAL, decision(policy, "LOCKED_DOWN", "execute_command", "{\"command\":\"mvn test\"}"));
        assertEquals(ProjectToolPolicy.Effect.DENY, decision(policy, "LOCKED_DOWN", "mcp__github__create_issue", "{}"));
    }

    @Test
    void rulesMatchArgumentsCommandsMcpAndWorkingDirectoryWithDenyPriority() {
        ProjectToolPolicy.Rule allowExact = new ProjectToolPolicy.Rule("allow-tests",
                Set.of(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN), ProjectToolPolicy.Effect.ALLOW,
                "execute_command", "mvn test -Dtest=[A-Za-z]+Test", "", "", root.toString(),
                Map.of("command", "mvn test.*"));
        ProjectToolPolicy.Rule denySecret = new ProjectToolPolicy.Rule("deny-secret",
                Set.of(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN), ProjectToolPolicy.Effect.DENY,
                "execute_command", ".*SECRET.*", "", "", root.toString(), Map.of());
        ProjectToolPolicy.Rule approveMcp = new ProjectToolPolicy.Rule("approve-github-read",
                Set.of(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN), ProjectToolPolicy.Effect.REQUIRE_APPROVAL,
                "mcp__github__get_issue", "", "github", "get_issue", root.toString(), Map.of("owner", "openai"));
        ProjectToolPolicy policy = new ProjectToolPolicy("project", 7,
                List.of(allowExact, denySecret, approveMcp), Instant.EPOCH);

        assertEquals(ProjectToolPolicy.Effect.ALLOW,
                policy.evaluate(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, "execute_command",
                        "{\"command\":\"mvn test -Dtest=FooTest\"}", root).effect());
        assertEquals(ProjectToolPolicy.Effect.DENY,
                policy.evaluate(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, "execute_command",
                        "{\"command\":\"mvn test -Dtest=SECRETTest\"}", root).effect());
        assertEquals(ProjectToolPolicy.Effect.REQUIRE_APPROVAL,
                policy.evaluate(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, "mcp__github__get_issue",
                        "{\"owner\":\"openai\"}", root).effect());
        assertEquals(ProjectToolPolicy.Effect.DENY,
                policy.evaluate(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, "mcp__github__get_issue",
                        "{\"owner\":\"elsewhere\"}", root.resolve("other")).effect());
    }

    private ProjectToolPolicy.Effect decision(ProjectToolPolicy policy, String profile, String tool, String args) {
        return policy.evaluate(ExecutionRoute.ToolPolicyProfile.valueOf(profile), tool, args, root).effect();
    }
}
