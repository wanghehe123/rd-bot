package com.wish.rd.exec.repair.code;

import com.wish.rd.exec.repair.execution.RepairArtifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建代码评审请求命令。
 *
 * @param repoOwner          仓库所属空间
 * @param repoName           仓库名称
 * @param baseBranch         目标分支
 * @param workBranch         修复工作分支
 * @param title              评审请求标题
 * @param prBody             评审请求正文
 * @param artifactReferences 执行产物引用
 * @param metadata           扩展元数据
 */
public record CreatePullRequestCommand(
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        String title,
        String prBody,
        List<RepairArtifact> artifactReferences,
        Map<String, String> metadata
) {

    public CreatePullRequestCommand {
        repoOwner = requireText(repoOwner, "repoOwner");
        repoName = requireText(repoName, "repoName");
        baseBranch = requireText(baseBranch, "baseBranch");
        workBranch = requireText(workBranch, "workBranch");
        title = normalize(title);
        prBody = normalize(prBody);
        artifactReferences = artifactReferences == null ? List.of() : List.copyOf(artifactReferences);
        metadata = copyMetadata(metadata);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, "metadata key");
            if (copied.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("metadata key must be unique after normalization: " + normalizedKey);
            }
            copied.put(normalizedKey, normalize(value));
        });
        return Map.copyOf(copied);
    }
}
