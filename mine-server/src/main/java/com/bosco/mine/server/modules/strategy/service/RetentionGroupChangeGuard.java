package com.bosco.mine.server.modules.strategy.service;

import java.util.List;
import java.util.UUID;

/**
 * 在设备组变化间接激活数据保留策略前校验并消费精确影响预览凭证。
 */
public interface RetentionGroupChangeGuard {

    /**
     * 校验设备组拟变更与预览时的成员、优先级和最终保留策略仍完全一致。
     *
     * @param command 锁内重新计算的设备组变化
     * @return 当前变化是否需要在事务内消费预览凭证
     */
    boolean verify(ActivationCommand command);

    /**
     * 把已经校验的凭证绑定到同一事务中的设备组变化。
     *
     * @param previewToken 预览凭证
     * @param groupId      设备组标识
     */
    void markGroupChangeActivated(UUID previewToken, UUID groupId);

    /**
     * 会改变设备组保留策略适用关系的操作类型。
     */
    enum ChangeType {
        /** 替换设备组成员。 */
        MEMBERSHIP,
        /** 修改设备组优先级。 */
        PRIORITY,
        /** 归档设备组。 */
        GROUP_ARCHIVE
    }

    /**
     * 设备组保留策略间接激活候选。
     *
     * @param previewToken    预览凭证
     * @param changeType      变化类型
     * @param groupId         设备组标识
     * @param productId       产品标识
     * @param groupVersion    设备组当前乐观锁版本
     * @param previousPriority 变化前优先级
     * @param nextPriority    变化后优先级，归档时沿用变化前值
     * @param addedDeviceIds  新增成员设备标识
     * @param removedDeviceIds 移除成员设备标识
     * @param affectedDeviceIds 最终策略可能变化的设备标识
     * @param operatorId      当前管理员标识
     */
    record ActivationCommand(
            UUID previewToken,
            ChangeType changeType,
            UUID groupId,
            UUID productId,
            long groupVersion,
            int previousPriority,
            int nextPriority,
            List<UUID> addedDeviceIds,
            List<UUID> removedDeviceIds,
            List<UUID> affectedDeviceIds,
            UUID operatorId
    ) {
        /**
         * 冻结设备集合，避免校验期间被修改。
         */
        public ActivationCommand {
            addedDeviceIds = addedDeviceIds == null ? List.of() : List.copyOf(addedDeviceIds);
            removedDeviceIds = removedDeviceIds == null ? List.of() : List.copyOf(removedDeviceIds);
            affectedDeviceIds = affectedDeviceIds == null ? List.of() : List.copyOf(affectedDeviceIds);
        }
    }
}
