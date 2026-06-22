package com.wish.rd.bootstrap.feishu.ticket;

import com.wish.rd.engine.ticket.TicketFieldMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书工单 P1 相关 Bean 装配。
 *
 * <p>仅装配无法直接注解化的第三方/配置驱动 Bean；业务编排（{@code *Engine}）、
 * 控制器（{@code *Controller}）、适配器（{@code *Adapter}）均由组件扫描进入 IOC。
 *
 * <p>{@link TicketFieldMapping} 依赖飞书自定义字段名映射，由配置驱动，因此在此装配。
 */
@Configuration
public class FeishuTicketBeansConfiguration {

    /**
     * 注册飞书 Helpdesk 配置属性 bean。
     *
     * @return 配置属性
     */
    @Bean
    @ConfigurationProperties(prefix = "rd.feishu.helpdesk")
    public FeishuHelpdeskProperties feishuHelpdeskProperties() {
        return new FeishuHelpdeskProperties();
    }

    /**
     * 装配工单字段映射器，字段名从 {@code rd.feishu.helpdesk.field-mapping.*} 读取。
     *
     * @param problemSystem 自定义字段名：故障系统
     * @param symptom       自定义字段名：现象
     * @param triggerWay    自定义字段名：触发方式
     * @param logs          自定义字段名：日志
     * @param repository    自定义字段名：代码仓库
     * @param branch        自定义字段名：分支
     * @param expectedResult 自定义字段名：期望结果
     * @param actualResult   自定义字段名：实际结果
     * @return 字段映射器
     */
    @Bean
    public TicketFieldMapping ticketFieldMapping(
            @Value("${rd.feishu.helpdesk.field-mapping.problemSystem:problemSystem}") String problemSystem,
            @Value("${rd.feishu.helpdesk.field-mapping.symptom:symptom}") String symptom,
            @Value("${rd.feishu.helpdesk.field-mapping.triggerWay:triggerWay}") String triggerWay,
            @Value("${rd.feishu.helpdesk.field-mapping.logs:logs}") String logs,
            @Value("${rd.feishu.helpdesk.field-mapping.repository:repository}") String repository,
            @Value("${rd.feishu.helpdesk.field-mapping.branch:branch}") String branch,
            @Value("${rd.feishu.helpdesk.field-mapping.expectedResult:expectedResult}") String expectedResult,
            @Value("${rd.feishu.helpdesk.field-mapping.actualResult:actualResult}") String actualResult
    ) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(TicketFieldMapping.KEY_PROBLEM_SYSTEM, problemSystem);
        mapping.put(TicketFieldMapping.KEY_SYMPTOM, symptom);
        mapping.put(TicketFieldMapping.KEY_TRIGGER_WAY, triggerWay);
        mapping.put(TicketFieldMapping.KEY_LOGS, logs);
        mapping.put(TicketFieldMapping.KEY_REPOSITORY, repository);
        mapping.put(TicketFieldMapping.KEY_BRANCH, branch);
        mapping.put(TicketFieldMapping.KEY_EXPECTED_RESULT, expectedResult);
        mapping.put(TicketFieldMapping.KEY_ACTUAL_RESULT, actualResult);
        return new TicketFieldMapping(mapping);
    }

    /**
     * 装配飞书 Helpdesk DTO 映射器。自定义字段 ID->名 映射暂用空（按 ID 直出）。
     *
     * @return 映射器
     */
    @Bean
    public FeishuTicketMapper feishuTicketMapper() {
        return FeishuTicketMapper.defaults();
    }
}
