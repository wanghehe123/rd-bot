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
 * <p>将 /admin、/admin/dashboard、/admin/knowledge、/admin/rd-tasks、/admin/projects、
 * /admin/users、/admin/skills、/admin/traces、/admin/evaluations、/admin/settings 等前端路由统一回退到
 * 类路径下的静态 index.html，以支持单页应用（SPA）的前端直连刷新。
 */
@Controller
public final class AdminFrontendController {

    @GetMapping(value = {
            "/admin",
            "/admin/dashboard",
            "/admin/knowledge",
            "/admin/knowledge/{knowledgeBaseId}",
            "/admin/knowledge/{knowledgeBaseId}/docs/{documentId}",
            "/admin/rd-tasks",
            "/admin/rd-tasks/{taskId}",
            "/admin/projects",
            "/admin/users",
            "/admin/skills",
            "/admin/traces",
            "/admin/traces/{traceId}",
            "/admin/evaluations",
            "/admin/settings"
    }, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String admin() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/admin/index.html");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
