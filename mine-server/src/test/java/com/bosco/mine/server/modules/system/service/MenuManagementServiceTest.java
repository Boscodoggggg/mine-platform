package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.system.repository.MenuManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验菜单在线维护的结构约束和核心节点保护规则。
 */
class MenuManagementServiceTest {

    private MenuManagementMapper mapper;
    private MenuManagementService service;

    @BeforeEach
    void 创建菜单服务测试环境() {
        mapper = mock(MenuManagementMapper.class);
        service = new MenuManagementService(mapper, mock(OperationAuditService.class));
    }

    @Test
    void 应拒绝未注册的前端组件键() {
        MenuManagementService.MenuSaveCommand command = new MenuManagementService.MenuSaveCommand(
                null, "未知页面", "MENU", "/unknown", "vendor-page",
                "system:unknown:view", "mdi:help", 10, true, "ENABLED", "mine.menu.unknown"
        );

        assertThatThrownBy(() -> service.createMenu(command))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-422-002"));
        verify(mapper, never()).insertMenu(any(), any(), anyString(), anyString(), any(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), any(), any());
    }

    @Test
    void 应禁止删除核心菜单节点() {
        UUID menuId = UUID.randomUUID();
        when(mapper.findMenuById(menuId)).thenReturn(Optional.of(menu(
                menuId, null, "系统管理", "DIRECTORY", null, null, null, true
        )));

        assertThatThrownBy(() -> service.deleteMenu(menuId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-409-006"));
        verify(mapper, never()).deleteMenu(menuId);
    }

    @Test
    void 应拒绝把节点移动到自己的后代下面() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        when(mapper.findMenuById(rootId)).thenReturn(Optional.of(menu(
                rootId, null, "设备管理", "DIRECTORY", null, null, null, false
        )));
        when(mapper.findAllMenus()).thenReturn(List.of(
                menu(rootId, null, "设备管理", "DIRECTORY", null, null, null, false),
                menu(childId, rootId, "设备列表", "MENU", "/devices", "device-device-list",
                        "device:view", false)
        ));

        MenuManagementService.MenuSaveCommand command = new MenuManagementService.MenuSaveCommand(
                childId, "设备管理", "DIRECTORY", null, null, null,
                "mdi:factory", 10, true, "ENABLED", "mine.menu.device"
        );

        assertThatThrownBy(() -> service.updateMenu(rootId, command, 0))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-422-002"));
        verify(mapper, never()).updateMenu(any(), any(), anyString(), anyString(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), anyString(),
                any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    private MenuManagementMapper.MenuRecord menu(
            UUID id,
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            boolean protectedNode
    ) {
        return new MenuManagementMapper.MenuRecord(
                id, parentId, name, type, path, componentKey, permission, "mdi:menu",
                10, true, "ENABLED", "mine.menu.test", protectedNode, 0
        );
    }
}
