package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.agent.model.AgentRole;

import java.util.List;

/**
 * 从 Coding 结果提取恢复发布所需的完整、紧凑事实，避免依赖截断的审计预览。
 */
final class RequirementPublicationFactsProjection {

    static final String ARTIFACT_TYPE = "PUBLICATION_FACTS_JSON";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> CODING_FIELDS = List.of(
            "status", "summary", "prBody", "changedFiles", "testCommands", "testStatus", "riskLevel");

    private RequirementPublicationFactsProjection() {
    }

    static String from(AgentRole role, String resultJson) {
        if (role != AgentRole.CODING_AGENT) {
            return "";
        }
        try {
            JsonNode source = OBJECT_MAPPER.readTree(resultJson == null ? "" : resultJson);
            if (source == null || !source.isObject()) {
                return "";
            }
            ObjectNode projection = OBJECT_MAPPER.createObjectNode();
            for (String field : CODING_FIELDS) {
                JsonNode value = source.get(field);
                if (value != null && !value.isNull()) {
                    projection.set(field, value.deepCopy());
                }
            }
            return projection.toString();
        } catch (JsonProcessingException exception) {
            return "";
        }
    }
}
