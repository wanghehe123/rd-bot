package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionAcceptanceFastGateTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteFreshLedgerBeforeRunningFinalGate() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("production-acceptance-evidence-ledger-20260704-100000.json"),
                """
                        {
                          "conclusion": "FAILED_PRODUCTION_ACCEPTANCE_LEDGER",
                          "totalAcceptancePointCount": 15,
                          "passedCount": 3,
                          "failedCount": 2,
                          "notRunCount": 10,
                          "rows": [
                            {"number": 1, "status": "FAILED"}
                          ],
                          "nextActions": []
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("multi-agent-production-acceptance-20260704-100001.json"),
                passedFullProductionMatrix()
        );
        ProductionAcceptanceFastGate gate = new ProductionAcceptanceFastGate(fixedClock());

        Path accepted = gate.writeFreshLedgerAndAssertPassed(evidenceRoot);

        assertEquals(
                evidenceRoot.resolve("production-acceptance-evidence-ledger-20260704-102000.json"),
                accepted
        );
    }

    private static String passedFullProductionMatrix() {
        StringBuilder matrix = new StringBuilder();
        for (int point = 1; point <= 15; point++) {
            if (point > 1) {
                matrix.append(",\n");
            }
            matrix.append("""
                            {
                              "number": %d,
                              "title": "%s",
                              "status": "PASSED",
                              "evidence": "taskId=task-prod-1, PR=https://github.com/acme/rd-bot/pull/99"
                            }""".formatted(point, title(point)));
        }
        return """
                {
                  "conclusion": "PASSED_FULL_PRODUCTION_ACCEPTANCE",
                  "multiAgentProductionEvidenceValidated": true,
                  "taskId": "task-prod-1",
                  "matrix": [
                %s
                  ]
                }
                """.formatted(matrix);
    }

    private static String title(int point) {
        return switch (point) {
            case 1 -> "需求任务可进入多 Agent 工作流";
            case 2 -> "角色上下文包真实落库且内容不同";
            case 3 -> "需求评审 Agent 能阻断不可交付需求";
            case 4 -> "方案 Agent 产出可执行开发方案";
            case 5 -> "多 provider 降级重试真实生效";
            case 6 -> "编码 Agent 在 Docker 中真实改代码并运行测试";
            case 7 -> "QA Agent 逐条验收并阻断失败交付";
            case 8 -> "交付复核通过后才提交为已交付";
            case 9 -> "状态机可恢复且不会重复派发";
            case 10 -> "错误通知真实送达 Feishu";
            case 11 -> "Skill 安装和使用受策略控制";
            case 12 -> "经验自动沉淀且可被后续 RAG 检索";
            case 13 -> "密钥和敏感信息不进入产物";
            case 14 -> "指标和审计可观测";
            case 15 -> "生产真实测试结论要求";
            default -> throw new IllegalArgumentException("unknown point " + point);
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-04T10:20:00Z"), ZoneOffset.UTC);
    }
}
