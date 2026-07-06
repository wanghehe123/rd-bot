package com.wish.rd.exec.repair.alert;

import com.wish.rd.exec.repair.alert.model.RepairAlert;


/**
 * 修复执行告警输出端口，供 bootstrap 层适配数据库、飞书、日志或其他通知系统。
 */
@FunctionalInterface
public interface RepairAlertSinkPort {

    /**
     * 发布一条修复执行告警。
     *
     * @param alert 待发布的告警
     */
    void publish(RepairAlert alert);
}
