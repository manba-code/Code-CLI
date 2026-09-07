# PaiCLI

PaiCLI 是一个 Java Coding Agent；PaiChange 是其上的受控变更交付平台，把 Issue、ChangeSpec、验证 Evidence、人工审批和 GitHub PR / GitLab MR 串成可审计闭环。

当前进度：已完成第 16.1 期 inline 流式 TUI 形态修正、第 17 期 `LSP 诊断注入` MVP、第 18 期 `Git Side-History 快照与回滚` MVP、第 19 期 `Prompt 分层架构` MVP、第 20 期 `异步后台任务 + Runtime API` MVP、第 21 期 `图片复制粘贴输入` MVP、第 23 期 `微信 iLink 通道` 文本 MVP。

新安装或没有 `~/.paicli/config.json` 时默认使用 `deepseek / DeepSeek-V4-pro`；已有持久配置继续优先，仍可通过 `/model ...` 或 `/config provider ...` 切换模型。

## 测试策略

日常开发不需要每次都跑全量测试。`mvn clean package` 默认跳过测试，优先产出可手工验收的 jar；需要回归时按改动范围选择：

```bash
# 第 16 期终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 真实 LLM Agent A/B 质量评测（会产生网络请求和 Token 费用，默认不运行）
mvn test -Pagent-eval

# ChangeSpec A/B/C 评测（默认 13 个任务 × 3 组 × 2 次 = 78 次；会产生 Token 费用）
mvn test -Pchange-spec-eval

# 代码搜索 deterministic golden set
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# 发版或大范围重构前再跑全量
mvn test -DskipTests=false
```

`agent-eval` 会在隔离工作区中用隐藏测试比较 ReAct、Plan-and-Execute 和 Multi-Agent，
生成成功率、调用次数、Token、耗时与 Reviewer 误放行/误拒报告。历史实验结果见
[`docs/agent-ab-evaluation-pilot-report-2026-08-10.md`](docs/agent-ab-evaluation-pilot-report-2026-08-10.md)。

`change-spec-eval` 独立比较 A=普通 ReAct、B=ChangeSpec 但关闭修复、C=ChangeSpec + 一次修复。
同一任务/轮次的 B/C 共用锁定 Spec digest，13 个分层 fixture（4 small / 5 medium / 4 high）使用公开 Verifier 与隐藏 Oracle；其中 `clarified-display-name` 向 A/B/C 提供完全相同的统一澄清记录，
报告使用 `PASS / FAIL / NOT_EVALUABLE / NOT_MEASURED` 分维度判断，区分成功率天花板、零缺陷下限、
无修复机会和未采集人工时间；同时显示成功率 95% Wilson 区间、A→B/B→C/A→C 配对胜负、完成声明
覆盖率、声明内/全运行虚假率、Scope、修复机会/条件成功率，以及客观正确候选、可信产品决策、
失败实际耗时和惩罚 TTA。固定失败值只作为历史惩罚评分，不代表实际失败耗时或严格统计删失。
B/C 产品耗时包含配对 Draft；自动 Pilot 的人工总投入记为 `NOT_MEASURED`，不会把自动确认冒充真人耗时。
ReAct 耗时另行拆出 LLM 请求等待墙钟和工具批次墙钟；并行工具按整批等待时间统计，公开 Verifier 继续单列。
自动评测默认给每个 ReAct 阶段 15 次迭代和 250k Token 的安全预算，并检测重复两步工具周期，
但生产 CLI 默认预算不变。YAML 类型错误会报告具体字段路径；配对 Draft 的结构或评测语义资格失败
共用最多两次生成的纠错链路，最终仍失败时保存脱敏、单次最多 8 KiB 的 Draft 诊断并在报告中链接。
结构有效的配对 Draft 还必须声明满足任务公开证据契约的 command Verifier：命令、JUnit glob、最低执行测试数都要达标，
且每条非 scope deterministic Criterion 都要引用其中至少一个。报告显示公开证据执行数/任务下限；不合格时按 `DRAFT_INVALID` 保存诊断，不进入 B/C。Spec Run 以失败
Verifier 进入唯一一次修复时还会注入首次 changed-files 数量；若为 0，会明确要求实际使用工具修改，
不能只描述计划。评测报告把完整结束、隐藏任务失败且零改动的 Spec Run 标为
`NO_CHANGE_COMPLETION`。每个 fixture 另有一个单点错误突变，免费预检要求公开 Verifier 必须拒绝它。
任务目录和公开证据契约在历史 Pilot 后已变化，新的完整付费评测必须建立新版基线且需单独批准。自动评测协议与参数见
[`docs/change-spec-abc-evaluation.md`](docs/change-spec-abc-evaluation.md)；真人主动人时的分类、计时、分配和 CSV 模板见
[`docs/change-spec-human-effort-study.md`](docs/change-spec-human-effort-study.md)；首次 Pilot 的修复与复跑顺序见
[`docs/change-spec-pilot-remediation-checklist.md`](docs/change-spec-pilot-remediation-checklist.md)。9-session 真人可行性试跑已完成，中文更正版见 [`docs/change-spec-human-effort-feasibility-20260823-01-zh.md`](docs/change-spec-human-effort-feasibility-20260823-01-zh.md)：模式工程价值有正面证据，量化效率与质量增益尚未证明；正式实验仍未执行。

2026-08-23 的 GLM 同模型完整复跑已归档：`glm-4.6v-flashx` 下 A/B/C 成功率为
58.33%/41.67%/41.67%，配对 Draft/digest 12/12，C 虚假完成率为 0%。随后冻结三个代表任务运行
`deepseek-v4-pro-0813` 小样，A/B/C 均为 100%，说明模型/API 组合影响显著，但成功率天花板、零虚假完成
下限和零修复机会使增量质量与修复价值不可判定。DeepSeek 36 次完整复跑尚未执行；不能把任一小样
概括为“ChangeSpec 无价值”或“ChangeSpec 已提效”。

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、文件 glob、代码 grep、执行命令、创建项目、RAG 语义辅助检索、联网搜索、MCP 动态工具
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，默认项目级作用域，跨会话复用
- 项目级记忆通过 `PAI.md` / `.paicli/PAI.md` 启动自动注入，适合提交到仓库的团队共享规则；`PAI.local.md` / `.paicli/PAI.local.md` 只做本地覆盖
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时自动做摘要压缩
- 新增 `/memory` 查看状态、`/memory list/search/delete/clear` 管理长期记忆、`/save` 手动保存事实；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- project path 入库前统一规范化为宿主绝对路径；确定性测试注入 stub Embedding，不依赖本地 Ollama 或远程 API
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- `search_code` 作为语义辅助检索工具；精确代码定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查

### 第五期：Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- Worker 生命周期池默认预热 2 个实例，按独立步骤并行压力动态扩容至 4 个，批次结束后缩容回 2 个
- Worker 通过 Lease 独占租借并在归还前清理会话；Team 任务结束通过 `AutoCloseable` 显式关闭子 Agent
- 并行步骤使用独立 Reviewer，Planner / Worker / Reviewer 各自维护独立 `conversationHistory`
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### 第六期：Human-in-the-Loop + 审批流

- 危险操作静态规则识别：`write_file`、`execute_command`、`create_project`、`revert_turn`
- 三级危险等级：高危（`execute_command`）、中危（`write_file` / `create_project`）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，通过 `/hitl on` 启用
- 新增 `/hitl` CLI 命令，支持 `/hitl on`、`/hitl off`、`/hitl`（查看状态）

### 第七期：异步执行 + 并行工具调用

- 同一轮 LLM 返回多个 `tool_calls` 时，工具层会并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 都复用统一的批量工具执行入口
- 工具结果仍按原始 `tool_call` 顺序回灌，保证消息历史协议稳定
- 批量工具调用有统一超时与取消兜底，单个 `execute_command` 仍保留 60 秒命令级超时
- Plan-and-Execute 与 Multi-Agent 已支持按依赖批次并行执行独立任务

### 第八期：多模型适配 + 运行时切换

- `LlmClient` 接口抽象 + `AbstractOpenAiCompatibleClient` 模板基类
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient`、`KimiClient`、`FreeLlmApiClient`、`AgnesClient` 六个瘦实现
- 新安装默认使用 `deepseek / DeepSeek-V4-pro`，已有 `~/.paicli/config.json` 不强制迁移
- `/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 切 provider 并读取配置里的具体模型
- 配置持久化到 `~/.paicli/config.json`，API Key 可从配置、环境变量或 `.env` 读取

### 第九期：联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 当当前模型是 `step-3.7-flash*` 且自动/显式 `step_search` 远程 server 已就绪时，内置 `web_search` / `web_fetch` 会优先走 StepSearch MCP；未就绪或调用失败时自动回退到原 provider。
- ReAct 对“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题会先做一次 `web_search` 预检并注入本轮上下文，避免模型在工具可用时误说无法实时搜索；用户明确不要联网时跳过。
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### 第十期：MCP 协议核心

- 新增 `com.paicli.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.paicli/mcp.json` 与 `.paicli/mcp.json`，项目级配置按 server 名覆盖用户级配置
- MCP `${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`；检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP，显式同名配置优先
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 会清洗 `$ref` / `anyOf` / 超长 description，降低模型调用失败率
- 所有 MCP 工具默认走 HITL 审批和审计，审计参数会脱敏 token / key / password / Authorization / Bearer 凭证
- 支持 MCP resources：server 声明 `resources` capability 后，自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具
- 普通输入支持 `@server:protocol://path` 显式引用 resource，提交给 Agent 前展开为 `<resource>` 内联块
- 被动处理 `notifications/tools/list_changed`、`notifications/resources/list_changed`、`notifications/resources/updated`
- 运行中输入 `/cancel` 并回车可请求取消当前 Agent run
- CLI 命令：`/mcp`、`/mcp restart <name>`、`/mcp logs <name>`、`/mcp disable <name>`、`/mcp enable <name>`、`/mcp resources <name>`、`/mcp prompts <name>`
- `~/.paicli/mcp.json` 不存在时会自动创建默认 chrome-devtools 配置；项目级 `.paicli/mcp.json` 仍可按 server 名覆盖

### 第十二期：长上下文工程

- `LlmClient` 声明模型能力：`maxContextWindow()`、`supportsPromptCaching()`、`promptCacheMode()`
- GLM-5.1 默认 200k window，DeepSeek V4 默认 1M window，Agnes 2.0 Flash 默认 1M window，StepFun 默认 256k window，Kimi K2.6 默认 256k window，FreeLLMAPI 默认按 128k 保守预算
- `AgentBudget` 按当前模型动态计算预算，默认 `80% * maxContextWindow`，仍可用系统属性覆盖
- short / balanced / long 三种上下文模式：长上下文模式跳过摘要压缩，语义检索 topK 可提升到 20
- `search_code` 未显式传 `top_k` 时按上下文模式自适应；默认代码定位仍优先实时 grep/read
- 长上下文模式下自动把 MCP resources 的 URI / 描述索引注入 system prompt，不自动注入正文
- inline 模式下 Token / cached input tokens / 估算成本 / 耗时进入底部状态栏，避免占用正文输出区
- `/context` 会显示当前上下文模式、prompt cache 模式、RAG topK、resources 自动索引状态

### 第十三期：Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.paicli/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果会作为图片输入附加到下一轮；文本 fallback 仍保留，用于日志、人类可读摘要，以及 DeepSeek 等不接受图片块的 provider 自动降级上下文
- MCP initialize 默认超时为 60 秒；CLI 首屏默认最多等待 8 秒，超时后先进入交互，未完成的 server 保持 `starting` 并在后台继续启动，可用 `/mcp` 和 `/mcp logs <name>` 追踪

### 第十四期：CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 PaiCLI 自己创建的 tab；无法证明是 PaiCLI 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### 第十五期：Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.paicli/skills/<name>/` < 项目级 `<project>/.paicli/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，PaiCLI 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.paicli/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（PaiCLI 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### 第十六期：TUI 产品化（v16.1 形态修正后：双形态可切换）

v16.1 抽出 `Renderer` 接口 + 三个实现：

| 形态 | 启用方式 | 视觉风格 |
|---|---|---|
| **inline 流式 TUI**（默认） | 直接运行 / `PAICLI_RENDERER=inline` | Claude Code / Qoder 风格：π 主题彩色开屏、主屏直出、transcript 当前位置的 `* ` 输入提示、JLine `Status` 托管的底部 dock（YOLO/HITL、MCP、Skill、model、ctx、token、cwd 等关键字段带克制彩色高亮；ctx 是当前上下文估算，in/out/cache 是调用统计）、右侧输入提示、行内可折叠工具块（`Read 3 files (ctrl+o to expand)`）、行内 git diff、HITL 单字符 `[y/n/a/s/m]` 提示 |
| **lanterna 全屏 TUI** | `PAICLI_RENDERER=lanterna`（或兼容旧 `PAICLI_TUI=true`） | v16 三栏全屏：文件树 + 对话流 + 状态栏 + 底部输入栏，HITL 模态弹窗 |
| **plain 兜底** | `PAICLI_RENDERER=plain` | 纯 println，无折叠 / 状态栏，等价 v15 行为 |

- 三种形态共享同一套 `Agent` / `ToolRegistry` / `MemoryManager` / MCP server / SkillRegistry / HITL handler，不创建孤立空会话
- 普通输入走 ReAct；`/plan <任务>` 走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent；`/spec <需求>` 确认并锁定 ChangeSpec 后交给现有 ReAct；`/cancel` 可取消运行中任务
- 通用命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/export`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/exit`
- 对话历史保存到 `~/.paicli/history/session_*.jsonl`
- 兼容旧设置：`PAICLI_TUI=true` 自动映射为 `PAICLI_RENDERER=lanterna`（已 deprecated）
- `PAICLI_NO_STATUSBAR=true` 在 inline 模式下禁用 JLine 底部 dock（不适合 ANSI 光标控制的终端）
- `NO_COLOR=1` 禁用所有 ANSI 颜色，保留布局

### 第十七期：LSP 诊断注入（MVP）

- `write_file` 成功后触发 post-edit 诊断，诊断结果不会阻塞工具主流程
- 当前 MVP 对 Java 文件使用 JavaParser 做轻量语法诊断，不依赖本机安装 JDT LS
- ReAct、Plan-and-Execute、Multi-Agent 三条路径都会在下一轮 LLM 请求前注入 pending 诊断
- 诊断按 error / warning / info、文件、行列号、message 格式化，默认最多注入 20 条
- 配置：`PAICLI_LSP_ENABLED=false` 可关闭，`PAICLI_LSP_MAX_DIAGNOSTICS=20` 可调整注入上限
- 后续增强：接入 JDT LS / rust-analyzer / pyright / gopls 的 stdio JSON-RPC transport

### 第十八期：Git Side-History 快照与回滚（MVP）

- 每个 ReAct / Plan / Team turn 开始前创建 `pre-turn` 快照，结束后异步创建 `post-turn` 快照
- 快照仓库使用 JGit 纯 Java 实现，默认位于 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git`，不写用户项目 `.git`
- `/snapshot` 查看最近快照，`/snapshot status` 查看配置与 side-git 目录，`/snapshot clean` 清理当前项目快照目录
- `/restore <N>` 恢复到最近第 N 个 `pre-turn` 快照；恢复前会先创建 `pre-restore` 快照
- Agent 内置 `revert_turn` 工具，纳入 HITL 与 AuditLog 危险工具链
- 配置：`PAICLI_SNAPSHOT_ENABLED=false` 可关闭，`PAICLI_SNAPSHOT_MAX=50`、`PAICLI_SNAPSHOT_EXCLUDES=...`、`PAICLI_SNAPSHOT_DIR=...` 可调整策略

### 第十九期：Prompt 分层架构（MVP）

- ReAct、Plan task executor、Multi-Agent 三角色、Planner 的 system prompt 已从 Java 硬编码抽离到 `src/main/resources/prompts/`
- `PromptAssembler` 按 `base -> personality -> mode -> approval -> runtime_context -> project_context -> skills -> context_mgmt -> handoff` 组装；`runtime_context` 注入当前日期/时区，动态项目上下文靠后注入
- `project_context` 会先注入 `PAI.md` 项目记忆，再注入 `/save` 检索到的相关长期记忆和 MCP resource 索引
- 支持用户级覆盖 `~/.paicli/prompts/...`，支持项目级覆盖 `.paicli/prompts/...`，项目级优先级最高
- 覆盖是整文件替换；`base.md` 和最终 prompt 必须包含 `## Language`
- Prompt 改动审计模板见 `docs/prompt-analysis-template.md`

### 第二十期：异步后台任务 + Runtime API（MVP）

- `DurableTaskManager` 使用 SQLite 持久化后台任务队列，默认位置 `~/.paicli/tasks/tasks.db`
- 任务生命周期：`enqueued -> running -> completed / failed / canceled`
- `/task`、`/task add <任务内容>`、`/task cancel <task_id>`、`/task log <task_id>` 提供 CLI 闭环
- Worker Pool 默认 2 个后台 worker，可通过 `PAICLI_TASK_WORKERS` 调整
- `java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8080` 启动 localhost Runtime API
- Runtime API 端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`
- Runtime API 强制要求 `PAICLI_RUNTIME_API_KEY` 或 `-Dpaicli.runtime.api.key`
- 详细文档见 `docs/phase-20-runtime-api.md`

### PaiChange Phase 1–6 + M1–M8：本地 Web、GitHub/GitLab/Mock 交付与可选 Docker 隔离

`serve --http` 现在同时装配 Change API 和原有 threads API。ChangeTask、事件、审批、Worker Job 以及 SCM 发布记录保存在 `~/.paichange/changes.db`，可通过 `PAICHANGE_DATA_DIR` / `-Dpaichange.data.dir` 指定数据目录；同一目录只允许一个平台进程。工作区位置继续使用 `PAICHANGE_WORKSPACE_DIR` / `-Dpaichange.workspace.dir`，必须在源仓库外。

- `POST /v1/changes`：Mock 本地请求以 `idempotencyKey` 幂等创建，必填 `title`、`requirement`、`repository.path/baseRef`；GitLab/GitHub 模式以 `{"workItem":"<issue number>"}` 从服务端固定 project/repository 导入。requester 来自服务端认证 Principal。
- `GET /v1/changes`、`GET /v1/changes/{changeId}`：列表与详情，包含当前 Spec、风险、route、run 和 Mock delivery。
- `GET /v1/changes/{changeId}/events?after=0`：JSON 事件回放，`after` 是已读事件的 `sequence`，不包含该事件。
- `POST /v1/changes/{changeId}/spec-decisions`：`APPROVE / SUPPLEMENT / REJECT`，校验 `expectedVersion + expectedDraftDigest`。
- `POST /v1/changes/{changeId}/draft-cancel` / `draft-retry`：取消正在生成的 Draft / 显式重试生成失败；请求携带 `expectedVersion + expectedGeneration`，过期返回 409。详情的 `draftJob` 展示 generation、目标 revision、attempt、退避时间与安全失败原因。
- `POST /v1/changes/{changeId}/delivery-decisions`：`APPROVE / REJECT`，校验 `expectedVersion + expectedSpecDigest + expectedRunId + expectedHeadSha + expectedJudgmentRevision`。
- `POST /v1/changes/{changeId}/human-evidence`：按 Human Criterion 追加 PASS/FAIL/SKIPPED、理由与当前 Artifact ID，绑定同样五项身份；详情返回原始 Run 和有版本的当前交付判断。
- `GET /v1/changes/{changeId}/tool-approvals`、`POST /v1/changes/{changeId}/tool-approvals/{approvalId}/decisions`：查看脱敏 Worker 调用并以 policy version、参数 digest、callId、runId、specDigest 精确批准或拒绝。
- `GET/PUT /v1/changes/projects/{projectId}/tool-policy`：按项目读取或 CAS 更新版本化工具规则；更新需要 `MANAGE_TOOL_POLICY`。

两组端点继续只监听 `127.0.0.1`。`RuntimeApiServer` 先通过可插拔 `PrincipalAdapter` 验证凭据，再由 Change API 按项目成员与动作权限强制鉴权。旧 `actorId` 仅是可选一致性字段：存在时必须等于认证 subject，不能决定操作者。默认 `Authorization: Bearer <key>` / `X-PaiCLI-API-Key` 进入固定 `local-user` 的单操作者本地可信模式；它不是共享部署身份方案。Draft 创建/补充请求只保存任务与调度意图，模型在后台运行；201 不代表草稿已就绪。服务重启恢复未完成 Draft，已锁定 Spec 不重新生成；普通启动不会自动创建新任务。真实提交请求会使用配置的模型并产生模型费用，确定性测试不调用模型。

Mock 工单示例见 `docs/fixtures/paichange/local-issue.json`：修改本地仓库路径与需求后，复制到数据目录的 `fixtures/`，以 `POST /v1/changes` 请求体 `{"fixture":"local-issue.json"}` 创建。Fixture 仅接受该目录内的 JSON 文件名。

远程 SCM 默认关闭；`PAICHANGE_SCM` 只允许 `mock|gitlab|github` 严格单选，Token 不从 Web/API 请求读取。GitLab 配置：

```bash
export PAICHANGE_SCM=gitlab
export PAICHANGE_GITLAB_BASE_URL=https://gitlab.example.com
export PAICHANGE_GITLAB_PROJECT_ID=group/project
export PAICHANGE_GITLAB_TOKEN='<project access token>'
export PAICHANGE_GITLAB_REPOSITORY=/absolute/path/to/local-checkout
export PAICHANGE_GITLAB_BASE_REF=main          # 可省略
export PAICHANGE_GITLAB_REMOTE=origin          # 可省略
export PAICHANGE_GITLAB_TIMEOUT_SECONDS=15     # 可省略
```

GitHub 配置：

```bash
export PAICHANGE_SCM=github
export PAICHANGE_GITHUB_API_BASE_URL=https://api.github.com
export PAICHANGE_GITHUB_OWNER=example-owner
export PAICHANGE_GITHUB_REPOSITORY_NAME=example-repository
export PAICHANGE_GITHUB_TOKEN='<fine-grained token>'
export PAICHANGE_GITHUB_CHECKOUT=/absolute/path/to/local-checkout
export PAICHANGE_GITHUB_BASE_REF=main
export PAICHANGE_GITHUB_REMOTE=origin
export PAICHANGE_GITHUB_TIMEOUT_SECONDS=15
```

本地 checkout 必须是可解析 base ref 的 Git 工作树，remote 必须与所选 provider 的仓库身份一致；HTTP(S) remote 不得内嵌凭据。系统不自动 clone、不配置分支保护、也不合并 PR/MR。GitHub 使用 `github:<owner>/<repository>:issue:<number>` 幂等导入，GitLab 保持既有 IID 语义。两者的真实实例验收均等待专用测试仓库、Issue、最小权限 Token 和明确真实写入授权；本轮只使用 loopback 假服务和临时 bare remote。

发布前重新核对锁定 Spec、分支 head、持久化原始 Verdict/Evidence、当前交付判断和有效审批；HIGH 必须有 Delivery Approval。`NEEDS_HUMAN` 仅发布 pending，Delivery Approval 不能覆盖它；当前交付判断失败仅发布 failure。发布失败保持未完成状态，后台可安全重试。`ConfiguredScm` 在唯一装配点配对 WorkItem/SCM adapter；Workflow、Worker、API 和 RBAC 不含 provider 分支。GitHub/GitLab 都先推送精确 Worker 分支、回读远端 head、对账或创建 open PR/MR，再发布绑定 head SHA 与 publication identity 的 commit status。超时、响应丢失或本地 ledger/完成事件落账失败后先远端对账，不盲目重复写入。

当前同源 Web 已接入服务端 Principal、项目 RBAC、登录失效和 401/403 反馈，并按 capability 显示 GitHub Issue number、GitLab Issue IID 或 Mock 创建表单。M3 将 `STANDARD / RESTRICTED / LOCKED_DOWN` 从 route 标签变为实际 Worker 策略，并支持项目版本规则：本地只读默认允许；STANDARD 的联网/MCP 与 RESTRICTED/LOCKED_DOWN 的写文件和命令需逐调用审批；未知工具默认拒绝。Agent 初次执行、一次修复和 command Verifier 共用 `GovernedToolRegistry`，批准后仍执行原 PathGuard/CommandGuard。审批只保存脱敏摘要和参数 SHA-256，绑定 change/run/call/cwd/Spec/策略版本，M5 的 `APPROVE_TOOL` 与执行前撤权重检生效。默认最多一个审批等待槽、300 秒超时；重启不会恢复或重放丢失的调用栈。M8 不包含 Jira、Webhook 或自动合并。详见 [M8 实施记录](docs/paichange-m8-implementation.md)。

M6a 增加可选 Docker 执行平面和默认启用的可信 Evidence 归档。共享试点必须设置 `PAICHANGE_DOCKER_ENABLED=true` 与本机已存在、固定 digest 的 `PAICHANGE_DOCKER_IMAGE`；Docker/镜像/网络检查失败时不降级到宿主命令。每个任务只挂载自己的 worktree，以非 root、只读根、无 capabilities/no-new-privileges 运行，并限制 CPU、内存、PID 与任务总时长；取消或超时用 `docker rm -f` 清理进程树。默认 `--network none`，显式出口只能使用带项目及策略摘要 label 的 Docker internal 代理网络，并同时注入大小写 HTTP(S) proxy 变量以兼容不同客户端。容器默认不注入 Secret；部署装配只能提供短期 `*_FILE` 租约，Secret tmpfs 绑定配置的非 root uid/gid，模型和 SCM 凭据留在控制面。

Worker Evidence 会由控制面复制到 `evidence-archive`，以 `(changeId, runId)` 不可覆盖地保存对象大小、SHA-256 和 manifest 摘要；Artifact、人工判断、交付审批和发布前都会复核，篡改/缺失/额外对象或归档失败均不得 success。2026-09-06 的目标主机验收已实测双任务文件隔离、internal 假代理 ACL/审计、cgroup 资源限制、取消/异常/Secret 到期清理、应用级 orphan 恢复、Docker Desktop daemon 重启故障注入及 Evidence 重启复核。该本地哈希归档不是 WORM 对象存储，Docker 也不是绝对安全边界。配置、威胁模型和验收入口见 [M6a 实施记录](docs/paichange-m6a-implementation.md)。

M6b 增加显式生产存储装配：`ChangePersistence` 的 PostgreSQL adapter、带租约/heartbeat/`SKIP LOCKED` 的 PostgreSQL reference-only Worker queue、S3-compatible 不可覆盖 Evidence 对象以及本地可信 cache。M6b 引入带 checksum 的 V1 migration；M7a 已将当前 schema 前向升级到 V2。共享 publication ledger 同步进入 PostgreSQL。SQLite 和本地 Evidence 仍是默认及离线演示实现。生产模式要求 GitHub 或 GitLab + PostgreSQL + 预建 S3 bucket，不提供双写、HA、多区域、Kubernetes、性能优化或跨资源事务。配置、离线迁移/回滚边界和容器验收见 [M6b 实施记录](docs/paichange-m6b-implementation.md)。

M7a 在 M5/M6b seam 上补齐生产身份最小切片：单 issuer OIDC Bearer JWT 严格校验 issuer、audience、RS256/384/512 allowlist、JWKS、`exp` 和 `nbf`，未知 `kid` 或签名失败触发一次 JWKS 刷新后仍 fail closed；PostgreSQL V2 保存成员 principal type、roles、乐观版本、时间和 actor，并追加不可覆盖的成员审计。`GET/PUT/DELETE /v1/changes/projects/{projectId}/members` 与审计查询只允许 HUMAN `PROJECT_ADMIN`，SERVICE 不能管理成员，类型 claim 与目录不一致不授权；并发旧版本返回 409，不能移除最后一个 HUMAN 管理员。空目录只能由配置的 bootstrap subject 将自己初始化为首个管理员，完成后配置不再绕过数据库。生产 PostgreSQL 路径强制 OIDC + 持久化成员目录；默认 localhost 和离线 demo 继续固定 API Key。2026-09-06 已通过假 OIDC/JWKS + PostgreSQL 容器闭环、V1→V2 升级、M6b 复跑、975 项 quick 与 17 项 Web 回归。配置、API、迁移/回滚和容器验收见 [M7a 实施记录](docs/paichange-m7a-implementation.md)。

M7b 在上述 seam 上增加运行保障 module，不改业务 Workflow/RBAC/OIDC/成员目录：`GET /health/live`、`GET /health/ready` 和 `/metrics` 分别提供进程存活、PostgreSQL/queue/S3/SCM/JWKS 分项 readiness 与无敏感 label 的 Prometheus 指标；生产启动要求远端 TLS、显式 RPO/RTO、有效 queue lease 和已存在且身份匹配的 GitHub/GitLab checkout。成员管理员可从 `/v1/changes/projects/{projectId}/members/audit/export` 导出带 SHA-256/条数头的有界 JSONL，权限仍逐请求读取成员目录。`ProductionRecoveryVerifier` 对恢复后的 PostgreSQL V2、全部 S3 Evidence 内容/metadata/manifest、COMPLETED publication 和发布身份做 fail-closed 复核。本地脚本使用 PostgreSQL 17.6、主/备两个 MinIO、假 OIDC/JWKS 和假 GitLab，删除原数据库/bucket 后恢复；本机小 fixture 从停写恢复点计算的实测 RPO 2 秒、RTO 2 秒、备份耗时 1 秒（目标 300/600 秒），只构成本地演练证据。M7b 容器 profile 5 项、独立恢复 1 项、M6b 兼容容器 1 项及 quick 983 项（16 skipped）均通过。配置、指标/告警、脱敏、备份恢复、故障、升级/回滚和剩余真实环境验收见 [M7b 实施记录](docs/paichange-m7b-implementation.md)。

M8 本地实现已补齐 GitHub Adapter、单 provider 装配、假 GitHub/真实 Git push 闭环以及普通/容器/Tag GitHub Actions。M8 最终完成仍要求在另行授权后执行 GitHub/GitLab 真实测试仓库验收，并实际运行 Tag Release；实施边界见 [M8 收口计划](docs/paichange-m8-resume-release-plan.md)。

#### Web 与离线演示

正常 `serve --http` 在同一端口提供 `/changes` 页面：任务创建/工单导入、列表、详情与事件时间线、Draft/锁定 Spec、revision diff、风险与路由、代码 diff、Verifier/Criteria/Evidence、逐项人工验收、两阶段审批及 delivery 历史。页面空壳不含任务数据；登录后显示服务端 Principal，列表按项目过滤，任务响应包含当前动作权限并据此隐藏无权按钮。隐藏只改善体验，服务端仍逐请求鉴权。凭据仅在当前页面内存中使用，不放入 URL、静态资源、浏览器存储或日志；401 会清除登录态和轮询，刷新页面需要重新登录。URL fragment 保留当前 changeId，登录后继续跟踪。后台状态采用 1–10 秒退避轮询，可暂停；页面隐藏、退出和待审批/终态在当前判断的 Check 发布对齐后停止轮询。

无需模型 Key、无需外网的演示（本机需要 JDK 17+ 和 Git）：

```bash
mvn package -DskipTests
export PAICLI_RUNTIME_API_KEY="<自行设置的本地测试密钥>"
java -Dpaichange.demo=true -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8086
```

打开 `http://127.0.0.1:8086/changes`，输入上面的 Key 后连接。默认创建新的系统临时目录并打印位置；如需重启继续审批，在 `-jar` 前添加 `-Dpaichange.demo.dir=/absolute/path/to/demo-data`。离线目录与正常 `PAICHANGE_DATA_DIR` 分离，不读取个人模型配置或启动 MCP。未显式设置 `paichange.demo=true` 时，原运行默认行为不变。

五分钟演示步骤：

1. 展开“创建 ChangeTask”并创建退款 fixture；页面标记“离线模拟执行 · Mock SCM”。重复创建返回同一任务，重新演示请使用新的演示数据目录。
2. 核对 Draft；可填写补充要求后勾选确认、点击 `Spec SUPPLEMENT`，查看 r1→r2 diff。离线 Draft 的可执行验收固定，补充文本只保留为确认记录，不模拟理解任意新需求。
3. 勾选确认并点击 `Spec APPROVE`。操作者由本地 Key 对应的 `local-user` 决定；离线固定演示显式关闭职责分离，仅用于单操作者模拟。正常服务对 MEDIUM/HIGH 默认禁止 requester 自批，并要求 Spec 与 Delivery 由不同主体完成。确定性 RiskEngine 得出 MEDIUM，路由明确标记 `offline-demo / deterministic-fixture`。
4. Worker 使用真实 Git worktree、SpecExecutionEngine、Java command Verifier 和 SQLite。首次替身写入 `hours >= 24`，23/24/25 边界验收 FAIL；一次受控修复改为 `hours > 24` 后 PASS。检查两轮 Evidence、Criterion Results 和最终代码 diff。
5. 在 `DELIVERY_REVIEW` 核对当前 version/digest/run/headSha/判断 revision，勾选确认并点击 `Delivery APPROVE`；只有 Mock Check 保存成功才显示发布完成。Spec/Delivery 均可拒绝；普通交付审批不能覆盖 NEEDS_HUMAN 或失败 Verdict。

M3 工具审批浏览器验收使用全新的演示目录，并在启动参数中增加 `-Dpaichange.demo.tool.approvals=true`。固定任务会改用 LOCKED_DOWN，在初次写入、首轮 command Verifier、一次修复写入和修复后 Verifier 分别等待一条精确审批；页面的 “Worker 工具审批” 与 Spec/Delivery 操作相互独立。该开关只用于离线确定性验收，默认演示仍走 STANDARD。

新增只读接口：`GET /v1/changes/{changeId}/artifacts` 返回 `version`、Draft/锁定 Spec 正文、全部关联 revisions、最新 revision diff、Verifier/Criteria、最终代码 diff、Criterion Results、两轮验证与 Evidence。可用 `?fromRevision=1&toRevision=2` 比较已保存 revision；不存在返回 404。只接受 revision 整数参数，不接受文件路径；从任务关联和历史 digest 解析文件，拒绝目录遍历、符号链接及跨产物根访问，每文件限 4 MiB。代码 diff 是否被执行引擎截断会在页面标明；M6a 归档会额外显示 Evidence 完整性与 manifest digest。`GET /v1/changes/capabilities` 返回当前模拟执行、Mock/GitLab/GitHub SCM、Docker 隔离和 Evidence 校验状态。

页面把 Spec、工单、diff 和 Evidence 全部作为纯文本渲染，并设置同源 CSP。Spec 审批携带当前 `expectedVersion + digest`；交付审批与人工验收携带 `expectedVersion + specDigest + runId + headSha + judgmentRevision`；409 会刷新内容、清除勾选并要求重新确认，不自动重放；400/401/403/404/422/500 分别提示，内容读取失败时禁用审批。生成/执行中采用 1–10 秒退避轮询，审批阶段保留用户所核对的快照。点击创建后立即显示提交和保存提示，按钮显示加载状态并禁用重复提交；201 后恢复按钮并展示后台生成状态，创建区提示随结果更新，HTTP 错误和草稿生成失败分别反馈。创建请求保留幂等键，响应不明时以同一请求重试；需不同需求时使用“新请求”。

2026-09-04 验证：Phase 1–6 联合针对性 149 项全通过；`mvn test -Pquick` 为 899 项、0 failures/errors、5 skipped；浏览器完成创建、补充、双页面 409、注入防护、修复证据与 Mock success 交付验收。

离线 fixture 位于 `src/main/resources/paichange-demo/`，仅替代 Draft 与 ReAct；验证实际运行本地 Java fixture，SCM 仅写本地 SQLite。演示模式只允许创建固定 `offline-refund.json`，不接受任意仓库创建请求，即使配置了 GitHub/GitLab、M6b 生产存储或 M7a OIDC 也不会联网。离线模式拥有 M3 工具策略、M5 本地 Principal 边界和 M6a Evidence 哈希归档，但默认不开 Docker；单 Key 和显式自批例外都不能用于共享部署。M6b 存储和 M7a 身份均只完成本地容器最小闭环；真实 GitHub/GitLab/IdP、目标环境备份恢复、监控和容量仍未验收，不能把离线演示描述为生产平台或真实模型提效证据。

M1 已实现 Draft 异步生成、取消、失败重试和重启恢复。SQLite 自动增量添加 Draft 调度列；调度与任务/事件同事务，默认两个并发、每 generation 最多三次基础设施 attempt、每次 600 秒超时，失败退避 1/2 秒。内容资格纠错仍单独最多两次。HTTP 取消终止当前生成请求；不可取消实现的迟到结果不会覆盖当前版本。迁移、崩溃窗口和操作说明见 [M1 实施记录](docs/paichange-m1-implementation.md)。

M2 已实现 Human Evidence 补录闭环。人工记录按 Criterion 追加，更正保留旧记录并使旧 Delivery Approval 失效；缺项/跳过仍为 NEEDS_HUMAN，确定性失败或异常不能由人工判断升级。全部通过后按风险路由进入独立交付审批，HIGH 仍需有效批准。Mock Check 支持 pending 到最终结论的版本化幂等发布，保存全部发布历史，拒绝迟到结果回退。页面分别显示原始 Run Verdict 与当前交付判断，人工入口只接受说明及当前任务已有 Artifact 引用；原固定退款 fixture 不含人工项。API、迁移、测试与浏览器验收详见 [M2 实施记录](docs/paichange-m2-implementation.md)。

M5 已实现可信 Principal、四角色动作矩阵、真人/服务账号区分、项目列表过滤和跨项目读取保护。MEDIUM/HIGH 默认禁止 requester 自批，且 Spec 与 Delivery Approval 需要不同 subject，项目管理员也不豁免。M7a 已为生产路径补齐单 issuer JWT/JWKS verifier 和 PostgreSQL 成员管理；登录页、Refresh Token、SCIM、组织同步和多 IdP 仍是部署边界。详见 [M5 实施记录](docs/paichange-m5-implementation.md)与 [M7a 实施记录](docs/paichange-m7a-implementation.md)。

M3 已实现项目级版本工具策略、三 profile 强制执行、持久化精确调用审批、M5 工具审批权限、脱敏事件/Web、策略/权限变化失效、有界等待与重启不重放。默认矩阵、API、迁移和测试见 [M3 实施记录](docs/paichange-m3-implementation.md)。M3 本身没有提供沙箱；M6a 后续为 PaiChange Worker 增加上述有限 Docker 边界，普通 CLI 与未启用 Docker 的本地演示仍在宿主执行。

2026-09-05 M5 验证：M1/M2/M5 联合针对性 98 项、Node Web 14 项通过；`mvn test -Pquick` 为 929 项、0 failures/errors、5 skipped。浏览器完成错误凭据 401、固定服务端 Principal、无 actor 输入创建、两阶段审批、一次确定性修复和 Mock success；没有调用付费模型或真实 SCM。

2026-09-05 M3 验证：M3 定向并包含 M1/M2/M5 的联合回归为 27 个测试类、168 项，Node Web 15 项通过；`mvn test -Pquick` 为 941 项、0 failures/errors、5 skipped。浏览器完成错误凭据 401、LOCKED_DOWN、初次写入/首轮 Verifier/修复写入/复验四条精确审批、正文脱敏、一次确定性修复、Delivery Approval 和 Mock success / COMPLETED；没有调用付费模型或真实 SCM。

2026-09-06 M6a 目标主机验证：Docker Desktop 29.7.2 使用本机预置、digest 固定的 Alpine 镜像运行 7 条真实容器测试，全程 `--pull=never`。实测覆盖双任务 worktree 与宿主敏感路径隔离、默认断网、internal 假代理 ACL/审计及错误 label/digest fail closed、cgroup OOM/PID 拒绝、命令/任务超时、主动取消、异常退出、Secret 到期、应用级 orphan 恢复、进程树和失败清理；测试标签容器与网络均零遗留。除 Docker 29 的 mount 兼容问题外，又修复小写 proxy 变量、并发取消锁和 non-root Secret tmpfs 属主三项缺陷。初次 M6a 针对性 36 项、M1/M2/M3/M5 扩展回归 133 项、Node Web 16 项、quick 964 项（5 skipped）均 0 failures/errors，打包成功。浏览器完成一次修复到 Mock success / COMPLETED，并在应用重启后复核同一 `VERIFIED` Evidence manifest。随后经单独授权重启 Docker Desktop：重启前无用户容器，唯一活动 M6a 验收命令以 255 退出并留下 `Exited (137)` 容器；daemon 恢复后平台按精确 owner 清理遗留，未知 Worker 结果不重放，原 Evidence manifest 仍为 `VERIFIED` 且摘要不变。重启后当前 M6a 针对性 39 项全部通过（含 7 条真实 Docker 用例），最终容器与验收网络均零遗留；M6a 验收没有调用付费模型或连接真实 SCM。

2026-09-06 M4 本地验证：Java 针对性 39 项、Node Web 17 项、quick 967 项（12 skipped）均通过。`GitLabScmEndToEndTest` 只连接 `127.0.0.1` 假 GitLab，并向临时 bare remote 执行真实 Git push；覆盖重复 Issue 导入、MR/status 响应不明、完成事件事务失败、远端 head 不一致和 401/429。该次 M4 验收没有连接真实 GitLab、使用真实 Token、运行付费模型评测，也没有涉及随后实施的 M6b。

### 第二十一期：图片复制粘贴输入（MVP）

- `LlmClient.Message` 支持 `ContentPart`，包括 `text`、`image_base64`、`image_url`
- 请求体在含图片且 provider 支持图片输入时输出带图片块的 content array，纯文本仍保持 string content
- `LlmClient` 公共接口用 `supportsImageInput()` 声明图片能力；DeepSeek 等文本 provider 会把图片块替换成文本提示，避免 `image_url` 进入不支持多模态的 API 请求体
- GLM 套餐用户可通过 `/model glm-5v-turbo` 切换到 GLM-5V-Turbo 多模态模型，再用 Ctrl+V 或 `@image:` 输入图片；本地 base64 图片会按智谱格式写入 `image_url.url`
- MCP `image` content 会保留 base64 与 `mimeType`，在 ReAct / Plan / SubAgent 工具结果后作为图片 user message 回灌；当前 provider 不支持图片输入时，请求序列化层会自动省略图片 payload 并保留文本提示
- 用户可通过 `@image:file:///abs/path.png`、`@image:/abs/path.png` 或 `@image:relative/path.png` 引用本地图片；Windows 也兼容常见的 `file://C:\path\image.png`，并支持未编码空格、非 ASCII 与 `%20`
- 本地图片和 MCP 图片都会按 Claude Code 同类策略预处理：不是 OCR 成文本，而是压缩 / 缩放后作为图片块发送；带 alpha 的 PNG 会铺白底重编码；额外注入来源、尺寸和坐标映射元信息
- 本地 `@image:` 消息会要求模型优先分析本轮图片；除非用户明确要求结合历史，历史对话和历史工具结果不能替代当前图片内容
- 新一轮 ReAct / SubAgent 任务开始前会省略历史 image payload，仅保留文本元信息，避免旧截图反复进入上下文；模型 `reasoning_content` 默认只写日志 / 展示，DeepSeek V4 / Kimi thinking tool-call 续轮会按 provider 协议带回上一轮 assistant reasoning
- DeepSeek 流式调用默认使用 HTTP/1.1，规避部分 HTTP/2 网关长 SSE 响应被重置导致的 `stream was reset: INTERNAL_ERROR`
- 当前边界：不做视频 / 音频、图像生成、TUI sixel 图片预览

### 第二十三期：微信 iLink 通道（文本 MVP）

- 新增进程级入口：`paicli wechat setup`、`paicli wechat start`、`paicli wechat status`、`paicli wechat daemon start|stop|restart|status|logs`
- 新增交互式入口：在 PaiCLI 主界面输入 `/wechat` 可扫码绑定并在当前进程后台启动微信通道；`/wechat setup` 重新扫码绑定，`/wechat status` 查看状态，`/wechat stop` 停止通道
- 默认不开启微信通道；用户必须主动执行 `setup` 并扫码确认完成绑定
- 支持在 Warp / iTerm2 / WezTerm 等兼容终端内直接显示 260px PNG 二维码；不支持终端图片协议时回退为字符二维码和链接
- 微信侧使用 iLink `getupdates` 长轮询收消息、`sendmessage` 分片回消息，不依赖 SSE；这是独立通道，不是 Skill，也不是 Runtime API
- 运行时只接受绑定用户私聊；普通消息单并发排队，`/help`、`/status`、`/pause`、`/resume`、`/stop` 走队列外控制路径
- 微信侧用户消息会回显到 PaiCLI 终端 transcript；PaiCLI 终端继续显示 thinking / 工具调用过程，微信侧只接收 assistant 正文。iLink 协议层仍是 `text_item.text` 文本消息，没有显式 Markdown parse mode；PaiCLI 会保留 ClawBot 稳定支持的 Markdown 子集（列表、引用、粗体、行内代码、真实代码块），把标题转成粗体标题、把表格转成移动端更稳的键值/列表，并过滤图片 Markdown / H5-H6 / 中文斜体等兼容性差的标记；非代码类 fenced block（流程说明、长中文箭头链）会解包并换行，避免微信侧出现横向滚动代码块。iLink 不提供真正 SSE 或改单条消息能力。
- 微信通道使用非交互式默认拒绝策略：只读工具默认允许，`write_file` / `create_project` 继续受 workspace PathGuard 限制，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝
- 当前文本 MVP 会保留图片 / 文件消息的媒体元数据提示，但 CDN 下载解密、图片块输入和 `/send` 文件推送仍待后续媒体链路补齐

### 第六期 HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计）

`com.paicli.policy` 包，作为 HITL 之外的辅助层（不是沙箱、不提供进程隔离）：

- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / Windows 盘符绝对路径上的 `del` / `rmdir` / `format` / `diskpart` / `shutdown`），减少 HITL 弹窗骚扰
- `AuditLog` 结构化审计：危险工具调用按天写 JSONL 到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`；`revert_turn` 也纳入危险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计

**为什么不叫沙箱**：本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做容器/VM 沙箱——沙箱削弱 Agent 能力、给虚假安全感、体验更差。生产级 Agent 沙箱实际是 microVM-level（Devin / Modal / Anthropic Computer Use 用 Firecracker / gVisor）。PaiCLI 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离。

## 启动界面

### 当前启动界面

当前启动输出以命令行实际产物为准：

```text
   ████████    PaiCLI π  v16.1.0
     ██  ██    Model step-3.5-flash-2603 (step)
     ██  ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
     ██  ██    ReAct · Plan · MCP · Browser · Image
     ██  ██

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## 功能

### 第一期

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、确定性代码搜索、Shell命令、项目创建、RAG 语义检索、联网搜索、MCP 动态工具）
- 💬 交互式命令行界面
- 📝 普通任务和斜杠命令提交后会先把本轮原始输入以 `>` 暗色整行块写回 transcript；输入态仍显示 `* `，单行提交只占一行，不额外追加空白行。普通任务随后再进入 Thinking / 工具调用，避免 dock 刷新或 activity 重绘后用户输入从可见历史里消失
- 🧠 默认通过流式接口获取模型输出；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，content / tool call 开始前清掉 live 区并把完整 reasoning 引用块落到 transcript，回答正文用低调标记起始；web_search / web_fetch 会在折叠头展示 query / URL，并在执行后输出一行结果摘要
- 🖥️ 终端会对常见 Markdown（标题、列表、表格、代码块）做渲染后再显示；表格会按当前窗口宽度分配列宽，并在单元格内部换行，避免长 URL / 中文内容把列打散

### 第二期

- 📋 Plan-and-Execute + DAG 任务拆解与顺序执行
- ⌨️ `/plan` 一次性进入计划执行
- 🧭 更清晰的复杂任务执行顺序与依赖展示
- ⚖️ 简单任务会自动生成最小计划，不再为了凑步数扩展无关步骤

### 第三期

- 🧠 短期记忆、长期记忆与相关记忆检索
- 📦 长对话摘要压缩与 Token 预算管理
- 🧮 长上下文动态预算、prompt cache 可见化与成本估算
- 💾 `/memory` 与 `/save` 记忆管理入口

### 第四期

- 🔍 代码库实时搜索 + RAG 语义辅助（精确定位优先 glob/grep/read，自然语言模糊查询再 search_code）
- 🕸️ 代码关系图谱（类继承、接口实现、方法调用）
- 📡 本地 Ollama Embedding + 远程 API 可配置
- 🗃️ SQLite 向量存储与持久化

### 第五期

- 👥 多 Agent 协作（规划者 + 执行者 + 检查者）
- 🎯 主从架构编排器自动分配任务
- 🔍 检查者审查质量，未通过自动重试
- 🛠️ 执行者共享工具集，支持文件操作与代码检索

### 第六期

- 🔒 危险操作静态规则识别（`write_file` / `execute_command` / `create_project` / `revert_turn`）
- ⚠️ 三级危险等级展示（高危 / 中危 / 安全）
- ✅ 审批决策：批准、全部放行、拒绝、跳过、修改参数后执行
- 🔓 HITL 默认关闭，`/hitl on` 启用、`/hitl off` 关闭

### 第七期

- ⚡ 同一轮多个工具调用会并行执行，适合同时读取多个文件、同时列目录、同时跑独立检查
- 🧵 ReAct、Plan-and-Execute、Multi-Agent Worker 共用同一套并行工具执行机制
- ⏱️ 工具批次有统一超时，超时工具会被取消并把超时结果回灌给模型
- 📋 Plan-and-Execute 与 Multi-Agent 会按 DAG 依赖批次并行推进独立任务

### 第八期

- 🔄 GLM-5.1、GLM-5V-Turbo、DeepSeek V4、阶跃星辰 StepFun、Kimi K2.6、FreeLLMAPI 与 Agnes 2.0 Flash 多模型，`/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型，`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 读取配置模型
- 🧱 `LlmClient` 接口 + 模板方法基类，新增 provider 只需 ~20 行
- 💾 默认模型持久化到 `~/.paicli/config.json`

### 第九期

- 🌐 `web_search` 工具支持四条路：Step 3.7 Flash + StepSearch MCP 优先、智谱 Web Search（与 GLM 共用 Key默认推荐）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- 📰 `web_fetch` 工具：抓 URL → readability 提取 → 返回 Markdown 正文
- 🛡️ 内置网络访问策略：屏蔽内网、loopback、`file://`；5MB 响应上限；每分钟 30 次限流
- 🚧 边界明确：SPA / 防爬墙返回空正文 + 已知边界提示，不重试

### 第六期 HITL 增强

- 🛡️ 路径围栏：文件类工具强制限定在项目根之内，绝对路径外逃 / `..` 穿越 / 符号链接逃逸全部拦截
- 🧯 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- 📦 资源上限：`write_file` 5MB；`execute_command` 60 秒超时 + 8KB 输出截断
- 📋 结构化审计：危险工具调用按天写一行 JSONL 到 `~/.paicli/audit/`，可通过 `/audit [N]` 查看
- 🧱 定位：HITL 之外的辅助层，不是沙箱、不提供进程隔离

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 或 Agnes API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
# 或
export STEP_API_KEY=your_step_api_key_here
export STEP_MODEL=step-3.5-flash
# 或
export KIMI_API_KEY=your_kimi_api_key_here
export KIMI_MODEL=kimi-k2.6
# 或
export FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1
export FREELLMAPI_MODEL=auto
# 或
export AGNES_API_KEY=your_agnes_api_key_here
export AGNES_MODEL=agnes-2.0-flash
export AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
```

也可以在 PaiCLI 内用命令写入 `~/.paicli/config.json`，不会覆盖 Kimi 配置：

```text
/config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
/model freellmapi
/config provider agnes --api-key <key> --model agnes-2.0-flash --default
/model agnes
```

长期记忆默认保存在用户目录下的 `~/.paicli/memory/long_term_memory.json`。
长期记忆只保存显式保存意图下的稳定事实：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`。默认保存为当前项目作用域；跨项目通用偏好可用 `/save --global <事实>` 或 `save_memory(scope=global)`。它不应包含一次性任务请求或临时文件名/目录名。
可用 `/memory list` 查看长期记忆，`/memory search <关键词>` 搜索当前项目可见记忆，`/memory delete <id>` 删除单条记忆。

项目级记忆使用 Markdown 文件维护，和 `/save` 的长期记忆分工不同：

- `~/.paicli/PAI.md`：用户级稳定偏好，所有项目可见。
- `PAI.md` / `.paicli/PAI.md`：项目级团队规则，建议提交到 git。
- `PAI.local.md` / `.paicli/PAI.local.md`：本地覆盖，适合个人调试约定，建议加入 `.gitignore`。
- `@relative/path.md`：在 `PAI.md` 中导入项目根内的相对文件；越靠后的文件越接近本地覆盖，优先级越高。

可用 `/init` 为当前项目生成一份短 `PAI.md`。该命令默认不覆盖已有文件；确认需要重建时使用 `/init --force`。
代码索引默认保存在 `~/.paicli/rag/codebase.db`。
调试日志默认滚动写入 `~/.paicli/logs/paicli.log`，旧日志会按保留天数和总容量自动清理。
ReAct / Plan task / SubAgent / Planner 的模型 `reasoning_content` 会以 `LLM reasoning [...]` 形式写入该日志，便于排查模型为什么选择某个工具或路径。

如果你想为某次运行指定单独目录，可以额外传入：

```bash
# 指定记忆目录
java -Dpaicli.memory.dir=/tmp/paicli-memory -jar target/paicli-1.0-SNAPSHOT.jar

# 指定 RAG 索引目录
java -Dpaicli.rag.dir=/tmp/paicli-rag -jar target/paicli-1.0-SNAPSHOT.jar

# 指定日志目录与保留策略
java -Dpaicli.log.dir=/tmp/paicli-logs \
     -Dpaicli.log.level=DEBUG \
     -Dpaicli.log.maxHistory=3 \
     -Dpaicli.log.maxFileSize=5MB \
     -Dpaicli.log.totalSizeCap=20MB \
     -jar target/paicli-1.0-SNAPSHOT.jar
```

也可以放到 `.env` 或环境变量中：

```bash
PAICLI_LOG_LEVEL=DEBUG
PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
PAICLI_LOG_MAX_HISTORY=7
PAICLI_LOG_MAX_FILE_SIZE=10MB
PAICLI_LOG_TOTAL_SIZE_CAP=100MB
```

### 2. 可选：配置 MCP server

MCP 子系统默认开启。`~/.paicli/mcp.json` 不存在时，PaiCLI 会自动创建默认 chrome-devtools 配置：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

需要继续接入其他 server 时，可编辑 `~/.paicli/mcp.json` 或项目内 `.paicli/mcp.json`：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "${PROJECT_DIR}"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    },
    "step_search": {
      "url": "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
      "headers": {"Authorization": "Bearer ${STEP_API_KEY}"}
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量读取；缺失会在启动时直接提示。

`step_search` 是约定名称：如果项目 `.env`、用户 `~/.env` 或系统环境变量里存在 `STEP_API_KEY`，PaiCLI 会自动内置这个远程 MCP；上面的手写配置只用于覆盖默认地址或自定义鉴权。当前模型为 `step-3.7-flash*` 时，内置 `web_search` / `web_fetch` 会优先代理到该 MCP server。

需要复用当前登录态时，Chrome 144+ 推荐打开 `chrome://inspect/#remote-debugging` 并勾选 `Allow remote debugging for this browser instance`。旧版本或需要显式 CDP 端口时，可以启动带远程调试端口和独立 user-data-dir 的 Chrome，并在这个调试 Chrome 中完成登录：

```bash
# macOS
open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile

# Windows
start chrome.exe --remote-debugging-port=9222 --user-data-dir=%TEMP%\paicli-chrome-profile

# Linux
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile
```

通常不需要用户预先切换；Agent 如果遇到登录页会自己调用 `browser_connect`。手工调试时也可以在 PaiCLI 内执行：

```text
/browser status
/browser connect
/browser tabs
/browser disconnect
```

`/browser connect` 只在当前进程内把 `chrome-devtools` 切到 shared 模式，不会改写 `~/.paicli/mcp.json`。如果希望启动后默认 shared，可手动把 args 改为：

```json
["-y", "chrome-devtools-mcp@latest", "--autoConnect"]
```

旧式 CDP HTTP JSON 端口也可使用：

```json
["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]
```

浏览器测试可直接让 Agent 读取动态页面，例如：

```text
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

期望路径是 `web_fetch` 尝试失败后，fallback 到 `mcp__chrome-devtools__navigate_page` 与 `take_snapshot`。

如果 server 支持 resources，可以直接查看或引用：

```text
/mcp resources filesystem
/mcp prompts filesystem
帮我看下 @filesystem:file://README.md 这份文档
```

OAuth 和 `sampling/createMessage` 当前未实现；远程 server 需要鉴权时仍使用 `headers` + 环境变量注入 Bearer token。

### 3. 编译运行

```bash
# 编译（默认跳过测试）
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text；grep_code 会优先使用本机 ripgrep，未安装时自动回退）
java -jar target/paicli-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
```

### 4. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 的方式只有 `/plan`：

1. 输入 `/plan`
2. 下一条任务会用计划模式执行
3. 执行完成后自动回到默认 `ReAct`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

计划生成后，CLI 会先停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划
- 按方向键不会触发取消；只有单独按下 `ESC` 才会取消待执行 plan

## 使用示例

### 第一期：ReAct 示例

```text
* 创建一个Java项目叫myapp

🧠 思考过程:
用户要创建一个 Java 项目。我先调用 create_project 工具生成基础结构，再根据工具返回结果确认是否创建成功。

🤖 最终结果:
已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### 第二期：Plan-and-Execute 示例

```text
💡 提示:
   - 输入你的问题或任务
   - 输入 '/' 后按 Tab 补全命令
   - 输入 '@server:protocol://path' 可显式引用 MCP resource
   - 任务运行中按 ESC 取消当前任务
   - 默认模式是 ReAct
   - 未识别的 `/xxx` 命令会直接提示“未知命令”，不会再交给 Agent 当普通对话处理

* /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取... ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无        ║
║     创建 demoapp 项目结构                              ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1    ║
║     读取 demoapp/pom.xml 内容                          ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2  ║
║     验证项目结构与 Maven 配置                          ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求后重新规划

I
补充> 请在执行前先检查 README

📝 已收到补充要求，正在重新规划...

🚀 开始执行计划...
```

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过常见构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先使用 ripgrep，返回文件、行号、可选上下文、partial 状态与 suggested_reads
- `execute_command` - 在当前项目目录执行短时命令；Windows 使用原生 `cmd.exe`，Linux/macOS 使用 POSIX `sh`，不要求额外安装 Bash/WSL；默认 60 秒超时并清理子进程树，黑名单拦截破坏性命令
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询，适合作为模糊语义或常规搜索无果时的辅助）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，PaiCLI 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

文件类与代码检索工具（`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝；`glob_files` / `grep_code` 对模型返回的项目相对路径统一使用 `/`。`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh`，以及 Windows 盘符绝对路径上的危险删除和磁盘命令。`revert_turn` 会批量回写工作区，默认触发 HITL 和审计。所有 `mcp__` 前缀工具默认触发 HITL 和审计。详见 `/policy`。

## 命令

进程级入口：

- `paicli wechat setup` - 绑定微信 iLink 通道，选择 workspace 并完成扫码确认
- `paicli wechat start` - 前台启动微信通道
- `paicli wechat status` - 查看绑定状态和 daemon pid
- `paicli wechat daemon start|stop|restart|status|logs` - 管理本机微信通道后台进程

交互式斜杠命令：

- `/wechat` - 扫码绑定并启动微信 iLink 通道；已绑定时直接启动
- `/wechat setup` - 重新扫码绑定并启动微信通道
- `/wechat status` - 查看当前 PaiCLI 进程内微信通道状态
- `/wechat stop` - 停止当前 PaiCLI 进程内微信通道
- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/team` - 下一条任务使用 Multi-Agent 协作模式
- `/team <任务>` - 直接用 Multi-Agent 协作模式执行这条任务
- `/spec <代码变更需求>` - 使用当前模型和 Project Context 生成经结构校验的 ChangeSpec Draft；command Verifier 使用嵌套的 `expect: { exit_code: ... }` 结构，生成与纠错提示不会把 `expect.exit_code` 误作带点号的 YAML 键。非 scope 的 deterministic Criterion 必须至少引用一个 command Verifier，`path_scope` 不能单独证明行为或质量。模型回复在完整文档前附带说明或代码围栏时，系统提取其中以 `---` 开始的完整 front matter 后再严格校验。Enter 确认后不可覆盖地保存到 `.paicli/specs/<specId>-r1.md`，记录 ReAct 前 workspace baseline，执行后采集本轮 changed files/final diff，并依次运行 command/JUnit 与最终 `path_scope` Verifier。首次验证出现 deterministic `FAIL` 且没有 Verifier `ERROR` 时，系统会向同一个 ReAct 会话追加脱敏、截断后的失败 Evidence，最多自动修复一次，再基于原 baseline 重跑全部 Verifier。系统随后逐条生成最终 Criterion Result；确定性条件全部通过时用 `P / F / S` 处理 Human Criterion，归约为最终 Verdict，并把 `repairCount`、一至两轮 VerificationAttempt、紧凑 Evidence、指标和最终 diff 保存到 `.paicli/runs/<run-id>/`。Verifier 命令仍单独经过 HITL 与 CommandGuard
- `/cancel` - 运行中请求取消当前任务；空闲时会提示当前没有正在运行的任务
- `/hitl on` - 启用危险操作人工审批（HITL）
- `/hitl off` - 关闭 HITL 审批
- `/hitl` - 查看 HITL 当前状态
- `/mcp` - 查看所有 MCP server 状态
- `/mcp restart <name>` - 重启单个 MCP server
- `/mcp logs <name>` - 查看 MCP server 最近 200 行 stderr 日志
- `/mcp disable <name>` - 运行时禁用 MCP server 并移除其工具
- `/mcp enable <name>` - 运行时启用 MCP server
- `/mcp resources <name>` - 查看 MCP server 暴露的 resources
- `/mcp prompts <name>` - 查看 MCP server 暴露的 prompts（只查看，不注入对话）
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/audit [N]` - 查看今日最近 N 条危险工具审计记录（默认 10）
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory list` - 查看长期记忆列表
- `/memory search <关键词>` - 搜索当前项目可见长期记忆
- `/memory delete <id>` - 删除单条长期记忆
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存项目级关键事实到长期记忆；`/save --global <事实>` 保存跨项目通用偏好
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；默认 `scope=project`，跨项目通用偏好才用 `scope=global`
- `/init` - 生成精简项目级记忆 `PAI.md`；已存在时不覆盖，`/init --force` 可重写
- `/export` - 导出当前 ReAct 会话对话记录为 Markdown（包含完整 system prompt），写入 `~/.paicli/exports/session-*.md`
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码（RAG 辅助路径）
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空当前对话历史、短期记忆、待注入 Skill 上下文和上一轮检索记忆注入；长期记忆保留
- `/exit` / `/quit` - 退出程序

## 运行效果

### 第一期：旧版启动效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
║                                                          ║
║              简单的 Java Agent CLI v1.0.0                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

### 第三期：当前运行效果

```text
   ████████    PaiCLI π  v16.1.0
     ██  ██    Model glm-5.1 (glm)
     ██  ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
     ██  ██    ReAct · Plan · MCP · Browser · Image
     ██  ██

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:

* 你好，请列出当前目录的文件

🧠 思考过程:
用户想了解当前目录结构。我先读取目录，再基于结果做归类说明，而不是只回原始文件列表。

🤖 最终结果:
当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

* /exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine 4（终端交互、Status、输入 widgets）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/paicli
├── agent/
│   ├── Agent.java              # ReAct Agent
│   ├── PlanExecuteAgent.java   # Plan-and-Execute Agent
│   ├── AgentRole.java          # Agent 角色枚举
│   ├── AgentMessage.java       # Agent 间通信消息
│   ├── SubAgent.java           # 可配置子代理
│   ├── WorkerPool.java         # Worker 动态扩缩容与 Lease 生命周期池
│   └── AgentOrchestrator.java  # Multi-Agent 编排器与任务级生命周期管理
├── cli/
│   ├── Main.java               # CLI 入口
│   ├── CliCommandParser.java   # 命令解析
│   └── PlanReviewInputParser.java  # 计划审核输入
├── llm/
│   ├── GLMClient.java          # GLM API 客户端；glm-5.1 走 Coding endpoint，glm-5v-turbo 走多模态 endpoint
│   ├── DeepSeekClient.java     # DeepSeek API 客户端
│   ├── StepClient.java         # 阶跃星辰 StepFun API 客户端
│   ├── KimiClient.java         # Kimi / Moonshot API 客户端
│   ├── FreeLlmApiClient.java   # 本地 FreeLLMAPI OpenAI-compatible 网关客户端
│   └── AgnesClient.java        # Agnes AI OpenAI-compatible 客户端
├── context/
│   ├── ContextMode.java        # short / balanced / long 模式
│   ├── ContextProfile.java     # 模型窗口与上下文策略
│   └── TokenUsageFormatter.java # Token / cache / 成本展示
├── memory/
│   ├── MemoryEntry.java        # 记忆条目
│   ├── ConversationMemory.java # 短期记忆
│   ├── LongTermMemory.java     # 长期记忆
│   ├── ContextCompressor.java  # 上下文压缩
│   ├── TokenBudget.java        # Token 预算管理
│   ├── MemoryRetriever.java    # 记忆检索
│   └── MemoryManager.java      # 记忆门面类
├── plan/
│   ├── Task.java               # 任务定义
│   ├── ExecutionPlan.java      # 执行计划
│   └── Planner.java            # 规划器
├── rag/
│   ├── EmbeddingClient.java    # Embedding API 客户端
│   ├── VectorStore.java        # SQLite 向量存储
│   ├── CodeChunk.java          # 代码块模型
│   ├── CodeChunker.java        # 代码分块器
│   ├── CodeAnalyzer.java       # AST 关系分析
│   ├── CodeRelation.java       # 代码关系模型
│   ├── CodeIndex.java          # 索引管理器
│   └── CodeRetriever.java      # 检索入口
└── tool/
    └── ToolRegistry.java       # 工具注册表
```
