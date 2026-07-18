package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.system.repository.MenuManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
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
    void 在线菜单维护应提供全部已发布页面组件选项() {
        assertThat(service.findComponentOptions())
                .extracting(MenuManagementService.ComponentOption::key)
                .contains(
                        "device-strategy-workbench",
                        "retention-governance",
                        "aggregation-governance",
                        "alarm-center",
                        "mine-site-archive",
                        "mine-vehicle-management",
                        "mine-person-management",
                        "mine-location-tag-management",
                        "mine-binding-management",
                        "mine-location-monitor"
                );
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

    @Test
    void 批量删除父子节点时应先删除子节点再删除父节点() {
        UUID directoryId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        when(mapper.findAllMenus()).thenReturn(List.of(
                menu(directoryId, null, "临时目录", "DIRECTORY", null, null, null, false),
                menu(menuId, directoryId, "临时页面", "MENU", "/temporary", "device-device-list",
                        "temporary:view", false)
        ));
        when(mapper.countRoleReferences(directoryId)).thenReturn(0L);
        when(mapper.countRoleReferences(menuId)).thenReturn(0L);

        service.deleteMenus(List.of(directoryId, menuId));

        org.mockito.InOrder deletionOrder = inOrder(mapper);
        deletionOrder.verify(mapper).deleteMenu(menuId);
        deletionOrder.verify(mapper).deleteMenu(directoryId);
    }

    @Test
    void 批量删除包含核心节点时应整批拒绝且不删除任何节点() {
        UUID ordinaryId = UUID.randomUUID();
        UUID protectedId = UUID.randomUUID();
        when(mapper.findAllMenus()).thenReturn(List.of(
                menu(ordinaryId, null, "临时目录", "DIRECTORY", null, null, null, false),
                menu(protectedId, null, "系统管理", "DIRECTORY", null, null, null, true)
        ));

        assertThatThrownBy(() -> service.deleteMenus(List.of(ordinaryId, protectedId)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-409-006"));

        verify(mapper, never()).deleteMenu(any());
    }

    @Test
    void 批量删除未包含全部子节点时应整批拒绝() {
        UUID directoryId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        when(mapper.findAllMenus()).thenReturn(List.of(
                menu(directoryId, null, "临时目录", "DIRECTORY", null, null, null, false),
                menu(menuId, directoryId, "临时页面", "MENU", "/temporary", "device-device-list",
                        "temporary:view", false)
        ));
        when(mapper.countRoleReferences(directoryId)).thenReturn(0L);

        assertThatThrownBy(() -> service.deleteMenus(List.of(directoryId)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("SYSTEM-409-006"));

        verify(mapper, never()).deleteMenu(any());
    }

    @Test
    void 应只分页返回指定父节点下的菜单并标记可展开状态() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        when(mapper.countMenuItems(null, null, null, null)).thenReturn(1L);
        when(mapper.findMenuItems(null, null, null, null, 0, 10)).thenReturn(List.of(
                menuListItem(rootId, null, "设备管理", "DIRECTORY", null, null, null, true)
        ));
        when(mapper.countMenuItems(rootId, null, null, null)).thenReturn(1L);
        when(mapper.findMenuItems(rootId, null, null, null, 0, 10)).thenReturn(List.of(
                menuListItem(childId, rootId, "设备台账", "MENU", "/devices", "device-device-list", "device:view", false)
        ));

        PageResponse<MenuManagementService.MenuListItem> roots =
                service.findMenus(null, null, null, null, 1, 10);
        PageResponse<MenuManagementService.MenuListItem> children =
                service.findMenus(rootId, null, null, null, 1, 10);

        assertThat(roots.total()).isEqualTo(1);
        assertThat(roots.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("设备管理");
            assertThat(item.hasChildren()).isTrue();
        });
        assertThat(children.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("设备台账");
            assertThat(item.hasChildren()).isFalse();
        });
        verify(mapper, never()).findAllMenus();
    }

    private MenuManagementMapper.MenuListRecord menuListItem(
            UUID id,
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            boolean hasChildren
    ) {
        return new MenuManagementMapper.MenuListRecord(
                id, parentId, name, type, path, componentKey, permission, "mdi:menu",
                10, true, "ENABLED", "mine.menu.test", false, 0, hasChildren
        );
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
