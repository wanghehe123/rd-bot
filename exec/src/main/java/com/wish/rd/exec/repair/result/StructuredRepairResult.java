package com.wish.rd.exec.repair.result;

import java.util.List;

/**
 * Claude Code 修复任务输出的结构化 result.json，供执行结果校验器和后续编排适配器读取。
 *
 * @param status          修复状态
 * @param summary         修复摘要
 * @param prBody          PR 正文
 * @param changedFiles    变更文件列表
 * @param testCommands    已执行或建议执行的测试命令
 * @param testStatus      测试状态
 * @param riskLevel       风险等级
 * @param needHumanAction 是否需要人工处理
 */
public record StructuredRepairResult(
        String status,
        String summary,
        String prBody,
        List<String> changedFiles,
        List<String> testCommands,
        String testStatus,
        String riskLevel,
        Boolean needHumanAction
) {

    public StructuredRepairResult {
        status = normalize(status);
        summary = normalize(summary);
        prBody = normalize(prBody);
        changedFiles = normalizeList(changedFiles);
        testCommands = normalizeList(testCommands);
        testStatus = normalize(testStatus);
        riskLevel = normalize(riskLevel);
        needHumanAction = needHumanAction == null ? Boolean.FALSE : needHumanAction;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> normalizeList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .map(StructuredRepairResult::normalize)
                .toList();
    }
}
