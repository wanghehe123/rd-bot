package com.wish.rd.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * RD-Bot 单体 Spring Boot 服务入口。
 *
 * <p>整个项目按"分层"而非"微服务"拆分为五个 Maven 模块：
 * {@code rag}（RAG 能力层）、{@code engine}（编排层）、{@code exec}（修复执行占位）、
 * {@code skill}（修复技能占位）以及本模块 {@code bootstrap}（应用入口与 REST 控制器）。
 *
 * <p>{@code scanBasePackages = "com.wish.rd"} 让组件扫描覆盖全部模块，
 * 因此各模块的 {@code @Configuration}、{@code @RestController}、
 * {@code @Service} 等都会被同一进程加载，无需拆分部署。
 *
 * <p>本地启动：{@code ./mvnw install -DskipTests} 后执行
 * {@code ./mvnw -pl bootstrap spring-boot:run}，默认监听 8080 端口。
 */
@SpringBootApplication(
        scanBasePackages = "com.wish.rd",
        exclude = DataSourceAutoConfiguration.class
)
public class RdBotApplication {

    /**
     * 应用主入口，委派给 Spring Boot 启动流程完成容器初始化、内嵌 Tomcat 启动与配置加载。
     *
     * @param args 命令行参数，原样透传给 {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        SpringApplication.run(RdBotApplication.class, args);
    }
}
