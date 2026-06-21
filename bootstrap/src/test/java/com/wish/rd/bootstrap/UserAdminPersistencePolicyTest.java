package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAdminPersistencePolicyTest {

    @Test
    void userControllerDelegatesStorageToPersistenceLayer() throws Exception {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        Path controller = moduleRoot.resolve("src/main/java/com/wish/rd/bootstrap/user/controller/UserAdminController.java");
        String source = Files.readString(controller);

        assertFalse(source.contains("LinkedHashMap"), "UserAdminController must not own in-memory user storage");
        assertFalse(source.contains("AtomicLong"), "UserAdminController must not allocate user ids in memory");
        assertFalse(source.contains("synchronized"), "database-backed user CRUD must not synchronize on controller state");
    }

    @Test
    void postgresSqlDeclaresUserTable() throws Exception {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        String sql = Files.readString(moduleRoot.resolve("src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS admin_users"), "PostgreSQL SQL must create admin_users");
        assertTrue(sql.contains("username"), "admin_users must store username");
        assertTrue(sql.contains("password_hash"), "admin_users must store password hash");
    }
}
