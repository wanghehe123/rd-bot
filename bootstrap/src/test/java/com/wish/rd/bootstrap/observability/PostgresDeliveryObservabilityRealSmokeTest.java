package com.wish.rd.bootstrap.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real PostgreSQL / HTTP smoke. Disabled by default so a skip is not a pass.
 *
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=PostgresDeliveryObservabilityRealSmokeTest \
 *   -Drd.integration.delivery-observability.enabled=true \
 *   -Drd.delivery-observability.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rdbot_acceptance \
 *   -Drd.delivery-observability.smoke.postgres-user=postgres \
 *   -Drd.delivery-observability.smoke.postgres-password=postgres \
 *   -Drd.delivery-observability.smoke.base-url=http://127.0.0.1:18080 \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.delivery-observability.enabled", matches = "true")
class PostgresDeliveryObservabilityRealSmokeTest {

    @Test
    void queriesExistingLedgersAndAdminRoutesWithoutNewTables() throws Exception {
        String url = required("rd.delivery-observability.smoke.postgres-url");
        String user = System.getProperty("rd.delivery-observability.smoke.postgres-user", "postgres");
        String password = System.getProperty("rd.delivery-observability.smoke.postgres-password", "postgres");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            assertFalse(tableExists(statement, "rd_metrics_events"));
            assertFalse(tableExists(statement, "rd_delivery_observability"));
            ResultSet explain = statement.executeQuery("""
                    EXPLAIN (ANALYZE, BUFFERS)
                    SELECT COUNT(*) FROM rd_tasks
                    WHERE task_type = 'REQUIREMENT'
                    """);
            StringBuilder plan = new StringBuilder();
            while (explain.next()) {
                plan.append(explain.getString(1)).append('\n');
            }
            assertTrue(plan.toString().contains("Seq Scan") || plan.toString().contains("Index"));
        }

        String baseUrl = System.getProperty("rd.delivery-observability.smoke.base-url", "").trim();
        if (baseUrl.isEmpty()) {
            return;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        for (String path : List.of(
                "/actuator/prometheus",
                "/admin/observability/delivery/overview",
                "/admin/observability/delivery/timeseries",
                "/admin/observability/delivery/failures",
                "/admin/observability/delivery/tasks"
        )) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + " body=" + response.body());
            if (path.contains("prometheus")) {
                assertFalse(response.body().contains("taskId="));
                assertTrue(response.body().contains("rd_bot_delivery_completed_total"));
            } else {
                assertFalse(response.body().contains("\"prompt\""));
            }
        }
        HttpResponse<String> bad = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/admin/observability/delivery/overview?window=2h"))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());
    }

    static List<String> missingRequiredProperties() {
        List<String> missing = new ArrayList<>();
        if (blank("rd.delivery-observability.smoke.postgres-url")) {
            missing.add("rd.delivery-observability.smoke.postgres-url");
        }
        return missing;
    }

    private static boolean tableExists(Statement statement, String table) throws Exception {
        ResultSet resultSet = statement.executeQuery(
                "SELECT to_regclass('public." + table + "') IS NOT NULL");
        return resultSet.next() && resultSet.getBoolean(1);
    }

    private static String required(String key) {
        String value = System.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("missing " + key);
        }
        return value;
    }

    private static boolean blank(String key) {
        return System.getProperty(key, "").isBlank();
    }
}
