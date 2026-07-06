package com.wish.rd.bootstrap.feishu.ticket;

import com.wish.rd.bootstrap.feishu.ticket.impl.FeishuTicketAdapter;
import com.wish.rd.bootstrap.feishu.ticket.impl.MockFeishuTicketAdapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书 Helpdesk 配置。
 *
 * <p>键前缀 {@code rd.feishu.helpdesk.*}。所有密钥（app secret / helpdesk token）
 * 仅在启动期注入内存，运行期不会写入日志、异常消息或产物。
 *
 * <p>默认 {@code enabled=false}，使用 {@link MockFeishuTicketAdapter} 走 Mock 流程；
 * 显式 {@code enabled=true} 才装配真实 {@link FeishuTicketAdapter}。
 */
@ConfigurationProperties(prefix = "rd.feishu.helpdesk")
public class FeishuHelpdeskProperties {

    private boolean enabled = false;
    private String appId = "";
    private String appSecret = "";
    private String baseUrl = "https://open.feishu.cn";
    private String helpdeskId = "";
    private String helpdeskToken = "";
    /** 回复/更新工单时使用的客服身份 ID（飞书要求带上 staff_id）。 */
    private String staffId = "";
    /** 回写开关，独立于 enabled：enabled=true 只读访问；回写需额外开启 writeBack.enabled。 */
    private WriteBack writeBack = new WriteBack();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHelpdeskId() {
        return helpdeskId;
    }

    public void setHelpdeskId(String helpdeskId) {
        this.helpdeskId = helpdeskId;
    }

    public String getHelpdeskToken() {
        return helpdeskToken;
    }

    public void setHelpdeskToken(String helpdeskToken) {
        this.helpdeskToken = helpdeskToken;
    }

    public String getStaffId() {
        return staffId;
    }

    public void setStaffId(String staffId) {
        this.staffId = staffId;
    }

    public WriteBack getWriteBack() {
        return writeBack;
    }

    public void setWriteBack(WriteBack writeBack) {
        this.writeBack = writeBack == null ? new WriteBack() : writeBack;
    }

    /** 回写策略。 */
    public static class WriteBack {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
