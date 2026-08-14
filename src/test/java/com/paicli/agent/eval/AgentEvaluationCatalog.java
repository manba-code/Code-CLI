package com.paicli.agent.eval;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentEvaluationCatalog {
    private AgentEvaluationCatalog() {
    }

    static List<AgentEvaluationCase> defaultCases() {
        return List.of(safeDivider(), slugifier());
    }

    private static AgentEvaluationCase safeDivider() {
        return new AgentEvaluationCase(
                "safe-divider",
                """
                修复当前项目中的 SafeDivider.divide(int dividend, int divisor)。要求：
                1. divisor 为 0 时返回 OptionalInt.empty()，不能抛 ArithmeticException；
                2. 其他情况返回 Java 整数除法结果；
                3. 只允许修改 src/main/java/eval/SafeDivider.java；
                4. 完成后运行测试或编译验证。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/SafeDivider.java", """
                                package eval;

                                import java.util.OptionalInt;

                                public final class SafeDivider {
                                    private SafeDivider() {
                                    }

                                    public static OptionalInt divide(int dividend, int divisor) {
                                        return OptionalInt.of(dividend / divisor);
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SafeDividerHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;

                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        import static org.junit.jupiter.api.Assertions.assertTrue;

                        class SafeDividerHiddenTest {
                            @Test void dividesPositiveAndNegativeValues() {
                                assertEquals(4, SafeDivider.divide(9, 2).orElseThrow());
                                assertEquals(-4, SafeDivider.divide(-9, 2).orElseThrow());
                            }

                            @Test void returnsEmptyForZeroDivisor() {
                                assertTrue(SafeDivider.divide(7, 0).isEmpty());
                            }
                        }
                        """),
                Set.of("src/main/java/eval/SafeDivider.java"),
                mavenTestCommand(), Duration.ofMinutes(2));
    }

    private static AgentEvaluationCase slugifier() {
        return new AgentEvaluationCase(
                "ascii-slugifier",
                """
                实现当前项目中的 Slugifier.slugify(String input)。验收规则：
                1. null 或全空白输入返回空字符串；
                2. 使用 Locale.ROOT 转为小写；
                3. 任意连续的非 ASCII 字母/数字字符折叠成一个连字符；
                4. 删除结果首尾的连字符；
                5. 只允许修改 src/main/java/eval/Slugifier.java；
                6. 完成后运行测试或编译验证。
                """,
                Map.of(
                        "pom.xml", fixturePom(),
                        "src/main/java/eval/Slugifier.java", """
                                package eval;

                                public final class Slugifier {
                                    private Slugifier() {
                                    }

                                    public static String slugify(String input) {
                                        return input;
                                    }
                                }
                                """),
                Map.of("src/test/java/eval/SlugifierHiddenTest.java", """
                        package eval;

                        import org.junit.jupiter.api.Test;

                        import static org.junit.jupiter.api.Assertions.assertEquals;

                        class SlugifierHiddenTest {
                            @Test void handlesNullBlankAndCase() {
                                assertEquals("", Slugifier.slugify(null));
                                assertEquals("", Slugifier.slugify("   "));
                                assertEquals("hello-world", Slugifier.slugify("  Hello WORLD  "));
                            }

                            @Test void collapsesSeparatorsAndKeepsAsciiDigits() {
                                assertEquals("api-v2-guide", Slugifier.slugify("API___v2 / Guide"));
                                assertEquals("a-b", Slugifier.slugify("---A---B---"));
                                assertEquals("caf-42", Slugifier.slugify("Café 42"));
                            }
                        }
                        """),
                Set.of("src/main/java/eval/Slugifier.java"),
                mavenTestCommand(), Duration.ofMinutes(2));
    }

    private static String fixturePom() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>eval</groupId>
                    <artifactId>agent-quality-fixture</artifactId>
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

    private static List<String> mavenTestCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/d", "/c", "mvn -q -DskipTests=false test");
        }
        return List.of("sh", "-lc", "mvn -q -DskipTests=false test");
    }
}
