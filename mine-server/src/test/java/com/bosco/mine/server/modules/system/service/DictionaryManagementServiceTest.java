package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.system.repository.DictionaryManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通用字典管理的内置保护和默认项约束。
 */
class DictionaryManagementServiceTest {

    private DictionaryManagementMapper mapper;
    private DictionaryManagementService service;

    @BeforeEach
    void 创建字典服务测试环境() {
        mapper = mock(DictionaryManagementMapper.class);
        service = new DictionaryManagementService(mapper, mock(OperationAuditService.class));
    }

    @Test
    void 应禁止删除系统内置字典类型() {
        UUID typeId = UUID.randomUUID();
        when(mapper.findTypeById(typeId)).thenReturn(Optional.of(new DictionaryManagementMapper.DictTypeRecord(
                typeId, "command_status", "命令状态", "ENABLED", "系统状态", true, 0
        )));

        assertThatThrownBy(() -> service.deleteType(typeId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-409-008"));
        verify(mapper, never()).deleteType(typeId);
    }

    @Test
    void 创建默认项前应清除同类型旧默认项() {
        UUID typeId = UUID.randomUUID();
        when(mapper.findTypeById(typeId)).thenReturn(Optional.of(new DictionaryManagementMapper.DictTypeRecord(
                typeId, "alarm_level", "告警级别", "ENABLED", null, false, 0
        )));
        UUID itemId = UUID.randomUUID();
        when(mapper.findItemById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(
                new DictionaryManagementMapper.DictItemRecord(
                        itemId, typeId, "HIGH", "高", "High", "error", 10, true, "ENABLED", 0
                )
        ));

        service.createItem(typeId, new DictionaryManagementService.DictItemCommand(
                "HIGH", "高", "High", "error", 10, true, "ENABLED"
        ));

        verify(mapper).clearDefaultItem(typeId, null);
    }
}
