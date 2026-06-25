package com.wish.rd.bootstrap.controller.admin.repair;

import com.wish.rd.exec.repair.RepairAsset;
import com.wish.rd.exec.repair.RepairRecord;
import com.wish.rd.exec.repair.RepairRecordArtifact;
import com.wish.rd.exec.repair.RepairRecordPage;
import com.wish.rd.exec.repair.RepairRecordQuery;
import com.wish.rd.exec.repair.RepairRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 修复记录查询 REST 控制器（生产读接口）。
 *
 * <p>对外暴露 {@code /repair-records} 系列接口，提供分页查询、单条详情与产物列表。
 * 仅返回脱敏后的视图：{@link RepairRecordView} 不透出原始 secret，{@code extensionJson}
 * 中的 helpdesk token / access token 字段在视图层剔除。
 */
@RestController
public class RepairRecordController {

    /** extension_json 中需要剔除的敏感 key。 */
    private static final List<String> REDACTED_EXTENSION_KEYS = List.of(
            "helpdeskToken", "accessToken", "access_token", "authorization"
    );

    private final RepairRecordRepository repository;

    public RepairRecordController(RepairRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 分页查询修复记录。
     *
     * @param ticketId   工单 ID 过滤
     * @param status     状态过滤
     * @param priority   优先级过滤
     * @param createdFrom 创建时间起始（epoch millis）
     * @param createdTo   创建时间截止（epoch millis）
     * @param page       页码
     * @param pageSize   页大小
     * @return 分页结果
     */
    @GetMapping("/repair-records")
    public RepairRecordPageView list(
            @RequestParam(value = "ticketId", required = false) String ticketId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "createdFrom", required = false) Long createdFrom,
            @RequestParam(value = "createdTo", required = false) Long createdTo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        RepairRecordQuery query = new RepairRecordQuery(
                ticketId,
                status,
                priority,
                createdFrom == null ? 0L : createdFrom,
                createdTo == null ? 0L : createdTo,
                page,
                pageSize
        );
        RepairRecordPage result = repository.query(query);
        List<RepairRecordView> views = result.records().stream()
                .map(RepairRecordController::toView)
                .toList();
        return new RepairRecordPageView(views, result.page(), result.pageSize(), result.total());
    }

    /**
     * 查询单条修复记录。
     *
     * @param id 修复记录 ID
     * @return 记录视图
     */
    @GetMapping("/repair-records/{id}")
    public RepairRecordView get(@PathVariable("id") String id) {
        return repository.findById(id)
                .map(RepairRecordController::toView)
                .orElseThrow(() -> new NoSuchElementException("repair record not found: " + id));
    }

    /**
     * 查询修复记录的产物列表。
     *
     * @param id 修复记录 ID
     * @return 产物视图列表
     */
    @GetMapping("/repair-records/{id}/artifacts")
    public List<RepairRecordArtifactView> artifacts(@PathVariable("id") String id) {
        return repository.listArtifacts(id).stream()
                .map(RepairRecordController::toArtifactView)
                .toList();
    }

    /**
     * 查询修复记录沉淀出的可复用资产列表。
     *
     * @param id 修复记录 ID
     * @return 资产视图列表
     */
    @GetMapping("/repair-records/{id}/assets")
    public List<RepairAssetView> assets(@PathVariable("id") String id) {
        return repository.listAssets(id).stream()
                .map(RepairRecordController::toAssetView)
                .toList();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    private static RepairRecordView toView(RepairRecord record) {
        return new RepairRecordView(
                record.id(),
                record.ticketId(),
                record.ticketUrl(),
                record.title(),
                record.status().name(),
                record.ragSummary(),
                redactExtension(record.extensionJson()),
                record.createdAtEpochMillis(),
                record.updatedAtEpochMillis()
        );
    }

    private static RepairRecordArtifactView toArtifactView(RepairRecordArtifact artifact) {
        return new RepairRecordArtifactView(
                artifact.id(),
                artifact.repairRecordId(),
                artifact.artifactType(),
                artifact.artifactUri(),
                artifact.summary(),
                artifact.createdAtEpochMillis()
        );
    }

    private static RepairAssetView toAssetView(RepairAsset asset) {
        return new RepairAssetView(
                asset.id(),
                asset.repairRecordId(),
                asset.assetType().name(),
                asset.title(),
                asset.summary(),
                asset.contentJson(),
                asset.sourceArtifactId(),
                asset.reusable(),
                asset.createdAtEpochMillis()
        );
    }

    private static Map<String, String> redactExtension(Map<String, String> extension) {
        // 剔除敏感 key，避免把 helpdesk token / access token 透出给前端
        Map<String, String> safe = new java.util.LinkedHashMap<>(extension);
        REDACTED_EXTENSION_KEYS.forEach(safe::remove);
        return Map.copyOf(safe);
    }

    /** 修复记录视图，已脱敏。 */
    public record RepairRecordView(
            String id,
            String ticketId,
            String ticketUrl,
            String title,
            String status,
            String ragSummary,
            Map<String, String> extensionJson,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
    }

    /** 修复记录分页视图。 */
    public record RepairRecordPageView(
            List<RepairRecordView> records,
            int page,
            int pageSize,
            long total
    ) {
    }

    /** 修复产物视图。 */
    public record RepairRecordArtifactView(
            String id,
            String repairRecordId,
            String artifactType,
            String artifactUri,
            String summary,
            long createdAtEpochMillis
    ) {
    }

    /** 修复资产视图。 */
    public record RepairAssetView(
            String id,
            String repairRecordId,
            String assetType,
            String title,
            String summary,
            String contentJson,
            String sourceArtifactId,
            boolean reusable,
            long createdAtEpochMillis
    ) {
    }
}
