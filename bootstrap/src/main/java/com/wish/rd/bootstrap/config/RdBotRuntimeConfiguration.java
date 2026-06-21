package com.wish.rd.bootstrap.config;

import com.wish.rd.engine.admin.conversation.ConversationAdminEngine;
import com.wish.rd.engine.admin.ingestion.IngestionAdminEngine;
import com.wish.rd.engine.admin.intent.IntentTreeAdminEngine;
import com.wish.rd.engine.admin.knowledge.KnowledgeAdminEngine;
import com.wish.rd.engine.admin.feedback.MessageFeedbackAdminEngine;
import com.wish.rd.engine.admin.rewrite.QueryTermMappingAdminEngine;
import com.wish.rd.engine.rag.RagV3ChatEngine;
import com.wish.rd.engine.admin.sample.SampleQuestionAdminEngine;
import com.wish.rd.rag.feedback.MessageFeedbackRegistry;
import com.wish.rd.rag.ingestion.IngestionAdminRegistry;
import com.wish.rd.rag.ingestion.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.memory.ConversationRegistry;
import com.wish.rd.rag.memory.ConversationMemoryService;
import com.wish.rd.rag.memory.DefaultConversationMemoryService;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.sample.SampleQuestionRegistry;
import com.wish.rd.rag.trace.RagTraceStore;
import com.wish.rd.bootstrap.storage.S3ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
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

    /** 知识库工作区：内存中聚合知识库、文档、分块与向量存储，是知识域的单一事实来源。 */
    @Bean
    public KnowledgeWorkspace knowledgeWorkspace() {
        return KnowledgeWorkspace.inMemory();
    }

    /** 知识管理编排层：对外暴露知识库/文档/分块/概览等管理能力。 */
    @Bean
    public KnowledgeAdminEngine knowledgeAdminEngine(KnowledgeWorkspace workspace) {
        return new KnowledgeAdminEngine(workspace);
    }

    /** 摄取管理注册表：维护可配置管线与已执行任务，依赖知识工作区落地文档与分块。 */
    @Bean
    public IngestionAdminRegistry ingestionAdminRegistry(KnowledgeWorkspace workspace) {
        return IngestionAdminRegistry.inMemory(workspace);
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
}
