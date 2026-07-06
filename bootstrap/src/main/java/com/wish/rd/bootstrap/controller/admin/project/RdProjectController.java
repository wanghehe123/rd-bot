package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.model.RdProjectCommand;
import com.wish.rd.rag.project.model.RdProjectPage;
import com.wish.rd.rag.project.model.RdProjectQuery;
import com.wish.rd.rag.project.RdProjectService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * RD 项目管理 REST 控制器。
 *
 * <p>对外暴露 {@code /admin/projects} 系列接口，供管理台配置可交付项目及其 Git 仓库信息。
 * 控制器只做 HTTP 参数适配，业务校验和仓库解析交给 {@link RdProjectService}。
 */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectController {

    private final RdProjectService projectService;

    public RdProjectController(RdProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 分页查询项目。
     *
     * @param keyword 关键词
     * @param enabled 启用状态过滤
     * @param page    页码
     * @param pageSize 每页大小
     * @return 项目分页视图
     */
    @GetMapping(value = "/admin/projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectPageView list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        RdProjectPage result = projectService.query(new RdProjectQuery(keyword, enabled, page, pageSize));
        return new RdProjectPageView(
                result.records().stream().map(RdProjectController::toView).toList(),
                result.total(),
                result.page(),
                result.pageSize(),
                result.pages()
        );
    }

    /**
     * 查询项目详情。
     *
     * @param projectId 项目 ID
     * @return 项目视图
     */
    @GetMapping(value = "/admin/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectView get(@PathVariable("projectId") String projectId) {
        return toView(projectService.get(projectId));
    }

    /**
     * 创建项目。
     *
     * @param request 创建请求
     * @return 项目视图
     */
    @PostMapping(value = "/admin/projects", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectView create(@RequestBody RdProjectRequest request) {
        return toView(projectService.create(toCommand(request)));
    }

    /**
     * 更新项目。
     *
     * @param projectId 项目 ID
     * @param request   更新请求
     * @return 项目视图
     */
    @PutMapping(value = "/admin/projects/{projectId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectView update(
            @PathVariable("projectId") String projectId,
            @RequestBody RdProjectRequest request
    ) {
        return toView(projectService.update(projectId, toCommand(request)));
    }

    /**
     * 逻辑删除项目。
     *
     * @param projectId 项目 ID
     * @return 删除结果
     */
    @DeleteMapping(value = "/admin/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DeleteProjectResponse delete(@PathVariable("projectId") String projectId) {
        projectService.delete(projectId);
        return new DeleteProjectResponse(true);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    private static RdProjectCommand toCommand(RdProjectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return new RdProjectCommand(
                request.projectKey(),
                request.name(),
                request.description(),
                request.repositoryUrl(),
                request.repoOwner(),
                request.repoName(),
                request.defaultBranch(),
                request.enabled()
        );
    }

    private static RdProjectView toView(RdProject project) {
        return new RdProjectView(
                project.projectId(),
                project.projectKey(),
                project.name(),
                project.description(),
                project.repositoryUrl(),
                project.repoOwner(),
                project.repoName(),
                project.defaultBranch(),
                project.enabled(),
                project.createTimeEpochMillis(),
                project.updateTimeEpochMillis()
        );
    }

    /** 项目创建 / 更新请求体。 */
    public record RdProjectRequest(
            String projectKey,
            String name,
            String description,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String defaultBranch,
            boolean enabled
    ) {
    }

    /** 项目视图。 */
    public record RdProjectView(
            String projectId,
            String projectKey,
            String name,
            String description,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String defaultBranch,
            boolean enabled,
            long createTimeEpochMillis,
            long updateTimeEpochMillis
    ) {
    }

    /** 项目分页视图。 */
    public record RdProjectPageView(
            List<RdProjectView> records,
            long total,
            int page,
            int pageSize,
            int pages
    ) {
    }

    /** 删除项目响应体。 */
    public record DeleteProjectResponse(boolean deleted) {
    }
}
