package com.bosco.mine.server.modules.notification.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyActionRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyPublishRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyResponse;
import com.bosco.mine.server.modules.notification.model.NotificationEventType;
import com.bosco.mine.server.modules.notification.model.NotificationPolicy;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyDependencyStatus;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyOverlapCandidate;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyStep;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyVersion;
import com.bosco.mine.server.modules.notification.repository.NotificationPolicyMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通知升级策略草稿、发布、冲突和不可变快照规则。
 */
class NotificationPolicyServiceTest {

    private NotificationPolicyMapper mapper;
    private OperationAuditService auditService;
    private NotificationPolicyService service;

    @BeforeEach
    void 创建升级策略测试环境() {
        mapper = mock(NotificationPolicyMapper.class);
        auditService = mock(OperationAuditService.class);
        service = new NotificationPolicyService(mapper, auditService, new ObjectMapper());
    }

    @Test
    void 创建策略必须同时创建第一版草稿和稳定排序步骤() {
        when(mapper.findByCode("critical-escalation")).thenReturn(Optional.empty());
        when(mapper.insertPolicy(any(), any())).thenReturn(1);
        when(mapper.insertVersion(any(), any())).thenReturn(1);
        when(mapper.insertSteps(any())).thenReturn(2);

        NotificationPolicyResponse response = service.create(request(true, 0));

        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().versionNumber()).isOne();
        assertThat(response.draft().state()).isEqualTo("DRAFT");
        assertThat(response.draft().steps())
                .extracting(NotificationPolicyResponse.Step::stepOrder)
                .containsExactly(0, 1);
        assertThat(response.draft().steps().getFirst().recoveryTemplateId()).isNotNull();
        ArgumentCaptor<List<NotificationPolicyStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertSteps(steps.capture());
        assertThat(steps.getValue()).extracting(NotificationPolicyStep::stepOrder)
                .containsExactly(0, 1);
    }

    @Test
    void 草稿步骤必须连续且延迟单调不减() {
        NotificationPolicyRequest invalidOrder = requestWithSteps(List.of(
                step(0, 0, true), step(2, 60, true)));
        NotificationPolicyRequest invalidDelay = requestWithSteps(List.of(
                step(0, 60, true), step(1, 30, true)));

        assertReason(() -> service.create(invalidOrder), "STEP_ORDER_INVALID");
        assertReason(() -> service.create(invalidDelay), "STEP_DELAY_NOT_MONOTONIC");
    }

    @Test
    void 发布必须校验全部依赖并把草稿原子转换为不可变快照() {
        UUID policyId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        NotificationPolicy policy = policy(policyId, 3);
        NotificationPolicyVersion draft = draft(draftId, policyId, true);
        List<NotificationPolicyStep> steps = storedSteps(draftId, true);
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy));
        when(mapper.findVersionById(policyId, draftId)).thenReturn(Optional.of(draft));
        when(mapper.findSteps(draftId)).thenReturn(steps);
        when(mapper.findDependencyStatuses(steps)).thenReturn(validDependencies(steps, true));
        when(mapper.findOverlapCandidates(500, policyId)).thenReturn(List.of());
        when(mapper.findPublished(policyId)).thenReturn(Optional.empty());
        when(mapper.publishVersion(eq(draftId), any(), any(), eq("首版严重告警升级")))
                .thenReturn(1);
        when(mapper.touchPolicy(eq(policyId), eq(3L), nullable(UUID.class))).thenReturn(1);

        NotificationPolicyResponse response = service.publish(
                policyId,
                new NotificationPolicyPublishRequest(
                        draftId, "首版严重告警升级", 3));

        assertThat(response.published()).isNotNull();
        assertThat(response.published().state()).isEqualTo("PUBLISHED");
        assertThat(response.draft()).isNull();
        verify(mapper).publishVersion(eq(draftId), any(), any(), eq("首版严重告警升级"));
        verify(mapper).touchPolicy(policyId, 3, null);
    }

    @Test
    void 发布必须拒绝没有任何触达步骤的异常草稿() {
        UUID policyId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy(policyId, 0)));
        when(mapper.findVersionById(policyId, draftId))
                .thenReturn(Optional.of(draft(draftId, policyId, false)));
        when(mapper.findSteps(draftId)).thenReturn(List.of());

        assertReason(() -> service.publish(policyId,
                new NotificationPolicyPublishRequest(draftId, "发布", 0)),
                "STEP_INVALID");
        verify(mapper, never()).publishVersion(any(), any(), any(), any());
    }

    @Test
    void 开启恢复通知必须提供同渠道已启用的恢复模板和接收对象() {
        UUID policyId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        NotificationPolicy policy = policy(policyId, 0);
        NotificationPolicyVersion draft = draft(draftId, policyId, true);
        List<NotificationPolicyStep> steps = storedSteps(draftId, true);
        List<NotificationPolicyDependencyStatus> invalid = validDependencies(steps, true);
        NotificationPolicyDependencyStatus first = invalid.getFirst();
        invalid = List.of(new NotificationPolicyDependencyStatus(
                first.stepId(), "ENABLED", NotificationChannelType.EMAIL,
                "ENABLED", 0, "ENABLED", NotificationChannelType.EMAIL,
                NotificationEventType.ALARM_RAISED, "ENABLED",
                NotificationChannelType.EMAIL, NotificationEventType.ALARM_RAISED));
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy));
        when(mapper.findVersionById(policyId, draftId)).thenReturn(Optional.of(draft));
        when(mapper.findSteps(draftId)).thenReturn(List.of(steps.getFirst()));
        when(mapper.findDependencyStatuses(any())).thenReturn(invalid);

        assertReason(() -> service.publish(policyId,
                new NotificationPolicyPublishRequest(draftId, "发布", 0)),
                "DEPENDENCY_INVALID");
    }

    @Test
    void 同优先级且矿区规则等级范围重叠的已发布策略必须阻止发布() {
        UUID policyId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        NotificationPolicy policy = policy(policyId, 0);
        NotificationPolicyVersion draft = draft(draftId, policyId, false);
        List<NotificationPolicyStep> steps = storedSteps(draftId, false);
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy));
        when(mapper.findVersionById(policyId, draftId)).thenReturn(Optional.of(draft));
        when(mapper.findSteps(draftId)).thenReturn(steps);
        when(mapper.findDependencyStatuses(steps)).thenReturn(validDependencies(steps, false));
        when(mapper.findOverlapCandidates(500, policyId)).thenReturn(List.of(
                new NotificationPolicyOverlapCandidate(
                        UUID.randomUUID(), "other-critical",
                        draft.matchConditionsJson(), "[\"CRITICAL\"]")));

        assertThatThrownBy(() -> service.publish(policyId,
                new NotificationPolicyPublishRequest(draftId, "发布", 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void 重复发布同一版本必须幂等返回且不得重复写数据库() {
        UUID policyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        NotificationPolicy policy = policy(policyId, 4);
        NotificationPolicyVersion published = published(versionId, policyId);
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy));
        when(mapper.findVersionById(policyId, versionId)).thenReturn(Optional.of(published));
        when(mapper.findSteps(versionId)).thenReturn(storedSteps(versionId, false));

        NotificationPolicyResponse response = service.publish(
                policyId,
                new NotificationPolicyPublishRequest(versionId, "重复提交", 3));

        assertThat(response.published().id()).isEqualTo(versionId);
        verify(mapper, never()).publishVersion(any(), any(), any(), any());
    }

    @Test
    void 已发布策略必须通过派生新草稿修改且复制步骤为新标识() {
        UUID policyId = UUID.randomUUID();
        UUID publishedId = UUID.randomUUID();
        NotificationPolicy policy = policy(policyId, 5);
        NotificationPolicyVersion published = published(publishedId, policyId);
        List<NotificationPolicyStep> publishedSteps = storedSteps(publishedId, false);
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy));
        when(mapper.findDraft(policyId)).thenReturn(Optional.empty());
        when(mapper.findPublished(policyId)).thenReturn(Optional.of(published));
        when(mapper.findSteps(publishedId)).thenReturn(publishedSteps);
        when(mapper.insertVersion(any(), any())).thenReturn(1);
        when(mapper.insertSteps(any())).thenReturn(2);
        when(mapper.touchPolicy(eq(policyId), eq(5L), nullable(UUID.class))).thenReturn(1);

        NotificationPolicyResponse response = service.createDraft(
                policyId, new NotificationPolicyActionRequest(5));

        assertThat(response.draft().versionNumber()).isEqualTo(2);
        assertThat(response.draft().id()).isNotEqualTo(publishedId);
        assertThat(response.draft().steps())
                .allMatch(step -> publishedSteps.stream()
                        .noneMatch(old -> old.id().equals(step.id())));
    }

    @Test
    void 没有草稿时修改接口必须拒绝直接修改已发布版本() {
        UUID policyId = UUID.randomUUID();
        when(mapper.lockById(policyId)).thenReturn(Optional.of(policy(policyId, 2)));
        when(mapper.findDraft(policyId)).thenReturn(Optional.empty());

        assertReason(() -> service.updateDraft(policyId, request(false, 2)),
                "DRAFT_NOT_FOUND");
    }

    private void assertReason(Runnable operation, String reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", reason));
    }

    private NotificationPolicyRequest request(boolean notifyOnRecovery, long version) {
        return requestWithSteps(List.of(
                step(0, 0, notifyOnRecovery),
                step(1, 300, notifyOnRecovery)), notifyOnRecovery, version);
    }

    private NotificationPolicyRequest requestWithSteps(
            List<NotificationPolicyRequest.Step> steps
    ) {
        return requestWithSteps(steps, true, 0);
    }

    private NotificationPolicyRequest requestWithSteps(
            List<NotificationPolicyRequest.Step> steps,
            boolean notifyOnRecovery,
            long version
    ) {
        return new NotificationPolicyRequest(
                "critical-escalation", "严重告警升级", "严重告警五分钟升级",
                500,
                new NotificationPolicyRequest.MatchConditions(
                        Set.of(UUID.fromString("01910000-0000-7000-8000-000000000001")),
                        Set.of("temperature-critical")),
                Set.of("CRITICAL"), true, notifyOnRecovery, steps, version);
    }

    private NotificationPolicyRequest.Step step(
            int order,
            int delay,
            boolean recovery
    ) {
        return new NotificationPolicyRequest.Step(
                order, delay, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                recovery ? UUID.randomUUID() : null, "NONE", null);
    }

    private NotificationPolicy policy(UUID id, long version) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationPolicy(
                id, "critical-escalation", "严重告警升级", "严重告警五分钟升级",
                500, "ACTIVE", now, now, null, version);
    }

    private NotificationPolicyVersion draft(UUID id, UUID policyId, boolean recovery) {
        return new NotificationPolicyVersion(
                id, policyId, 1, "DRAFT",
                "{\"mineIds\":[\"01910000-0000-7000-8000-000000000001\"],"
                        + "\"ruleKeys\":[\"temperature-critical\"]}",
                "[\"CRITICAL\"]", true, recovery,
                null, null, null, null, Instant.parse("2026-07-21T00:00:00Z"));
    }

    private NotificationPolicyVersion published(UUID id, UUID policyId) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationPolicyVersion(
                id, policyId, 1, "PUBLISHED", "{\"mineIds\":[],\"ruleKeys\":[]}",
                "[\"CRITICAL\"]", true, false,
                now, UUID.randomUUID(), "首版", null, now);
    }

    private List<NotificationPolicyStep> storedSteps(UUID versionId, boolean recovery) {
        return List.of(
                storedStep(versionId, 0, 0, recovery),
                storedStep(versionId, 1, 300, recovery));
    }

    private NotificationPolicyStep storedStep(
            UUID versionId,
            int order,
            int delay,
            boolean recovery
    ) {
        return new NotificationPolicyStep(
                UUID.randomUUID(), versionId, order, delay,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                recovery ? UUID.randomUUID() : null, "NONE", null,
                Instant.parse("2026-07-21T00:00:00Z"));
    }

    private List<NotificationPolicyDependencyStatus> validDependencies(
            List<NotificationPolicyStep> steps,
            boolean recovery
    ) {
        return steps.stream().map(step -> new NotificationPolicyDependencyStatus(
                step.id(), "ENABLED", NotificationChannelType.EMAIL,
                "ENABLED", 1, "ENABLED", NotificationChannelType.EMAIL,
                NotificationEventType.ALARM_RAISED,
                recovery ? "ENABLED" : null,
                recovery ? NotificationChannelType.EMAIL : null,
                recovery ? NotificationEventType.ALARM_RECOVERED : null)).toList();
    }
}
