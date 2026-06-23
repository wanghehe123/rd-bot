package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.engine.ticket.TicketFieldMapping;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书 IM 工单文本解析器。
 *
 * <p>支持用户在飞书消息中使用简单的 {@code key: value} 或 {@code key：value}
 * 行格式提交自动修复信息；未命中结构化字段时，把整段文本作为标题和描述。
 */
@Component
public class FeishuImTicketParser {

    private static final int TITLE_MAX_LENGTH = 80;

    /**
     * 解析飞书 IM 文本为内部工单草稿。
     *
     * @param text 用户消息文本
     * @return 内部工单草稿
     */
    public FeishuImTicketDraft parse(String text) {
        String raw = text == null ? "" : text.strip();
        if (raw.isBlank()) {
            return new FeishuImTicketDraft("", "", "P2", Map.of(), "");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        String priority = "P2";
        String symptom = "";
        StringBuilder description = new StringBuilder();
        for (String line : raw.lines().map(String::strip).filter(value -> !value.isBlank()).toList()) {
            ParsedLine parsed = parseLine(line);
            if (parsed.key().isBlank()) {
                appendLine(description, line);
                continue;
            }
            String mappedKey = mapKey(parsed.key());
            if ("priority".equals(mappedKey)) {
                priority = parsed.value();
            } else if (!mappedKey.isBlank()) {
                fields.put(mappedKey, parsed.value());
                if (TicketFieldMapping.KEY_SYMPTOM.equals(mappedKey)) {
                    symptom = parsed.value();
                }
            } else {
                appendLine(description, line);
            }
        }

        if (description.isEmpty()) {
            description.append(raw);
        }
        String title = symptom.isBlank() ? fallbackTitle(raw) : symptom;
        return new FeishuImTicketDraft(title, description.toString().strip(), priority, fields, raw);
    }

    private static ParsedLine parseLine(String line) {
        int colon = line.indexOf(':');
        int chineseColon = line.indexOf('：');
        int index;
        if (colon < 0) {
            index = chineseColon;
        } else if (chineseColon < 0) {
            index = colon;
        } else {
            index = Math.min(colon, chineseColon);
        }
        if (index <= 0 || index >= line.length() - 1) {
            return new ParsedLine("", line);
        }
        return new ParsedLine(line.substring(0, index).strip(), line.substring(index + 1).strip());
    }

    private static String mapKey(String key) {
        return switch (key.strip()) {
            case "系统", "模块", "服务", "problemSystem" -> TicketFieldMapping.KEY_PROBLEM_SYSTEM;
            case "仓库", "代码仓库", "repository" -> TicketFieldMapping.KEY_REPOSITORY;
            case "分支", "branch" -> TicketFieldMapping.KEY_BRANCH;
            case "问题", "现象", "symptom" -> TicketFieldMapping.KEY_SYMPTOM;
            case "日志", "log", "logs" -> TicketFieldMapping.KEY_LOGS;
            case "期望", "expectedResult" -> TicketFieldMapping.KEY_EXPECTED_RESULT;
            case "实际", "actualResult" -> TicketFieldMapping.KEY_ACTUAL_RESULT;
            case "优先级", "priority" -> "priority";
            default -> "";
        };
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private static String fallbackTitle(String raw) {
        String firstLine = raw.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        if (firstLine.length() <= TITLE_MAX_LENGTH) {
            return firstLine;
        }
        return firstLine.substring(0, TITLE_MAX_LENGTH);
    }

    private record ParsedLine(String key, String value) {
    }
}
