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
