package com.wish.rd.bootstrap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 在应用启动完成时输出一次安全范围摘要（openspec/changes/prepare-personal-open-source-release）。
 * 让操作者在日志里直接确认：监听地址、外部入口开关、executor 与存储模式。
 * 摘要只输出开关布尔值与模式名，绝不输出任何 token / PAT / provider key 的值。
 *
 * <p>依赖：application.yaml 的 server.address、rd.feishu.im.*、rd.ticket.write-back.enabled、
 * rd.executor.*、rd.knowledge.store；由 Spring 组件扫描注册。</p>
 */
@Component
public class SecurityPostureLogger {

    private static final Logger log = LoggerFactory.getLogger(SecurityPostureLogger.class);

    private final String summaryLine;

    public SecurityPostureLogger(
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${rd.feishu.im.enabled:false}") boolean feishuImEnabled,
            @Value("${rd.feishu.im.local-listener.enabled:false}") boolean feishuListenerEnabled,
            @Value("${rd.feishu.im.write-back.enabled:false}") boolean feishuWriteBackEnabled,
            @Value("${rd.ticket.write-back.enabled:false}") boolean ticketWriteBackEnabled,
            @Value("${rd.executor.agent-runtime.enabled:true}") boolean agentRuntimeEnabled,
            @Value("${rd.executor.docker.enabled:true}") boolean dockerExecutorEnabled,
            @Value("${rd.knowledge.store:postgres}") String knowledgeStore
    ) {
        this.summaryLine = "security posture: adminAddress=" + serverAddress
                + ", feishuIm=" + onOff(feishuImEnabled)
                + ", feishuListener=" + onOff(feishuListenerEnabled)
                + ", feishuWriteBack=" + onOff(feishuWriteBackEnabled)
                + ", ticketWriteBack=" + onOff(ticketWriteBackEnabled)
                + ", agentRuntime=" + onOff(agentRuntimeEnabled)
                + ", dockerExecutor=" + onOff(dockerExecutorEnabled)
                + ", knowledgeStore=" + knowledgeStore;
    }

    /** 摘要行只包含布尔开关与模式名，供测试钉住「无凭据值」约束。 */
    public String summaryLine() {
        return summaryLine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnceOnStartup() {
        log.info("{}", summaryLine);
    }

    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }
}
