package com.wish.rd.bootstrap.controller.admin.skill;

import com.wish.rd.bootstrap.skill.SkillHubAdminService;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Skill Hub 管理 REST 入口。
 *
 * <p>路径前缀 {@code /admin/skills}；业务编排下沉到 {@link SkillHubAdminService}。
 */
@RestController
@RequestMapping(path = "/admin/skills", produces = MediaType.APPLICATION_JSON_VALUE)
public final class SkillHubAdminController {

    private final SkillHubAdminService adminService;

    /**
     * @param adminService Skill Hub 管理编排
     */
    public SkillHubAdminController(SkillHubAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 列出 Skill Hub 目录。
     *
     * @return 目录条目列表
     */
    @GetMapping
    public List<SkillCatalogEntry> listCatalog() {
        return adminService.listCatalog();
    }

    /**
     * 列出全部角色绑定。
     *
     * @return role → bindings
     */
    @GetMapping("/role-bindings")
    public Map<String, List<SkillRoleBinding>> listRoleBindings() {
        return adminService.listAllBindings();
    }

    /**
     * 替换指定角色绑定。
     *
     * @param role     Agent 角色
     * @param bindings 绑定请求体
     * @return 持久化后的绑定
     */
    @PutMapping(path = "/role-bindings/{role}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<SkillRoleBinding> replaceRoleBindings(
            @PathVariable("role") String role,
            @RequestBody List<RoleBindingRequest> bindings
    ) {
        List<SkillRoleBinding> next = (bindings == null ? List.<RoleBindingRequest>of() : bindings).stream()
                .map(item -> new SkillRoleBinding(role, item.skillId(), item.sortOrder(), item.forceGuide()))
                .toList();
        return adminService.replaceBindings(role, next);
    }

    /**
     * 上传 zip 或 SKILL.md。
     *
     * @param file         上传文件
     * @param version      版本
     * @param riskLevel    风险等级
     * @param allowedRoles 允许角色
     * @param guidePrompt  引导提示词
     * @param forceGuide   是否强制引导
     * @return 目录条目
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillCatalogEntry upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "allowedRoles", required = false) String allowedRoles,
            @RequestParam(value = "guidePrompt", required = false) String guidePrompt,
            @RequestParam(value = "forceGuide", required = false, defaultValue = "false") boolean forceGuide
    ) {
        return adminService.upload(file, version, riskLevel, allowedRoles, guidePrompt, forceGuide);
    }

    /**
     * 查询单个 Skill。
     *
     * @param skillId Skill ID
     * @return 目录条目
     */
    @GetMapping("/{skillId}")
    public SkillCatalogEntry getSkill(@PathVariable("skillId") String skillId) {
        return adminService.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found"));
    }

    /**
     * 更新目录元数据。
     *
     * @param skillId Skill ID
     * @param request 更新请求
     * @return 更新后的条目
     */
    @PutMapping(path = "/{skillId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SkillCatalogEntry update(
            @PathVariable("skillId") String skillId,
            @RequestBody SkillUpdateRequest request
    ) {
        SkillUpdateRequest safe = request == null ? new SkillUpdateRequest(null, null, null, null, null) : request;
        return adminService.update(
                skillId,
                safe.guidePrompt(),
                safe.forceGuide(),
                safe.allowedRoles(),
                safe.status(),
                safe.description()
        );
    }

    /**
     * 审批 WAITING_APPROVAL Skill 为 ACTIVE。
     *
     * @param skillId Skill ID
     * @return 更新后的条目
     */
    @PostMapping("/{skillId}/approve")
    public SkillCatalogEntry approve(@PathVariable("skillId") String skillId) {
        return adminService.approve(skillId);
    }

    /**
     * @param exception 非法参数
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage() == null ? "" : exception.getMessage()));
    }

    /**
     * @param exception 状态冲突
     * @return 409 响应
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", exception.getMessage() == null ? "" : exception.getMessage()));
    }

    /**
     * Skill 元数据更新请求。
     *
     * @param guidePrompt  引导提示词
     * @param forceGuide   强制引导
     * @param allowedRoles 允许角色
     * @param status       状态
     * @param description  说明
     */
    public record SkillUpdateRequest(
            String guidePrompt,
            Boolean forceGuide,
            List<String> allowedRoles,
            SkillCatalogStatus status,
            String description
    ) {
    }

    /**
     * 角色绑定请求项。
     *
     * @param skillId    Skill ID
     * @param sortOrder  排序
     * @param forceGuide 角色级强制引导
     */
    public record RoleBindingRequest(
            String skillId,
            int sortOrder,
            boolean forceGuide
    ) {
    }
}
