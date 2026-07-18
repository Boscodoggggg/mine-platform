package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 换绑影响预览或已执行结果。
 *
 * @param operationId 已执行换绑操作标识，预览时为空
 * @param executed 是否已经执行
 * @param executable 当前校验结果是否允许执行
 * @param bindingType 拟建立的绑定类型
 * @param previousBinding 生效时刻的旧关系，无旧关系时为空
 * @param proposedBinding 拟建立或已建立的新关系
 * @param qualification 司机资质判断
 * @param affectedObjects 受换绑影响的资源和新旧目标
 * @param blockers 固定低基数阻断原因码
 * @param outboxEventId 已执行时的业务事件标识
 */
public record BindingPreviewResponse(
        UUID operationId,
        boolean executed,
        boolean executable,
        String bindingType,
        BindingRelation previousBinding,
        BindingRelation proposedBinding,
        QualificationAssessment qualification,
        List<AffectedObject> affectedObjects,
        List<String> blockers,
        UUID outboxEventId
) {

    /**
     * 一条带半开有效区间的关系投影。
     *
     * @param bindingId 关系标识，预览拟建关系时为空
     * @param bindingType 关系类型
     * @param resourceId 被移动资源标识
     * @param targetId 关系目标标识
     * @param role 关系角色
     * @param validFrom 生效时刻
     * @param validTo 失效时刻，为空表示无上界
     */
    public record BindingRelation(
            UUID bindingId,
            String bindingType,
            UUID resourceId,
            UUID targetId,
            String role,
            Instant validFrom,
            Instant validTo
    ) {
    }

    /**
     * 司机换车的车型和日期资质判断。
     *
     * @param required 是否需要司机资质
     * @param qualified 是否命中有效资质
     * @param qualificationId 命中的资质标识
     */
    public record QualificationAssessment(
            boolean required,
            boolean qualified,
            UUID qualificationId
    ) {
    }

    /**
     * 换绑影响对象。
     *
     * @param objectType 对象类型
     * @param objectId 对象标识
     * @param impact 资源、旧目标或新目标
     */
    public record AffectedObject(String objectType, UUID objectId, String impact) {
    }
}
