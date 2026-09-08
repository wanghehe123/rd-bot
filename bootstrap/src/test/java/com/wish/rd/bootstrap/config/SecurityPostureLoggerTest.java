package com.wish.rd.bootstrap.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全摘要合同：输出监听地址与各入口开关，绝不输出任何 token/凭据值。
 */
class SecurityPostureLoggerTest {

    @Test
    void summaryReportsLoopbackAndDisabledExternalIntakeByDefault() {
        SecurityPostureLogger logger = new SecurityPostureLogger(
                "127.0.0.1", false, false, false, false, true, true, "postgres");

        String line = logger.summaryLine();
        assertTrue(line.contains("adminAddress=127.0.0.1"));
        assertTrue(line.contains("feishuIm=off"));
        assertTrue(line.contains("feishuListener=off"));
        assertTrue(line.contains("feishuWriteBack=off"));
        assertTrue(line.contains("ticketWriteBack=off"));
        assertTrue(line.contains("agentRuntime=on"));
        assertTrue(line.contains("dockerExecutor=on"));
        assertTrue(line.contains("knowledgeStore=postgres"));
    }

    @Test
    void summaryNeverContainsSecretValues() {
        String secretToken = "super-secret-operator-token-value";
        SecurityPostureLogger logger = new SecurityPostureLogger(
                "0.0.0.0", true, true, true, true, true, true, "postgres");

        // 即使调用方把凭据值误传入布尔/模式参数之外的位置，摘要行也只能包含开关名。
        assertFalse(logger.summaryLine().contains(secretToken));
        assertTrue(logger.summaryLine().contains("feishuIm=on"));
    }
}
