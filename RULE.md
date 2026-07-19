# RD-Bot 后端开发规范

> 本规范是 RD-Bot 项目（Java 21 / Spring Boot 3.5 多模块单体）的强制开发约定。
> 它融合 **阿里巴巴 Java 开发手册（嵩山版）** 与本项目落地实践，并在关键环节强调
> **设计模式的合理运用**。所有新增/修改代码必须遵守；历史代码在改动时逐步对齐。
>
> 规范分级的含义：
> - **【强制】** 必须遵守，违反即视为缺陷
> - **【推荐】** 应当遵守，有充分理由才可偏离
> - **【参考】** 提供方向性指引

---

## 一、工程结构与分层

### 1.1 模块职责【强制】

项目按"分层"而非"微服务"拆分为五个 Maven 模块，依赖只能单向流动：

```
`bootstrap`（应用入口、HTTP、配置、PostgreSQL 与外部 SDK 适配）
    ├──> `engine`（单任务与多角色业务编排、控制面用例）
    ├──> `exec`（Docker/模型执行与验证能力抽象）
    ├──> `skill`（技能注册、策略、安装与执行切片）
    └──> `rag`（知识、检索、上下文、trace、任务运行时基础域）

`engine` ──> `rag`
`exec`   ──> `rag`
`skill`  ──> `rag`
```

- **依赖方向【强制】**：以根 `pom.xml` 与子模块 `pom.xml` 为真值；允许 `bootstrap -> engine/exec/skill/rag` 与 `engine/exec/skill -> rag`，严禁反向依赖。
- **职责边界【强制】**：
  - `bootstrap` 只做 HTTP 适配（参数校验、响应封装、异常翻译），**不得写业务逻辑**；业务一律下沉到 `engine` 或 `rag`。
  - `engine` 是编排层，负责多角色流水线、执行控制与恢复用例，只通过 Store/Port 读取和推进共享状态。
  - `exec` 负责执行器、provider、验证和健康治理的领域抽象；外部 SDK 与进程适配仍放在 `bootstrap`。
  - `skill` 负责技能策略与可复用能力切片，不直接接触外部基础设施 SDK。
  - `rag` 是基础领域层，承载知识、检索、上下文、trace 与任务运行时端口。
- **包命名【强制】**：`com.wish.rd.<模块>.<子域>`，子域按业务划分（如 `rag.retrieval`、`rag.ingestion`、`rag.prompt`）。控制器统一收敛到 `bootstrap.controller.*`。

### 1.2 组件注册与配置【强制】

- 【强制】业务编排、领域服务、控制器、外部适配器必须优先使用注解式组件注册，严格对齐 ragent 风格：
  - REST 入口使用 `@RestController`。
  - 业务编排类（如 `*Engine`）使用 `@Service`。
  - 外部适配器、限流器、存储适配等基础组件使用 `@Component` 或语义更明确的 stereotype。
  - 依赖统一通过构造器注入；有 Lombok 时可用 `@RequiredArgsConstructor`，没有 Lombok 时手写构造器。
- 【强制】禁止把 `*Engine`、`*Service`、`*Controller` 等业务组件集中写进 `@Configuration` 的 `@Bean` 方法里管理；这类组件必须由 Spring component scan 发现并进入 IOC。
- 【强制】`@Configuration` 只用于装配无法直接注解化的基础设施或第三方客户端 Bean，例如 `DataSource`、`ExecutorService`、`RedissonClient` 包装配置、S3 客户端、条件化存储实现等。
- 【强制】配置类中的 `@Bean` 方法必须注明依赖入参，禁止在 Bean 内部 `new` 已有 Bean；确需创建第三方客户端时必须放在 `bootstrap` 适配层，不能泄漏到 `engine`/`rag`。
- 【强制】可通过 `@Value` 读取的配置项必须给出**合理默认值**，保证零配置可启动：
  ```java
  @Value("${rag.rate-limit.global.enabled:false}") boolean enabled
  @Value("${rag.rate-limit.global.max-concurrent:4}") int maxConcurrent
  ```
- 【推荐】可切换实现的基础设施 Bean（如 `ObjectStorageService` 的 memory/S3）用配置开关选择实现，业务代码只依赖端口接口。

---

## 二、命名规范（对齐阿里巴巴手册）

### 2.1 通用【强制】

- **类名 UpperCamelCase**：`RepairRagPipeline`、`KnowledgeWorkspace`。
- **方法名/变量 lowerCamelCase**：`prepareContext`、`vectorStore`。
- **常量全大写下划线**：`MAX_CHUNK_LENGTH`、`DEFAULT_USER_ID`。
- **包名全小写单数**：`retrieval`（非 `retrievals`）、`ingestion`。

### 2.2 类名后缀约定【强制】

按角色使用固定后缀，一眼看出职责（本项目已落地，须延续）：

| 角色 | 后缀 / 命名 | 示例 |
|------|------------|------|
| REST 控制器 | `*Controller` | `RagV3ChatController` |
| 业务编排 | `*Engine` | `KnowledgeAdminEngine` |
| 内存仓储/聚合 | `*Registry` / `*Workspace` / `*Store` | `QueryTermMappingRegistry`、`KnowledgeWorkspace`、`RagTraceStore` |
| 领域模型 | 名词（无后缀） | `KnowledgeBase`、`IntentNode` |
| 不可变值对象 | `record`，无后缀 | `RetrievedChunk`、`RepairRagRequest` |
| 命令对象（写操作入参） | `*Command` | `CreateKnowledgeBaseCommand` |
| 端口（外部系统抽象） | `*Port` | `LogCenterPort` |
| 策略 | `*Strategy` | `ChunkingStrategy` |
| 工厂 | `*Factory` | `RagRuntimeFactory`、`ChunkingStrategyFactory` |
| 测试结果 | `*Result` | `RagFullFlowTestResult` |
| 选择器 | `*Selector` | `DocumentParserSelector` |

### 2.3 方法命名【强制】

- 布尔返回值方法用 `is/has/can/should` 前缀：`isEnabled(request)`、`blank(value)`。
- 校验型私有方法用 `require/ensure/validate` 前缀：`requireBase(id)`、`ensureChunkBelongsToDocument(...)`。
- 转换方法用 `to/normalize/with` 前缀：`toRetrievedChunk(chunk)`、`normalizeKeyword`、`withScore(newScore)`。
- 【强制】**禁止**用 `getXxx` 命名非 getter 语义的方法（record 自动生成的访问器除外）。

### 2.4 注释【强制】

- **每个公开类/接口/record 必须有 JavaDoc**，说明：职责一句话 + 对应接口路径/调用方 + 依赖。
- **公开方法必须有 JavaDoc**，含 `@param` / `@return` / `@throws`（参数多于 1 个时强制）。
- 关键算法步骤用**行内中文注释**说明"为什么这么做"，而非复述代码。例：
  ```java
  // 同一 chunk 被多通道命中时，保留得分更高的那个
  (left, right) -> left.score() >= right.score() ? left : right
  ```
- 【强制】注释与代码同步更新；过时注释比没有注释更糟。
- 【参考】参考已注释的核心文件（`RepairRagPipeline`、`RagBugFixEngine`、`KnowledgeWorkspace`）的密度与风格。

---

## 三、设计模式运用（重点）

> 原则：**模式服务于可读性与扩展性，不为用而用**。下面是本项目已采用或推荐采用的模式，新增代码遇到同类场景应延续。

### 3.1 分层架构 + 端口适配器（Hexagonal）【强制】

- 外部系统（日志中心、代码仓库、工单、对象存储、LLM）一律抽象为 **Port 接口**，定义在 `rag`/`adapter` 层，实现在 `bootstrap` 或测试 mock：
  ```java
  // rag 层只定义契约
  public interface LogCenterPort {
      List<String> searchLogs(LogQuery query);
  }
  ```
- **收益**：核心 RAG 流程不耦合具体中间件，单测可用 mock 端口，生产可换真实实现。
- 【强制】禁止在 `rag`/`engine` 层直接 `new` 外部 SDK 客户端（如 S3Client、HttpClient）。

### 3.2 策略模式（Strategy）【强制用于"可切换算法"】

凡是"同一抽象、多种实现"的能力，必须用策略接口 + 工厂，而非 `if/else` 分支：

- **已落地**：分块（`ChunkingStrategy` + `FixedSizeTextChunker` / `StructureAwareTextChunker`，由 `ChunkingStrategyFactory` 按 `ChunkingMode` 分发）、解析（`DocumentParser` + 选择器）。
- **规则【强制】**：新增一种分块/解析/检索算法时，实现接口并注册到工厂，**不得**在调用方写 `switch`。
- 选择器/工厂内部用 `Map<Key, Strategy>` 装配，O(1) 查找：
  ```java
  EnumMap<ChunkingMode, ChunkingStrategy> mapped = new EnumMap<>(ChunkingMode.class);
  ```

### 3.3 工厂模式（Factory）【推荐用于"复杂对象组装"】

- **已落地**：`RagRuntimeFactory` 集中装配 `RepairRagPipeline`（基础版/完整版两个重载），避免调用方逐个 `new` 组件。
- 【推荐】当某个对象的构造依赖 4 个以上组件时，提取静态工厂方法，命名 `xxx(...)` / `inMemory(...)` / `withDefaults(...)`。

### 3.4 模板方法 / 流程编排【强制用于"多步骤链路"】

固定步骤的链路用**显式编排方法** + **`@RagTraceNode` 标记**，步骤间用不可变上下文对象传递：

```java
@RagTraceNode(value = "repair-rag-pipeline", category = "rag")
public RepairContextPackage prepareContext(RepairRagRequest request) {
    // 1. 合并查询文本 → 2. 意图分类 → 3. 歧义引导 → 4. 多通道检索 → 5. 上下文打包
}
```

- 摄取管线（`TaskIngestionEngine`）用**节点链 + Context 对象**实现可配置流程，支持任意节点拓扑（含环检测）。
- 【强制】每个可观测步骤都要标注 `@RagTraceNode`，保证链路可追踪。

### 3.5 注册表模式（Registry）【仅用于内存实现】

生产共享状态已经以 PostgreSQL Store 为真值；`*Registry` / `InMemory*Store` 只用于单测、显式 memory 配置和本地临时演示：

- 特征：`DistributedLockExecutor` 保护跨实例状态推进、`LinkedHashMap` 保持插入顺序、自增 ID 序列、`inMemory()`/`withDefaults()` 静态工厂、对外返回不可变快照（`List.copyOf`）。
- 【强制】生产路径的共享可变状态不得依赖 JVM 级 `synchronized`；多实例部署需要通过锁端口接入 Redisson 等分布式锁，读操作返回 `List.copyOf` / `Map.copyOf` 快照，禁止把内部集合引用泄露出去。

### 3.5.1 项目管理持久化【强制】

- 【强制】项目管理（如 RD 项目、仓库地址、默认分支、启用状态）属于生产共享配置，必须通过 `RdProjectStore` 端口落 PostgreSQL 表 `rd_projects`。
- 【强制】禁止新增 `InMemoryRdProjectStore`、静态集合、JVM 本地缓存兜底或配置文件列表作为项目管理真值；任务创建只能读取数据库项目快照，并把项目 ID/key/name 与仓库字段写入 `rd_tasks`。
- 【强制】项目唯一性必须依赖数据库唯一约束（`project_key` 未删除唯一），不能只靠单实例内存校验。

### 3.5.2 管理台生产数据持久化【强制】

- 【强制】管理台可新增、编辑、启停或删除的生产可见数据，必须以 PostgreSQL 为真值来源；`rd.knowledge.store=postgres` 时不得回退到 JVM 内存注册表。
- 【强制】知识库、文档、分块、意图树、摄取管道、摄取任务、项目、RD 任务、任务材料、阶段执行记录等管理台数据必须通过端口/Store 读写 PostgreSQL；内存实现只能在显式 `rd.knowledge.store=memory`、单测或本地临时演示中使用。
- 【强制】新增管理台页面或数据域时，必须同时提供：Store 端口、PostgreSQL 适配器、SQL DDL、以及防退化测试，证明注册表/Engine 不直接持有生产可见 `LinkedHashMap`、`AtomicLong` 等内存真值。
- 【强制】排查“重启后数据消失/全部不可见”时，先确认后端实际配置与数据库计数：`rd.knowledge.store`、`rd.storage.mode`、PostgreSQL 表记录数、HTTP 查询结果；不得在未验证 PostgreSQL 链路前把问题归因于前端空态。

### 3.5.3 任务状态与持久化派发【强制】

- 【强制】`RdTaskStatus` 虽为共享枚举，合法边必须按 `RdTaskType` 分图校验；需求任务不得走 BugFix 的 `SEARCHING` 捷径。
- 【强制】任务快照与对应状态事件必须通过同一个事务端口写入；PostgreSQL 实现必须使用 `@Transactional`，禁止先改快照、后补事件。
- 【强制】任务写锁使用 task ID 粒度；工单幂等创建使用 ticket ID 粒度。禁止用全局注册表锁串行化不同任务。
- 【强制】需求交付派发先写 `rd_requirement_delivery_jobs`，再提交线程池。Worker 必须通过条件更新获得租约；进程重启后恢复 PENDING、FAILED_RETRYABLE 与租约过期的 RUNNING 作业。
- 【强制】阶段重试必须创建新的 `attemptNo`；`FAILED_RETRYABLE` 是旧 attempt 的终态，不得把旧记录改写为 `RECOVERING`。
- 【强制】达到派发重试上限时，作业与主任务都进入 `DEAD_LETTERED` 并保留失败原因。
- 【强制】既有 `AgentStageRun` 的状态推进和元数据保存都必须使用 `WHERE id = ? AND status = ?` 的数据库 CAS；只有首次插入允许 upsert。影响行数不为 1 必须抛出 stale write，禁止旧快照复活已取消或已失败阶段。
- 【强制】重试准备或派发失败必须补偿本轮新建 Attempt、retry checkpoint 与主任务状态；主任务不得遗留在 `RECOVERING`。补偿失败不能覆盖原始异常，应作为 suppressed error 保留。
- 【强制】delivery job 已持久化后即使本地线程池拒绝，也必须保留可恢复的 `PENDING` 真值；不得把“未进入当前 JVM 线程池”等同于“未提交”。

### 3.6 聚合根（Aggregate Root）【强制用于"强一致实体群"】

- **已落地**：`KnowledgeWorkspace` 是知识域聚合根，统一管理 知识库→文档→分块→向量 的级联一致性（删除知识库级联删文档/分块/向量；更新文档同步刷新分块与向量库）。
- 【强制】聚合内的跨实体一致性操作必须通过聚合根方法完成，外部不得绕过根直接改子实体。

### 3.7 值对象与不可变性（Value Object）【强制】

- 纯数据载体一律用 `record`，并在紧凑构造器里做**防御性归一**：
  ```java
  public record RepairContextPackage(...) {
      public RepairContextPackage {
          retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
      }
  }
  ```
- 【强制】`null` 入参必须归一为安全默认值（空集合/空串/`Optional.empty()`），禁止把 `null` 传到下游。

### 3.8 建造者（Builder）【推荐用于"多可选参数的领域对象"】

- **已落地**：`IntentNode.builder()...build()` 用于有大量可选字段的领域模型。
- 【推荐】当构造参数 ≥5 个且多数可选时，用 Builder 而非 telescoping 构造器。

### 3.9 函数式接口作为回调【推荐】

- 端口/钩子优先用 `@FunctionalInterface`（如 `RepairTaskContextPort`），而非定义带单个方法的抽象类。

---

## 四、编码规范（对齐阿里巴巴手册）

### 4.1 空指针与边界【强制】

- 【强制】所有外部入参（HTTP 参数、public 方法入参）必须 `null` 校验或归一，**信任边界内**才可省略。
- 【强制】集合返回值永不返回 `null`，用 `List.of()` / `Set.of()` / `Map.of()` 代替。
- 【强制】`Optional` 只用作返回值，**不要**用作字段或参数；`Optional.get()` 前必须有 `isPresent()` 或改用 `orElse/orElseGet/map`。

### 4.2 集合【强制】

- 【强制】遍历集合时禁止修改（增强 for + remove 会 CME），需要过滤用 Stream `filter`，需要修改先复制。
- 【强制】去重保序用 `LinkedHashMap`/`LinkedHashSet`；需要线程安全的"写时复制"用 `CopyOnWriteArrayList`（如 `InMemoryVectorStore`）。
- 【强制】`subList` 返回的是视图，修改会影响原列表——需要独立副本用 `new ArrayList<>(sub)`。

### 4.3 字符串与文本【强制】

- 【强制】文本拼接到 JSON / SSE / SQL 时必须转义，本项目已有 `json(value)` 工具方法处理反斜杠与双引号。
- 【强制】涉及字符编码一律显式 `StandardCharsets.UTF_8`，禁止依赖平台默认编码。
- 【强制】字符串比较常量在左：`"runtime-log".equals(chunk.knowledgeType())`，避免 NPE。

### 4.4 并发【强制】

- 【强制】会被多实例同时访问的共享可变状态必须通过端口接入分布式锁或数据库原子约束；`synchronized` 只允许用于单进程私有资源保护（如本地文件 append、生命周期关闭保护、测试桩快照）。
- 【强制】进程内高频局部计数可使用 `java.util.concurrent` 原子类（如限流的 `AtomicInteger` + CAS）。
- 【强制】并发检索用 `Executors.newVirtualThreadPerTaskExecutor()`（Java 21 虚拟线程），每个通道一个线程，`try-with-resources` 关闭。
- 【强制】`CompletableFuture` 聚合多任务时用 `join` 等待，异常会在此抛出，需在调用方处理。
- 【推荐】耗时操作（记忆加载）用虚拟线程异步化，`.exceptionally(ignored -> List.of()).join()` 做兜底降级。

### 4.5 异常【强制】

- 【强制】业务校验失败抛 `IllegalArgumentException`（带清晰中文消息）；资源不存在抛 `NoSuchElementException`；非法状态抛 `IllegalStateException`。
- 【强制】异常消息要可定位：`"knowledge base not found: " + knowledgeBaseId`。
- 【强制】Controller 用 `@ExceptionHandler` 统一翻译异常为 HTTP 状态码与错误体，禁止栈信息直接暴露给前端。
- 【强制】不要捕获异常后忽略（空 catch），至少记录日志或包装重抛。

### 4.6 控制语句【强制】

- 【强制】`if/else/for/while/do` 必须用大括号 `{}`，即使单行。
- 【强制】`switch` 必须有 `default`（除非是穷尽枚举的增强 switch 表达式）。
- 【推荐】优先用 Stream + 表达式风格的 `switch`（见 `TaskIngestionEngine.executeNode`）。

### 4.7 Java 21 特性【推荐】

- 【推荐】能用 `record` 就不用普通类做值对象。
- 【推荐】模式匹配 `switch`、文本块 `"""..."""`、`SealedClass` 在合适场景使用。
- 【推荐】IO/网络密集型并发优先用虚拟线程。

---

## 五、REST 接口规范

### 5.1 路径与动词【强制】

- 【强制】资源用名词复数或领域前缀：`/knowledge-base`、`/conversations`、`/mappings`、`/ingestion/tasks`。
- 【强制】动词语义：查询 `GET`、创建 `POST`、全量更新 `PUT`、部分更新 `PATCH`、删除 `DELETE`。
- 【强制】测试通道统一 `/test/...` 前缀（如 `/test/rag/full-flow`），与正式接口隔离。

### 5.2 参数与响应【强制】

- 【强制】复杂入参用请求体 record（`*Request`），简单布尔/ID 用 `@RequestParam`/`@PathVariable`。
- 【强制】分页参数命名统一：后端管理用 `current`/`size`，兼容外部系统时用 `pageNo`/`pageSize`（按既有约定）。
- 【强制】响应体用 record，禁止 `Map<String,Object>` 当万能返回（除非是动态配置如 `/rag/settings`）。
- 【强制】SSE 流式接口设置 `Content-Type: text/event-stream; charset=UTF-8`。

### 5.3 状态码【强制】

- 200 正常；400 参数错误；404 资源不存在；409 冲突；429 限流；500 服务端异常。对应 `@ExceptionHandler` 统一映射。

### 5.4 管理端契约与开发代理【强制】

- 【强制】公开 DTO 的金额字段必须带币种后缀并与页面单位一致，例如 `estimatedSpendCny`；禁止将内部 USD 字段直接作为人民币展示。
- 【强制】Vite SPA bypass 只能匹配明确的页面导航路由；`/admin/rd-tasks/{id}/timeline`、`execution-overview`、`content` 等嵌套接口必须代理到后端，不能因 `Accept: text/html` 或同路径前缀返回 `index.html`。
- 【强制】每次新增或修改 `/admin/*` 前端请求，都必须更新代理契约测试，分别断言页面导航和嵌套 API 的行为。
- 【强制】异步加载的 Select/Input 必须始终保持 controlled；不能在 `undefined` 与具体值之间切换并把 React warning 留到运行期。
- 【强制】管理端响应式变更至少验证 390px、900px 与桌面视口：不得出现横向溢出或顶栏交叠；隐藏侧栏必须 `aria-hidden` 且 `inert`，打开后转移焦点，关闭或按 Escape 后归还焦点。

---

## 六、测试规范

### 6.1 覆盖要求【强制】

- 【强制】每个公开能力必须有对应测试（本项目 README "Verification" 列出的覆盖范围须持续维护）。
- 【强制】核心链路（BugFix Chat、兼容聊天入口、修复主流程、检索、改写、Prompt、摄取）必须有端到端冒烟测试（`/test/*` 通道 + JUnit 断言）。
- 【推荐】单测覆盖边界：空入参、空集合、并发、限流命中、歧义引导。

### 6.2 测试编写【强制】

- 【强制】测试类与被测类同包，命名 `XxxTest`；方法命名用 `should_预期_当条件` 或中文描述。
- 【强制】测试必须可独立运行，不依赖外部中间件（用 mock 端口 / `inMemory()` 工厂）。
- 【强制】提交前本地 `./mvnw test` 全绿。
- 【强制】修改任务状态、事件、阶段 CAS 或持久化派发后，除模块测试外必须运行 `PostgresRdTaskStateAtomicRealSmokeTest`，以真实 PostgreSQL 证明事务与原子状态更新可用。
- 【强制】前端改动提交前必须运行全部 Node contract test、TypeScript typecheck 和生产 build；涉及布局或路由时再补真实浏览器验证，不能只凭静态代码审查判定通过。

### 6.3 请求链回归验收【强制】

- 【强制】单元测试通过后，必须补充一套**真实 HTTP 请求链**（至少一条核心链路），通过 `curl/http` 实际发起请求并断言响应码、响应体关键字段与副作用，形成回归不变量。
- 【强制】请求链必须覆盖“调用前后行为不变”场景：至少包含一次关键接口成功链路、一次回写或状态变更链路，必要时加 1 条反查链路，确认已有行为未被改坏。
- 【强制】若服务未启用外部集成，允许使用本地测试前置与测试前缀接口，但不能仅靠数据库或内存断言替代 HTTP 真实请求；请求脚本/命令与验证断言需在测试报告或任务记录中保留。

---

## 七、日志与可观测性

### 7.1 日志【强制】

- 【强制】使用 SLF4J，禁止 `System.out.println` 进生产代码（测试调试例外）。
- 【强制】日志带上下文：`log.warn("intent ambiguous, candidates={}", candidates)`，禁止字符串拼接。
- 【强制】ERROR 记录完整异常栈，INFO/WARN 记录关键业务节点。

### 7.2 链路追踪【强制】

- 【强制】关键链路方法标注 `@RagTraceNode(value, category)`，便于 `/rag/traces` 查询。
- 【强制】测试通道跑完链路后必须 `startRun` → `recordNode` → `finishRun` 三段式记录。

---

## 八、Git 与协作

- 【强制】提交信息格式：`<type>: <desc>`，type 用 `feat/fix/refactor/docs/test/chore`。
- 【强制】一次提交只做一件事，禁止混合功能与格式化。
- 【推荐】分支命名：`feat/xxx`、`fix/xxx`、`refactor/xxx`。
- 【强制】禁止提交本地 IDE 配置、`target/`、临时文件；`.gitignore` 保持更新。
- 【强制】Playwright CLI session、console log、临时 page snapshot 与本地 QA 输出不得随功能代码提交；需要长期保留的证据必须放入明确的验收报告目录并先完成脱敏。

---

## 九、与本项目的对应关系（速查）

| 规范要点 | 项目中的范例 |
|---------|------------|
| 分层单向依赖 | `bootstrap -> engine/exec/skill/rag`，`engine/exec/skill -> rag`，见各级 `pom.xml` |
| 端口适配器 | `LogCenterPort` / `CodeRepositorySearchPort` / `ObjectStorageService` |
| 策略 + 工厂 | `ChunkingStrategy` + `ChunkingStrategyFactory` |
| 模板方法/编排 | `RepairRagPipeline.prepareContext`、`TaskIngestionEngine.execute` |
| 注册表 | `QueryTermMappingRegistry`、`IntentTreeRegistry`、`RagStreamTaskRegistry` |
| 聚合根 | `KnowledgeWorkspace`（级联一致性） |
| 值对象 | `RetrievedChunk`、`RepairContextPackage`（record + 防御性归一） |
| Builder | `IntentNode.builder()` |
| 函数式端口 | `RepairTaskContextPort`（`@FunctionalInterface`） |
| 虚拟线程并发 | `MultiChannelRetrievalEngine.retrieve`、`DefaultConversationMemoryService` |
| Redis 队列限流 | `ChatQueueLimiter` + `RedisChatQueueLimiter` + `FairDistributedRateLimiter` |
| 链路追踪 | `@RagTraceNode` + `RagTraceStore` |

---

## 十、落地检查清单（Code Review 用）

- [ ] 分层依赖是否单向？控制器是否只做适配？
- [ ] `*Engine`、`*Service`、Controller、外部适配器是否使用注解式组件注册，而不是集中 `@Bean` 管理？
- [ ] 每个公开类/方法是否有 JavaDoc？关键步骤是否有行内注释？
- [ ] 外部入参是否做了 null 归一与校验？
- [ ] 集合返回值是否永不为 null？内部集合是否未泄露？
- [ ] 生产共享状态是否通过分布式锁、数据库原子约束或并发原语保护，且未依赖 JVM 级 `synchronized`？
- [ ] 既有阶段保存与状态推进是否使用期望状态 CAS，重试失败是否完成 Attempt/checkpoint/task 补偿？
- [ ] 新增算法分支是否走了策略/工厂，而非 `if/else`？
- [ ] 外部系统是否走端口抽象，未在领域层 `new` SDK？
- [ ] 异常是否分类清晰、消息可定位、Controller 统一处理？
- [ ] 关键链路是否标注 `@RagTraceNode`？
- [ ] 是否补充了对应测试，`./mvnw test` 是否全绿？
- [ ] 管理端 API 是否通过 Vite 代理契约测试，390px/900px/桌面布局和 console 是否真实验证？
- [ ] 提交是否符合"一事一提交 + 规范信息"？

---

*本规范随项目演进持续更新；规范的解释权归 RD-Bot 维护者。*
