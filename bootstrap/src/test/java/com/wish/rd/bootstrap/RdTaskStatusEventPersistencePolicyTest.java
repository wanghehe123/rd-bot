package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RD 任务状态事件持久化策略测试。
 *
 * <p>验证 {@code rd_task_status_events} 表 DDL 与 Postgres 适配文件齐备，且
 * {@code rd_tasks} 已具备管理台暂停标记列。不依赖真实 PostgreSQL，符合 AGENTS.md
 * 「持久化工作需在仓库边界证明」的要求。
 */
class RdTaskStatusEventPersistencePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void shouldProvideStatusEventStoreAndProjectManagedSql() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdTaskStatusEventStore.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdTaskStatusEventRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdTaskStatusEventMapper.java")));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_status_events"));
        assertTrue(sql.contains("task_id       BIGINT NOT NULL"));
        assertTrue(sql.contains("duration_ms   BIGINT       NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("trigger       VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM'"));
        assertTrue(sql.contains("idx_rd_task_events_task ON rd_task_status_events (task_id, entered_at)"));
    }

    @Test
    void rdTasksTableShouldSupportPausedFlag() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT FALSE"));
    }
}
