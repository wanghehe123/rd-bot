# RD-Bot 修复队列 Redis Stream 迁移 Spec

日期：2026-08-02

## 1. 目标与范围

将修复工单队列的生产实现从 RocketMQ 迁移为 Redis Stream（Redisson 4.0.0），不改变
engine 对队列的公开契约，也不引入 Spring Data Redis、数据库 schema 变更或跨运行时自动
fallback。

运行模式只有以下两种：

| `rd.repair.queue.mode` | 用途 | 默认值 |
| --- | --- | --- |
| `redis-stream` | Redis Stream 生产队列 | 是 |
| `memory` | 显式本地/测试同步队列 | 否 |

迁移后不保留 RocketMQ client 依赖、`rd.rocketmq.*` 配置、生产适配器或活动测试。历史
报告和历史 smoke 记录可以保留其当时的事实，不作为当前运行配置。

## 2. 兼容契约

下列既有字段和方法名是稳定边界，迁移不得改变其签名或语义：

- `RepairQueuePublisher.publish(RepairTicketMessage)`；
- `RepairQueueConsumer.handle(RepairTicketMessage)`；
- `RepairQueuePublishResult` 的全部 record 字段；其中 `targetTopic` 作为兼容字段承载
  Stream key，`messageId` 承载 Redis record ID；
- `RepairTicketMessage` 的字段、`tag()`、`nextAttempt()` 与 `deduplicationKey()`。

`priority` 仍然通过 `tag()` 暴露，以兼容已有调用方；Redis Stream 不依赖 broker tag。
消息只允许包含 `ticketId`、`priority`、`traceId`、`attempt`、`source`、`eventId`、
`eventType`、`createdAt`。标题、描述、日志、token、原始 webhook 响应均不得进入 Stream。

## 3. 配置与隔离

配置前缀为 `rd.redis-stream.repair`，定义在
`bootstrap/src/main/resources/application.yaml`：

| 属性 | 环境变量 | 默认值 |
| --- | --- | --- |
| `stream-key` | `RD_REDIS_STREAM_REPAIR_STREAM_KEY` | `rd-bot:repair:tickets` |
| `consumer-group` | `RD_REDIS_STREAM_REPAIR_CONSUMER_GROUP` | `rd-bot-repair-workers` |
| `consumer-name-base` | `RD_REDIS_STREAM_REPAIR_CONSUMER_NAME_BASE` | `rd-bot-repair` |
| `consumer-concurrency` | `RD_REDIS_STREAM_REPAIR_CONSUMER_CONCURRENCY` | `2` |
| `poll-timeout-millis` | `RD_REDIS_STREAM_REPAIR_POLL_TIMEOUT_MILLIS` | `1000` |
| `batch-size` | `RD_REDIS_STREAM_REPAIR_BATCH_SIZE` | `10` |
| `pending-claim-idle-millis` | `RD_REDIS_STREAM_REPAIR_PENDING_CLAIM_IDLE_MILLIS` | `60000` |
| `failure-backoff-millis` | `RD_REDIS_STREAM_REPAIR_FAILURE_BACKOFF_MILLIS` | `1000` |
| `max-retry-attempts` | `RD_REDIS_STREAM_REPAIR_MAX_RETRY_ATTEMPTS` | `3` |

所有计数和超时在绑定层归一为安全值：`consumer-concurrency` 固定限制在 `[1, 16]`，
`failure-backoff-millis` 至少为 `1ms`，`pending-claim-idle-millis` 至少为 `100ms`。活跃
处理中的 PEL lease 每隔该 idle 窗口的三分之一续约一次（至少 `1ms`），避免配置为零或负值
创建无界空转或让活跃 handler 被立即抢占。队列使用专门的
`redisStreamRepairQueueRedissonClient`，通过 qualifier 注入；它独立于
`distributedLockRedissonClient`，因此 `rd.distributed-lock.mode=local` 与 `redisson`
下都能启动，不会出现 `RedissonClient` 注入歧义。

## 4. Stream 生命周期与并发

`RedisStreamRepairQueueAdapter` 使用 `RStream<String, String>` 和 `StringCodec.INSTANCE`：

1. Spring lifecycle 启动时先执行 `XGROUP CREATE <stream> <group> 0 MKSTREAM`。仅当
   `RedisBusyException` 明确包含 `BUSYGROUP` 时视为已存在；其他 Redis 错误必须向上抛出。
2. 之后只向 `RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LOOP_EXECUTOR_BEAN`
   （`rdRedisStreamRepairLoopTaskExecutor`）提交 `consumer-concurrency` 个长期循环。该专用、
   条件化生命周期 executor 的 core/max 都等于归一后的并发数，队列容量为 `0`；长期循环绝不能
   占用共享 repair-dispatch executor。consumer name 使用 `consumer-name-base + UUID + index`，
   在进程和循环之间唯一。
3. 循环直接内联调用 `RepairQueueConsumer.handle`，不得 submit-and-wait，也不得占用 HTTP、
   webhook 或 scheduler 线程。`rdRedisStreamRepairLeaseTaskScheduler` 是独立的、有名称的
   lifecycle scheduler，pool size 等于同一归一化并发数，只负责活跃 handler 的 lease 续约。
4. `stop()` 先翻转运行标记，再取消所有 lease future 和 `cancel(true)` 所有 loop future；阻塞读取
   或 backoff 遇到中断必须退出。连续 Redis 故障只记录一次完整警告并按
   `failure-backoff-millis` 退避，成功读取后才重置该错误日志门闩，避免无限日志。

## 5. 消费、PEL 与原子状态转移

每次外层循环先扫描 PEL，再读取从未投递的记录：

1. `XAUTOCLAIM` 以 `0-0` 开始，使用 `pending-claim-idle-millis` 和 `batch-size`；返回非
   `0-0` cursor 时继续分页直到扫描耗尽。下一次外层循环重新从 `0-0` 开始，以便未来变为 idle
   的记录仍能被恢复。
2. `XREADGROUP ... >` 只读取从未投递的记录。
3. 每个开始处理的 PEL entry 都建立 in-flight lease。续约 Lua 在一个 Redis 原子脚本中先执行
   `XPENDING stream group recordId recordId 1` 并比较其 consumer，再以相同 consumer 执行
   `XCLAIM ... 0 JUSTID` 重置 idle。它不能把已被其他 consumer 接管的 entry 抢回；检测到 owner
   改变后本地 handler 放弃后续完成操作。
4. `handle(...) == true` 时使用同一个 owner-fenced Lua script 执行 `XACK`，且仅在确认 ACK
   成功后 `XDEL`。脚本同时验证 XPENDING 的 consumer 等于当前 loop consumer，因此旧 owner
   不能在 entry 被 XAUTOCLAIM 后确认或删除它。
5. `handle(...) == false` 或在 `attempt <= max-retry-attempts` 时抛出 `RuntimeException`，
   都先构造 `message.nextAttempt()`，再在同一个 owner-fenced Lua script 中执行：先以
   `XPENDING` 比对 recordId 的 expected consumer；`XADD` 写入一次 retry；`XACK` 原记录；
   `XDEL` 原记录。脚本是 Redis 单线程原子操作，因此旧 owner 或重复调用不能写出第二条 retry。
   脚本未完成时不 ACK 原记录，后续 `XAUTOCLAIM` 负责恢复。
6. `attempt > max-retry-attempts` 的消费者异常或 `handle(...) == false` 均不能再创建
   `attempt + 1`。其中 terminal `false` 必须先调用
   `RepairQueueDeadLetterRepository.save(message, reason)`，以有界、稳定的 `reason` 持久化有效消息的
   终态 dead letter；只有该调用成功后才执行原有的 owner-fenced ACK/DEL。持久化
   抛错时不 ACK，原记录保持 PEL，供后续 `XAUTOCLAIM` 恢复；owner 在持久化窗口改变时，后续
   ACK/DEL 仍由 expected-consumer fence 拒绝。PostgreSQL 仓储以 `ticketId`、`source`、`eventId`、
   `eventType`（与原有有效消息去重身份一致，不含 attempt）导出确定性主键，并用原子 insert-if-absent
   吸收旧/新 owner 的重复终态保存。
7. 引擎消费者收到超过 `max-retry-attempts` 的消息时仍可先保存 dead letter 并返回 `true`，使该记录
   进入既有正常 ACK/DEL 路径；适配器不得对该 `true` 路径再次保存。

这是一条 at-least-once 消费链路；下游仍按 `deduplicationKey()` 做业务幂等。不能把 PEL
恢复改为无界 follow-up prompt、跨运行时 fallback 或一次错误就丢弃。

## 6. 畸形记录与死信

不能构造有效 `RepairTicketMessage` 的原始 Stream entry（例如空 `ticketId`、非法 `attempt`
或非法时间）必须调用扩展端口：

```java
RepairQueueDeadLetterRepository.saveMalformed(
    String redisRecordId,
    Map<String, String> rawSafeFields,
    String reason
)
```

畸形 entry 的适配器和仓储都仅保存白名单薄字段及 `redisRecordId`；未知字段不会泄露到死信 JSON。
畸形 entry 与有效消息的 terminal `false` 都必须在死信持久化成功后才能执行 ACK/DEL；持久化抛错时
让 PEL 保留该记录供后续恢复。PostgreSQL 实现对有效 terminal `false` 以
`ticketId`、`source`、`eventId`、`eventType` 生成不超过 128 字符的稳定主键
`redis-stream-valid-<sha256>`；畸形 entry 则把 `redisRecordId` 保留在现有 `message_json`，并生成
独立命名空间的 `redis-stream-malformed-<sha256>`。两种路径共用 mapper 的
`INSERT ... ON CONFLICT (id) DO NOTHING` 原子插入；冲突后按该 id 查询现有行，不扫描整张表，也不新增
表列或迁移。memory 模式保存到内存仓储。
no-op 仓储不实现畸形记录持久化，必须抛错而不是伪造成功。

## 7. 验收与回归命令

聚焦单元与配置验证：

```bash
./mvnw -q -pl bootstrap -am -Dtest=RedisStreamRepairQueueAdapterTest,RedisStreamRepairQueueLifecycleTest,RedisStreamRepairQueueConfigurationTest,InMemoryRepairQueueAdapterTest,PostgresRepairQueueDeadLetterRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

本机 Redis 可用时，运行被显式开关保护的真实 smoke。测试使用唯一 Stream key，并且只删除
该测试创建的 key；它验证发布、原子 retry、消费者异常后的递增 retry（投递 attempt 为
`[1, 2, 3]`）与最终 ACK/DEL、超过上限的 terminal `false` 只持久化一条 dead letter 且不会被
`XAUTOCLAIM` 再次调用，并以两个 consumer 和超过 claim idle 的长 handler 验证活跃 lease 不会
发生重复执行，且只允许当前 owner settle：

```bash
./mvnw -q -pl bootstrap -am -Drd.redis.stream.smoke=true -Dtest=RedisStreamRepairQueueRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

完整回归前还应执行：

```bash
./mvnw -q test
cd frontend && npm run typecheck && npm run build
rg -n -i 'rocketmq|rd\.rocketmq' bootstrap/src/main engine/src/main frontend/src README.md
```

前端 build 负责刷新 `bootstrap/src/main/resources/static/admin` 的生成资源；不得手改压缩后的
静态 bundle。
