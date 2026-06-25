package com.wish.rd.engine.bugfix.acceptance;

import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 RAG 证据生成验收计划的本地生成器。
 *
 * <p>该实现不调用外部模型，只把已检索到的工单证据收敛成执行器可读的验收步骤。
 */
public final class RagEvidenceAcceptancePlanGenerator implements AcceptancePlanGeneratorPort {

    @Override
    public AcceptancePlanGenerationResult generate(AcceptancePlanGenerationCommand command) {
        BugFixMessage message = command == null ? null : command.ragMessage();
        String taskId = command == null ? "" : command.taskId();
        String ticketId = command == null ? "" : command.ticketId();
        if (message == null || message.retrievedChunks().isEmpty()) {
            return new AcceptancePlanGenerationResult(AcceptancePlan.unsupported(
                    taskId,
                    ticketId,
                    "rd-bot-rag-evidence",
                    "missing RAG evidence for acceptance planning"
            ));
        }
        return new AcceptancePlanGenerationResult(new AcceptancePlan(
                taskId,
                ticketId,
                AcceptancePlanStatus.READY,
                "rd-bot-rag-evidence",
                "",
                steps(message),
                assertions(message),
                evidenceChunkIds(message)
        ));
    }

    private List<AcceptancePlanStep> steps(BugFixMessage message) {
        List<AcceptancePlanStep> steps = new ArrayList<>();
        steps.add(new AcceptancePlanStep(
                "inspect",
                "code",
                "对照 RAG 证据检查相关代码文件：" + evidenceFiles(message),
                "{\"evidenceChunkIds\":\"%s\"}".formatted(String.join(",", evidenceChunkIds(message)))
        ));
        if (isWaimaiOrderIssue(message)) {
            steps.add(new AcceptancePlanStep(
                    "execute",
                    "http",
                    "验证 POST /api/orders 的前后端字段契约，确保 createOrder 请求体与服务端一致。",
                    "{\"method\":\"POST\",\"path\":\"/api/orders\",\"focus\":\"order field contract\"}"
            ));
            steps.add(new AcceptancePlanStep(
                    "execute",
                    "shell",
                    "运行聚焦校验：cd client && npx tsc --noEmit 2>&1 | grep api.ts",
                    "{\"command\":\"cd client && npx tsc --noEmit 2>&1 | grep api.ts\"}"
            ));
        } else {
            steps.add(new AcceptancePlanStep(
                    "execute",
                    "shell",
                    "运行与工单直接相关的最小回归测试，并保留命令输出。",
                    "{\"ticketTitle\":\"%s\"}".formatted(json(message.ticketTitle()))
            ));
        }
        return List.copyOf(steps);
    }

    private List<AcceptanceAssertion> assertions(BugFixMessage message) {
        if (isWaimaiOrderIssue(message)) {
            return List.of(
                    new AcceptanceAssertion(
                            "order-create-fields",
                            "client/src/api.ts createOrder request body",
                            "contains",
                            "address, phone, customer_name, note"
                    ),
                    new AcceptanceAssertion(
                            "legacy-fields-removed",
                            "client/src/api.ts createOrder request body",
                            "not_contains",
                            "delivery_address, remark"
                    ),
                    new AcceptanceAssertion(
                            "focused-typecheck",
                            "client/src/api.ts",
                            "passes",
                            "npx tsc --noEmit focused api.ts check has no new api.ts error"
                    )
            );
        }
        return List.of(new AcceptanceAssertion(
                "focused-regression",
                "repair result",
                "passes",
                "all commands listed in test.log pass or unrelated failures are explicitly scoped"
        ));
    }

    private boolean isWaimaiOrderIssue(BugFixMessage message) {
        String haystack = (message.ticketTitle() + "\n"
                + message.ticketDescription() + "\n"
                + message.contextSummary() + "\n"
                + message.retrievedChunks()).toLowerCase();
        return (haystack.contains("waimai") || haystack.contains("外卖"))
                && (haystack.contains("/api/orders") || haystack.contains("createorder") || haystack.contains("下单"));
    }

    private List<String> evidenceChunkIds(BugFixMessage message) {
        if (!message.evidenceChunkIds().isEmpty()) {
            return message.evidenceChunkIds();
        }
        return message.retrievedChunks().stream()
                .map(RetrievedChunk::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private String evidenceFiles(BugFixMessage message) {
        String files = message.retrievedChunks().stream()
                .map(RetrievedChunk::sourceName)
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("RAG evidence chunks");
        return files.isBlank() ? "RAG evidence chunks" : files;
    }

    private static String json(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
