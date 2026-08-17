package com.wish.rd.bootstrap.observability;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryObservabilityPersistencePolicyTest {

    @Test
    void thisChangeDoesNotAddMetricTablesOrWideIndexes() throws Exception {
        Path sqlRoot = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres");
        StringBuilder sql = new StringBuilder();
        try (Stream<Path> files = Files.list(sqlRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                sql.append(Files.readString(file)).append('\n');
            }
        }
        String text = sql.toString();
        assertFalse(text.contains("CREATE TABLE IF NOT EXISTS rd_metrics"));
        assertFalse(text.contains("CREATE TABLE IF NOT EXISTS rd_delivery_observability"));
        assertFalse(text.contains("CREATE TABLE IF NOT EXISTS rd_metrics_events"));
        assertTrue(text.contains("CREATE TABLE IF NOT EXISTS rd_tasks"));
        assertTrue(text.contains("CREATE TABLE IF NOT EXISTS rd_agent_stage_runs"));
    }

    @Test
    void queryAdapterDoesNotLiveInTheHttpController() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        String controller = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/observability/DeliveryObservabilityController.java"));
        assertFalse(controller.contains("DataSource"));
        assertFalse(controller.contains("DeliveryObservabilityMapper"));
        assertTrue(controller.contains("DeliveryObservabilityQueryService"));
        String mapper = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/DeliveryObservabilityMapper.java"));
        assertTrue(mapper.contains("#{projectId,jdbcType=VARCHAR}"),
                "null projectId must carry a JDBC type so PostgreSQL can bind IS NULL");
    }
}
