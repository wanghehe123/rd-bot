package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresQueryTermMappingStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires exactly one query-rule registry with PostgreSQL as the production source of truth. */
@Configuration
public class QueryTermMappingConfiguration {

    @Bean
    @ConditionalOnMissingBean(QueryTermMappingRegistry.class)
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
    QueryTermMappingRegistry postgresQueryTermMappingRegistry(
            PostgresQueryTermMappingStore store,
            SnowflakeIdGenerator idGenerator
    ) {
        return new QueryTermMappingRegistry(store, idGenerator::nextIdString);
    }

    @Bean
    @ConditionalOnMissingBean(QueryTermMappingRegistry.class)
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    QueryTermMappingRegistry inMemoryQueryTermMappingRegistry() {
        return QueryTermMappingRegistry.withDefaults();
    }
}
