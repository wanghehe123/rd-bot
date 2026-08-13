package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Transactional} Bean 不得是 {@code final} 类。
 *
 * <p>Boot 默认 CGLIB 子类代理；final 类在真机启动时抛
 * {@code Cannot subclass final class} 并放弃整个 ApplicationContext。单元测试不加载
 * Spring 上下文，所以这个错误只有生产启动才暴露——2026-08-13 两个 Postgres 投影
 * 适配器就这样把 {@code rd.knowledge.store=postgres} 的后端整个打挂。
 * 规则见 RULE.md 3.5.3。
 */
class TransactionalProxyPolicyTest {

    @Test
    void transactionalBeansMustNotBeFinalClasses() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collect(path, violations));
        }
        assertTrue(violations.isEmpty(),
                "@Transactional 类不能是 final，否则 CGLIB 无法生成事务代理，"
                        + "应用在真机启动时直接失败：\n" + String.join("\n", violations));
    }

    private static void collect(Path path, List<String> violations) {
        try {
            String content = Files.readString(path);
            if (content.contains("@Transactional") && content.contains("public final class")) {
                violations.add(path.toString());
            }
        } catch (IOException exception) {
            violations.add(path + " -> unreadable: " + exception.getMessage());
        }
    }
}
