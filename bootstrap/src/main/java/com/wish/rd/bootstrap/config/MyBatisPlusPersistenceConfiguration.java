package com.wish.rd.bootstrap.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus mapper 扫描配置，仅在 PostgreSQL 持久化模式启用。
 */
@Configuration
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
@MapperScan({
        "com.wish.rd.bootstrap.persistence.mapper",
        "com.wish.rd.bootstrap.user.dao.mapper"
})
public class MyBatisPlusPersistenceConfiguration {
}
