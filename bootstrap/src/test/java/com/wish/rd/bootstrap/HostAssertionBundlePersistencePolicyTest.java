package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HostAssertionBundlePersistencePolicyTest {

    @Test
    void shouldProvideImmutableHostAssertionBundleStorage() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String sql = Files.readString(projectRoot.resolve("src/main/resources/sql/postgres/p5_qa_evidence.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_host_assertion_bundles"));
        assertTrue(sql.contains("PRIMARY KEY (task_id, stage_run_id, scope)"));
        assertTrue(sql.contains("canonical_specs_json JSONB NOT NULL"));
        assertTrue(sql.contains("content_hash TEXT NOT NULL"));
        assertTrue(sql.contains("version BIGINT NOT NULL"));
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                "src/main/java/com/wish/rd/bootstrap/persistence/entity/HostAssertionBundleRow.java")));
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                "src/main/java/com/wish/rd/bootstrap/persistence/mapper/HostAssertionBundleMapper.java")));
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                "src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresHostAssertionBundleStore.java")));
    }
}
