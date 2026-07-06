package com.wish.rd.exec.repair.docker.model;

import java.nio.file.Path;

/**
 * Docker Claude Code 工作区内的标准输入和输出文件路径。
 *
 * @param prompt            执行提示词文件
 * @param context           执行上下文 JSON 文件
 * @param resultSchema      结构化结果 schema 文件
 * @param resultJson        执行器结构化结果文件
 * @param patchDiff         修复补丁文件
 * @param testLog           测试日志文件
 * @param claudeEventsJsonl Claude Code 事件流文件
 * @param dockerMetaJson    Docker 执行元数据文件
 */
public record RepairWorkspaceFiles(
        Path prompt,
        Path context,
        Path resultSchema,
        Path resultJson,
        Path patchDiff,
        Path testLog,
        Path claudeEventsJsonl,
        Path dockerMetaJson
) {
}
