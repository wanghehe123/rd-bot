package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceImplementationPolicyTest {

    @Test
    void postgresPersistenceUsesMyBatisPlusInsteadOfJdbcTemplate() throws Exception {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        String javaSources;
        try (var paths = Files.walk(moduleRoot.resolve("src/main/java"))) {
            javaSources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", String::concat);
        }

        assertFalse(javaSources.contains("JdbcTemplate"), "PostgreSQL adapters must not use JdbcTemplate");
        assertFalse(javaSources.contains("org.springframework.jdbc"), "PostgreSQL adapters must not use Spring JDBC CRUD");
        assertFalse(javaSources.contains("jdbcTemplate"), "PostgreSQL adapters must route CRUD through MyBatis-Plus mappers");

        String pom = Files.readString(moduleRoot.resolve("pom.xml"));
        assertTrue(pom.contains("mybatis-plus-spring-boot3-starter"), "bootstrap must depend on MyBatis-Plus Boot3 starter");
        assertFalse(pom.contains("<artifactId>spring-jdbc</artifactId>"), "bootstrap must not keep direct spring-jdbc dependency");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to read " + path, exception);
        }
    }
}
