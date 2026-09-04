package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;

import java.io.IOException;
import java.util.Map;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;

/**
 * Docker 修复工作区的代码仓库端口，由 bootstrap 适配 Git CLI 或其他代码平台工作区实现。
 */
public interface RepairWorkspaceRepositoryPort {

    /**
     * 在容器启动前准备本地仓库工作树。
     *
     * @param command   修复执行命令
     * @param workspace Docker 修复工作区
     * @return 仓库准备结果
     * @throws IOException 仓库克隆或分支检出失败
     */
    RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) throws IOException;

    /**
     * 在容器成功修复后提交并发布工作分支。
     *
     * @param command   修复执行命令
     * @param workspace Docker 修复工作区
     * @return 仓库发布结果
     * @throws IOException 仓库提交或推送失败
     */
    RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) throws IOException;

    /**
     * Inspects whether the prepared worktree remains unchanged after a read-only QA run.
     * Implementations that cannot inspect repository state return {@link RepositoryState#unsupported()}.
     */
    default RepositoryState repositoryState(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
        return RepositoryState.unsupported();
    }

    /**
     * 返回不操作代码仓库的默认实现，供非真实执行或测试场景使用。
     *
     * @return no-op 仓库端口
     */
    static RepairWorkspaceRepositoryPort noop() {
        return new RepairWorkspaceRepositoryPort() {
            @Override
            public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) {
                return RepositoryOperationResult.empty();
            }

            @Override
            public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
                return RepositoryOperationResult.empty();
            }
        };
    }

    /**
     * 仓库操作结果元数据。
     *
     * @param metadataJson 仓库操作元数据
     */
    record RepositoryOperationResult(Map<String, String> metadataJson) {

        public RepositoryOperationResult {
            metadataJson = metadataJson == null ? Map.of() : Map.copyOf(metadataJson);
        }

        /**
         * 返回空仓库操作结果。
         *
         * @return 空结果
         */
        public static RepositoryOperationResult empty() {
            return new RepositoryOperationResult(Map.of());
        }
    }

    /**
     * Repository snapshot used by the QA no-mutation guard.
     *
     * <p>QA may start with a platform-applied Coding patch in a local-only workflow, so it must compare its
     * post-run state with this immutable baseline rather than require a globally clean worktree.
     */
    record RepositoryState(
            boolean supported,
            boolean clean,
            String fingerprint,
            String summary,
            String headSha,
            String trackedTreeSha256,
            int trackedFileCount
    ) {

        public RepositoryState {
            fingerprint = fingerprint == null ? "" : fingerprint.strip();
            summary = summary == null ? "" : summary.strip();
            headSha = headSha == null ? "" : headSha.strip();
            trackedTreeSha256 = trackedTreeSha256 == null ? "" : trackedTreeSha256.strip();
            if (trackedFileCount < 0) {
                trackedFileCount = 0;
            }
        }

        /**
         * Compatibility constructor for ports that only expose a combined fingerprint.
         *
         * @param supported whether inspection is available
         * @param clean whether the worktree is porcelain-clean
         * @param fingerprint combined fingerprint
         * @param summary operator-facing summary
         */
        public RepositoryState(boolean supported, boolean clean, String fingerprint, String summary) {
            this(supported, clean, fingerprint, summary, "", fingerprint == null ? "" : fingerprint, 0);
        }

        /** Compatibility constructor for ports that can provide a stable state description but not a hash. */
        public RepositoryState(boolean supported, boolean clean, String summary) {
            this(supported, clean, legacyFingerprint(supported, clean, summary), summary);
        }

        public static RepositoryState unsupported() {
            return new RepositoryState(false, true, "unsupported", "");
        }

        public static RepositoryState cleanState() {
            return new RepositoryState(true, true, "clean", "");
        }

        private static String legacyFingerprint(boolean supported, boolean clean, String summary) {
            return (supported ? "supported" : "unsupported")
                    + ":" + (clean ? "clean" : "dirty")
                    + ":" + (summary == null ? "" : summary.strip());
        }
    }
}
