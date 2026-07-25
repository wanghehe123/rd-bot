package com.wish.rd.bootstrap.executor.impl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-owned token limit for Markdown handoffs passed between requirement roles. */
@Component
@ConfigurationProperties(prefix = "rd.requirement-delivery.handoff")
public class RoleHandoffProperties {

    private int maxTokens = 4_000;

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens < 64 || maxTokens > 32_768) {
            throw new IllegalArgumentException("handoff maxTokens must be between 64 and 32768");
        }
        this.maxTokens = maxTokens;
    }

    long maxMarkdownBytes() {
        return Math.multiplyExact((long) maxTokens, 4L);
    }
}
