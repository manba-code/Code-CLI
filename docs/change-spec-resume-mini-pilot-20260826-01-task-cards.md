# Mini Pilot 评审任务卡（公开材料）

study_id: resume-mini-pilot-20260826-01

## feature-flag-precedence（MEDIUM）

修复 FeatureFlagConfig.load(Map<String,String>, Properties) 的兼容优先级：
1. 系统属性 paicli.feature.preview 优先，其次 PAICLI_FEATURE_PREVIEW，再其次旧变量 PAI_FEATURE_PREVIEW；
2. 都未提供时默认关闭；
3. 只接受忽略大小写的 true 或 false，其他值抛 IllegalArgumentException；
4. 保持 enabled() 公共方法不变，不新增自动猜测 1/0、yes/no；
5. 只允许修改 src/main/java/eval/FeatureFlagConfig.java，不得修改测试和 pom.xml；
6. 公开验证命令必须使用：mvn -q -DskipTests=false test。

## operation-result-api-compat（HIGH_RISK）

为 OperationResult<T> 增加稳定错误码，同时保持源兼容：
1. 保留公共构造器 OperationResult(T value, String error) 和现有 value()/error()/isSuccess()；
2. 新增三参数构造器 OperationResult(T value, String error, String errorCode)；
3. 新增 errorCode()，旧构造器和 success() 创建的结果返回空字符串；
4. 新增 failure(String message, String errorCode)，结果必须失败且保留消息和错误码；
5. 只允许修改 src/main/java/eval/OperationResult.java，不得修改测试和 pom.xml；
6. 公开验证命令必须使用：mvn -q -DskipTests=false test。

## token-expiry-cross-file（HIGH_RISK）

完成 TokenClaims 与 TokenPolicy 的跨文件过期策略：
1. 保留 TokenClaims(long expiresAtEpochSecond) 构造器和 expiresAtEpochSecond()；
2. 为 TokenClaims 新增 isExpired(long nowEpochSecond)，expiresAt <= now 时为过期；
3. TokenPolicy.isUsable(TokenClaims claims, long nowEpochSecond, long skewSeconds) 在 claims 非空、skew 非负且 token 在 now+skew 后仍有效时返回 true；
4. now+skew 必须防 long 溢出，溢出视为已过期；
5. 只允许修改 src/main/java/eval/TokenClaims.java 和 src/main/java/eval/TokenPolicy.java，不得修改测试和 pom.xml；
6. 公开验证命令必须使用：mvn -q -DskipTests=false test。

---

## A 组（react 工作区）允许查看

1. 本文件的任务描述
2. `$W\<workspace>\run.log`
3. `$W\<workspace>\src\main\java\eval\*.java`（修改后源码）
4. `$W\<workspace>\target\surefire-reports\*.txt`（公开测试输出）
5. 修改文件范围：检查 src 下是否有任务允许清单之外的改动

## C 组（spec_with_repair 工作区）允许查看

A 组全部材料，另加：

1. `$W\<workspace>\.paicli\specs\CHANGE-*.md`（锁定 ChangeSpec）
2. `$W\<workspace>\.paicli\runs\RUN-*\change.diff`
3. `$W\<workspace>\.paicli\runs\RUN-*\result.json`（Criterion / Verifier Evidence / Scope / Verdict）

## 禁止查看（决定冻结前）

- hidden-verification.log（每个工作区根目录都有）
- 评测根目录的 report.md
- *-spec_no_repair-*（B 组）工作区
- 同一任务的另一模式工作区
