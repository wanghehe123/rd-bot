# RD 任务控制面增强经验规范

日期：2026-07-10

适用范围：Bug/需求阶段编排、任务材料、项目告警、项目模板、AI 草稿、管理端静态发布和 PostgreSQL 迁移。

关联材料：

- `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-design.md`
- `docs/qa/rd-task-control-plane-enhancements-acceptance-plan-2026-07-10.md`
- `docs/qa/rd-task-control-plane-enhancements-acceptance-report-2026-07-10.md`

## 1. 阶段记录必须反映真实执行边界

1. 阶段只能在真实动作开始前创建，在动作结束或失败时落终态，不能根据任务主状态事后拼装“成功记录”。
2. 每个阶段必须绑定真实 `RoleContextPackage`，并保存 `INPUT`、`PROMPT`、`RESULT` 产物；失败时同时保留 `errorCategory`、脱敏后的 `errorMessage` 和时间线。
3. 终态 `AgentStageRun` 不得复活。重试必须创建更大的 `attemptNo`，概览与恢复逻辑只取同角色最新 attempt。
4. Bug 与需求使用各自角色顺序。概览必须按当前任务类型过滤角色，不能让历史脏数据或另一条流水线污染进度。
5. 新阶段接入至少验证：成功、动作前失败、动作后失败、重试、新旧 attempt 选择和前端错误展示。

## 2. 二进制材料必须穿过完整信任边界

1. 原始字节写对象存储；数据库只保存归属、URI、MIME、大小、文件名和 SHA-256，禁止把图片 base64 塞进任务表或 prompt。
2. 上传必须联合校验大小、数量、扩展名、声明 MIME 和文件签名。下载与执行解析时都要重新计算 hash，不能只信数据库 metadata。
3. HTML 等可执行内容只允许 attachment 下载，不能 inline；内容接口必须校验 `taskId + materialId` 归属。
4. 执行目录文件名必须包含 `materialId`，避免同名覆盖。写入前后都要防路径穿越和 symlink，最终落盘使用原子移动。
5. 任何新增图片格式都必须同步更新上传校验、content 响应、执行附件、浏览器预览和负向测试。

## 3. 项目告警采用可审计的近似 exactly-once 语义

1. 发送前必须先在 PostgreSQL 预留 `PENDING` 投递记录；分布式锁和唯一键共同约束 `task + event + recipient` 幂等键。
2. Feishu 请求必须传稳定 `uuid`。重试已有 `PENDING` 时复用同一个 provider UUID，禁止先发送后补审计。
3. provider 成功、失败和审计更新失败都要留下结果。告警失败应 fail-open，不得反向改变任务状态。
4. 收件人、错误文本和 provider 响应进入日志或 DB 前必须脱敏；凭证只读环境变量或秘密存储。
5. 项目阈值优先于全局阈值；项目未配置时才允许显式全局 fallback。真实外部投递必须使用已授权目标，未执行只能标记 `BLOCKED_EXTERNAL/NOT_VERIFIED`。

## 4. 项目共享配置只认 PostgreSQL 真值

1. 告警配置和任务模板不得以 JVM 内存 Store 作为生产真值；内存实现只用于单测或显式非生产 profile。
2. Mapper 多参数方法必须使用 `@Param`，不能以 H2/Mock 成功推断 PostgreSQL/MyBatis 生产路径可用。
3. 每个新增项目配置都要完成真实 `PUT -> GET -> DB 反查 -> bootstrap 重启 -> GET` 验收。
4. SQL 迁移必须可重复执行。至少连续执行两次，并用 PostgreSQL 模式启动 bootstrap；这样才能暴露历史字段宽度、索引和旧 schema 漂移。

## 5. AI 只生成候选草稿，不替用户作决定

1. 模型输出只接受一个严格 JSON object：拒绝 code fence、尾随 token、错误类型、缺必填字段和越界 confidence。
2. 无凭证、provider 不可用或结构非法时返回明确 unavailable，不得用规则文本冒充 AI 结果。
3. 草稿接口不得创建任务、推进状态或触发执行；前端默认只填空字段，任何用户非空输入都不得覆盖。
4. 弹窗关闭、项目切换、任务类型切换或再次请求后，旧响应不得写回新表单。所有异步加载都要带 request version/sequence guard。
5. credential 只通过环境变量注入，不得进入请求日志、错误详情、静态 bundle 或仓库文本。

## 6. React 源码完成不等于管理端已交付

1. `frontend` 修改后必须运行 `npm run typecheck` 和 `npm run build`，并确认 Spring Boot 托管的 `bootstrap/src/main/resources/static/admin` 已同步。
2. 对关键功能检查构建产物中的 API 路径和按钮文案，再用真实静态 bundle 做桌面与移动浏览器验收。
3. 浏览器验收至少检查横向溢出、控件重叠、图片非空、旧响应覆盖和 console error；不能只看源码截图。

## 7. 本地模块和生产实链防误判

1. 修改 `engine`/`exec`/`rag` 后，单模块启动前先 install 对应依赖；出现迁移类 `NoClassDefFoundError` 时先执行全量 `./mvnw -q install -DskipTests`。
2. 不得把 stale jar 的旧行为误判为代码修复无效，也不得把 mock/内存模式通过写成 PostgreSQL 或 provider 实链通过。
3. 最终报告必须分开列出自动化、HTTP/DB/对象存储、浏览器和外部系统证据。任何未执行的外部副作用必须显式保留缺口。

## 8. 最小交付门槛

涉及本规范范围的修改，至少执行：

```bash
./mvnw -q test
cd frontend && npm run typecheck && npm run build
git diff --check
```

同时按变更面补充真实 PostgreSQL、HTTP、对象存储或浏览器验证，并在 QA 报告中记录资源 ID、响应码、关键字段和未验证项。

## 9. 预算币种与收件人编辑的稳定规则

1. RD-Bot 的预算真值是 CNY。provider 返回的 USD 只能在 provider metadata 解析点短暂存在，必须在进入 watchdog、告警、项目配置、任务概览和前端 DTO 前按同一固定汇率规范化。
2. 公共字段必须使用 `estimatedSpendCny`、`budgetAlertCny` 和 `budgetThresholdCny`；原始 `Usd` 金额不得出现在 HTTP 响应、Feishu 告警 metadata 或管理端 UI。
3. 存量 USD 配置迁移只能在目标 CNY 列为空时计算一次。连续执行 SQL 后，已迁移记录必须保持不变，禁止把换算后的金额再次乘以汇率。
4. 群聊和个人告警收件人分别维护 `CHAT_ID` 与 `OPEN_ID` 列表；两个列表都允许为空。前端只接受非空 `oc_` 群聊 ID 与 `ou_` 用户 ID，并在保存前去重、过滤空行。
5. 图标式添加和删除控件必须有 Tooltip 与可访问名称；弹窗内容区需要受限高度与内部滚动，保证窄视口下的取消/保存操作可见可用。
6. Vite 构建完成后，若正在运行的 Spring Boot 从 `bootstrap/target/classes/static` 提供资源，必须同步资源或重启后端；只更新 `src/main/resources/static` 不等于真实静态 bundle 已被本机服务加载。

## 10. 新建任务前端反馈与开发代理规则

1. 文件 input 只能负责选择，不应作为用户确认界面。待上传图片必须进入独立状态列表，展示真实缩略图，并允许逐张删除后再提交。
2. `URL.createObjectURL` 必须和单个预览组件生命周期绑定，在文件移除、表单关闭或组件卸载时执行 `URL.revokeObjectURL`。
3. 多次选择图片要追加、去重并在前端执行类型、单张大小和总数量限制；每次处理后清空 file input，保证删除后可以重新选择同一文件。
4. 页面使用 `sonner.toast` 时，应用根节点必须挂载 Sonner `Toaster`。自定义事件 ToastHost 不能替代第三方通知宿主，否则业务会执行但用户看不到成功或失败反馈。
5. 新增 `/admin/...` API 时要同步检查 Vite proxy。生产静态 bundle 直连 Spring 成功，不代表 `localhost:5173` 开发模式也能访问该接口。
6. AI 接口 HTTP 200 且 `available=false` 是可观测的 provider 配置状态，不应与 Vite 404 混为一谈；前端必须原样显示后端可读原因。

## 11. 本地启动只服务于验证，验证结束必须关闭

1. Agent 为编译、HTTP、数据库或浏览器验收而启动的 `spring-boot:run`、Vite、临时 HTTP server 或其他长驻进程，只能在验证窗口内存活；成功、失败或被用户中断后都必须立即进入关闭流程。
2. 关闭范围只能是本轮 Agent 自己启动并持有 session/port 证据的进程。不得按进程名猜测或停止用户已存在的 Java、Vite、Docker、数据库或中间件进程。
3. 对 Agent 持有的交互式进程优先发送 `Ctrl-C`，等待退出码，再用端口反查确认监听已消失。例如后端使用 `lsof -nP -iTCP:<port> -sTCP:LISTEN`；无输出才算关闭完成。
4. 当一次启动验证发现 ApplicationContext、迁移、provider 或 HTTP 错误时，先保留必要诊断输出，再关闭该实例；不得为了“方便继续排查”无限期保留失败或已验证的服务。
5. 只有用户明确要求“保持运行”“不要关闭”或继续要求浏览器/HTTP 联调时，才允许在最终答复前保留该服务。最终答复必须列出仍运行的 URL、启动归属和关闭命令；未获该授权时，最终答复必须确认服务已经关闭。
6. 验收报告中的启动命令必须配套关闭与端口反查证据。没有关闭证据的本地服务验收不算收口完成。

最小验证模板：

```bash
# 仅对本轮 Agent 启动的 session 发送 Ctrl-C，等待进程退出。
lsof -nP -iTCP:<port> -sTCP:LISTEN
# 期望无输出；若仍有监听，先确认其归属，禁止盲目 kill 用户进程。
```

## 12. 阶段状态必须同时保护 transition 与 save

2026-07-20 的并发风险复审发现：只给 `transition()` 加 CAS 不够。执行线程可能先读到 `RUNNING` 快照，管理员随后把阶段取消为 `CANCELLED`，执行线程再通过无条件 upsert 保存 provider、artifact 或日志元数据，就会把阶段复活成 `RUNNING`。

1. 首次创建阶段记录可以使用 insert/upsert；既有记录的 `save()` 与 `transition()` 都必须执行 `WHERE id = ? AND status = ?` 的条件更新。
2. 条件更新影响行数不为 1 时抛出 stale write，调用方不得继续追加与旧状态矛盾的事件或产物。
3. 测试必须覆盖“读取 RUNNING -> 并发 CANCELLED -> 旧快照保存失败”，不能只覆盖不同目标状态之间的 transition 竞争。
4. 当前 CAS 以状态为版本，同一状态的并发元数据写入仍是最后写入者胜出；若后续需要字段级无损合并，应增加独立 version，而不是放宽 CAS。

## 13. 重试准备失败也属于需要审计的状态机分支

1. 从失败点恢复时，只为缺失或已终态的角色创建新 `attemptNo`；同角色最新 Attempt 仍为非终态时必须复用/跳过，不能并行复制。
2. checkpoint 创建后，任何 Attempt 准备、job 持久化或派发异常都必须关闭本轮新建 Attempt、将 checkpoint 置为 `FAILED_RETRYABLE`，并把主任务从 `RECOVERING` 补偿为 `FAILED_RETRYABLE`。
3. 补偿异常作为 suppressed error 附着到原始异常，避免为了“收尾成功”丢失真正根因。
4. delivery job 已成功写入后，本地线程池拒绝只代表当前 JVM 未立即执行。提交 future 可以失败，但数据库 job 必须保留 `PENDING`，由恢复调度继续领取。
5. retry checkpoint 标记 `DISPATCHED` 与 delivery job 持久化目前不是同一事务，进程在两步之间崩溃仍有小窗口；后续若提升一致性，应使用同一事务/outbox，而不是依赖日志顺序。

## 14. 恢复证据和公开契约必须精确

1. 恢复材料写入前必须验证 `stageRunId` 存在并属于当前 task；角色 Prompt、QA/RAG 证据和恢复动作读取时绑定界面当前选中的 Attempt，不能隐式取第一条历史记录。
2. 当前归属校验尚未强制恢复目标一定是“最新失败 Attempt”。在引入更严格约束前，Controller 不得宣称已完成该校验，前端仍需提交它实际展示的失败 `stageRunId`。
3. 对外 token 花费契约统一使用 `estimatedSpendCny`；内部 provider 若以 USD 计费，必须在适配边界转换后再返回，不能只改页面单位。

## 15. Vite 路由与响应式布局必须做运行时验收

1. Vite bypass 仅允许任务列表和数字任务详情等明确 SPA 导航。相同前缀下的 `timeline`、`execution-overview`、`content` 等请求始终走 Spring Boot，即使请求头包含 `text/html`。
2. 代理测试必须同时覆盖“导航得到 HTML”和“嵌套接口没有被 SPA 劫持”；仅在生产静态 bundle 下返回 200 不能证明开发代理正确。
3. 管理端顶栏在 1024px 及以下提前收起次要动作，避免 900px 平板视口发生品牌、项目选择器和动作区交叠。
4. 移动侧栏关闭时同时设置 `aria-hidden` 与 `inert`；打开后焦点进入首个导航项，关闭、Escape 或路由切换后焦点回到菜单按钮。完整 focus trap 仍是后续增强项。
5. 异步项目选择器必须从初始空值到真实项目 ID 始终 controlled，浏览器 console 的 uncontrolled/controlled warning 视为验收失败。

## 16. 2026-07-20 回归证据

- `./mvnw -q test`：全量 Maven 测试通过。
- `./mvnw -q -pl bootstrap -Drd.integration.task-state-atomic.enabled=true -Dtest=PostgresRdTaskStateAtomicRealSmokeTest test`：连接真实 PostgreSQL 并通过。
- `node --test test/*.test.ts`：101/101 通过；`npm run typecheck`、`npm run build` 通过。
- 900x800 与 390x844 浏览器验证均为横向溢出 0，顶栏无交叠；最终生产 bundle console 为 0 error、0 warning。
- 本轮没有触发真实 provider、仓库写入或 PR 外部副作用；这些链路不能用本轮结果冒充已重新验收。
