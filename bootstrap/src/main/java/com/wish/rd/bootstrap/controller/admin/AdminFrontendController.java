package com.wish.rd.bootstrap.controller.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理后台前端路由控制器。
 *
 * <p>将 /admin 与全部前端页面路由（dashboard、knowledge、rd-tasks、projects、model-providers、
 * memories、users、skills、traces、evaluations、observability、settings 等）统一回退到
 * 类路径下的静态 index.html，以支持单页应用（SPA）的前端直连刷新。
 * 仅精确匹配页面导航路径；API、静态 asset 与证据内容路由不在此列，不会被兜底吞掉。</p>
 */
@Controller
public final class AdminFrontendController {

    @GetMapping(value = {
            "/admin",
            "/admin/dashboard",
            "/admin/knowledge",
            "/admin/knowledge/{knowledgeBaseId}",
            "/admin/knowledge/{knowledgeBaseId}/docs/{documentId}",
            "/admin/knowledge/{knowledgeBaseId}/openviking",
            "/admin/rd-tasks",
            "/admin/rd-tasks/{taskId}",
            "/admin/projects",
            "/admin/projects/{projectId}/agent-strategy",
            "/admin/projects/{projectId}/memories",
            "/admin/model-providers",
            "/admin/users",
            "/admin/skills",
            "/admin/traces",
            "/admin/traces/{traceId}",
            "/admin/evaluations",
            "/admin/observability",
            "/admin/settings"
    }, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String admin() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/admin/index.html");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
