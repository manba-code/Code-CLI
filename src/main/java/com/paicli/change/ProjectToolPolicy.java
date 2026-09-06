package com.paicli.change;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Versioned project policy layered over the selected ExecutionRoute profile. */
public record ProjectToolPolicy(
        String projectId,
        long version,
        List<Rule> rules,
        Instant updatedAt
) {
    private static final Set<String> LOCAL_READS = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code");

    public ProjectToolPolicy {
        projectId = requireText(projectId, "projectId");
        if (version < 1) throw new IllegalArgumentException("tool policy version 必须 >= 1");
        rules = rules == null ? List.of() : List.copyOf(rules);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (rules.size() > 200) throw new IllegalArgumentException("tool policy rule 最多 200 条");
    }

    public static ProjectToolPolicy defaults(String projectId, Instant now) {
        return new ProjectToolPolicy(projectId, 1, List.of(), now);
    }

    public Decision evaluate(
            ExecutionRoute.ToolPolicyProfile profile,
            String toolName,
            String argumentsJson,
            Path workingDirectory
    ) {
        Objects.requireNonNull(profile, "profile");
        String tool = requireText(toolName, "toolName");
        Path cwd = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        JsonNode arguments = parseArguments(argumentsJson);

        Decision matched = null;
        for (Rule rule : rules) {
            if (!rule.matches(profile, tool, arguments, cwd)) continue;
            Decision candidate = new Decision(rule.effect(), "项目规则 " + rule.id(), version, rule.id());
            if (matched == null || candidate.effect().severity() > matched.effect().severity()) matched = candidate;
        }
        if (matched != null) return matched;
        return baseline(profile, tool, arguments);
    }

    private Decision baseline(ExecutionRoute.ToolPolicyProfile profile, String tool, JsonNode arguments) {
        if (LOCAL_READS.contains(tool)) return decision(Effect.ALLOW, "本地只读工具");
        if ("write_file".equals(tool)) {
            return decision(profile == ExecutionRoute.ToolPolicyProfile.STANDARD
                    ? Effect.ALLOW : Effect.REQUIRE_APPROVAL, "文件写入受 profile 管理");
        }
        if ("execute_command".equals(tool)) {
            String command = arguments.path("command").asText("").trim();
            if (command.isEmpty() || command.length() > 4096) {
                return decision(Effect.DENY, "命令为空或超过策略长度上限");
            }
            return decision(profile == ExecutionRoute.ToolPolicyProfile.STANDARD
                    ? Effect.ALLOW : Effect.REQUIRE_APPROVAL, "Shell 命令受 profile 管理");
        }
        if ("web_search".equals(tool) || "web_fetch".equals(tool)) {
            return decision(profile == ExecutionRoute.ToolPolicyProfile.STANDARD
                    ? Effect.REQUIRE_APPROVAL : Effect.DENY, "联网工具受 profile 管理");
        }
        if (tool.startsWith("mcp__")) {
            return decision(profile == ExecutionRoute.ToolPolicyProfile.STANDARD
                    ? Effect.REQUIRE_APPROVAL : Effect.DENY, "MCP 工具受 profile 管理");
        }
        return decision(Effect.DENY, "未知或未列入 Worker 白名单的工具默认拒绝");
    }

    private Decision decision(Effect effect, String reason) {
        return new Decision(effect, reason, version, "profile-default");
    }

    private static JsonNode parseArguments(String json) {
        try {
            JsonNode parsed = ChangeJson.MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
            return parsed;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("工具参数不是有效 JSON", e);
        }
    }

    public enum Effect {
        ALLOW(1), REQUIRE_APPROVAL(2), DENY(3);
        private final int severity;
        Effect(int severity) { this.severity = severity; }
        int severity() { return severity; }
    }

    public record Decision(Effect effect, String reason, long policyVersion, String ruleId) {
        public Decision {
            effect = Objects.requireNonNull(effect, "effect");
            reason = requireText(reason, "reason");
            if (policyVersion < 1) throw new IllegalArgumentException("policyVersion 必须 >= 1");
            ruleId = requireText(ruleId, "ruleId");
        }
    }

    /** Optional project override. If several rules match, DENY wins, then approval, then allow. */
    public record Rule(
            String id,
            Set<ExecutionRoute.ToolPolicyProfile> profiles,
            Effect effect,
            String tool,
            String commandPattern,
            String mcpServer,
            String mcpTool,
            String workingDirectoryPrefix,
            Map<String, String> argumentPatterns
    ) {
        public Rule {
            id = requireText(id, "rule.id");
            profiles = profiles == null || profiles.isEmpty()
                    ? Set.of(ExecutionRoute.ToolPolicyProfile.values()) : Set.copyOf(profiles);
            effect = Objects.requireNonNull(effect, "rule.effect");
            tool = requireText(tool, "rule.tool");
            commandPattern = normalizePattern(commandPattern, "commandPattern");
            mcpServer = optional(mcpServer);
            mcpTool = optional(mcpTool);
            workingDirectoryPrefix = optional(workingDirectoryPrefix);
            if (!workingDirectoryPrefix.isEmpty() && !Path.of(workingDirectoryPrefix).isAbsolute()) {
                throw new IllegalArgumentException("workingDirectoryPrefix 必须是绝对路径");
            }
            argumentPatterns = argumentPatterns == null ? Map.of() : Map.copyOf(argumentPatterns);
            if (argumentPatterns.size() > 32) throw new IllegalArgumentException("argumentPatterns 最多 32 项");
            argumentPatterns.forEach((key, value) -> {
                requireText(key, "argumentPatterns key");
                normalizePattern(value, "argumentPatterns value");
            });
            if ((!mcpServer.isEmpty() || !mcpTool.isEmpty()) && !tool.startsWith("mcp__") && !"*".equals(tool)) {
                throw new IllegalArgumentException("MCP 限定只能用于 mcp__* 或通配工具规则");
            }
        }

        boolean matches(ExecutionRoute.ToolPolicyProfile profile, String name, JsonNode arguments, Path cwd) {
            if (!profiles.contains(profile) || !("*".equals(tool) || tool.equals(name))) return false;
            if (!workingDirectoryPrefix.isEmpty()) {
                Path prefix = Path.of(workingDirectoryPrefix).toAbsolutePath().normalize();
                if (!cwd.startsWith(prefix)) return false;
            }
            if (!commandPattern.isEmpty()
                    && !Pattern.matches(commandPattern, arguments.path("command").asText(""))) return false;
            if (!mcpServer.isEmpty() || !mcpTool.isEmpty()) {
                String[] parts = name.split("__", 3);
                if (parts.length != 3) return false;
                if (!mcpServer.isEmpty() && !mcpServer.equals(parts[1])) return false;
                if (!mcpTool.isEmpty() && !mcpTool.equals(parts[2])) return false;
            }
            for (Map.Entry<String, String> entry : argumentPatterns.entrySet()) {
                if (!Pattern.matches(entry.getValue(), arguments.path(entry.getKey()).asText(""))) return false;
            }
            return true;
        }

        private static String normalizePattern(String value, String name) {
            String normalized = optional(value);
            if (normalized.length() > 512) throw new IllegalArgumentException(name + " 最长 512 字符");
            if (!normalized.isEmpty()) {
                try { Pattern.compile(normalized); }
                catch (PatternSyntaxException e) { throw new IllegalArgumentException(name + " 不是有效正则", e); }
            }
            return normalized;
        }
    }

    private static String optional(String value) { return value == null ? "" : value.trim(); }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }
}
