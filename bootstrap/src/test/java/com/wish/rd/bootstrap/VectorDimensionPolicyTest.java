package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VectorDimensionPolicyTest {

    @Test
    void applicationVectorDimensionShouldMatchPostgresSchema() throws Exception {
        int applicationDimension = applicationVectorDimension();
        int schemaDimension = postgresSchemaVectorDimension();

        assertEquals(schemaDimension, applicationDimension);
    }

    @SuppressWarnings("unchecked")
    private int applicationVectorDimension() throws Exception {
        Path resource = existingPath(
                Path.of("bootstrap/src/main/resources/application.yaml"),
                Path.of("src/main/resources/application.yaml")
        );
        Map<String, Object> root = new Yaml().loadAs(Files.newInputStream(resource), Map.class);
        Map<String, Object> rag = (Map<String, Object>) root.get("rag");
        Map<String, Object> defaults = (Map<String, Object>) rag.get("default");
        return (Integer) defaults.get("dimension");
    }

    private Path existingPath(Path first, Path second) {
        return Files.exists(first) ? first : second;
    }

    private int postgresSchemaVectorDimension() throws Exception {
        ClassPathResource resource = new ClassPathResource("sql/postgres/p0_knowledge_productionization.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("embedding\\s+vector\\((\\d+)\\)").matcher(sql);
        if (!matcher.find()) {
            throw new AssertionError("knowledge_vectors.embedding vector dimension not found");
        }
        return Integer.parseInt(matcher.group(1));
    }
}
