package com.wish.rd.bootstrap.evaluation;

import com.wish.rd.bootstrap.evaluation.impl.DockerCodingBenchmarkExecutor;
import com.wish.rd.bootstrap.evaluation.impl.FakeCodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 验证编码基准执行器的条件装配：默认仍是 Docker 实现，只有显式选择 fake 时才切换。
 *
 * <p>使用 {@link ApplicationContextRunner} 而不是完整 {@code @SpringBootTest}，因为本用例只关心
 * {@code rd.evaluation.coding-benchmark.executor} 这一个开关，不应依赖 Redis/PostgreSQL。
 */
class CodingBenchmarkExecutionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    EvaluationPropertiesConfiguration.class,
                    CodingBenchmarkExecutionConfiguration.class,
                    DockerCodingBenchmarkExecutor.class);

    @Test
    void should_只装配Docker执行器_当未配置执行器时() {
        runner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(CodingBenchmarkExecutionPort.class).length);
            assertInstanceOf(DockerCodingBenchmarkExecutor.class, context.getBean(CodingBenchmarkExecutionPort.class));
            assertEquals("docker", context.getBean(EvaluationProperties.class).codingBenchmarkExecutor());
        });
    }

    @Test
    void should_只装配Fake执行器_当选择fake时() {
        runner.withPropertyValues("rd.evaluation.coding-benchmark.executor=fake").run(context -> {
            assertEquals(1, context.getBeanNamesForType(CodingBenchmarkExecutionPort.class).length);
            assertInstanceOf(FakeCodingBenchmarkExecutionPort.class, context.getBean(CodingBenchmarkExecutionPort.class));
            assertEquals("fake", context.getBean(EvaluationProperties.class).codingBenchmarkExecutor());
        });
    }

    @Test
    void should_保留Docker执行器_当显式配置docker时() {
        runner.withPropertyValues("rd.evaluation.coding-benchmark.executor=docker").run(context -> {
            assertEquals(1, context.getBeanNamesForType(CodingBenchmarkExecutionPort.class).length);
            assertInstanceOf(DockerCodingBenchmarkExecutor.class, context.getBean(CodingBenchmarkExecutionPort.class));
        });
    }

    @Test
    void should_让随包application_yaml默认解析为docker() throws Exception {
        // 直接绑定发布用的 YAML（测试类路径上的 application.yaml 是另一份精简配置），
        // 确保键路径就是条件注解读取的 rd.evaluation.coding-benchmark.executor
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yaml", new FileSystemResource(shippedApplicationYaml()))
                .forEach(source -> environment.getPropertySources().addFirst(source));

        EvaluationProperties.CodingBenchmark bound = Binder.get(environment)
                .bind("rd.evaluation.coding-benchmark", EvaluationProperties.CodingBenchmark.class)
                .orElseThrow(() -> new AssertionError("application.yaml 缺少 rd.evaluation.coding-benchmark 配置"));

        assertEquals("docker", bound.getExecutor());
    }

    /** @return 生产 application.yaml 路径，兼容模块目录与仓库根目录两种执行位置 */
    private static Path shippedApplicationYaml() {
        Path moduleLocal = Path.of("src/main/resources/application.yaml");
        return Files.isRegularFile(moduleLocal) ? moduleLocal : Path.of("bootstrap/src/main/resources/application.yaml");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EvaluationProperties.class)
    static class EvaluationPropertiesConfiguration {
    }
}
