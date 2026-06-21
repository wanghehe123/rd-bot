package com.wish.rd.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresUserAdminService;
import com.wish.rd.bootstrap.persistence.PostgresIngestionTaskStore;
import com.wish.rd.bootstrap.persistence.PostgresKnowledgeBaseStore;
import com.wish.rd.bootstrap.persistence.PostgresKnowledgeChunkStore;
import com.wish.rd.bootstrap.persistence.PostgresKnowledgeDocumentStore;
import com.wish.rd.bootstrap.persistence.PostgresRepairRecordRepository;
import com.wish.rd.bootstrap.persistence.PostgresVectorStore;
import com.wish.rd.bootstrap.persistence.mapper.AdminUserMapper;
import com.wish.rd.bootstrap.persistence.mapper.IngestionTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.IngestionTaskNodeMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeBaseMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeChunkMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeVectorMapper;
import com.wish.rd.bootstrap.persistence.mapper.RepairRecordArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RepairRecordMapper;
import com.wish.rd.engine.admin.conversation.ConversationAdminEngine;
import com.wish.rd.engine.admin.ingestion.IngestionAdminEngine;
import com.wish.rd.engine.admin.intent.IntentTreeAdminEngine;
import com.wish.rd.engine.admin.knowledge.KnowledgeAdminEngine;
import com.wish.rd.engine.admin.feedback.MessageFeedbackAdminEngine;
import com.wish.rd.engine.admin.rewrite.QueryTermMappingAdminEngine;
import com.wish.rd.engine.rag.RagV3ChatEngine;
import com.wish.rd.engine.admin.sample.SampleQuestionAdminEngine;
import com.wish.rd.exec.repair.InMemoryRepairRecordRepository;
import com.wish.rd.exec.repair.RepairRecordRepository;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.ingestion.InMemoryIngestionTaskStore;
import com.wish.rd.rag.feedback.MessageFeedbackRegistry;
import com.wish.rd.rag.ingestion.IngestionTaskStore;
import com.wish.rd.rag.ingestion.IngestionAdminRegistry;
import com.wish.rd.rag.ingestion.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.knowledge.FeishuDocumentClient;
import com.wish.rd.rag.knowledge.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.FeishuDocKnowledgeImporter;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.memory.ConversationRegistry;
import com.wish.rd.rag.memory.ConversationMemoryService;
import com.wish.rd.rag.memory.DefaultConversationMemoryService;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.sample.SampleQuestionRegistry;
import com.wish.rd.rag.trace.RagTraceStore;
import com.wish.rd.rag.vector.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;
import com.wish.rd.bootstrap.storage.S3ObjectStorageService;
import com.wish.rd.bootstrap.user.InMemoryUserAdminService;
import com.wish.rd.bootstrap.user.UserAdminService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RD-Bot 运行时 Bean 装配中心。
 *
 * <p>本项目不使用数据库等外部中间件（MVP 全部为内存实现），因此所有核心组件都通过
 * 本类以 {@code @Bean} 方式集中创建，注入到对应的 {@code engine}/{@code controller}。
 * 装配顺序遵循"知识库 → 摄取 → 对话/反馈 → 改写 → 意图 → 样例 → 流式任务 → V3 Chat"的依赖链。
 *
 * <p>各 Bean 的职责：
 * <ul>
 *   <li>知识域：{@link KnowledgeWorkspace}（知识库/文档/分块/向量库的内存聚合根）；</li>
 *   <li>摄取域：{@link IngestionAdminRegistry}（管线与任务管理）+ {@link ObjectStorageService}
 *       （可按 {@code rd.storage.mode} 在内存与 RustFS/S3 之间切换）；</li>
 *   <li>会话域：{@link ConversationRegistry}（会话+消息）+ {@link ConversationMemoryService}
 *       （基于虚拟线程异步加载历史）；</li>
 *   <li>反馈/改写/意图/样例：分别对应各自的内存注册表；</li>
 *   <li>运行时：{@link RagTraceStore}（轻量链路追踪）、{@link RagStreamTaskRegistry}
 *       （聊天任务状态）、{@link RagV3ChatEngine}（对外聊天总编排）。</li>
 * </ul>
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

    /** Snowflake ID 生成器：新增持久化实体统一使用 bigint 主键。 */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return SnowflakeIdGenerator.defaultGenerator();
    }

    /** 后台用户管理服务：默认内存，PostgreSQL 模式落库到 admin_users。 */
    @Bean
    public UserAdminService userAdminService(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            SnowflakeIdGenerator idGenerator,
            org.springframework.beans.factory.ObjectProvider<AdminUserMapper> mapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresUserAdminService(mapperProvider.getObject(), idGenerator);
        }
        return new InMemoryUserAdminService(idGenerator);
    }

    /** 向量库端口：默认内存，PostgreSQL 模式使用 pgvector 表。 */
    @Bean
    public VectorStore vectorStore(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            @Value("${rag.default.dimension:1536}") int dimension,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<KnowledgeVectorMapper> mapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresVectorStore(mapperProvider.getObject(), objectMapper, dimension);
        }
        return new InMemoryVectorStore();
    }

    /** 知识库 Store：默认内存，PostgreSQL 模式落库。 */
    @Bean
    public KnowledgeBaseStore knowledgeBaseStore(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            org.springframework.beans.factory.ObjectProvider<KnowledgeBaseMapper> mapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresKnowledgeBaseStore(mapperProvider.getObject());
        }
        return new InMemoryKnowledgeBaseStore();
    }

    /** 知识文档 Store：默认内存，PostgreSQL 模式落库。 */
    @Bean
    public KnowledgeDocumentStore knowledgeDocumentStore(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<KnowledgeDocumentMapper> mapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresKnowledgeDocumentStore(mapperProvider.getObject(), objectMapper);
        }
        return new InMemoryKnowledgeDocumentStore();
    }

    /** 知识分块 Store：默认内存，PostgreSQL 模式落库。 */
    @Bean
    public KnowledgeChunkStore knowledgeChunkStore(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<KnowledgeChunkMapper> mapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresKnowledgeChunkStore(mapperProvider.getObject(), objectMapper);
        }
        return new InMemoryKnowledgeChunkStore();
    }

    /** 摄取任务 Store：保存任务主记录与节点日志。 */
    @Bean
    public IngestionTaskStore ingestionTaskStore(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<IngestionTaskMapper> taskMapperProvider,
            org.springframework.beans.factory.ObjectProvider<IngestionTaskNodeMapper> nodeMapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresIngestionTaskStore(taskMapperProvider.getObject(), nodeMapperProvider.getObject(), objectMapper);
        }
        return new InMemoryIngestionTaskStore();
    }

    /** 修复记录仓储：P0 提供 repair_records 与 repair_record_artifacts 持久化端口。 */
    @Bean
    public RepairRecordRepository repairRecordRepository(
            @Value("${rd.knowledge.store:memory}") String storeMode,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator,
            org.springframework.beans.factory.ObjectProvider<RepairRecordMapper> recordMapperProvider,
            org.springframework.beans.factory.ObjectProvider<RepairRecordArtifactMapper> artifactMapperProvider
    ) {
        if ("postgres".equalsIgnoreCase(storeMode)) {
            return new PostgresRepairRecordRepository(
                    recordMapperProvider.getObject(),
                    artifactMapperProvider.getObject(),
                    objectMapper,
                    idGenerator
            );
        }
        return new InMemoryRepairRecordRepository(idGenerator);
    }

    /** 知识库工作区：聚合知识库、文档、分块与向量存储，是知识域 facade。 */
    @Bean
    public KnowledgeWorkspace knowledgeWorkspace(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore
    ) {
        return KnowledgeWorkspace.withStores(vectorStore, idGenerator, baseStore, documentStore, chunkStore);
    }

    /** Feishu 文档读取端口：P0 默认 mock，可替换为真实 OpenAPI 适配。 */
    @Bean
    public FeishuDocumentClient feishuDocumentClient() {
        return source -> new FeishuDocumentSnapshot(
                extractFeishuToken(source),
                source == null ? "" : source,
                "feishu-" + extractFeishuToken(source),
                "mock-revision-1",
                "# Feishu Mock Document\n\n来源：" + (source == null ? "" : source) + "\n\nRD-Bot P0 知识库生产化导入验证内容。",
                System.currentTimeMillis()
        );
    }

    /** Feishu 知识导入器：负责 URL/token → 文档快照 → 知识库写入与分块。 */
    @Bean
    public FeishuDocKnowledgeImporter feishuDocKnowledgeImporter(
            KnowledgeWorkspace workspace,
            FeishuDocumentClient feishuDocumentClient
    ) {
        return new FeishuDocKnowledgeImporter(workspace, feishuDocumentClient);
    }

    /** 知识管理编排层：对外暴露知识库/文档/分块/概览等管理能力。 */
    @Bean
    public KnowledgeAdminEngine knowledgeAdminEngine(KnowledgeWorkspace workspace) {
        return new KnowledgeAdminEngine(workspace);
    }

    /** 摄取管理注册表：维护可配置管线与已执行任务，依赖知识工作区落地文档与分块。 */
    @Bean
    public IngestionAdminRegistry ingestionAdminRegistry(
            KnowledgeWorkspace workspace,
            IngestionTaskStore taskStore,
            SnowflakeIdGenerator idGenerator
    ) {
        return IngestionAdminRegistry.withTaskStore(workspace, taskStore, idGenerator);
    }

    /** 摄取管理编排层：对外暴露管线 CRUD、任务执行、上传等接口。 */
    @Bean
    public IngestionAdminEngine ingestionAdminEngine(IngestionAdminRegistry registry) {
        return new IngestionAdminEngine(registry);
    }

    /**
     * 对象存储服务：上传文件时使用。通过 {@code rd.storage.mode} 切换实现：
     * <ul>
     *   <li>{@code memory}：进程内模拟存储，便于无依赖的单测；</li>
     *   <li>其他（默认 {@code s3}）：走 RustFS 兼容的 S3 协议，连接信息由
     *       {@code rustfs.*} 系列配置提供。</li>
     * </ul>
     */
    @Bean
    public ObjectStorageService objectStorageService(
            @Value("${rd.storage.mode:s3}") String storageMode,
            @Value("${rustfs.url:http://localhost:9000}") String rustfsUrl,
            @Value("${rustfs.access-key-id:rustfsadmin}") String accessKeyId,
            @Value("${rustfs.secret-access-key:rustfsadmin}") String secretAccessKey
    ) {
        if ("memory".equalsIgnoreCase(storageMode)) {
            return new InMemoryObjectStorageService();
        }
        return new S3ObjectStorageService(rustfsUrl, accessKeyId, secretAccessKey);
    }

    /** RAG 链路追踪存储：记录 run 与 node 级别的执行轨迹，供 /rag/traces 查询。 */
    @Bean
    public RagTraceStore ragTraceStore() {
        return new RagTraceStore();
    }

    /** 会话注册表：维护会话元数据与消息历史，是会话域的内存聚合根。 */
    @Bean
    public ConversationRegistry conversationRegistry() {
        return ConversationRegistry.inMemory();
    }

    /**
     * 对话记忆服务：将历史消息异步加载（默认虚拟线程）并写入会话注册表，
     * 供 V3 Chat 在生成本轮 Prompt 前拼接"对话记忆"段落。
     */
    @Bean
    public ConversationMemoryService conversationMemoryService(ConversationRegistry conversationRegistry) {
        return new DefaultConversationMemoryService(conversationRegistry, null);
    }

    /** 会话管理编排层：对外暴露会话列表/消息历史/重命名/删除接口。 */
    @Bean
    public ConversationAdminEngine conversationAdminEngine(ConversationRegistry conversationRegistry) {
        return new ConversationAdminEngine(conversationRegistry);
    }

    /** 消息反馈注册表：保存对助手消息的点赞/点踩，并回写到会话消息的 vote 字段。 */
    @Bean
    public MessageFeedbackRegistry messageFeedbackRegistry(ConversationRegistry conversationRegistry) {
        return MessageFeedbackRegistry.inMemory(conversationRegistry);
    }

    /** 消息反馈编排层：校验投票合法性并落库（仅允许对助手消息投票）。 */
    @Bean
    public MessageFeedbackAdminEngine messageFeedbackAdminEngine(MessageFeedbackRegistry registry) {
        return new MessageFeedbackAdminEngine(registry);
    }

    /** 查询术语映射注册表：内置支付域默认映射，/rag/v3/chat 生成的 meta.rewrittenQuestion 即源自此处。 */
    @Bean
    public QueryTermMappingRegistry queryTermMappingRegistry() {
        return QueryTermMappingRegistry.withDefaults();
    }

    /** 查询术语映射编排层：对外暴露映射 CRUD 接口。 */
    @Bean
    public QueryTermMappingAdminEngine queryTermMappingAdminEngine(QueryTermMappingRegistry registry) {
        return new QueryTermMappingAdminEngine(registry);
    }

    /** 意图树注册表：内置默认意图节点，/rag/v3/chat 据此构建分类器并输出 meta.intentSystemId。 */
    @Bean
    public IntentTreeRegistry intentTreeRegistry() {
        return IntentTreeRegistry.withDefaults();
    }

    /** 意图树编排层：对外暴露树查询、节点 CRUD、批量启停删接口。 */
    @Bean
    public IntentTreeAdminEngine intentTreeAdminEngine(IntentTreeRegistry registry) {
        return new IntentTreeAdminEngine(registry);
    }

    /** 样例问题注册表：支撑欢迎页示例问题与管理后台的样例 CRUD。 */
    @Bean
    public SampleQuestionRegistry sampleQuestionRegistry() {
        return SampleQuestionRegistry.inMemory();
    }

    /** 样例问题编排层：对外暴露欢迎页与后台管理的样例接口。 */
    @Bean
    public SampleQuestionAdminEngine sampleQuestionAdminEngine(SampleQuestionRegistry registry) {
        return new SampleQuestionAdminEngine(registry);
    }

    /** 流式聊天任务注册表：跟踪 /rag/v3/chat 的运行态、停止、完成与限流拒绝。 */
    @Bean
    public RagStreamTaskRegistry ragStreamTaskRegistry() {
        return RagStreamTaskRegistry.inMemory();
    }

    /**
     * V3 聊天总编排引擎，注入所有运行时依赖。
     *
     * <p>全局并发限流开关与上限来自配置 {@code rag.rate-limit.global.*}：
     * 默认关闭（{@code enabled=false}），上限 {@code max-concurrent=4}。
     */
    @Bean
    public RagV3ChatEngine ragV3ChatEngine(
            ConversationMemoryService memoryService,
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            @Value("${rag.rate-limit.global.enabled:false}") boolean globalRateLimitEnabled,
            @Value("${rag.rate-limit.global.max-concurrent:4}") int globalMaxConcurrent
    ) {
        return new RagV3ChatEngine(
                memoryService,
                queryTermMappingRegistry,
                intentTreeRegistry,
                knowledgeWorkspace,
                streamTaskRegistry,
                globalRateLimitEnabled,
                globalMaxConcurrent
        );
    }

    private String extractFeishuToken(String source) {
        String trimmed = source == null ? "" : source.strip();
        if (trimmed.isBlank()) {
            return "empty";
        }
        String[] parts = trimmed.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "wiki".equalsIgnoreCase(parts[i])) {
                String token = parts[i + 1];
                int queryIndex = token.indexOf('?');
                return queryIndex > 0 ? token.substring(0, queryIndex) : token;
            }
        }
        return trimmed.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
