package com.wish.rd.bootstrap.controller.rag;

import com.wish.rd.engine.rag.RagV3ChatEngine;
import com.wish.rd.engine.rag.RagV3StopResult;
import com.wish.rd.rag.runtime.RagStreamTask;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 对外暴露的 RAG V3 聊天 REST 控制器。
 *
 * <p>这是项目最核心的对外接口，模仿 ragent 的 {@code /rag/v3/chat} 形态：
 * <ul>
 *   <li>{@code GET /rag/v3/chat}：以 SSE（Server-Sent Events）流式返回
 *       {@code meta}/{@code delta}/{@code done} 等事件，meta 中携带改写后的
 *       问题、命中的意图系统与名称，证明查询改写与意图分类已生效；</li>
 *   <li>{@code POST /rag/v3/stop}：停止某个任务（写取消态）；</li>
 *   <li>{@code GET /rag/v3/tasks/{taskId}}：本地调试通道，查看任务运行状态。</li>
 * </ul>
 *
 * <p>控制器本身只负责 HTTP 适配，全部业务逻辑下沉到 {@link RagV3ChatEngine}。
 */
@RestController
public class RagV3ChatController {

    private final RagV3ChatEngine chatEngine;

    public RagV3ChatController(RagV3ChatEngine chatEngine) {
        this.chatEngine = chatEngine;
    }

    /**
     * 聊天主入口，返回 SSE 文本流。
     *
     * @param question       用户问题（必填）
     * @param conversationId 会话 ID，为空时由引擎自动生成（用于记忆与历史聚合）
     * @param deepThinking   是否开启深度思考（当前 MVP 仅透传，未影响检索逻辑）
     * @return UTF-8 编码的 {@code text/event-stream} 响应体
     */
    @GetMapping(value = "/rag/v3/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> chat(
            @RequestParam("question") String question,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "deepThinking", defaultValue = "false") boolean deepThinking
    ) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .body(chatEngine.chat(question, conversationId, deepThinking).sseBody());
    }

    /**
     * 停止指定任务，保持 ragent 公共停止响应结构。
     *
     * @param taskId 由 /rag/v3/chat 在 meta 事件中返回的任务 ID
     */
    @PostMapping("/rag/v3/stop")
    public RagV3StopResult stop(@RequestParam("taskId") String taskId) {
        return chatEngine.stop(taskId);
    }

    /**
     * 本地测试/调试通道，查看任务的运行态、取消态或完成态。
     *
     * @param taskId 任务 ID
     */
    @GetMapping("/rag/v3/tasks/{taskId}")
    public RagStreamTask task(@PathVariable("taskId") String taskId) {
        return chatEngine.task(taskId);
    }
}
