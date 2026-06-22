package com.wish.rd.exec.repair.docker;

import java.nio.file.Path;

/**
 * 单次修复执行的本地 Docker 工作区，供执行器挂载仓库、输入和输出目录。
 *
 * @param root            工作区根目录
 * @param inputDirectory  输入目录
 * @param repoDirectory   代码仓库目录
 * @param outputDirectory 输出目录
 * @param files           标准协议文件路径
 */
public record RepairWorkspace(
        Path root,
        Path inputDirectory,
        Path repoDirectory,
        Path outputDirectory,
        RepairWorkspaceFiles files
) {
}
