package com.wish.rd.bootstrap.openviking;

import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexPollEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexSyncEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionSettlePort;
import com.wish.rd.bootstrap.openviking.impl.DisabledExternalKnowledgeIndexPort;
import com.wish.rd.bootstrap.openviking.impl.JdkOpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.impl.OpenVikingRestIndexAdapter;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 外部索引投影的装配。这里只造基础设施与端口实现，判定逻辑全在 rag 模块的引擎里。
 */
@Configuration
@EnableConfigurationProperties(OpenVikingProperties.class)
public class OpenVikingProjectionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenVikingProjectionConfiguration.class);

    /**
     * 租约标识。带上主机与进程信息便于排障，再加随机段，
     * 避免同一台机器重启后新进程"继承"旧进程还没过期的租约。
     *
     * @return 本进程的租约标识
     */
    @Bean
    public Supplier<String> projectionLeaseOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            host = "unknown-host";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String owner = host + "/" + pid + "/" + UUID.randomUUID().toString().substring(0, 8);
        return () -> owner;
    }

    @Bean
    public ProjectionWorkerSettings projectionWorkerSettings(
            @Value("${rd.knowledge.projection.worker.batch-size:10}") int batchSize,
            @Value("${rd.knowledge.projection.worker.lease-millis:120000}") long workerLeaseMillis,
            @Value("${rd.knowledge.projection.poll.lease-millis:15000}") long pollLeaseMillis,
            @Value("${rd.knowledge.projection.poll.interval-millis:3000}") long pollIntervalMillis,
            @Value("${rd.knowledge.projection.poll.max-interval-millis:60000}") long maxPollIntervalMillis,
            @Value("${rd.knowledge.projection.worker.retry-backoff-millis:2000}") long retryBackoffMillis,
            @Value("${rd.knowledge.projection.worker.max-retry-backoff-millis:300000}") long maxRetryBackoffMillis,
            @Value("${rd.knowledge.projection.poll.unknown-outcome-timeout-millis:1800000}") long unknownTimeoutMillis
    ) {
        return new ProjectionWorkerSettings(
                batchSize, workerLeaseMillis, pollLeaseMillis, pollIntervalMillis, maxPollIntervalMillis,
                retryBackoffMillis, maxRetryBackoffMillis, unknownTimeoutMillis);
    }

    @Bean
    @ConditionalOnProperty(name = "rd.openviking.enabled", havingValue = "true")
    public OpenVikingHttpExchange openVikingHttpExchange(OpenVikingProperties properties) {
        if (apiKey(properties).isBlank()) {
            log.warn("openviking is enabled but {} is empty; projection will stay paused until it is set",
                    properties.getApiKeyEnv());
        }
        return new JdkOpenVikingHttpExchange(
                properties.getBaseUrl(),
                () -> apiKey(properties),
                Duration.ofMillis(properties.getConnectTimeoutMillis()),
                Duration.ofMillis(properties.getRequestTimeoutMillis()));
    }

    @Bean
    @ConditionalOnProperty(name = "rd.openviking.enabled", havingValue = "true")
    public ExternalKnowledgeIndexPort openVikingIndexPort(
            OpenVikingHttpExchange exchange,
            OpenVikingProperties properties
    ) {
        return new OpenVikingRestIndexAdapter(
                exchange,
                () -> apiKey(properties),
                properties.getOwnedRoot());
    }

    /**
     * 闸门与请求必须读同一个 key。{@code ready()} 判"有 key"、请求却发另一个值，
     * 就是投影显示健康而每次写入都 401 的那种故障；两处共用一个解析路径就没有这个缝。
     */
    private static String apiKey(OpenVikingProperties properties) {
        String apiKey = System.getenv(properties.getApiKeyEnv());
        return apiKey == null ? "" : apiKey;
    }

    @Bean
    @ConditionalOnProperty(name = "rd.openviking.enabled", havingValue = "false", matchIfMissing = true)
    public ExternalKnowledgeIndexPort disabledIndexPort() {
        return new DisabledExternalKnowledgeIndexPort();
    }

    @Bean
    public KnowledgeExternalIndexSyncEngine knowledgeExternalIndexSyncEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeProjectionSettlePort settlePort,
            ProjectionWorkerSettings settings,
            Supplier<String> projectionLeaseOwner
    ) {
        return new KnowledgeExternalIndexSyncEngine(
                indexPort, outboxStore, bindingStore, revisionStore, settlePort, settings, projectionLeaseOwner);
    }

    @Bean
    public KnowledgeExternalIndexPollEngine knowledgeExternalIndexPollEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeProjectionSettlePort settlePort,
            ProjectionWorkerSettings settings,
            Supplier<String> projectionLeaseOwner
    ) {
        return new KnowledgeExternalIndexPollEngine(
                indexPort, outboxStore, bindingStore, settlePort, settings, projectionLeaseOwner);
    }
}
