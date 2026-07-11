package com.wish.rd.bootstrap.controller.admin.dashboard;

import com.wish.rd.engine.admin.dashboard.RdDashboardQueryService;
import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 项目交付驾驶舱 HTTP 适配器。
 *
 * <p>暴露 {@code GET /admin/dashboard/overview}；状态口径、项目校验和数据聚合完全由
 * {@link RdDashboardQueryService} 负责。
 */
@RestController
public final class RdDashboardController {

    private final RdDashboardQueryService dashboardQueryService;

    /**
     * 创建 Dashboard 控制器。
     *
     * @param dashboardQueryService 项目交付聚合服务
     */
    public RdDashboardController(RdDashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = dashboardQueryService;
    }

    /**
     * 返回项目或全部项目的交付驾驶舱数据。
     *
     * @param projectId 项目 ID；为空时表示全部项目
     * @param limit 当前执行和近期交付各自的最大行数
     * @return 项目交付聚合
     */
    @GetMapping("/admin/dashboard/overview")
    public RdDashboardOverview overview(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "limit", defaultValue = "10") int limit
    ) {
        return dashboardQueryService.overview(projectId, limit);
    }

    /** Maps missing project references to a stable management API status. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    /** Maps invalid view parameters to a stable management API status. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
