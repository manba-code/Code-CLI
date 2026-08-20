package com.paicli.spec.eval;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChangeSpecEvaluationCatalog {
    static final String PUBLIC_VERIFIER = "mvn -q -DskipTests=false test";

    private ChangeSpecEvaluationCatalog() {
    }

    static List<ChangeSpecEvaluationCase> defaultCases() {
        return List.of(
                safeDivider(),
                slugifier(),
                loginRetry(),
                timeoutConfig(),
                workspacePath(),
                operationResultCompatibility());
    }

    private static ChangeSpecEvaluationCase safeDivider() {
        return evaluationCase(
                "safe-divider",
                ChangeSpecEvaluationTier.SMALL,
                """
                修复 SafeDivider.divide(int dividend, int divisor)：
                1. divisor 为 0 时返回 OptionalInt.empty()，不能抛 ArithmeticException；
                2. 其他情况保持 Java 整数除法语义；
                3. 只允许修改 src/main/java/eval/SafeDivider.java，不得修改测试和 pom.xml；
                4. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/SafeDivider.java", """
                                package eval;

                                import java.util.OptionalInt;

                                public final class SafeDivider {
                                    private SafeDivider() { }

                                    public static OptionalInt divide(int dividend, int divisor) {
                                        return OptionalInt.of(dividend / divisor);
                                    }
                                }
                                """,
                        "src/test/java/eval/SafeDividerVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class SafeDividerVisibleTest {
                                    @Test void returnsEmptyForZero() {
                                        assertTrue(SafeDivider.divide(7, 0).isEmpty());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SafeDividerHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class SafeDividerHiddenTest {
                            @Test void keepsIntegerDivisionForPositiveAndNegativeValues() {
                                assertEquals(4, SafeDivider.divide(9, 2).orElseThrow());
                                assertEquals(-4, SafeDivider.divide(-9, 2).orElseThrow());
                            }
                        }
                        """),
                Set.of("src/main/java/eval/SafeDivider.java"));
    }

    private static ChangeSpecEvaluationCase slugifier() {
        return evaluationCase(
                "ascii-slugifier",
                ChangeSpecEvaluationTier.SMALL,
                """
                实现 Slugifier.slugify(String input)：
                1. null 或全空白输入返回空字符串；
                2. 使用 Locale.ROOT 转为小写；
                3. 连续的非 ASCII 字母/数字字符折叠成一个连字符；
                4. 删除结果首尾连字符；
                5. 只允许修改 src/main/java/eval/Slugifier.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/Slugifier.java", """
                                package eval;

                                public final class Slugifier {
                                    private Slugifier() { }

                                    public static String slugify(String input) {
                                        return input;
                                    }
                                }
                                """,
                        "src/test/java/eval/SlugifierVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class SlugifierVisibleTest {
                                    @Test void normalizesSimpleWords() {
                                        assertEquals("hello-world", Slugifier.slugify("  Hello WORLD  "));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SlugifierHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class SlugifierHiddenTest {
                            @Test void handlesEmptySeparatorsDigitsAndNonAscii() {
                                assertEquals("", Slugifier.slugify(null));
                                assertEquals("", Slugifier.slugify("   "));
                                assertEquals("api-v2-guide", Slugifier.slugify("API___v2 / Guide"));
                                assertEquals("caf-42", Slugifier.slugify("Café 42"));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/Slugifier.java"));
    }

    private static ChangeSpecEvaluationCase loginRetry() {
        return evaluationCase(
                "login-retry-policy",
                ChangeSpecEvaluationTier.MEDIUM,
                """
                修复 LoginRetrier 的登录重试策略：
                1. TIMEOUT 最多重试三次，即包含首次调用在内最多调用四次；
                2. UNAUTHORIZED 和 SERVER_ERROR 不得重试；
                3. 中途成功应立即返回；最终失败必须抛出最后一次 LoginFailure；
                4. 只允许修改 src/main/java/eval/LoginRetrier.java，不得修改其他源码、测试和 pom.xml；
                5. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/FailureKind.java", """
                                package eval;
                                public enum FailureKind { TIMEOUT, UNAUTHORIZED, SERVER_ERROR }
                                """,
                        "src/main/java/eval/LoginFailure.java", """
                                package eval;

                                public final class LoginFailure extends RuntimeException {
                                    private final FailureKind kind;
                                    public LoginFailure(FailureKind kind, String message) {
                                        super(message);
                                        this.kind = kind;
                                    }
                                    public FailureKind kind() { return kind; }
                                }
                                """,
                        "src/main/java/eval/LoginOperation.java", """
                                package eval;
                                @FunctionalInterface
                                public interface LoginOperation { String call(); }
                                """,
                        "src/main/java/eval/LoginRetrier.java", """
                                package eval;

                                public final class LoginRetrier {
                                    public String execute(LoginOperation operation) {
                                        LoginFailure last = null;
                                        for (int attempt = 0; attempt < 5; attempt++) {
                                            try {
                                                return operation.call();
                                            } catch (LoginFailure failure) {
                                                last = failure;
                                            }
                                        }
                                        throw last;
                                    }
                                }
                                """,
                        "src/test/java/eval/LoginRetrierVisibleTest.java", """
                                package eval;

                                import java.util.concurrent.atomic.AtomicInteger;
                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class LoginRetrierVisibleTest {
                                    @Test void doesNotRetryUnauthorized() {
                                        AtomicInteger calls = new AtomicInteger();
                                        assertThrows(LoginFailure.class, () -> new LoginRetrier().execute(() -> {
                                            calls.incrementAndGet();
                                            throw new LoginFailure(FailureKind.UNAUTHORIZED, "denied");
                                        }));
                                        assertEquals(1, calls.get());
                                    }

                                    @Test void timeoutCanRecover() {
                                        AtomicInteger calls = new AtomicInteger();
                                        String value = new LoginRetrier().execute(() -> {
                                            if (calls.incrementAndGet() < 3) {
                                                throw new LoginFailure(FailureKind.TIMEOUT, "slow");
                                            }
                                            return "ok";
                                        });
                                        assertEquals("ok", value);
                                        assertEquals(3, calls.get());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/LoginRetrierHiddenTest.java", """
                        package eval;

                        import java.util.concurrent.atomic.AtomicInteger;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class LoginRetrierHiddenTest {
                            @Test void persistentTimeoutStopsAfterThreeRetriesAndThrowsLastFailure() {
                                AtomicInteger calls = new AtomicInteger();
                                LoginFailure failure = assertThrows(LoginFailure.class,
                                        () -> new LoginRetrier().execute(() -> {
                                            int call = calls.incrementAndGet();
                                            throw new LoginFailure(FailureKind.TIMEOUT, "timeout-" + call);
                                        }));
                                assertEquals(4, calls.get());
                                assertEquals("timeout-4", failure.getMessage());
                            }

                            @Test void serverErrorsAreNotRetried() {
                                AtomicInteger calls = new AtomicInteger();
                                assertThrows(LoginFailure.class, () -> new LoginRetrier().execute(() -> {
                                    calls.incrementAndGet();
                                    throw new LoginFailure(FailureKind.SERVER_ERROR, "down");
                                }));
                                assertEquals(1, calls.get());
                            }
                        }
                        """),
                Set.of("src/main/java/eval/LoginRetrier.java"));
    }

    private static ChangeSpecEvaluationCase timeoutConfig() {
        return evaluationCase(
                "timeout-config-compat",
                ChangeSpecEvaluationTier.MEDIUM,
                """
                修复 TimeoutConfig.load(Map<String,String>, Properties) 的兼容性和校验：
                1. 系统属性 paicli.timeout.ms 优先，其次 PAICLI_TIMEOUT_MS，再其次旧变量 PAI_TIMEOUT_MS；
                2. 都未提供时使用 3000；
                3. 值必须是 100..60000 的整数，否则抛 IllegalArgumentException；
                4. 保持 timeoutMillis() 公共方法不变；
                5. 只允许修改 src/main/java/eval/TimeoutConfig.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/TimeoutConfig.java", """
                                package eval;

                                import java.util.Map;
                                import java.util.Properties;

                                public final class TimeoutConfig {
                                    private final int timeoutMillis;
                                    private TimeoutConfig(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
                                    public int timeoutMillis() { return timeoutMillis; }

                                    public static TimeoutConfig load(Map<String, String> env, Properties properties) {
                                        String raw = env.get("PAICLI_TIMEOUT_MS");
                                        return new TimeoutConfig(raw == null ? 3000 : Integer.parseInt(raw));
                                    }
                                }
                                """,
                        "src/test/java/eval/TimeoutConfigVisibleTest.java", """
                                package eval;

                                import java.util.Map;
                                import java.util.Properties;
                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class TimeoutConfigVisibleTest {
                                    @Test void propertyWinsAndDefaultIsStable() {
                                        Properties properties = new Properties();
                                        properties.setProperty("paicli.timeout.ms", "900");
                                        assertEquals(900, TimeoutConfig.load(
                                                Map.of("PAICLI_TIMEOUT_MS", "800"), properties).timeoutMillis());
                                        assertEquals(3000, TimeoutConfig.load(Map.of(), new Properties()).timeoutMillis());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/TimeoutConfigHiddenTest.java", """
                        package eval;

                        import java.util.Map;
                        import java.util.Properties;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class TimeoutConfigHiddenTest {
                            @Test void supportsNewAndLegacyEnvironmentNames() {
                                assertEquals(700, TimeoutConfig.load(
                                        Map.of("PAICLI_TIMEOUT_MS", "700", "PAI_TIMEOUT_MS", "600"),
                                        new Properties()).timeoutMillis());
                                assertEquals(600, TimeoutConfig.load(
                                        Map.of("PAI_TIMEOUT_MS", "600"), new Properties()).timeoutMillis());
                            }

                            @Test void rejectsInvalidValues() {
                                assertThrows(IllegalArgumentException.class,
                                        () -> TimeoutConfig.load(Map.of("PAICLI_TIMEOUT_MS", "99"), new Properties()));
                                assertThrows(IllegalArgumentException.class,
                                        () -> TimeoutConfig.load(Map.of("PAICLI_TIMEOUT_MS", "oops"), new Properties()));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/TimeoutConfig.java"));
    }

    private static ChangeSpecEvaluationCase workspacePath() {
        return evaluationCase(
                "workspace-path-safety",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                加固 WorkspacePath.resolve(Path root, String input)：
                1. 只允许解析 root 内的相对路径，并返回规范化的绝对路径；
                2. 拒绝 null、空白、绝对路径以及规范化后逃逸 root 的路径；
                3. 拒绝时统一抛 IllegalArgumentException；
                4. 不允许仅用字符串前缀判断目录边界；
                5. 只允许修改 src/main/java/eval/WorkspacePath.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/WorkspacePath.java", """
                                package eval;

                                import java.nio.file.Path;

                                public final class WorkspacePath {
                                    private WorkspacePath() { }
                                    public static Path resolve(Path root, String input) {
                                        return root.resolve(input).normalize();
                                    }
                                }
                                """,
                        "src/test/java/eval/WorkspacePathVisibleTest.java", """
                                package eval;

                                import java.nio.file.Path;
                                import org.junit.jupiter.api.Test;
                                import org.junit.jupiter.api.io.TempDir;
                                import static org.junit.jupiter.api.Assertions.*;

                                class WorkspacePathVisibleTest {
                                    @TempDir Path root;
                                    @Test void resolvesNestedPathAndRejectsTraversal() {
                                        assertEquals(root.resolve("src/Main.java").toAbsolutePath().normalize(),
                                                WorkspacePath.resolve(root, "src/Main.java"));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, "../outside.txt"));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/WorkspacePathHiddenTest.java", """
                        package eval;

                        import java.nio.file.Path;
                        import org.junit.jupiter.api.Test;
                        import org.junit.jupiter.api.io.TempDir;
                        import static org.junit.jupiter.api.Assertions.*;

                        class WorkspacePathHiddenTest {
                            @TempDir Path root;
                            @Test void rejectsBlankAbsoluteAndDeepTraversal() {
                                assertThrows(IllegalArgumentException.class,
                                        () -> WorkspacePath.resolve(root, "  "));
                                assertThrows(IllegalArgumentException.class,
                                        () -> WorkspacePath.resolve(root, root.resolve("absolute.txt").toString()));
                                assertThrows(IllegalArgumentException.class,
                                        () -> WorkspacePath.resolve(root, "a/../../outside.txt"));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/WorkspacePath.java"));
    }

    private static ChangeSpecEvaluationCase operationResultCompatibility() {
        return evaluationCase(
                "operation-result-api-compat",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                为 OperationResult<T> 增加稳定错误码，同时保持源兼容：
                1. 保留公共构造器 OperationResult(T value, String error) 和现有 value()/error()/isSuccess()；
                2. 新增三参数构造器 OperationResult(T value, String error, String errorCode)；
                3. 新增 errorCode()，旧构造器和 success() 创建的结果返回空字符串；
                4. 新增 failure(String message, String errorCode)，结果必须失败且保留消息和错误码；
                5. 只允许修改 src/main/java/eval/OperationResult.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/OperationResult.java", """
                                package eval;

                                public final class OperationResult<T> {
                                    private final T value;
                                    private final String error;

                                    public OperationResult(T value, String error) {
                                        this.value = value;
                                        this.error = error;
                                    }

                                    public static <T> OperationResult<T> success(T value) {
                                        return new OperationResult<>(value, null);
                                    }

                                    public T value() { return value; }
                                    public String error() { return error; }
                                    public boolean isSuccess() { return error == null; }
                                }
                                """,
                        "src/test/java/eval/OperationResultVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class OperationResultVisibleTest {
                                    @Test void exposesNewErrorCodeApi() {
                                        OperationResult<String> failed = OperationResult.failure("denied", "AUTH-403");
                                        assertFalse(failed.isSuccess());
                                        assertEquals("denied", failed.error());
                                        assertEquals("AUTH-403", failed.errorCode());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/OperationResultHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class OperationResultHiddenTest {
                            @Test void preservesOldConstructorAndSuccessFactory() {
                                OperationResult<Integer> old = new OperationResult<>(null, "old-error");
                                assertFalse(old.isSuccess());
                                assertEquals("", old.errorCode());
                                OperationResult<Integer> success = OperationResult.success(42);
                                assertTrue(success.isSuccess());
                                assertEquals(42, success.value());
                                assertEquals("", success.errorCode());
                            }

                            @Test void supportsExplicitThreeArgumentConstructor() {
                                OperationResult<String> value = new OperationResult<>(null, "bad", "E-1");
                                assertEquals("E-1", value.errorCode());
                            }
                        }
                        """),
                Set.of("src/main/java/eval/OperationResult.java"));
    }

    private static ChangeSpecEvaluationCase evaluationCase(
            String id,
            ChangeSpecEvaluationTier tier,
            String task,
            Map<String, String> visibleFiles,
            Map<String, String> hiddenFiles,
            Set<String> allowedChangedFiles
    ) {
        String includes = String.join(", ", allowedChangedFiles.stream().sorted().toList());
        String context = """
                这是隔离的 Java 17 Maven 评测项目。ChangeSpec 必须使用 bounded scope，include 只能包含：%s。
                pom.xml 和 src/test/** 必须排除。所有 Acceptance Criterion 必须是 deterministic；不要生成 Human Criterion。
                command Verifier 必须原样使用 `%s`，JUnit glob 使用 target/surefire-reports/TEST-*.xml，minimum_tests 至少为 1。
                """.formatted(includes, PUBLIC_VERIFIER);
        return new ChangeSpecEvaluationCase(
                id,
                tier,
                task.strip(),
                context.strip(),
                visibleFiles,
                hiddenFiles,
                allowedChangedFiles,
                PUBLIC_VERIFIER,
                mavenTestCommand(),
                Duration.ofMinutes(2));
    }

    private static List<String> mavenTestCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/d", "/c", PUBLIC_VERIFIER);
        }
        return List.of("sh", "-lc", PUBLIC_VERIFIER);
    }

    /** 仅供确定性基础设施测试证明六个 fixture 存在可通过公开和隐藏 Oracle 的实现。 */
    static Map<String, Map<String, String>> referenceSolutions() {
        return Map.of(
                "safe-divider", Map.of("src/main/java/eval/SafeDivider.java", """
                        package eval;
                        import java.util.OptionalInt;
                        public final class SafeDivider {
                            private SafeDivider() { }
                            public static OptionalInt divide(int dividend, int divisor) {
                                return divisor == 0 ? OptionalInt.empty() : OptionalInt.of(dividend / divisor);
                            }
                        }
                        """),
                "ascii-slugifier", Map.of("src/main/java/eval/Slugifier.java", """
                        package eval;
                        import java.util.Locale;
                        public final class Slugifier {
                            private Slugifier() { }
                            public static String slugify(String input) {
                                if (input == null || input.isBlank()) return "";
                                String value = input.toLowerCase(Locale.ROOT);
                                StringBuilder out = new StringBuilder();
                                boolean separator = false;
                                for (int i = 0; i < value.length(); i++) {
                                    char ch = value.charAt(i);
                                    if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                                        if (separator && !out.isEmpty()) out.append('-');
                                        out.append(ch);
                                        separator = false;
                                    } else {
                                        separator = true;
                                    }
                                }
                                return out.toString();
                            }
                        }
                        """),
                "login-retry-policy", Map.of("src/main/java/eval/LoginRetrier.java", """
                        package eval;
                        public final class LoginRetrier {
                            public String execute(LoginOperation operation) {
                                int retries = 0;
                                while (true) {
                                    try {
                                        return operation.call();
                                    } catch (LoginFailure failure) {
                                        if (failure.kind() != FailureKind.TIMEOUT || retries >= 3) throw failure;
                                        retries++;
                                    }
                                }
                            }
                        }
                        """),
                "timeout-config-compat", Map.of("src/main/java/eval/TimeoutConfig.java", """
                        package eval;
                        import java.util.Map;
                        import java.util.Properties;
                        public final class TimeoutConfig {
                            private final int timeoutMillis;
                            private TimeoutConfig(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
                            public int timeoutMillis() { return timeoutMillis; }
                            public static TimeoutConfig load(Map<String, String> env, Properties properties) {
                                String raw = properties.getProperty("paicli.timeout.ms");
                                if (raw == null) raw = env.get("PAICLI_TIMEOUT_MS");
                                if (raw == null) raw = env.get("PAI_TIMEOUT_MS");
                                if (raw == null) return new TimeoutConfig(3000);
                                try {
                                    int value = Integer.parseInt(raw);
                                    if (value < 100 || value > 60000) throw new IllegalArgumentException("range");
                                    return new TimeoutConfig(value);
                                } catch (NumberFormatException e) {
                                    throw new IllegalArgumentException("timeout must be an integer", e);
                                }
                            }
                        }
                        """),
                "workspace-path-safety", Map.of("src/main/java/eval/WorkspacePath.java", """
                        package eval;
                        import java.nio.file.Path;
                        public final class WorkspacePath {
                            private WorkspacePath() { }
                            public static Path resolve(Path root, String input) {
                                if (root == null || input == null || input.isBlank()) {
                                    throw new IllegalArgumentException("path is required");
                                }
                                Path relative = Path.of(input);
                                if (relative.isAbsolute()) throw new IllegalArgumentException("absolute path");
                                Path normalizedRoot = root.toAbsolutePath().normalize();
                                Path resolved = normalizedRoot.resolve(relative).normalize();
                                if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("escape");
                                return resolved;
                            }
                        }
                        """),
                "operation-result-api-compat", Map.of("src/main/java/eval/OperationResult.java", """
                        package eval;
                        public final class OperationResult<T> {
                            private final T value;
                            private final String error;
                            private final String errorCode;
                            public OperationResult(T value, String error) { this(value, error, ""); }
                            public OperationResult(T value, String error, String errorCode) {
                                this.value = value;
                                this.error = error;
                                this.errorCode = errorCode == null ? "" : errorCode;
                            }
                            public static <T> OperationResult<T> success(T value) {
                                return new OperationResult<>(value, null, "");
                            }
                            public static <T> OperationResult<T> failure(String message, String errorCode) {
                                return new OperationResult<>(null, message, errorCode);
                            }
                            public T value() { return value; }
                            public String error() { return error; }
                            public String errorCode() { return errorCode; }
                            public boolean isSuccess() { return error == null; }
                        }
                        """));
    }

    private static String fixturePom() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>eval</groupId>
                    <artifactId>change-spec-evaluation-fixture</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
    }
}
