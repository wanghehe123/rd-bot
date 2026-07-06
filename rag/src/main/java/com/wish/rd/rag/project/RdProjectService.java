package com.wish.rd.rag.project;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.model.RdProjectCommand;
import com.wish.rd.rag.project.model.RdProjectPage;
import com.wish.rd.rag.project.model.RdProjectQuery;

/**
 * RD 项目管理领域服务。
 *
 * <p>供管理台项目 CRUD 与任务创建时解析项目仓库快照。该服务只依赖 {@link RdProjectStore}
 * 端口，生产实现必须落 PostgreSQL。
 */
@Service
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectService {

    private static final Pattern PROJECT_KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,126}[a-z0-9]");

    private final SnowflakeIdGenerator idGenerator;
    private final RdProjectStore store;

    public RdProjectService(SnowflakeIdGenerator idGenerator, RdProjectStore store) {
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.store = java.util.Objects.requireNonNull(store, "rd project store must not be null");
    }

    /**
     * 创建项目。
     *
     * @param command 创建命令
     * @return 新项目快照
     */
    public RdProject create(RdProjectCommand command) {
        RdProjectCommand safeCommand = requireCommand(command);
        ensureProjectKeyAvailable(safeCommand.projectKey(), "");
        RepositoryParts parts = repositoryParts(safeCommand);
        long now = System.currentTimeMillis();
        RdProject project = new RdProject(
                idGenerator.nextIdString(),
                safeCommand.projectKey(),
                safeCommand.name(),
                safeCommand.description(),
                safeCommand.repositoryUrl(),
                parts.owner(),
                parts.name(),
                safeCommand.defaultBranch(),
                safeCommand.enabled(),
                false,
                now,
                now
        );
        return store.save(project);
    }

    /**
     * 更新项目。
     *
     * @param projectId 项目 ID
     * @param command   更新命令
     * @return 更新后的项目快照
     */
    public RdProject update(String projectId, RdProjectCommand command) {
        RdProject existing = get(projectId);
        RdProjectCommand safeCommand = requireCommand(command);
        ensureProjectKeyAvailable(safeCommand.projectKey(), existing.projectId());
        RepositoryParts parts = repositoryParts(safeCommand);
        return store.save(existing.withUpdatedFields(
                safeCommand,
                parts.owner(),
                parts.name(),
                System.currentTimeMillis()
        ));
    }

    /**
     * 查询项目详情。
     *
     * @param projectId 项目 ID
     * @return 项目快照
     */
    public RdProject get(String projectId) {
        String safeProjectId = requireProjectId(projectId);
        return store.findById(safeProjectId)
                .filter(project -> !project.deleted())
                .orElseThrow(() -> new NoSuchElementException("project not found: " + safeProjectId));
    }

    /**
     * 查询启用项目，供创建任务选择。
     *
     * @param projectId 项目 ID
     * @return 启用项目快照
     */
    public RdProject getEnabled(String projectId) {
        RdProject project = get(projectId);
        if (!project.enabled()) {
            throw new IllegalArgumentException("project is disabled: " + projectId);
        }
        return project;
    }

    /**
     * 分页查询项目。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public RdProjectPage query(RdProjectQuery query) {
        RdProjectQuery safeQuery = query == null ? new RdProjectQuery("", null, 1, 20) : query;
        String keyword = safeQuery.keyword().toLowerCase(java.util.Locale.ROOT);
        List<RdProject> filtered = store.list().stream()
                .filter(project -> !project.deleted())
                .filter(project -> safeQuery.enabled() == null || project.enabled() == safeQuery.enabled())
                .filter(project -> keyword.isBlank() || matches(keyword, project))
                .sorted(Comparator.comparingLong(RdProject::updateTimeEpochMillis).reversed()
                        .thenComparing(RdProject::projectId))
                .toList();
        int total = filtered.size();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeQuery.pageSize());
        int fromIndex = Math.min((safeQuery.page() - 1) * safeQuery.pageSize(), total);
        int toIndex = Math.min(fromIndex + safeQuery.pageSize(), total);
        return new RdProjectPage(filtered.subList(fromIndex, toIndex), total, safeQuery.page(), safeQuery.pageSize(), pages);
    }

    /**
     * 逻辑删除项目。
     *
     * @param projectId 项目 ID
     * @return 删除后的项目快照
     */
    public RdProject delete(String projectId) {
        RdProject existing = get(projectId);
        return store.save(existing.deleted(System.currentTimeMillis()));
    }

    private static boolean matches(String keyword, RdProject project) {
        return contains(project.projectKey(), keyword)
                || contains(project.name(), keyword)
                || contains(project.repositoryUrl(), keyword)
                || contains(project.repoOwner(), keyword)
                || contains(project.repoName(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    private RdProjectCommand requireCommand(RdProjectCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        if (command.projectKey().isBlank() || !PROJECT_KEY_PATTERN.matcher(command.projectKey()).matches()) {
            throw new IllegalArgumentException("projectKey must be 3-128 lowercase letters, digits, dot, underscore or dash");
        }
        if (command.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (command.repositoryUrl().isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must not be blank");
        }
        if (command.defaultBranch().isBlank()) {
            throw new IllegalArgumentException("defaultBranch must not be blank");
        }
        return command;
    }

    private void ensureProjectKeyAvailable(String projectKey, String currentProjectId) {
        store.findByKey(projectKey)
                .filter(project -> !project.deleted())
                .filter(project -> !project.projectId().equals(currentProjectId))
                .ifPresent(project -> {
                    throw new IllegalArgumentException("projectKey already exists: " + projectKey);
                });
    }

    private static String requireProjectId(String projectId) {
        String safeProjectId = projectId == null ? "" : projectId.strip();
        if (safeProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        return safeProjectId;
    }

    private static RepositoryParts repositoryParts(RdProjectCommand command) {
        String owner = command.repoOwner();
        String name = stripGitSuffix(command.repoName());
        if (!owner.isBlank() && !name.isBlank()) {
            return new RepositoryParts(owner, name);
        }
        RepositoryParts parsed = parseRepositoryUrl(command.repositoryUrl());
        return new RepositoryParts(
                owner.isBlank() ? parsed.owner() : owner,
                name.isBlank() ? parsed.name() : name
        );
    }

    private static RepositoryParts parseRepositoryUrl(String repositoryUrl) {
        String normalized = repositoryUrl == null ? "" : repositoryUrl.strip();
        if (normalized.startsWith("git@")) {
            int colon = normalized.indexOf(':');
            if (colon > 0) {
                return partsFromPath(normalized.substring(colon + 1));
            }
        }
        try {
            URI uri = URI.create(normalized);
            return partsFromPath(uri.getPath());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("repositoryUrl must contain owner and repository name");
        }
    }

    private static RepositoryParts partsFromPath(String path) {
        String normalizedPath = path == null ? "" : path.strip();
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        String[] parts = normalizedPath.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must contain owner and repository name");
        }
        return new RepositoryParts(parts[0], stripGitSuffix(parts[1]));
    }

    private static String stripGitSuffix(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private record RepositoryParts(String owner, String name) {
    }
}
