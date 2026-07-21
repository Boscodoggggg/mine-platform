package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.model.strategy.StrategyScopeType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在数据保留策略激活事务中校验并消费精确影响预览凭证。
 */
public interface RetentionActivationGuard {

    /**
     * 校验凭证绑定的版本、作用域、管理员和当前逐设备解析状态。
     *
     * @param command 激活候选
     */
    void verify(ActivationCommand command);

    /**
     * 把已经校验的凭证绑定到同一事务中新建的策略发布。
     *
     * @param previewToken 预览凭证
     * @param releaseId    策略发布标识
     */
    void markActivated(UUID previewToken, UUID releaseId);

    /**
     * 数据保留激活候选。
     *
     * @param previewToken      预览凭证
     * @param strategyVersionId 策略版本标识
     * @param productId         产品标识
     * @param scopeType         作用域类型
     * @param scopeId           作用域标识
     * @param groupPriority     设备组当前优先级
     * @param values            已规范化候选策略字段
     * @param affectedDeviceIds 锁内重新计算的目标设备标识
     * @param operatorId        当前管理员标识
     */
    record ActivationCommand(
            UUID previewToken,
            UUID strategyVersionId,
            UUID productId,
            StrategyScopeType scopeType,
            UUID scopeId,
            Integer groupPriority,
            Map<String, Object> values,
            List<UUID> affectedDeviceIds,
            UUID operatorId
    ) {
        /**
         * 冻结候选字段和设备集合，避免校验期间被修改。
         */
        public ActivationCommand {
            values = values == null ? Map.of() : Map.copyOf(values);
            affectedDeviceIds = affectedDeviceIds == null ? List.of() : List.copyOf(affectedDeviceIds);
        }
    }
}
