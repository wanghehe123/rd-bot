package com.wish.rd.bootstrap.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RD-Bot 运行时基础设施配置。
 *
 * <p>业务编排、注册表、端口实现等项目类统一通过 {@code @Service}/{@code @Component}
 * 声明并由组件扫描注入；本配置类只保留第三方基础设施工厂。
 */
@Configuration
public class RdBotRuntimeConfiguration {

    /** PostgreSQL 数据源：仅在显式开启 {@code rd.knowledge.store=postgres} 时创建。 */
    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
    public DataSource rdBotDataSource(
            @Value("${spring.datasource.url:jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8}") String url,
            @Value("${spring.datasource.username:postgres}") String username,
            @Value("${spring.datasource.password:postgres}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:2}") int minimumIdle,
            @Value("${spring.datasource.hikari.connection-timeout:5000}") long connectionTimeout
    ) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setPoolName("RdBotHikariPool");
        return new HikariDataSource(config);
    }
}
