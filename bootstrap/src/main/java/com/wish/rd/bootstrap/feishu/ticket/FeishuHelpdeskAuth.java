package com.wish.rd.bootstrap.feishu.ticket;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞书 Helpdesk 鉴权辅助：构造 {@code X-Lark-Helpdesk-Authorization} 头。
 *
 * <p>Helpdesk 接口除普通 {@code Authorization: Bearer <tenant_access_token>} 外，
 * 还需要 {@code X-Lark-Helpdesk-Authorization: base64(helpdeskId:helpdeskToken)}。
 *
 * <p>关键安全约束：{@link #toString()} 永不泄漏 helpdesk token；
 * 仅暴露 helpdeskId 用于排障。
 */
public final class FeishuHelpdeskAuth {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private final String helpdeskId;
    private final String helpdeskToken;

    public FeishuHelpdeskAuth(String helpdeskId, String helpdeskToken) {
        this.helpdeskId = helpdeskId == null ? "" : helpdeskId;
        this.helpdeskToken = helpdeskToken == null ? "" : helpdeskToken;
    }

    /**
     * 从配置构造鉴权辅助。
     *
     * @param properties 飞书 Helpdesk 配置
     * @return 鉴权辅助
     */
    public static FeishuHelpdeskAuth from(FeishuHelpdeskProperties properties) {
        return new FeishuHelpdeskAuth(properties.getHelpdeskId(), properties.getHelpdeskToken());
    }

    /**
     * 生成 {@code X-Lark-Helpdesk-Authorization} 头值：{@code base64(helpdeskId:helpdeskToken)}。
     *
     * @return base64 编码后的鉴权头值
     */
    public String helpdeskAuthorizationHeader() {
        String raw = helpdeskId + ":" + helpdeskToken;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return helpdeskId（非敏感）
     */
    public String helpdeskId() {
        return helpdeskId;
    }

    /**
     * 是否已配置有效的 helpdesk 凭据。
     *
     * @return 已配置返回 true
     */
    public boolean isConfigured() {
        return !helpdeskId.isBlank() && !helpdeskToken.isBlank();
    }

    @Override
    public String toString() {
        // 永不泄漏 helpdesk token：仅暴露 helpdeskId 与配置状态
        return "FeishuHelpdeskAuth{helpdeskId='" + helpdeskId + "', configured=" + isConfigured() + "}";
    }
}
