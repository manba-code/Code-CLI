## Multi-Agent Development Workflow

复杂功能、跨模块改动或不确定性较高时使用多 Agent。

### 分析阶段

并行启动以下只读 Agent：

- Codebase Onboarding Engineer：梳理调用链和影响文件。
- Software Architect：设计方案和职责边界。
- Database Optimizer：仅在涉及数据库时启动。

主线程必须等待分析完成并形成计划，之后才能写代码。

### 实现阶段

- 前端工作交给 Frontend Developer。
- 后端工作交给 Backend Architect。
- 小型修复交给 Minimal Change Engineer。
- 同一文件只能由一个 Agent 修改。
- 前后端不能独立确定相互冲突的接口契约。

### 验证阶段

实现完成后并行启动：

- Code Reviewer：正确性、回归、测试覆盖。
- Application Security Engineer：涉及 API、鉴权、用户输入、
  上传、支付或敏感数据时启动。

审查问题交给 Minimal Change Engineer 做最小修复。

重要版本发布前使用 Reality Checker 做最终验收。

### 限制

- 明确的一两个文件修改不启动完整多 Agent 流程。
- 分析 Agent 不得修改文件。
- 主线程负责最终决策、测试和交付。
- 所有子 Agent 完成后再输出最终结果。