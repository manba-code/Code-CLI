package com.paicli.spec.eval;

import java.time.Duration;
import java.util.LinkedHashMap;
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
                emailCanonicalizer(),
                inclusiveClamp(),
                loginRetry(),
                timeoutConfig(),
                featureFlagPrecedence(),
                orderStateMachine(),
                clarifiedDisplayName(),
                workspacePath(),
                operationResultCompatibility(),
                secretRedactor(),
                tokenExpiryPolicy(),
                budgetAllocator(),
                slidingWindowLimiter(),
                deadlineRetryRunner());
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
                List.of(
                        "divisor 为 0 时返回 OptionalInt.empty()",
                        "非零 divisor 保持 Java 整数除法语义"),
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

                                    @Test void keepsIntegerDivisionForNonZeroDivisors() {
                                        assertEquals(4, SafeDivider.divide(9, 2).orElseThrow());
                                        assertEquals(-4, SafeDivider.divide(-9, 2).orElseThrow());
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
                List.of(
                        "null 或全空白输入返回空字符串",
                        "使用 Locale.ROOT 转为小写",
                        "连续非 ASCII 字母数字字符折叠为单个连字符",
                        "删除结果首尾连字符"),
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

                                import java.util.Locale;
                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class SlugifierVisibleTest {
                                    @Test void returnsEmptyForNullAndBlank() {
                                        assertEquals("", Slugifier.slugify(null));
                                        assertEquals("", Slugifier.slugify("   "));
                                    }

                                    @Test void lowercasesWithLocaleRoot() {
                                        Locale previous = Locale.getDefault();
                                        try {
                                            Locale.setDefault(Locale.forLanguageTag("tr"));
                                            assertEquals("i", Slugifier.slugify("I"));
                                        } finally {
                                            Locale.setDefault(previous);
                                        }
                                    }

                                    @Test void foldsSeparatorRuns() {
                                        assertEquals("api-v2-guide", Slugifier.slugify("API___v2 / Guide"));
                                    }

                                    @Test void trimsLeadingAndTrailingSeparators() {
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

    private static ChangeSpecEvaluationCase emailCanonicalizer() {
        return evaluationCase(
                "email-canonicalizer",
                ChangeSpecEvaluationTier.SMALL,
                """
                实现 EmailCanonicalizer.canonicalize(String input)：
                1. null 或全空白输入返回 Optional.empty()；
                2. 去除首尾空白并使用 Locale.ROOT 小写；
                3. 只接受恰好一个 @ 且两侧非空的地址，否则返回 Optional.empty()；
                4. 不进行 IDN、Unicode 规范化或 plus-tag 改写；
                5. 只允许修改 src/main/java/eval/EmailCanonicalizer.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "null 或全空白输入返回 Optional.empty()",
                        "去除首尾空白并使用 Locale.ROOT 小写",
                        "只接受恰好一个 @ 且两侧非空的地址"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/EmailCanonicalizer.java", """
                                package eval;

                                import java.util.Optional;

                                public final class EmailCanonicalizer {
                                    private EmailCanonicalizer() { }
                                    public static Optional<String> canonicalize(String input) {
                                        return Optional.ofNullable(input);
                                    }
                                }
                                """,
                        "src/test/java/eval/EmailCanonicalizerVisibleTest.java", """
                                package eval;

                                import java.util.Locale;
                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class EmailCanonicalizerVisibleTest {
                                    @Test void rejectsMissingInput() {
                                        assertTrue(EmailCanonicalizer.canonicalize(null).isEmpty());
                                        assertTrue(EmailCanonicalizer.canonicalize("   ").isEmpty());
                                    }

                                    @Test void trimsAndLowercasesWithLocaleRoot() {
                                        Locale previous = Locale.getDefault();
                                        try {
                                            Locale.setDefault(Locale.forLanguageTag("tr"));
                                            assertEquals("info@example.com",
                                                    EmailCanonicalizer.canonicalize(" INFO@Example.COM ").orElseThrow());
                                        } finally {
                                            Locale.setDefault(previous);
                                        }
                                    }

                                    @Test void rejectsMalformedSeparatorCounts() {
                                        assertTrue(EmailCanonicalizer.canonicalize("missing-at").isEmpty());
                                        assertTrue(EmailCanonicalizer.canonicalize("@example.com").isEmpty());
                                        assertTrue(EmailCanonicalizer.canonicalize("a@b@c").isEmpty());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/EmailCanonicalizerHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class EmailCanonicalizerHiddenTest {
                            @Test void preservesCharactersOutsideTheDeclaredNormalization() {
                                assertEquals("user+tag@éxample.test",
                                        EmailCanonicalizer.canonicalize("User+Tag@Éxample.Test").orElseThrow());
                            }

                            @Test void rejectsEmptyDomain() {
                                assertTrue(EmailCanonicalizer.canonicalize("user@").isEmpty());
                            }
                        }
                        """),
                Set.of("src/main/java/eval/EmailCanonicalizer.java"));
    }

    private static ChangeSpecEvaluationCase inclusiveClamp() {
        return evaluationCase(
                "inclusive-clamp",
                ChangeSpecEvaluationTier.SMALL,
                """
                修复 IntClamp.clamp(int value, int minimum, int maximum)：
                1. value 小于 minimum 时返回 minimum，大于 maximum 时返回 maximum；
                2. 区间内值和两个边界值保持不变；
                3. minimum 大于 maximum 时抛 IllegalArgumentException；
                4. 保持现有公共静态方法签名，不增加饱和算术等额外行为；
                5. 只允许修改 src/main/java/eval/IntClamp.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "区间外值被限制到 minimum 或 maximum",
                        "区间内值和边界值保持不变",
                        "minimum 大于 maximum 时抛 IllegalArgumentException"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/IntClamp.java", """
                                package eval;

                                public final class IntClamp {
                                    private IntClamp() { }
                                    public static int clamp(int value, int minimum, int maximum) {
                                        return Math.min(value, maximum);
                                    }
                                }
                                """,
                        "src/test/java/eval/IntClampVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class IntClampVisibleTest {
                                    @Test void clampsValuesOutsideTheInterval() {
                                        assertEquals(10, IntClamp.clamp(5, 10, 20));
                                        assertEquals(20, IntClamp.clamp(25, 10, 20));
                                    }

                                    @Test void preservesInteriorAndBoundaryValues() {
                                        assertEquals(10, IntClamp.clamp(10, 10, 20));
                                        assertEquals(15, IntClamp.clamp(15, 10, 20));
                                        assertEquals(20, IntClamp.clamp(20, 10, 20));
                                    }

                                    @Test void rejectsInvertedIntervals() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> IntClamp.clamp(15, 20, 10));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/IntClampHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class IntClampHiddenTest {
                            @Test void handlesIntegerExtremesWithoutArithmetic() {
                                assertEquals(-1, IntClamp.clamp(Integer.MIN_VALUE, -1, 1));
                                assertEquals(1, IntClamp.clamp(Integer.MAX_VALUE, -1, 1));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/IntClamp.java"));
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
                List.of(
                        "TIMEOUT 包含首次调用在内最多调用四次",
                        "UNAUTHORIZED 和 SERVER_ERROR 不重试",
                        "中途成功立即返回",
                        "最终失败抛出最后一次 LoginFailure"),
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
                                    @Test void doesNotRetryNonTimeoutFailures() {
                                        AtomicInteger calls = new AtomicInteger();
                                        assertThrows(LoginFailure.class, () -> new LoginRetrier().execute(() -> {
                                            calls.incrementAndGet();
                                            throw new LoginFailure(FailureKind.UNAUTHORIZED, "denied");
                                        }));
                                        assertEquals(1, calls.get());

                                        calls.set(0);
                                        assertThrows(LoginFailure.class, () -> new LoginRetrier().execute(() -> {
                                            calls.incrementAndGet();
                                            throw new LoginFailure(FailureKind.SERVER_ERROR, "down");
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

                                    @Test void persistentTimeoutStopsAfterFourCalls() {
                                        AtomicInteger calls = new AtomicInteger();
                                        assertThrows(LoginFailure.class, () -> new LoginRetrier().execute(() -> {
                                            calls.incrementAndGet();
                                            throw new LoginFailure(FailureKind.TIMEOUT, "slow");
                                        }));
                                        assertEquals(4, calls.get());
                                    }

                                    @Test void finalFailureIsTheLastObservedFailure() {
                                        AtomicInteger calls = new AtomicInteger();
                                        LoginFailure failure = assertThrows(LoginFailure.class,
                                                () -> new LoginRetrier().execute(() -> {
                                                    int call = calls.incrementAndGet();
                                                    throw new LoginFailure(FailureKind.TIMEOUT, "timeout-" + call);
                                                }));
                                        assertEquals("timeout-4", failure.getMessage());
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
                List.of(
                        "系统属性、新环境变量、旧环境变量按顺序取值",
                        "未配置时使用 3000",
                        "只接受 100..60000 的整数",
                        "保持 timeoutMillis() 公共方法可用"),
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
                                    @Test void followsPropertyAndEnvironmentPrecedence() {
                                        Properties properties = new Properties();
                                        properties.setProperty("paicli.timeout.ms", "900");
                                        assertEquals(900, TimeoutConfig.load(
                                                Map.of("PAICLI_TIMEOUT_MS", "800", "PAI_TIMEOUT_MS", "700"),
                                                properties).timeoutMillis());
                                        assertEquals(800, TimeoutConfig.load(
                                                Map.of("PAICLI_TIMEOUT_MS", "800", "PAI_TIMEOUT_MS", "700"),
                                                new Properties()).timeoutMillis());
                                        assertEquals(700, TimeoutConfig.load(
                                                Map.of("PAI_TIMEOUT_MS", "700"), new Properties()).timeoutMillis());
                                    }

                                    @Test void defaultIsStable() {
                                        assertEquals(3000, TimeoutConfig.load(Map.of(), new Properties()).timeoutMillis());
                                    }

                                    @Test void rejectsNonIntegerAndOutOfRangeValues() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> TimeoutConfig.load(
                                                        Map.of("PAICLI_TIMEOUT_MS", "oops"), new Properties()));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> TimeoutConfig.load(
                                                        Map.of("PAICLI_TIMEOUT_MS", "99"), new Properties()));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> TimeoutConfig.load(
                                                        Map.of("PAICLI_TIMEOUT_MS", "60001"), new Properties()));
                                    }

                                    @Test void keepsTimeoutMillisAccessor() {
                                        TimeoutConfig config = TimeoutConfig.load(
                                                Map.of("PAICLI_TIMEOUT_MS", "1200"), new Properties());
                                        assertEquals(1200, config.timeoutMillis());
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

    private static ChangeSpecEvaluationCase featureFlagPrecedence() {
        return evaluationCase(
                "feature-flag-precedence",
                ChangeSpecEvaluationTier.MEDIUM,
                """
                修复 FeatureFlagConfig.load(Map<String,String>, Properties) 的兼容优先级：
                1. 系统属性 paicli.feature.preview 优先，其次 PAICLI_FEATURE_PREVIEW，再其次旧变量 PAI_FEATURE_PREVIEW；
                2. 都未提供时默认关闭；
                3. 只接受忽略大小写的 true 或 false，其他值抛 IllegalArgumentException；
                4. 保持 enabled() 公共方法不变，不新增自动猜测 1/0、yes/no；
                5. 只允许修改 src/main/java/eval/FeatureFlagConfig.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "系统属性、新环境变量、旧环境变量按顺序取值",
                        "未配置时默认关闭",
                        "只接受忽略大小写的 true 或 false",
                        "保持 enabled() 公共方法可用"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/FeatureFlagConfig.java", """
                                package eval;

                                import java.util.Map;
                                import java.util.Properties;

                                public final class FeatureFlagConfig {
                                    private final boolean enabled;
                                    private FeatureFlagConfig(boolean enabled) { this.enabled = enabled; }
                                    public boolean enabled() { return enabled; }

                                    public static FeatureFlagConfig load(
                                            Map<String, String> env, Properties properties) {
                                        return new FeatureFlagConfig(Boolean.parseBoolean(
                                                env.get("PAICLI_FEATURE_PREVIEW")));
                                    }
                                }
                                """,
                        "src/test/java/eval/FeatureFlagConfigVisibleTest.java", """
                                package eval;

                                import java.util.Map;
                                import java.util.Properties;
                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class FeatureFlagConfigVisibleTest {
                                    @Test void followsCompatibilityPrecedence() {
                                        Properties properties = new Properties();
                                        properties.setProperty("paicli.feature.preview", "false");
                                        assertFalse(FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "true",
                                                        "PAI_FEATURE_PREVIEW", "true"), properties).enabled());
                                        assertTrue(FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "true",
                                                        "PAI_FEATURE_PREVIEW", "false"),
                                                new Properties()).enabled());
                                        assertTrue(FeatureFlagConfig.load(
                                                Map.of("PAI_FEATURE_PREVIEW", "true"),
                                                new Properties()).enabled());
                                    }

                                    @Test void defaultsToDisabled() {
                                        assertFalse(FeatureFlagConfig.load(Map.of(), new Properties()).enabled());
                                    }

                                    @Test void acceptsOnlyBooleanWords() {
                                        assertTrue(FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "TrUe"),
                                                new Properties()).enabled());
                                        assertThrows(IllegalArgumentException.class,
                                                () -> FeatureFlagConfig.load(
                                                        Map.of("PAICLI_FEATURE_PREVIEW", "1"),
                                                        new Properties()));
                                    }

                                    @Test void keepsEnabledAccessor() {
                                        FeatureFlagConfig config = FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "false"),
                                                new Properties());
                                        assertFalse(config.enabled());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/FeatureFlagConfigHiddenTest.java", """
                        package eval;

                        import java.util.Map;
                        import java.util.Properties;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class FeatureFlagConfigHiddenTest {
                            @Test void rejectsCommonButUndeclaredAliases() {
                                assertThrows(IllegalArgumentException.class,
                                        () -> FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "yes"),
                                                new Properties()));
                                assertThrows(IllegalArgumentException.class,
                                        () -> FeatureFlagConfig.load(
                                                Map.of("PAICLI_FEATURE_PREVIEW", "0"),
                                                new Properties()));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/FeatureFlagConfig.java"));
    }

    private static ChangeSpecEvaluationCase orderStateMachine() {
        return evaluationCase(
                "order-state-machine",
                ChangeSpecEvaluationTier.MEDIUM,
                """
                修复 OrderTransitions.next(OrderState current, OrderEvent event)：
                1. NEW 可通过 PAY 进入 PAID，或通过 CANCEL 进入 CANCELLED；
                2. PAID 可通过 SHIP 进入 SHIPPED，或通过 CANCEL 进入 CANCELLED；
                3. SHIPPED 与 CANCELLED 是终态，任何事件都必须拒绝；
                4. null 或未声明的状态/事件组合统一抛 IllegalArgumentException；
                5. 只允许修改 src/main/java/eval/OrderTransitions.java，不得修改枚举、测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "NEW 支持 PAY 和 CANCEL 转移",
                        "PAID 支持 SHIP 和 CANCEL 转移",
                        "SHIPPED 与 CANCELLED 拒绝所有事件",
                        "null 和未声明组合抛 IllegalArgumentException"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/OrderState.java", """
                                package eval;
                                public enum OrderState { NEW, PAID, SHIPPED, CANCELLED }
                                """,
                        "src/main/java/eval/OrderEvent.java", """
                                package eval;
                                public enum OrderEvent { PAY, SHIP, CANCEL }
                                """,
                        "src/main/java/eval/OrderTransitions.java", """
                                package eval;

                                public final class OrderTransitions {
                                    private OrderTransitions() { }
                                    public static OrderState next(OrderState current, OrderEvent event) {
                                        return event == OrderEvent.CANCEL ? OrderState.CANCELLED : current;
                                    }
                                }
                                """,
                        "src/test/java/eval/OrderTransitionsVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class OrderTransitionsVisibleTest {
                                    @Test void transitionsFromNew() {
                                        assertEquals(OrderState.PAID,
                                                OrderTransitions.next(OrderState.NEW, OrderEvent.PAY));
                                        assertEquals(OrderState.CANCELLED,
                                                OrderTransitions.next(OrderState.NEW, OrderEvent.CANCEL));
                                    }

                                    @Test void transitionsFromPaid() {
                                        assertEquals(OrderState.SHIPPED,
                                                OrderTransitions.next(OrderState.PAID, OrderEvent.SHIP));
                                        assertEquals(OrderState.CANCELLED,
                                                OrderTransitions.next(OrderState.PAID, OrderEvent.CANCEL));
                                    }

                                    @Test void rejectsEventsFromTerminalStates() {
                                        for (OrderEvent event : OrderEvent.values()) {
                                            assertThrows(IllegalArgumentException.class,
                                                    () -> OrderTransitions.next(OrderState.SHIPPED, event));
                                            assertThrows(IllegalArgumentException.class,
                                                    () -> OrderTransitions.next(OrderState.CANCELLED, event));
                                        }
                                    }

                                    @Test void rejectsNullAndUndeclaredCombinations() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> OrderTransitions.next(null, OrderEvent.PAY));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> OrderTransitions.next(OrderState.NEW, null));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> OrderTransitions.next(OrderState.NEW, OrderEvent.SHIP));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/OrderTransitionsHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class OrderTransitionsHiddenTest {
                            @Test void paidCannotBePaidAgain() {
                                assertThrows(IllegalArgumentException.class,
                                        () -> OrderTransitions.next(OrderState.PAID, OrderEvent.PAY));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/OrderTransitions.java"));
    }

    private static ChangeSpecEvaluationCase clarifiedDisplayName() {
        return evaluationCase(
                "clarified-display-name",
                ChangeSpecEvaluationTier.MEDIUM,
                """
                原始需求（存在歧义）：让 DisplayNameNormalizer.normalize(String) 更友好地处理名字，太长时截短。

                统一用户澄清记录（A/B/C 三组收到完全相同的记录）：
                1. null、空串或只含 Unicode whitespace/space character 的输入返回 Anonymous；
                2. 去除首尾空白，并把连续 Unicode whitespace/space character 折叠为一个 ASCII 空格；
                3. 大小写、标点和所有非空白 code point 必须保持不变，不做拼写、Locale 或字符替换；
                4. 最长 20 个 Unicode code point；超过时保留前 19 个 code point 并追加单字符省略号 …，不得按 UTF-16 char 截断代理对；
                5. 只允许修改 src/main/java/eval/DisplayNameNormalizer.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "空值与全空白输入使用 Anonymous",
                        "首尾空白移除且连续 Unicode 空白折叠为 ASCII 空格",
                        "大小写、标点和非空白 code point 原样保留",
                        "按 Unicode code point 执行 20 字符边界和省略号截断"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/DisplayNameNormalizer.java", """
                                package eval;

                                public final class DisplayNameNormalizer {
                                    private DisplayNameNormalizer() { }
                                    public static String normalize(String input) {
                                        if (input == null || input.isBlank()) return "Anonymous";
                                        String value = input.trim().replaceAll("\\s+", " ");
                                        return value.length() <= 20
                                                ? value
                                                : value.substring(0, 19) + "…";
                                    }
                                }
                                """,
                        "src/test/java/eval/DisplayNameNormalizerVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class DisplayNameNormalizerVisibleTest {
                                    @Test void substitutesAnonymousForMissingNames() {
                                        assertEquals("Anonymous", DisplayNameNormalizer.normalize(null));
                                        assertEquals("Anonymous", DisplayNameNormalizer.normalize(" \\t \\n"));
                                    }

                                    @Test void trimsAndCollapsesUnicodeWhitespace() {
                                        assertEquals("Ada Lovelace",
                                                DisplayNameNormalizer.normalize("\\t Ada   Lovelace \\n"));
                                    }

                                    @Test void preservesCasePunctuationAndNonWhitespace() {
                                        assertEquals("Mc'DONALD-Jr.",
                                                DisplayNameNormalizer.normalize("  Mc'DONALD-Jr.  "));
                                    }

                                    @Test void truncatesByUnicodeCodePoint() {
                                        assertEquals("abcdefghijklmnopqrst",
                                                DisplayNameNormalizer.normalize("abcdefghijklmnopqrst"));
                                        assertEquals("abcdefghijklmnopqrs…",
                                                DisplayNameNormalizer.normalize("abcdefghijklmnopqrstu"));
                                        assertEquals("abcdefghijklmnopqrs😀",
                                                DisplayNameNormalizer.normalize("abcdefghijklmnopqrs😀"));
                                        assertEquals("abcdefghijklmnopqr😀…",
                                                DisplayNameNormalizer.normalize("abcdefghijklmnopqr😀xy"));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/DisplayNameNormalizerHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class DisplayNameNormalizerHiddenTest {
                            @Test void treatsNoBreakSpaceAsSpaceCharacter() {
                                assertEquals("Ada Lovelace",
                                        DisplayNameNormalizer.normalize("Ada\u00a0\u00a0Lovelace"));
                            }

                            @Test void keepsExactlyNineteenCodePointsBeforeEllipsis() {
                                String result = DisplayNameNormalizer.normalize("123456789012345678😀ab");
                                assertEquals("123456789012345678😀…", result);
                                assertEquals(20, result.codePointCount(0, result.length()));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/DisplayNameNormalizer.java"));
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
                List.of(
                        "root 内相对路径解析为规范化绝对路径",
                        "拒绝 null、空白、绝对路径和规范化逃逸",
                        "所有拒绝统一抛 IllegalArgumentException",
                        "目录边界使用 Path 语义而不是字符串前缀"),
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

                                    @Test void resolvesNestedPathToNormalizedAbsolutePath() {
                                        assertEquals(root.resolve("src/Main.java").toAbsolutePath().normalize(),
                                                WorkspacePath.resolve(root, "src/Main.java"));
                                    }

                                    @Test void rejectsInvalidAndEscapingInputs() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, null));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, "  "));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, root.resolve("absolute.txt").toString()));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, "../outside.txt"));
                                    }

                                    @Test void rejectionUsesIllegalArgumentException() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, "a/../../outside.txt"));
                                    }

                                    @Test void siblingWithSharedStringPrefixIsOutsideRoot() {
                                        Path sibling = root.resolveSibling(root.getFileName() + "-other");
                                        Path relativeEscape = root.relativize(sibling.resolve("file.txt"));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> WorkspacePath.resolve(root, relativeEscape.toString()));
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
                List.of(
                        "保留旧构造器和 value/error/isSuccess API",
                        "新增三参数构造器",
                        "errorCode() 对旧构造器和 success() 返回空字符串",
                        "failure(message, errorCode) 保留失败消息和错误码"),
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
                                    @Test void preservesOldConstructorAndAccessors() {
                                        OperationResult<Integer> old = new OperationResult<>(null, "old-error");
                                        assertFalse(old.isSuccess());
                                        assertEquals("old-error", old.error());
                                        assertNull(old.value());
                                    }

                                    @Test void supportsThreeArgumentConstructor() {
                                        OperationResult<String> value = new OperationResult<>(null, "bad", "E-1");
                                        assertEquals("E-1", value.errorCode());
                                    }

                                    @Test void oldConstructorAndSuccessUseEmptyErrorCode() {
                                        assertEquals("", new OperationResult<>(null, "old-error").errorCode());
                                        assertEquals("", OperationResult.success(42).errorCode());
                                    }

                                    @Test void failureFactoryPreservesMessageAndCode() {
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

    private static ChangeSpecEvaluationCase secretRedactor() {
        return evaluationCase(
                "secret-redactor",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                加固 SecretRedactor.redact(String input) 的日志脱敏：
                1. null 返回空字符串，普通文本原样返回；
                2. 查询式 token= 和 api_key= 的值替换为 ***，键名匹配忽略大小写并保留键名；
                3. Bearer 凭据替换为 Bearer ***，scheme 匹配忽略大小写；
                4. 不得误伤 monkey、tokenized 等普通单词，不解析 JSON 或改变其他文本；
                5. 只允许修改 src/main/java/eval/SecretRedactor.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "null 与普通文本安全返回",
                        "token 和 api_key 查询值按键边界脱敏",
                        "Bearer 凭据忽略 scheme 大小写脱敏",
                        "不误伤包含敏感词片段的普通单词"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/SecretRedactor.java", """
                                package eval;

                                public final class SecretRedactor {
                                    private SecretRedactor() { }
                                    public static String redact(String input) {
                                        return input;
                                    }
                                }
                                """,
                        "src/test/java/eval/SecretRedactorVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class SecretRedactorVisibleTest {
                                    @Test void handlesNullAndBenignText() {
                                        assertEquals("", SecretRedactor.redact(null));
                                        assertEquals("request completed", SecretRedactor.redact("request completed"));
                                    }

                                    @Test void redactsQueryStyleSecretsCaseInsensitively() {
                                        assertEquals("url?token=***&API_KEY=***&page=1",
                                                SecretRedactor.redact(
                                                        "url?token=abc123&API_KEY=top-secret&page=1"));
                                    }

                                    @Test void redactsBearerCredentials() {
                                        assertEquals("Authorization: Bearer ***",
                                                SecretRedactor.redact("Authorization: bearer abc.DEF-123"));
                                    }

                                    @Test void preservesWordsContainingSensitiveFragments() {
                                        assertEquals("monkey tokenized api_keys",
                                                SecretRedactor.redact("monkey tokenized api_keys"));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SecretRedactorHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class SecretRedactorHiddenTest {
                            @Test void stopsQuerySecretAtWhitespaceOrAmpersand() {
                                assertEquals("token=*** next api_key=***&safe=yes",
                                        SecretRedactor.redact(
                                                "token=first next api_key=second&safe=yes"));
                            }

                            @Test void leavesJsonUntouchedAsDeclaredNonGoal() {
                                assertEquals("{\\\"token\\\":\\\"plain\\\"}",
                                        SecretRedactor.redact("{\\\"token\\\":\\\"plain\\\"}"));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/SecretRedactor.java"));
    }

    private static ChangeSpecEvaluationCase tokenExpiryPolicy() {
        return evaluationCase(
                "token-expiry-cross-file",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                完成 TokenClaims 与 TokenPolicy 的跨文件过期策略：
                1. 保留 TokenClaims(long expiresAtEpochSecond) 构造器和 expiresAtEpochSecond()；
                2. 为 TokenClaims 新增 isExpired(long nowEpochSecond)，expiresAt <= now 时为过期；
                3. TokenPolicy.isUsable(TokenClaims claims, long nowEpochSecond, long skewSeconds) 在 claims 非空、skew 非负且 token 在 now+skew 后仍有效时返回 true；
                4. now+skew 必须防 long 溢出，溢出视为已过期；
                5. 只允许修改 src/main/java/eval/TokenClaims.java 和 src/main/java/eval/TokenPolicy.java，不得修改测试和 pom.xml；
                6. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "保留 TokenClaims 构造器与 expiresAtEpochSecond()",
                        "TokenClaims.isExpired 使用 expiresAt <= now 边界",
                        "TokenPolicy 校验 null、负 skew 和提前量后的有效性",
                        "now+skew 溢出时安全判为不可用"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/TokenClaims.java", """
                                package eval;

                                public final class TokenClaims {
                                    private final long expiresAtEpochSecond;
                                    public TokenClaims(long expiresAtEpochSecond) {
                                        this.expiresAtEpochSecond = expiresAtEpochSecond;
                                    }
                                    public long expiresAtEpochSecond() { return expiresAtEpochSecond; }
                                }
                                """,
                        "src/main/java/eval/TokenPolicy.java", """
                                package eval;

                                public final class TokenPolicy {
                                    private TokenPolicy() { }
                                    public static boolean isUsable(
                                            TokenClaims claims, long nowEpochSecond, long skewSeconds) {
                                        return claims.expiresAtEpochSecond() >= nowEpochSecond + skewSeconds;
                                    }
                                }
                                """,
                        "src/test/java/eval/TokenPolicyVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class TokenPolicyVisibleTest {
                                    @Test void preservesClaimsApi() {
                                        TokenClaims claims = new TokenClaims(120);
                                        assertEquals(120, claims.expiresAtEpochSecond());
                                    }

                                    @Test void claimsExpiryUsesInclusiveBoundary() {
                                        TokenClaims claims = new TokenClaims(120);
                                        assertFalse(claims.isExpired(119));
                                        assertTrue(claims.isExpired(120));
                                        assertTrue(claims.isExpired(121));
                                    }

                                    @Test void policyValidatesInputsAndSkew() {
                                        assertThrows(IllegalArgumentException.class,
                                                () -> TokenPolicy.isUsable(null, 100, 0));
                                        assertThrows(IllegalArgumentException.class,
                                                () -> TokenPolicy.isUsable(new TokenClaims(120), 100, -1));
                                        assertTrue(TokenPolicy.isUsable(new TokenClaims(121), 100, 20));
                                        assertFalse(TokenPolicy.isUsable(new TokenClaims(120), 100, 20));
                                    }

                                    @Test void overflowIsNotUsable() {
                                        assertFalse(TokenPolicy.isUsable(
                                                new TokenClaims(Long.MAX_VALUE), Long.MAX_VALUE - 5, 10));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/TokenPolicyHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class TokenPolicyHiddenTest {
                            @Test void zeroSkewDelegatesToClaimsBoundary() {
                                assertTrue(TokenPolicy.isUsable(new TokenClaims(101), 100, 0));
                                assertFalse(TokenPolicy.isUsable(new TokenClaims(100), 100, 0));
                            }

                            @Test void handlesLargeButSafeSkew() {
                                assertTrue(TokenPolicy.isUsable(
                                        new TokenClaims(Long.MAX_VALUE), Long.MAX_VALUE - 10, 5));
                            }
                        }
                        """),
                Set.of(
                        "src/main/java/eval/TokenClaims.java",
                        "src/main/java/eval/TokenPolicy.java"));
    }

    private static ChangeSpecEvaluationCase budgetAllocator() {
        return evaluationCase(
                "budget-allocator",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                实现 BudgetAllocator.allocate(long total, List<Long> weights) 返回 List<Long>：
                1. weights 为 null、为空、含 null 元素或含 <= 0 的权重时抛 IllegalArgumentException；total < 0 时抛 IllegalArgumentException；
                2. total == 0 时返回与 weights 等长的全 0 列表；
                3. 每份的基础值为 floor(total × w ÷ W)，W 为全部权重之和；算术必须精确且在 long 范围内溢出安全，不得用 double/float 逼近；
                4. 基础值分配后剩余的余量按各份小数部分（total × w ÷ W 的小数部分）从大到小逐份加一，直至分完；
                5. 小数部分相同时索引更小的份先得到加一；
                6. 任何合法输入下返回列表元素之和必须恰好等于 total；
                7. 只允许修改 src/main/java/eval/BudgetAllocator.java，不得修改测试和 pom.xml；
                8. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "整除时按权重比例精确分配",
                        "余量按小数部分从大到小分配，同小数部分时索引小者优先",
                        "total 为 0 时返回全 0 列表",
                        "非法 total 或非法 weights 抛 IllegalArgumentException",
                        "任意合法输入下份额之和恒等于 total"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/BudgetAllocator.java", """
                                package eval;

                                import java.util.List;

                                public final class BudgetAllocator {
                                    private BudgetAllocator() { }

                                    public static List<Long> allocate(long total, List<Long> weights) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }
                                }
                                """,
                        "src/test/java/eval/BudgetAllocatorVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                import java.util.List;

                                class BudgetAllocatorVisibleTest {
                                    @Test void splitsEvenlyWhenWeightsSumDividesTotal() {
                                        assertEquals(List.of(50L, 50L), BudgetAllocator.allocate(100L, List.of(50L, 50L)));
                                        assertEquals(List.of(10L, 20L, 70L), BudgetAllocator.allocate(100L, List.of(1L, 2L, 7L)));
                                    }

                                    @Test void distributesRemainderToLargestFractionalPart() {
                                        assertEquals(List.of(1L, 3L, 6L), BudgetAllocator.allocate(10L, List.of(1L, 2L, 4L)));
                                    }

                                    @Test void returnsAllZerosForZeroTotal() {
                                        assertEquals(List.of(0L, 0L, 0L), BudgetAllocator.allocate(0L, List.of(3L, 7L, 5L)));
                                    }

                                    @Test void rejectsInvalidTotalsAndWeights() {
                                        assertThrows(IllegalArgumentException.class, () -> BudgetAllocator.allocate(-1L, List.of(1L)));
                                        assertThrows(IllegalArgumentException.class, () -> BudgetAllocator.allocate(10L, null));
                                        assertThrows(IllegalArgumentException.class, () -> BudgetAllocator.allocate(10L, List.of()));
                                        assertThrows(IllegalArgumentException.class, () -> BudgetAllocator.allocate(10L, List.of(1L, 0L)));
                                        assertThrows(IllegalArgumentException.class, () -> BudgetAllocator.allocate(10L, List.of(1L, -2L)));
                                    }

                                    @Test void keepsSumExactForAwkwardSplits() {
                                        List<Long> shares = BudgetAllocator.allocate(7L, List.of(1L, 1L, 1L));
                                        assertEquals(7L, shares.stream().mapToLong(Long::longValue).sum());
                                        assertEquals(List.of(3L, 2L, 2L), shares);
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/BudgetAllocatorHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        import java.util.Arrays;
                        import java.util.List;

                        class BudgetAllocatorHiddenTest {
                            @Test void staysExactNearLongMaxWithoutOverflow() {
                                long half = Long.MAX_VALUE / 2;
                                List<Long> shares = BudgetAllocator.allocate(Long.MAX_VALUE - 1, List.of(half, half + 1));
                                assertEquals(List.of(half, half), shares);
                                assertEquals(Long.MAX_VALUE - 1, shares.stream().mapToLong(Long::longValue).sum());
                            }

                            @Test void resolvesTiesTowardsLowerIndices() {
                                assertEquals(List.of(1L, 0L), BudgetAllocator.allocate(1L, List.of(1L, 1L)));
                                assertEquals(List.of(3L, 3L, 2L, 2L), BudgetAllocator.allocate(10L, List.of(1L, 1L, 1L, 1L)));
                                assertEquals(List.of(1L, 0L, 0L), BudgetAllocator.allocate(1L, List.of(2L, 2L, 2L)));
                            }

                            @Test void keepsSumsExactForAwkwardWeights() {
                                List<Long> shares = BudgetAllocator.allocate(999_983L, List.of(7L, 11L, 13L, 17L));
                                assertEquals(999_983L, shares.stream().mapToLong(Long::longValue).sum());
                                List<Long> bigShares = BudgetAllocator.allocate(
                                        Long.MAX_VALUE - 2, List.of(3L, 5L, 7L, 11L, 101L));
                                assertEquals(Long.MAX_VALUE - 2, bigShares.stream().mapToLong(Long::longValue).sum());
                            }

                            @Test void rejectsNullWeightElements() {
                                assertThrows(IllegalArgumentException.class,
                                        () -> BudgetAllocator.allocate(10L, Arrays.asList(1L, null)));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/BudgetAllocator.java"));
    }

    private static ChangeSpecEvaluationCase slidingWindowLimiter() {
        return evaluationCase(
                "sliding-window-limiter",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                实现滑动窗口限流器 SlidingWindowLimiter：
                1. 构造函数 SlidingWindowLimiter(int maxPerWindow, long windowMillis)：任一参数 <= 0 抛 IllegalArgumentException；
                2. boolean tryAcquire(long nowMillis)：以 nowMillis 为窗口右端点统计落在 (nowMillis - windowMillis, nowMillis] 内的既有获取次数（左端点不含、右端点含）；若加上本次后不超过 maxPerWindow，则记录本次获取并返回 true；否则返回 false 且不记录；
                3. 相同毫秒时间戳允许多次获取，每次独立计数；
                4. int availablePermits(long nowMillis)：返回 maxPerWindow 减去当前窗口内既有获取次数，最小为 0；本方法既不产生也不清除获取记录；
                5. tryAcquire 与 availablePermits 的 nowMillis 不得小于此前任一方法的最近调用值，否则抛 IllegalArgumentException；
                6. 只允许修改 src/main/java/eval/SlidingWindowLimiter.java，不得修改测试和 pom.xml；
                7. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "窗口内获取数不超过 maxPerWindow，超出时拒绝且不记录",
                        "availablePermits 反映剩余额度且不产生也不清除记录",
                        "非法构造参数与时间回退抛 IllegalArgumentException",
                        "同一毫秒的多次获取独立计数"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/SlidingWindowLimiter.java", """
                                package eval;

                                public final class SlidingWindowLimiter {
                                    public SlidingWindowLimiter(int maxPerWindow, long windowMillis) {
                                    }

                                    public boolean tryAcquire(long nowMillis) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }

                                    public int availablePermits(long nowMillis) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }
                                }
                                """,
                        "src/test/java/eval/SlidingWindowLimiterVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                class SlidingWindowLimiterVisibleTest {
                                    @Test void rejectsAcquireBeyondMaxInsideWindow() {
                                        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 100L);
                                        assertTrue(limiter.tryAcquire(10L));
                                        assertTrue(limiter.tryAcquire(20L));
                                        assertFalse(limiter.tryAcquire(50L));
                                        assertTrue(limiter.tryAcquire(111L));
                                    }

                                    @Test void exposesAvailablePermitsWithoutConsuming() {
                                        SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, 100L);
                                        assertEquals(3, limiter.availablePermits(0L));
                                        assertTrue(limiter.tryAcquire(5L));
                                        assertEquals(2, limiter.availablePermits(10L));
                                        assertEquals(2, limiter.availablePermits(10L));
                                        assertTrue(limiter.tryAcquire(10L));
                                        assertEquals(1, limiter.availablePermits(10L));
                                    }

                                    @Test void rejectsInvalidConstructorAndBackwardsClock() {
                                        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(0, 100L));
                                        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(2, 0L));
                                        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 100L);
                                        assertTrue(limiter.tryAcquire(100L));
                                        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(50L));
                                        assertThrows(IllegalArgumentException.class, () -> limiter.availablePermits(50L));
                                    }

                                    @Test void countsRepeatedAcquireAtSameTimestamp() {
                                        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 100L);
                                        assertTrue(limiter.tryAcquire(7L));
                                        assertTrue(limiter.tryAcquire(7L));
                                        assertFalse(limiter.tryAcquire(7L));
                                        assertEquals(0, limiter.availablePermits(7L));
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SlidingWindowLimiterHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        class SlidingWindowLimiterHiddenTest {
                            @Test void windowLeftBoundaryIsHalfOpen() {
                                SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 100L);
                                assertTrue(limiter.tryAcquire(0L));
                                assertFalse(limiter.tryAcquire(99L));
                                assertTrue(limiter.tryAcquire(100L));
                            }

                            @Test void availablePermitsFollowsSlidingWindowAndClampsAtZero() {
                                SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 50L);
                                assertTrue(limiter.tryAcquire(10L));
                                assertTrue(limiter.tryAcquire(20L));
                                assertEquals(0, limiter.availablePermits(30L));
                                assertEquals(1, limiter.availablePermits(69L));
                                assertEquals(2, limiter.availablePermits(70L));
                            }

                            @Test void permitsQueriesDoNotEraseAcquireRecords() {
                                SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 100L);
                                assertTrue(limiter.tryAcquire(10L));
                                assertEquals(0, limiter.availablePermits(50L));
                                assertFalse(limiter.tryAcquire(50L));
                                assertTrue(limiter.tryAcquire(110L));
                            }

                            @Test void clockMonotonicityIsSharedAcrossBothMethods() {
                                SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 100L);
                                assertTrue(limiter.tryAcquire(50L));
                                assertEquals(1, limiter.availablePermits(60L));
                                assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(59L));
                                assertThrows(IllegalArgumentException.class, () -> limiter.availablePermits(59L));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/SlidingWindowLimiter.java"));
    }

    private static ChangeSpecEvaluationCase deadlineRetryRunner() {
        return evaluationCase(
                "deadline-retry-runner",
                ChangeSpecEvaluationTier.HIGH_RISK,
                """
                实现跨文件的重试预算与截止时间执行器：
                1. RetryBudget(int transientAttempts, int timeoutAttempts)：任一参数 < 1 抛 IllegalArgumentException；两个参数都是包含首次尝试在内的总尝试次数上限；
                2. RetryBudget.maxAttempts(FailureKind kind)：TRANSIENT → transientAttempts；TIMEOUT → timeoutAttempts；PERMANENT → 1；kind 为 null 抛 IllegalArgumentException；
                3. RetryBudget.canRetry(FailureKind kind, int attemptsSoFar)：kind 为 null 或 attemptsSoFar < 0 抛 IllegalArgumentException；PERMANENT 一律返回 false；其余返回 attemptsSoFar < maxAttempts(kind)；
                4. DeadlineRetryRunner(RetryBudget budget, long deadlineEpochMs)：budget 为 null 抛 IllegalArgumentException；
                5. DeadlineRetryRunner.execute(Task task, LongSupplier clock)：每次尝试（包含第一次）之前先取 clock.getAsLong()，若该时刻 >= deadlineEpochMs 则抛 DeadlineExceededException，即使重试预算尚未用尽；
                6. 尝试成功则立即返回 Task.call() 的结果；
                7. 尝试抛出 TaskFailure 后：attemptsSoFar 加一；若 canRetry(kind, attemptsSoFar) 为 true 则回到第 5 步继续，否则原样重抛该 TaskFailure；
                8. FailureKind、Task、TaskFailure、DeadlineExceededException 已提供且不得修改；
                9. 只允许修改 src/main/java/eval/RetryBudget.java 和 src/main/java/eval/DeadlineRetryRunner.java，不得修改测试和 pom.xml；
                10. 公开验证命令必须使用：mvn -q -DskipTests=false test。
                """,
                List.of(
                        "TRANSIENT/TIMEOUT 在各自总尝试次数上限内重试直至成功",
                        "预算耗尽时原样重抛最后一次 TaskFailure",
                        "PERMANENT 失败不重试，立即重抛",
                        "每次尝试前（含首次）检查截止时间，超时抛 DeadlineExceededException"),
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/FailureKind.java", """
                                package eval;

                                public enum FailureKind {
                                    TRANSIENT,
                                    TIMEOUT,
                                    PERMANENT
                                }
                                """,
                        "src/main/java/eval/Task.java", """
                                package eval;

                                @FunctionalInterface
                                public interface Task {
                                    String call();
                                }
                                """,
                        "src/main/java/eval/TaskFailure.java", """
                                package eval;

                                public final class TaskFailure extends RuntimeException {
                                    private final FailureKind kind;

                                    public TaskFailure(FailureKind kind, String message) {
                                        super(message);
                                        if (kind == null) {
                                            throw new IllegalArgumentException("kind must not be null");
                                        }
                                        this.kind = kind;
                                    }

                                    public FailureKind kind() {
                                        return kind;
                                    }
                                }
                                """,
                        "src/main/java/eval/DeadlineExceededException.java", """
                                package eval;

                                public final class DeadlineExceededException extends RuntimeException {
                                    public DeadlineExceededException(String message) {
                                        super(message);
                                    }
                                }
                                """,
                        "src/main/java/eval/RetryBudget.java", """
                                package eval;

                                public final class RetryBudget {
                                    public RetryBudget(int transientAttempts, int timeoutAttempts) {
                                    }

                                    public int maxAttempts(FailureKind kind) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }

                                    public boolean canRetry(FailureKind kind, int attemptsSoFar) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }
                                }
                                """,
                        "src/main/java/eval/DeadlineRetryRunner.java", """
                                package eval;

                                import java.util.function.LongSupplier;

                                public final class DeadlineRetryRunner {
                                    public DeadlineRetryRunner(RetryBudget budget, long deadlineEpochMs) {
                                    }

                                    public String execute(Task task, LongSupplier clock) {
                                        throw new UnsupportedOperationException("not implemented");
                                    }
                                }
                                """,
                        "src/test/java/eval/DeadlineRetryVisibleTest.java", """
                                package eval;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.*;

                                import java.util.concurrent.atomic.AtomicInteger;
                                import java.util.concurrent.atomic.AtomicLong;

                                class DeadlineRetryVisibleTest {
                                    @Test void retriesTransientFailuresUntilSuccess() {
                                        AtomicInteger attempts = new AtomicInteger();
                                        AtomicLong clock = new AtomicLong(0L);
                                        Task task = () -> {
                                            if (attempts.incrementAndGet() < 3) {
                                                throw new TaskFailure(FailureKind.TRANSIENT, "flaky");
                                            }
                                            return "ok";
                                        };
                                        String result = new DeadlineRetryRunner(new RetryBudget(3, 2), 1_000L)
                                                .execute(task, clock::get);
                                        assertEquals("ok", result);
                                        assertEquals(3, attempts.get());
                                    }

                                    @Test void rethrowsLastFailureWhenBudgetIsExhausted() {
                                        AtomicInteger attempts = new AtomicInteger();
                                        AtomicLong clock = new AtomicLong(0L);
                                        Task task = () -> {
                                            attempts.incrementAndGet();
                                            throw new TaskFailure(FailureKind.TIMEOUT, "slow");
                                        };
                                        DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(2, 2), 1_000L);
                                        TaskFailure failure = assertThrows(
                                                TaskFailure.class, () -> runner.execute(task, clock::get));
                                        assertEquals(FailureKind.TIMEOUT, failure.kind());
                                        assertEquals(2, attempts.get());
                                    }

                                    @Test void permanentFailuresAreNeverRetried() {
                                        AtomicInteger attempts = new AtomicInteger();
                                        AtomicLong clock = new AtomicLong(0L);
                                        Task task = () -> {
                                            attempts.incrementAndGet();
                                            throw new TaskFailure(FailureKind.PERMANENT, "bad");
                                        };
                                        DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(5, 5), 1_000L);
                                        TaskFailure failure = assertThrows(
                                                TaskFailure.class, () -> runner.execute(task, clock::get));
                                        assertEquals(FailureKind.PERMANENT, failure.kind());
                                        assertEquals(1, attempts.get());
                                    }

                                    @Test void deadlineExpiryStopsFurtherAttempts() {
                                        AtomicInteger attempts = new AtomicInteger();
                                        AtomicLong clock = new AtomicLong(0L);
                                        Task task = () -> {
                                            attempts.incrementAndGet();
                                            throw new TaskFailure(FailureKind.TRANSIENT, "flaky");
                                        };
                                        DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(9, 9), 500L);
                                        assertThrows(DeadlineExceededException.class, () -> {
                                            runner.execute(task, () -> attempts.get() == 0 ? clock.get() : 600L);
                                        });
                                        assertEquals(1, attempts.get());
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/DeadlineRetryHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;

                        import java.util.concurrent.atomic.AtomicInteger;

                        class DeadlineRetryHiddenTest {
                            @Test void deadlineIsCheckedBeforeTheVeryFirstAttempt() {
                                AtomicInteger attempts = new AtomicInteger();
                                Task task = () -> {
                                    attempts.incrementAndGet();
                                    return "ok";
                                };
                                DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(9, 9), 100L);
                                assertThrows(DeadlineExceededException.class, () -> runner.execute(task, () -> 100L));
                                assertEquals(0, attempts.get());
                            }

                            @Test void deadlineBoundaryIsInclusiveAndAllowsJustBefore() {
                                AtomicInteger attempts = new AtomicInteger();
                                Task task = () -> {
                                    attempts.incrementAndGet();
                                    return "ok";
                                };
                                DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(9, 9), 100L);
                                assertEquals("ok", runner.execute(task, () -> 99L));
                                assertEquals(1, attempts.get());
                            }

                            @Test void attemptLimitCountsTheFirstAttempt() {
                                AtomicInteger attempts = new AtomicInteger();
                                Task task = () -> {
                                    attempts.incrementAndGet();
                                    throw new TaskFailure(FailureKind.TIMEOUT, "slow");
                                };
                                DeadlineRetryRunner runner = new DeadlineRetryRunner(new RetryBudget(3, 3), 10_000L);
                                assertThrows(TaskFailure.class, () -> runner.execute(task, () -> 0L));
                                assertEquals(3, attempts.get());
                            }

                            @Test void budgetValidationAndBoundaries() {
                                RetryBudget budget = new RetryBudget(2, 4);
                                assertEquals(2, budget.maxAttempts(FailureKind.TRANSIENT));
                                assertEquals(4, budget.maxAttempts(FailureKind.TIMEOUT));
                                assertEquals(1, budget.maxAttempts(FailureKind.PERMANENT));
                                assertTrue(budget.canRetry(FailureKind.TRANSIENT, 1));
                                assertFalse(budget.canRetry(FailureKind.TRANSIENT, 2));
                                assertFalse(budget.canRetry(FailureKind.PERMANENT, 0));
                                assertThrows(IllegalArgumentException.class, () -> budget.maxAttempts(null));
                                assertThrows(IllegalArgumentException.class, () -> budget.canRetry(null, 1));
                                assertThrows(IllegalArgumentException.class, () -> budget.canRetry(FailureKind.TRANSIENT, -1));
                                assertThrows(IllegalArgumentException.class, () -> new RetryBudget(0, 2));
                                assertThrows(IllegalArgumentException.class, () -> new RetryBudget(2, 0));
                            }

                            @Test void nullBudgetIsRejected() {
                                assertThrows(IllegalArgumentException.class, () -> new DeadlineRetryRunner(null, 100L));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/RetryBudget.java", "src/main/java/eval/DeadlineRetryRunner.java"));
    }

    private static ChangeSpecEvaluationCase evaluationCase(
            String id,
            ChangeSpecEvaluationTier tier,
            String task,
            List<String> publicEvidenceRequirements,
            Map<String, String> visibleFiles,
            Map<String, String> hiddenFiles,
            Set<String> allowedChangedFiles
    ) {
        String includes = String.join(", ", allowedChangedFiles.stream().sorted().toList());
        String evidence = publicEvidenceRequirements.stream()
                .map(value -> "- " + value)
                .reduce((first, second) -> first + "\n" + second)
                .orElseThrow();
        String context = """
                这是隔离的 Java 17 Maven 评测项目。ChangeSpec 必须使用 bounded scope，include 只能包含：%s。
                pom.xml 和 src/test/** 必须排除。所有 Acceptance Criterion 必须是 deterministic；不要生成 Human Criterion。
                command Verifier 必须原样使用 `%s`，JUnit glob 使用 target/surefire-reports/TEST-*.xml，minimum_tests 至少为 %d。
                每条非 scope Criterion 必须引用该 command Verifier。公开测试对以下 %d 项证据负责：
                %s
                """.formatted(includes, PUBLIC_VERIFIER, publicEvidenceRequirements.size(),
                publicEvidenceRequirements.size(), evidence);
        return new ChangeSpecEvaluationCase(
                id,
                tier,
                task.strip(),
                context.strip(),
                publicEvidenceRequirements,
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

    /** 仅供确定性基础设施测试证明十三个 fixture 存在可通过公开和隐藏 Oracle 的实现。 */
    static Map<String, Map<String, String>> referenceSolutions() {
        Map<String, Map<String, String>> solutions = new LinkedHashMap<>(Map.of(
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
                        """)));
        solutions.put("email-canonicalizer", Map.of(
                "src/main/java/eval/EmailCanonicalizer.java", """
                        package eval;
                        import java.util.Locale;
                        import java.util.Optional;
                        public final class EmailCanonicalizer {
                            private EmailCanonicalizer() { }
                            public static Optional<String> canonicalize(String input) {
                                if (input == null || input.isBlank()) return Optional.empty();
                                String value = input.strip().toLowerCase(Locale.ROOT);
                                int separator = value.indexOf('@');
                                if (separator <= 0
                                        || separator != value.lastIndexOf('@')
                                        || separator == value.length() - 1) {
                                    return Optional.empty();
                                }
                                return Optional.of(value);
                            }
                        }
                        """));
        solutions.put("inclusive-clamp", Map.of(
                "src/main/java/eval/IntClamp.java", """
                        package eval;
                        public final class IntClamp {
                            private IntClamp() { }
                            public static int clamp(int value, int minimum, int maximum) {
                                if (minimum > maximum) throw new IllegalArgumentException("inverted interval");
                                if (value < minimum) return minimum;
                                if (value > maximum) return maximum;
                                return value;
                            }
                        }
                        """));
        solutions.put("feature-flag-precedence", Map.of(
                "src/main/java/eval/FeatureFlagConfig.java", """
                        package eval;
                        import java.util.Locale;
                        import java.util.Map;
                        import java.util.Properties;
                        public final class FeatureFlagConfig {
                            private final boolean enabled;
                            private FeatureFlagConfig(boolean enabled) { this.enabled = enabled; }
                            public boolean enabled() { return enabled; }
                            public static FeatureFlagConfig load(
                                    Map<String, String> env, Properties properties) {
                                String raw = properties.getProperty("paicli.feature.preview");
                                if (raw == null) raw = env.get("PAICLI_FEATURE_PREVIEW");
                                if (raw == null) raw = env.get("PAI_FEATURE_PREVIEW");
                                if (raw == null) return new FeatureFlagConfig(false);
                                return switch (raw.toLowerCase(Locale.ROOT)) {
                                    case "true" -> new FeatureFlagConfig(true);
                                    case "false" -> new FeatureFlagConfig(false);
                                    default -> throw new IllegalArgumentException("feature flag must be true or false");
                                };
                            }
                        }
                        """));
        solutions.put("order-state-machine", Map.of(
                "src/main/java/eval/OrderTransitions.java", """
                        package eval;
                        public final class OrderTransitions {
                            private OrderTransitions() { }
                            public static OrderState next(OrderState current, OrderEvent event) {
                                if (current == null || event == null) {
                                    throw new IllegalArgumentException("state and event are required");
                                }
                                if (current == OrderState.NEW && event == OrderEvent.PAY) return OrderState.PAID;
                                if (current == OrderState.NEW && event == OrderEvent.CANCEL) {
                                    return OrderState.CANCELLED;
                                }
                                if (current == OrderState.PAID && event == OrderEvent.SHIP) return OrderState.SHIPPED;
                                if (current == OrderState.PAID && event == OrderEvent.CANCEL) {
                                    return OrderState.CANCELLED;
                                }
                                throw new IllegalArgumentException("transition is not allowed");
                            }
                        }
                        """));
        solutions.put("clarified-display-name", Map.of(
                "src/main/java/eval/DisplayNameNormalizer.java", """
                        package eval;
                        public final class DisplayNameNormalizer {
                            private DisplayNameNormalizer() { }
                            public static String normalize(String input) {
                                if (input == null) return "Anonymous";
                                StringBuilder normalized = new StringBuilder();
                                boolean pendingSpace = false;
                                for (int offset = 0; offset < input.length();) {
                                    int codePoint = input.codePointAt(offset);
                                    offset += Character.charCount(codePoint);
                                    if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                                        pendingSpace = !normalized.isEmpty();
                                    } else {
                                        if (pendingSpace) normalized.append(' ');
                                        normalized.appendCodePoint(codePoint);
                                        pendingSpace = false;
                                    }
                                }
                                if (normalized.isEmpty()) return "Anonymous";
                                String value = normalized.toString();
                                if (value.codePointCount(0, value.length()) <= 20) return value;
                                int end = value.offsetByCodePoints(0, 19);
                                return value.substring(0, end) + "…";
                            }
                        }
                        """));
        solutions.put("secret-redactor", Map.of(
                "src/main/java/eval/SecretRedactor.java", """
                        package eval;
                        public final class SecretRedactor {
                            private SecretRedactor() { }
                            public static String redact(String input) {
                                if (input == null) return "";
                                String queryRedacted = input.replaceAll(
                                        "(?i)(\\\\b(?:token|api_key)=)[^&\\\\s]*", "$1***");
                                return queryRedacted.replaceAll(
                                        "(?i)\\\\bBearer\\\\s+[A-Za-z0-9._~-]+", "Bearer ***");
                            }
                        }
                        """));
        solutions.put("token-expiry-cross-file", Map.of(
                "src/main/java/eval/TokenClaims.java", """
                        package eval;
                        public final class TokenClaims {
                            private final long expiresAtEpochSecond;
                            public TokenClaims(long expiresAtEpochSecond) {
                                this.expiresAtEpochSecond = expiresAtEpochSecond;
                            }
                            public long expiresAtEpochSecond() { return expiresAtEpochSecond; }
                            public boolean isExpired(long nowEpochSecond) {
                                return expiresAtEpochSecond <= nowEpochSecond;
                            }
                        }
                        """,
                "src/main/java/eval/TokenPolicy.java", """
                        package eval;
                        public final class TokenPolicy {
                            private TokenPolicy() { }
                            public static boolean isUsable(
                                    TokenClaims claims, long nowEpochSecond, long skewSeconds) {
                                if (claims == null) throw new IllegalArgumentException("claims are required");
                                if (skewSeconds < 0) throw new IllegalArgumentException("skew must not be negative");
                                if (nowEpochSecond > Long.MAX_VALUE - skewSeconds) return false;
                                return !claims.isExpired(nowEpochSecond + skewSeconds);
                            }
                        }
                        """));
        solutions.put("budget-allocator", Map.of(
                "src/main/java/eval/BudgetAllocator.java", """
                        package eval;
                        import java.math.BigInteger;
                        import java.util.ArrayList;
                        import java.util.List;
                        public final class BudgetAllocator {
                            private BudgetAllocator() { }
                            public static List<Long> allocate(long total, List<Long> weights) {
                                if (weights == null || weights.isEmpty()) {
                                    throw new IllegalArgumentException("weights must not be null or empty");
                                }
                                if (total < 0) {
                                    throw new IllegalArgumentException("total must not be negative");
                                }
                                BigInteger weightSum = BigInteger.ZERO;
                                List<BigInteger> bigWeights = new ArrayList<>(weights.size());
                                for (Long weight : weights) {
                                    if (weight == null || weight <= 0) {
                                        throw new IllegalArgumentException("weights must be positive");
                                    }
                                    bigWeights.add(BigInteger.valueOf(weight));
                                    weightSum = weightSum.add(BigInteger.valueOf(weight));
                                }
                                BigInteger totalBig = BigInteger.valueOf(total);
                                List<Long> shares = new ArrayList<>(weights.size());
                                List<BigInteger> remainders = new ArrayList<>(weights.size());
                                BigInteger allocated = BigInteger.ZERO;
                                for (BigInteger weight : bigWeights) {
                                    BigInteger[] division = totalBig.multiply(weight).divideAndRemainder(weightSum);
                                    shares.add(division[0].longValueExact());
                                    remainders.add(division[1]);
                                    allocated = allocated.add(division[0]);
                                }
                                List<Integer> order = new ArrayList<>(weights.size());
                                for (int index = 0; index < weights.size(); index++) {
                                    order.add(index);
                                }
                                order.sort((left, right) -> {
                                    int byRemainder = remainders.get(right).compareTo(remainders.get(left));
                                    return byRemainder != 0 ? byRemainder : Integer.compare(left, right);
                                });
                                BigInteger leftover = totalBig.subtract(allocated);
                                for (int index : order) {
                                    if (leftover.signum() == 0) break;
                                    shares.set(index, shares.get(index) + 1);
                                    leftover = leftover.subtract(BigInteger.ONE);
                                }
                                return List.copyOf(shares);
                            }
                        }
                        """));
        solutions.put("sliding-window-limiter", Map.of(
                "src/main/java/eval/SlidingWindowLimiter.java", """
                        package eval;
                        import java.util.ArrayDeque;
                        import java.util.Deque;
                        public final class SlidingWindowLimiter {
                            private final int maxPerWindow;
                            private final long windowMillis;
                            private final Deque<Long> acquisitions = new ArrayDeque<>();
                            private long lastNowMillis = Long.MIN_VALUE;
                            public SlidingWindowLimiter(int maxPerWindow, long windowMillis) {
                                if (maxPerWindow <= 0 || windowMillis <= 0) {
                                    throw new IllegalArgumentException("maxPerWindow and windowMillis must be positive");
                                }
                                this.maxPerWindow = maxPerWindow;
                                this.windowMillis = windowMillis;
                            }
                            public boolean tryAcquire(long nowMillis) {
                                checkMonotonic(nowMillis);
                                evictExpired(nowMillis);
                                if (acquisitions.size() >= maxPerWindow) {
                                    return false;
                                }
                                acquisitions.addLast(nowMillis);
                                return true;
                            }
                            public int availablePermits(long nowMillis) {
                                checkMonotonic(nowMillis);
                                evictExpired(nowMillis);
                                return Math.max(0, maxPerWindow - acquisitions.size());
                            }
                            private void checkMonotonic(long nowMillis) {
                                if (nowMillis < lastNowMillis) {
                                    throw new IllegalArgumentException("nowMillis must not go backwards");
                                }
                                lastNowMillis = nowMillis;
                            }
                            private void evictExpired(long nowMillis) {
                                while (!acquisitions.isEmpty() && nowMillis - acquisitions.peekFirst() >= windowMillis) {
                                    acquisitions.pollFirst();
                                }
                            }
                        }
                        """));
        solutions.put("deadline-retry-runner", Map.of(
                "src/main/java/eval/RetryBudget.java", """
                        package eval;
                        public final class RetryBudget {
                            private final int transientAttempts;
                            private final int timeoutAttempts;
                            public RetryBudget(int transientAttempts, int timeoutAttempts) {
                                if (transientAttempts < 1 || timeoutAttempts < 1) {
                                    throw new IllegalArgumentException("attempt limits must be at least 1");
                                }
                                this.transientAttempts = transientAttempts;
                                this.timeoutAttempts = timeoutAttempts;
                            }
                            public int maxAttempts(FailureKind kind) {
                                if (kind == null) {
                                    throw new IllegalArgumentException("kind must not be null");
                                }
                                return switch (kind) {
                                    case TRANSIENT -> transientAttempts;
                                    case TIMEOUT -> timeoutAttempts;
                                    case PERMANENT -> 1;
                                };
                            }
                            public boolean canRetry(FailureKind kind, int attemptsSoFar) {
                                if (kind == null) {
                                    throw new IllegalArgumentException("kind must not be null");
                                }
                                if (attemptsSoFar < 0) {
                                    throw new IllegalArgumentException("attemptsSoFar must not be negative");
                                }
                                if (kind == FailureKind.PERMANENT) {
                                    return false;
                                }
                                return attemptsSoFar < maxAttempts(kind);
                            }
                        }
                        """,
                "src/main/java/eval/DeadlineRetryRunner.java", """
                        package eval;
                        import java.util.function.LongSupplier;
                        public final class DeadlineRetryRunner {
                            private final RetryBudget budget;
                            private final long deadlineEpochMs;
                            public DeadlineRetryRunner(RetryBudget budget, long deadlineEpochMs) {
                                if (budget == null) {
                                    throw new IllegalArgumentException("budget must not be null");
                                }
                                this.budget = budget;
                                this.deadlineEpochMs = deadlineEpochMs;
                            }
                            public String execute(Task task, LongSupplier clock) {
                                int attemptsSoFar = 0;
                                while (true) {
                                    if (clock.getAsLong() >= deadlineEpochMs) {
                                        throw new DeadlineExceededException("deadline exceeded before attempt");
                                    }
                                    try {
                                        return task.call();
                                    } catch (TaskFailure failure) {
                                        attemptsSoFar++;
                                        if (!budget.canRetry(failure.kind(), attemptsSoFar)) {
                                            throw failure;
                                        }
                                    }
                                }
                            }
                        }
                        """));
        return Map.copyOf(solutions);
    }

    static List<PublicEvidenceMutation> publicEvidenceMutations() {
        return List.of(
                new PublicEvidenceMutation(
                        "safe-divider",
                        "src/main/java/eval/SafeDivider.java",
                        "return divisor == 0 ? OptionalInt.empty() : OptionalInt.of(dividend / divisor);",
                        "return OptionalInt.empty();"),
                new PublicEvidenceMutation(
                        "ascii-slugifier",
                        "src/main/java/eval/Slugifier.java",
                        "return out.toString();",
                        "return value;"),
                new PublicEvidenceMutation(
                        "email-canonicalizer",
                        "src/main/java/eval/EmailCanonicalizer.java",
                        "|| separator != value.lastIndexOf('@')",
                        ""),
                new PublicEvidenceMutation(
                        "inclusive-clamp",
                        "src/main/java/eval/IntClamp.java",
                        "if (minimum > maximum) throw new IllegalArgumentException(\"inverted interval\");",
                        ""),
                new PublicEvidenceMutation(
                        "login-retry-policy",
                        "src/main/java/eval/LoginRetrier.java",
                        "retries >= 3",
                        "retries >= 4"),
                new PublicEvidenceMutation(
                        "timeout-config-compat",
                        "src/main/java/eval/TimeoutConfig.java",
                        "String raw = properties.getProperty(\"paicli.timeout.ms\");",
                        "String raw = env.get(\"PAICLI_TIMEOUT_MS\");"),
                new PublicEvidenceMutation(
                        "feature-flag-precedence",
                        "src/main/java/eval/FeatureFlagConfig.java",
                        "default -> throw new IllegalArgumentException(\"feature flag must be true or false\");",
                        "default -> new FeatureFlagConfig(false);"),
                new PublicEvidenceMutation(
                        "order-state-machine",
                        "src/main/java/eval/OrderTransitions.java",
                        "throw new IllegalArgumentException(\"transition is not allowed\");",
                        "return current;"),
                new PublicEvidenceMutation(
                        "clarified-display-name",
                        "src/main/java/eval/DisplayNameNormalizer.java",
                        "int end = value.offsetByCodePoints(0, 19);",
                        "int end = value.offsetByCodePoints(0, 18);"),
                new PublicEvidenceMutation(
                        "workspace-path-safety",
                        "src/main/java/eval/WorkspacePath.java",
                        "if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException(\"escape\");",
                        ""),
                new PublicEvidenceMutation(
                        "operation-result-api-compat",
                        "src/main/java/eval/OperationResult.java",
                        "this.errorCode = errorCode == null ? \"\" : errorCode;",
                        "this.errorCode = \"\";"),
                new PublicEvidenceMutation(
                        "secret-redactor",
                        "src/main/java/eval/SecretRedactor.java",
                        "api_key",
                        "api-key"),
                new PublicEvidenceMutation(
                        "token-expiry-cross-file",
                        "src/main/java/eval/TokenPolicy.java",
                        "if (nowEpochSecond > Long.MAX_VALUE - skewSeconds) return false;",
                        ""),
                new PublicEvidenceMutation(
                        "budget-allocator",
                        "src/main/java/eval/BudgetAllocator.java",
                        "return byRemainder != 0 ? byRemainder : Integer.compare(left, right);",
                        "return byRemainder != 0 ? byRemainder : Integer.compare(right, left);"),
                new PublicEvidenceMutation(
                        "sliding-window-limiter",
                        "src/main/java/eval/SlidingWindowLimiter.java",
                        "if (acquisitions.size() >= maxPerWindow) {",
                        "if (acquisitions.size() > maxPerWindow) {"),
                new PublicEvidenceMutation(
                        "deadline-retry-runner",
                        "src/main/java/eval/RetryBudget.java",
                        "return attemptsSoFar < maxAttempts(kind);",
                        "return attemptsSoFar <= maxAttempts(kind);"));
    }

    record PublicEvidenceMutation(
            String caseId,
            String relativePath,
            String target,
            String replacement
    ) {
        Map<String, String> apply(Map<String, String> referenceSolution) {
            String source = referenceSolution.get(relativePath);
            if (source == null || !source.contains(target)) {
                throw new IllegalArgumentException("Mutation target 不存在: " + caseId + " / " + relativePath);
            }
            if (source.indexOf(target) != source.lastIndexOf(target)) {
                throw new IllegalArgumentException("Mutation target 不唯一: " + caseId + " / " + relativePath);
            }
            Map<String, String> mutated = new LinkedHashMap<>(referenceSolution);
            mutated.put(relativePath, source.replace(target, replacement));
            return Map.copyOf(mutated);
        }
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
