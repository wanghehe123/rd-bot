package com.wish.rd.engine.ticket;

import com.wish.rd.adapter.TicketSnapshot;

import java.util.Map;

/**
 * 工单字段映射器：把工单快照的自定义字段映射到 RD-Bot 修复流程所需的归一化字段。
 *
 * <p>飞书 Helpdesk 自定义字段名由配置 {@code rd.feishu.helpdesk.field-mapping.*} 提供，
 * 引擎层不感知具体字段名，只依赖此映射器的归一化输出。
 *
 * <p>映射出的标准字段：
 * <ul>
 *   <li>{@code problemSystem}：故障系统/模块</li>
 *   <li>{@code symptom}：故障现象</li>
 *   <li>{@code triggerWay}：触发方式</li>
 *   <li>{@code priority}：优先级（已由 TicketSnapshot 归一）</li>
 *   <li>{@code logs}：相关日志摘要</li>
 *   <li>{@code repository}：代码仓库</li>
 *   <li>{@code branch}：代码分支</li>
 *   <li>{@code expectedResult}：期望结果</li>
 *   <li>{@code actualResult}：实际结果</li>
 * </ul>
 */
public final class TicketFieldMapping {

    /** 自定义字段 key 常量：避免到处用魔法字符串。 */
    public static final String KEY_PROBLEM_SYSTEM = "problemSystem";
    public static final String KEY_SYMPTOM = "symptom";
    public static final String KEY_TRIGGER_WAY = "triggerWay";
    public static final String KEY_PRIORITY = "priority";
    public static final String KEY_LOGS = "logs";
    public static final String KEY_REPOSITORY = "repository";
    public static final String KEY_BRANCH = "branch";
    public static final String KEY_EXPECTED_RESULT = "expectedResult";
    public static final String KEY_ACTUAL_RESULT = "actualResult";

    private final Map<String, String> fieldNameByStandardKey;

    public TicketFieldMapping(Map<String, String> fieldNameByStandardKey) {
        this.fieldNameByStandardKey = fieldNameByStandardKey == null ? Map.of() : Map.copyOf(fieldNameByStandardKey);
    }

    /**
     * 创建默认映射：标准 key 与工单自定义字段名一一对应。
     *
     * @return 默认映射器
     */
    public static TicketFieldMapping defaults() {
        return new TicketFieldMapping(Map.of(
                KEY_PROBLEM_SYSTEM, KEY_PROBLEM_SYSTEM,
                KEY_SYMPTOM, KEY_SYMPTOM,
                KEY_TRIGGER_WAY, KEY_TRIGGER_WAY,
                KEY_LOGS, KEY_LOGS,
                KEY_REPOSITORY, KEY_REPOSITORY,
                KEY_BRANCH, KEY_BRANCH,
                KEY_EXPECTED_RESULT, KEY_EXPECTED_RESULT,
                KEY_ACTUAL_RESULT, KEY_ACTUAL_RESULT
        ));
    }

    /**
     * 把工单自定义字段按映射表抽取为标准 key 的归一化 Map。
     *
     * @param ticket 工单快照
     * @return 标准 key -> 值（缺省为空串）
     */
    public Map<String, String> extract(TicketSnapshot ticket) {
        if (ticket == null) {
            return Map.of();
        }
        Map<String, String> custom = ticket.customFields();
        java.util.Map<String, String> extracted = new java.util.LinkedHashMap<>();
        putIfMapped(extracted, custom, KEY_PROBLEM_SYSTEM);
        putIfMapped(extracted, custom, KEY_SYMPTOM);
        putIfMapped(extracted, custom, KEY_TRIGGER_WAY);
        putIfMapped(extracted, custom, KEY_LOGS);
        putIfMapped(extracted, custom, KEY_REPOSITORY);
        putIfMapped(extracted, custom, KEY_BRANCH);
        putIfMapped(extracted, custom, KEY_EXPECTED_RESULT);
        putIfMapped(extracted, custom, KEY_ACTUAL_RESULT);
        extracted.put(KEY_PRIORITY, ticket.priority());
        return java.util.Map.copyOf(extracted);
    }

    /**
     * 从工单自定义字段抽取日志文本（可能为多行）。
     *
     * @param ticket 工单快照
     * @return 日志文本，缺省 {@code ""}
     */
    public String extractLogs(TicketSnapshot ticket) {
        if (ticket == null) {
            return "";
        }
        return value(ticket, KEY_LOGS);
    }

    /**
     * 判断工单是否具备进入 RAG 的最小信息集合。
     *
     * <p>规则：描述或症状至少一项非空，且（日志或代码仓库至少一项非空）。
     *
     * @param ticket 工单快照
     * @return 信息充足返回 true
     */
    public boolean hasEnoughInfo(TicketSnapshot ticket) {
        if (ticket == null) {
            return false;
        }
        boolean hasContext = !ticket.description().isBlank()
                || !value(ticket, KEY_SYMPTOM).isBlank();
        boolean hasEvidence = !extractLogs(ticket).isBlank()
                || !value(ticket, KEY_REPOSITORY).isBlank();
        return hasContext && hasEvidence;
    }

    private void putIfMapped(java.util.Map<String, String> target, Map<String, String> custom, String standardKey) {
        target.put(standardKey, value(custom, standardKey));
    }

    private String value(TicketSnapshot ticket, String standardKey) {
        return value(ticket.customFields(), standardKey);
    }

    private String value(Map<String, String> custom, String standardKey) {
        String fieldName = fieldNameByStandardKey.getOrDefault(standardKey, standardKey);
        String value = custom.get(fieldName);
        return value == null ? "" : value.trim();
    }
}
