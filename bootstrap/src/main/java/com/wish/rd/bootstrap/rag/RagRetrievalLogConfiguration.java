package com.wish.rd.bootstrap.rag;

import com.wish.rd.bootstrap.rag.impl.FileRagRetrievalLogSink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.rag.RagRetrievalLogSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索评测日志的 bootstrap 装配。
 *
 * <p>该配置只提供文件适配实现，业务流程仍依赖 engine 层的 {@link RagRetrievalLogSink} 端口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagRetrievalLogProperties.class)
public class RagRetrievalLogConfiguration {

    /**
     * 在开启评测日志时注册 JSONL 文件 sink。
     *
     * @param objectMapper Jackson 序列化器
     * @param properties   RAG 检索日志配置
     * @return RAG 检索日志 sink
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rd.rag.retrieval-log", name = "enabled", havingValue = "true")
    public RagRetrievalLogSink ragRetrievalLogSink(
            ObjectMapper objectMapper,
            RagRetrievalLogProperties properties
    ) {
        return new FileRagRetrievalLogSink(objectMapper, properties.getPath());
    }
}
