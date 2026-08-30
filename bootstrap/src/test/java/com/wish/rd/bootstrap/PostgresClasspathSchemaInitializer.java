package com.wish.rd.bootstrap;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies boot-time PostgreSQL scripts before Spring beans query the database.
 *
 * <p>{@code @Sql} with separator {@code ;} plus a blank line splits {@code DO $$} bodies in
 * {@code p18}. This initializer executes each listed file as one JDBC statement so dollar-quoted
 * functions and constraint blocks survive intact, and runs before
 * {@code IngestionAdminRegistry} seeds {@code t_ingestion_pipeline}.
 */
public final class PostgresClasspathSchemaInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        String url = environment.getProperty("spring.datasource.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8");
        String username = environment.getProperty("spring.datasource.username", "postgres");
        String password = environment.getProperty("spring.datasource.password", "postgres");
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                connection.setAutoCommit(true);
                if (schemaAlreadyApplied(connection)) {
                    applyScriptIfMissing(connection, "p16_model_provider_credentials.sql", "rd_model_provider_credentials");
                    applyScriptIfMissing(connection, "p19_project_agent_memory.sql", "rd_project_memories");
                    return;
                }
                for (Resource script : listedScripts()) {
                    executeSql(connection, script);
                }
            }
        } catch (Exception failed) {
            throw new IllegalStateException("failed to apply PostgreSQL classpath schema before context refresh",
                    failed);
        }
    }

    private static List<Resource> listedScripts() throws java.io.IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> names = List.of(
                "p0_knowledge_productionization.sql",
                "p1_multi_agent_orchestration.sql",
                "p4_web_evaluation_console.sql",
                "p8_pi_agent_runtime.sql",
                "p16_model_provider_credentials.sql",
                "p18_pi_agent_state_and_remediation.sql",
                "p19_project_agent_memory.sql"
        );
        List<Resource> scripts = new ArrayList<>();
        for (String name : names) {
            scripts.add(resolver.getResource("classpath:sql/postgres/" + name));
        }
        return scripts;
    }

    private static boolean schemaAlreadyApplied(Connection connection) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT to_regclass('public.rd_agent_remediation_rounds')")) {
            return result.next() && result.getString(1) != null;
        }
    }

    private static void applyScriptIfMissing(Connection connection, String name, String tableName) throws Exception {
        if (tablePresent(connection, tableName)) {
            return;
        }
        applyScript(connection, name);
    }

    private static boolean tablePresent(Connection connection, String tableName) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT to_regclass('public." + tableName + "')")) {
            return result.next() && result.getString(1) != null;
        }
    }

    private static void applyScript(Connection connection, String name) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        executeSql(connection, resolver.getResource("classpath:sql/postgres/" + name));
    }

    private static void executeSql(Connection connection, Resource script) throws Exception {
        String sql = script.getContentAsString(StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception failed) {
            throw new IllegalStateException("failed to apply " + script.getFilename(), failed);
        }
    }
}
