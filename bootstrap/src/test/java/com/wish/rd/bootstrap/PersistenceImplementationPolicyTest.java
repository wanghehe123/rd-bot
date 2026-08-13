package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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

    /**
     * 把绑定参数直接和 NULL 比较时必须显式声明 {@code jdbcType}，否则 PostgreSQL 报
     * "could not determine data type of parameter" 并让整条查询失败。这类错误只在真实
     * 数据库出现，用 SQL 字符串断言的 mapper 测试永远看不见，所以在这里静态拦掉。
     * 参数属性用逗号分隔，因此"花括号内没有逗号"就等于没有声明类型。
     */
    @Test
    void mapperSqlMustNotCompareAnUntypedParameterAgainstNull() throws Exception {
        Path mapperRoot = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/wish/rd/bootstrap/persistence/mapper");
        Pattern untypedNullCheck = Pattern.compile("#\\{[^},]+}\\s+IS\\s+(NOT\\s+)?NULL", Pattern.CASE_INSENSITIVE);
        try (var paths = Files.walk(mapperRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                assertFalse(
                        untypedNullCheck.matcher(withoutComments(read(path))).find(),
                        path.getFileName() + " compares a bare bind parameter against NULL; "
                                + "give PostgreSQL a type (cast, sentinel value, or dynamic SQL) instead");
            }
        }
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to read " + path, exception);
        }
    }
}
