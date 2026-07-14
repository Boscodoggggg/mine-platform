package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.security.MineSecurityProperties;
import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 组装当前用户的角色、权限以及目录/菜单/按钮树。
 */
@Service
public final class NavigationService {

    private static final ErrorCode INVALID_SESSION =
            new ErrorCode("AUTH-401-002", "登录状态无效");
    private static final Comparator<MutableMenuNode> MENU_ORDER = Comparator
            .comparingInt((MutableMenuNode node) -> node.record.sortOrder())
            .thenComparing(node -> node.record.id());

    private final SystemAuthMapper authMapper;
    private final UUID superAdminId;

    /**
     * 创建当前用户导航服务。
     *
     * @param authMapper RBAC 数据访问接口
     * @param properties 平台安全配置
     */
    public NavigationService(SystemAuthMapper authMapper, MineSecurityProperties properties) {
        this.authMapper = authMapper;
        this.superAdminId = properties.bootstrapAdmin().id();
    }

    /**
     * 返回当前用户的角色、权限和可访问菜单树。
     *
     * @return 当前用户导航结果
     */
    public NavigationResult currentNavigation() {
        UUID userId = currentUserId();
        List<SystemAuthMapper.MenuRecord> records = userId.equals(superAdminId)
                ? authMapper.findAllEnabledMenus()
                : authMapper.findEnabledMenusByUserId(userId);
        return new NavigationResult(
                List.copyOf(StpUtil.getRoleList()),
                List.copyOf(StpUtil.getPermissionList()),
                buildTree(records)
        );
    }

    private UUID currentUserId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw new MineException(INVALID_SESSION, Map.of());
        }
    }

    private List<MenuNode> buildTree(List<SystemAuthMapper.MenuRecord> records) {
        Map<UUID, MutableMenuNode> nodes = new LinkedHashMap<>();
        for (SystemAuthMapper.MenuRecord record : records) {
            nodes.putIfAbsent(record.id(), new MutableMenuNode(record));
        }

        List<MutableMenuNode> roots = new ArrayList<>();
        for (MutableMenuNode node : nodes.values()) {
            UUID parentId = node.record.parentId();
            MutableMenuNode parent = parentId == null ? null : nodes.get(parentId);
            if (parent == null || createsCycle(node.record.id(), parentId, nodes)) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        sortRecursively(roots);
        return roots.stream().map(this::freeze).toList();
    }

    private boolean createsCycle(
            UUID nodeId,
            UUID parentId,
            Map<UUID, MutableMenuNode> nodes
    ) {
        Set<UUID> visited = new HashSet<>();
        UUID cursor = parentId;
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(nodeId)) {
                return true;
            }
            MutableMenuNode current = nodes.get(cursor);
            cursor = current == null ? null : current.record.parentId();
        }
        return cursor != null;
    }

    private void sortRecursively(List<MutableMenuNode> nodes) {
        nodes.sort(MENU_ORDER);
        nodes.forEach(node -> sortRecursively(node.children));
    }

    private MenuNode freeze(MutableMenuNode node) {
        SystemAuthMapper.MenuRecord record = node.record;
        return new MenuNode(
                record.id(),
                record.parentId(),
                record.name(),
                record.type(),
                record.path(),
                record.componentKey(),
                record.permission(),
                record.icon(),
                record.sortOrder(),
                record.visible(),
                node.children.stream().map(this::freeze).toList()
        );
    }

    private static final class MutableMenuNode {

        private final SystemAuthMapper.MenuRecord record;
        private final List<MutableMenuNode> children = new ArrayList<>();

        private MutableMenuNode(SystemAuthMapper.MenuRecord record) {
            this.record = record;
        }
    }

    /**
     * 当前用户导航契约。
     *
     * @param roles       角色编码
     * @param permissions 按钮和接口权限码
     * @param menus       目录、页面和按钮树
     */
    public record NavigationResult(
            List<String> roles,
            List<String> permissions,
            List<MenuNode> menus
    ) {
    }

    /**
     * 可序列化的菜单树节点。
     *
     * @param id           节点标识
     * @param parentId     父节点标识
     * @param name         展示名称
     * @param type         DIRECTORY、MENU 或 BUTTON
     * @param path         路由路径
     * @param componentKey 组件白名单键
     * @param permission   权限码
     * @param icon         图标键
     * @param sortOrder    同级顺序
     * @param visible      是否显示在菜单
     * @param children     子节点
     */
    public record MenuNode(
            UUID id,
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            String icon,
            int sortOrder,
            boolean visible,
            List<MenuNode> children
    ) {
    }
}
