package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.system.repository.MenuManagementMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 处理菜单、页面和按钮权限节点的在线维护用例。
 */
@Service
public class MenuManagementService {

    private static final ErrorCode MENU_NOT_FOUND =
            new ErrorCode("SYSTEM-404-003", "菜单或权限节点不存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("SYSTEM-409-003", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode PERMISSION_CONFLICT =
            new ErrorCode("SYSTEM-409-005", "权限码已被其他节点使用");
    private static final ErrorCode MENU_IN_USE =
            new ErrorCode("SYSTEM-409-006", "核心菜单、含子节点或已授权菜单不能删除");
    private static final ErrorCode MENU_INVALID =
            new ErrorCode("SYSTEM-422-002", "菜单节点结构不合法");

    private static final Set<String> TYPES = Set.of("DIRECTORY", "MENU", "BUTTON");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final List<ComponentOption> COMPONENTS = List.of(
            new ComponentOption("home-overview", "调度首页"),
            new ComponentOption("device-product-list", "产品与物模型"),
            new ComponentOption("device-device-list", "设备管理"),
            new ComponentOption("device-model-management", "物模型与协议"),
            new ComponentOption("device-strategy-workbench", "策略工作台"),
            new ComponentOption("device-monitor", "设备实时监控"),
            new ComponentOption("retention-governance", "数据保留"),
            new ComponentOption("aggregation-governance", "数值聚合"),
            new ComponentOption("command-center", "命令中心"),
            new ComponentOption("alarm-center", "告警中心"),
            new ComponentOption("mine-site-archive", "矿区档案"),
            new ComponentOption("mine-vehicle-management", "车辆管理"),
            new ComponentOption("mine-person-management", "人员管理"),
            new ComponentOption("mine-location-tag-management", "定位标签"),
            new ComponentOption("mine-binding-management", "绑定管理"),
            new ComponentOption("mine-location-monitor", "实时定位"),
            new ComponentOption("mine-spatial-management", "路线与围栏"),
            new ComponentOption("mine-scene-workbench", "三维场景"),
            new ComponentOption("system-user-list", "用户管理"),
            new ComponentOption("system-role-list", "角色管理"),
            new ComponentOption("system-menu-list", "菜单管理"),
            new ComponentOption("system-dictionary-list", "字典管理"),
            new ComponentOption("system-operations-monitor", "运行监控")
    );
    private static final Set<String> COMPONENT_KEYS = COMPONENTS.stream()
            .map(ComponentOption::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final MenuManagementMapper mapper;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建菜单管理服务。
     *
     * @param mapper       菜单数据接口
     * @param auditService 操作审计服务
     */
    public MenuManagementService(MenuManagementMapper mapper, OperationAuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 查询完整菜单树。
     *
     * @return 菜单树
     */
    @Transactional(readOnly = true)
    public List<MenuNode> findMenuTree() {
        Map<UUID, List<MenuManagementMapper.MenuRecord>> children = new HashMap<>();
        mapper.findAllMenus().forEach(menu ->
                children.computeIfAbsent(menu.parentId(), ignored -> new ArrayList<>()).add(menu));
        return children.getOrDefault(null, List.of()).stream()
                .map(menu -> toNode(menu, children))
                .toList();
    }

    /**
     * 按父节点分页查询菜单管理节点。
     *
     * @param parentId 父节点标识
     * @param keyword  名称关键词
     * @param type     菜单类型
     * @param status   菜单状态
     * @param page     页码
     * @param size     每页数量
     * @return 当前层级菜单分页
     */
    @Transactional(readOnly = true)
    public PageResponse<MenuListItem> findMenus(
            UUID parentId,
            String keyword,
            String type,
            String status,
            int page,
            int size
    ) {
        String normalizedKeyword = normalize(keyword);
        String normalizedType = normalize(type);
        String normalizedStatus = normalize(status);
        normalizedType = normalizedType == null ? null : normalizedType.toUpperCase(Locale.ROOT);
        normalizedStatus = normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT);
        long total = mapper.countMenuItems(parentId, normalizedKeyword, normalizedType, normalizedStatus);
        int offset = (page - 1) * size;
        List<MenuListItem> items = mapper.findMenuItems(
                        parentId, normalizedKeyword, normalizedType, normalizedStatus, offset, size
                ).stream()
                .map(this::toListItem)
                .toList();
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * 查询允许配置的前端组件键，避免数据库注入任意模块路径。
     *
     * @return 组件选项
     */
    public List<ComponentOption> findComponentOptions() {
        return COMPONENTS;
    }

    /**
     * 按需查询页面下的直接按钮权限。
     *
     * @param menuId 页面菜单标识
     * @return 按钮权限节点
     */
    @Transactional(readOnly = true)
    public List<MenuNode> findMenuButtons(UUID menuId) {
        return mapper.findButtonChildren(menuId).stream()
                .map(menu -> toNode(menu, Map.of()))
                .toList();
    }

    /**
     * 创建菜单节点。
     *
     * @param command 菜单保存命令
     * @return 新菜单节点
     */
    @Transactional
    public MenuNode createMenu(MenuSaveCommand command) {
        NormalizedMenu normalized = validateAndNormalize(null, command);
        UUID id = idGenerator.next();
        try {
            mapper.insertMenu(
                    id, normalized.parentId(), normalized.name(), normalized.type(), normalized.path(),
                    normalized.componentKey(), normalized.permission(), normalized.icon(), normalized.sortOrder(),
                    normalized.visible(), normalized.status(), normalized.i18nKey(), currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(PERMISSION_CONFLICT, Map.of("permission", safe(normalized.permission())));
        }
        MenuManagementMapper.MenuRecord created = requireMenu(id);
        auditService.recordSuccess(
                "CREATE_MENU", "system:menu:create", "MENU", id, Map.of(), summary(created)
        );
        return toNode(created, Map.of());
    }

    /**
     * 使用乐观锁修改菜单节点。
     *
     * @param id      菜单标识
     * @param command 菜单保存命令
     * @param version 期望版本号
     * @return 更新后的菜单节点
     */
    @Transactional
    public MenuNode updateMenu(UUID id, MenuSaveCommand command, long version) {
        MenuManagementMapper.MenuRecord before = requireMenu(id);
        NormalizedMenu normalized = validateAndNormalize(id, command);
        try {
            int affected = mapper.updateMenu(
                    id, normalized.parentId(), normalized.name(), normalized.type(), normalized.path(),
                    normalized.componentKey(), normalized.permission(), normalized.icon(), normalized.sortOrder(),
                    normalized.visible(), normalized.status(), normalized.i18nKey(), version, currentOperatorId()
            );
            if (affected == 0) {
                throw new MineException(VERSION_CONFLICT, Map.of("menuId", id));
            }
        } catch (DuplicateKeyException exception) {
            throw new MineException(PERMISSION_CONFLICT, Map.of("permission", safe(normalized.permission())));
        }
        MenuManagementMapper.MenuRecord updated = requireMenu(id);
        auditService.recordSuccess(
                "UPDATE_MENU", "system:menu:update", "MENU", id, summary(before), summary(updated)
        );
        return toNode(updated, Map.of());
    }

    /**
     * 删除未受保护、无子节点且未分配给角色的菜单节点。
     *
     * @param id 菜单标识
     */
    @Transactional
    public void deleteMenu(UUID id) {
        MenuManagementMapper.MenuRecord before = requireMenu(id);
        if (before.protectedNode() || mapper.countChildren(id) > 0 || mapper.countRoleReferences(id) > 0) {
            throw new MineException(MENU_IN_USE, Map.of("menuId", id));
        }
        mapper.deleteMenu(id);
        auditService.recordSuccess(
                "DELETE_MENU", "system:menu:delete", "MENU", id, summary(before), Map.of()
        );
    }

    /**
     * 原子删除一组菜单节点，父子节点同批删除时先删除子节点。
     *
     * @param ids 待删除菜单标识
     */
    @Transactional
    public void deleteMenus(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw invalid();
        }
        Set<UUID> selectedIds = new LinkedHashSet<>(ids);
        Map<UUID, MenuManagementMapper.MenuRecord> menus = mapper.findAllMenus().stream()
                .collect(java.util.stream.Collectors.toMap(MenuManagementMapper.MenuRecord::id, item -> item));
        List<MenuManagementMapper.MenuRecord> selectedMenus = selectedIds.stream()
                .map(id -> requireMenu(id, menus))
                .toList();

        selectedMenus.forEach(menu -> validateBatchDeletion(menu, selectedIds, menus.values()));
        selectedMenus.stream()
                .sorted(Comparator.comparingInt(
                        (MenuManagementMapper.MenuRecord menu) -> menuDepth(menu, menus)
                ).reversed())
                .forEach(menu -> {
                    mapper.deleteMenu(menu.id());
                    auditService.recordSuccess(
                            "DELETE_MENU", "system:menu:delete", "MENU", menu.id(), summary(menu), Map.of()
                    );
                });
    }

    /**
     * 从菜单快照中读取必然存在的节点。
     *
     * @param id    菜单标识
     * @param menus 菜单快照
     * @return 菜单节点
     */
    private MenuManagementMapper.MenuRecord requireMenu(
            UUID id,
            Map<UUID, MenuManagementMapper.MenuRecord> menus
    ) {
        MenuManagementMapper.MenuRecord menu = menus.get(id);
        if (menu == null) {
            throw new MineException(MENU_NOT_FOUND, Map.of("menuId", id));
        }
        return menu;
    }

    /**
     * 验证批量删除不会移除核心、已授权或仍有未选中子节点的菜单。
     *
     * @param menu        当前菜单
     * @param selectedIds 本批次标识
     * @param allMenus    完整菜单快照
     */
    private void validateBatchDeletion(
            MenuManagementMapper.MenuRecord menu,
            Set<UUID> selectedIds,
            java.util.Collection<MenuManagementMapper.MenuRecord> allMenus
    ) {
        boolean hasUnselectedChild = allMenus.stream()
                .anyMatch(child -> menu.id().equals(child.parentId()) && !selectedIds.contains(child.id()));
        if (menu.protectedNode() || mapper.countRoleReferences(menu.id()) > 0 || hasUnselectedChild) {
            throw new MineException(MENU_IN_USE, Map.of("menuId", menu.id()));
        }
    }

    /**
     * 计算菜单层级，用于保证批量删除的子节点先于父节点执行。
     *
     * @param menu  当前菜单
     * @param menus 完整菜单快照
     * @return 从根节点开始的层级
     */
    private int menuDepth(
            MenuManagementMapper.MenuRecord menu,
            Map<UUID, MenuManagementMapper.MenuRecord> menus
    ) {
        int depth = 0;
        UUID cursor = menu.parentId();
        Set<UUID> visited = new HashSet<>();
        while (cursor != null && visited.add(cursor)) {
            depth++;
            MenuManagementMapper.MenuRecord parent = menus.get(cursor);
            cursor = parent == null ? null : parent.parentId();
        }
        return depth;
    }

    private NormalizedMenu validateAndNormalize(UUID currentId, MenuSaveCommand command) {
        String type = command.type().strip().toUpperCase(Locale.ROOT);
        String status = command.status().strip().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type) || !STATUSES.contains(status) || command.sortOrder() < 0) {
            throw invalid();
        }
        if (currentId != null && currentId.equals(command.parentId())) {
            throw invalid();
        }

        Map<UUID, MenuManagementMapper.MenuRecord> menus = mapper.findAllMenus().stream()
                .collect(java.util.stream.Collectors.toMap(MenuManagementMapper.MenuRecord::id, item -> item));
        validateParent(currentId, command.parentId(), type, menus);

        String path = normalize(command.path());
        String componentKey = normalize(command.componentKey());
        String permission = normalize(command.permission());
        boolean visible = command.visible();
        if ("DIRECTORY".equals(type)) {
            path = null;
            componentKey = null;
            permission = null;
        } else if ("MENU".equals(type)) {
            if (path == null || !path.startsWith("/") || componentKey == null
                    || !COMPONENT_KEYS.contains(componentKey) || permission == null) {
                throw invalid();
            }
        } else {
            if (permission == null) {
                throw invalid();
            }
            path = null;
            componentKey = null;
            visible = false;
        }
        return new NormalizedMenu(
                command.parentId(), command.name().strip(), type, path, componentKey, permission,
                normalize(command.icon()), command.sortOrder(), visible, status, normalize(command.i18nKey())
        );
    }

    private void validateParent(
            UUID currentId,
            UUID parentId,
            String type,
            Map<UUID, MenuManagementMapper.MenuRecord> menus
    ) {
        if (parentId == null) {
            if (!"DIRECTORY".equals(type)) {
                throw invalid();
            }
            return;
        }
        MenuManagementMapper.MenuRecord parent = menus.get(parentId);
        if (parent == null) {
            throw invalid();
        }
        if (("BUTTON".equals(type) && !"MENU".equals(parent.type()))
                || (!"BUTTON".equals(type) && !"DIRECTORY".equals(parent.type()))) {
            throw invalid();
        }
        Set<UUID> visited = new HashSet<>();
        UUID cursor = parentId;
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(currentId)) {
                throw invalid();
            }
            MenuManagementMapper.MenuRecord node = menus.get(cursor);
            cursor = node == null ? null : node.parentId();
        }
    }

    private MenuManagementMapper.MenuRecord requireMenu(UUID id) {
        return mapper.findMenuById(id)
                .orElseThrow(() -> new MineException(MENU_NOT_FOUND, Map.of("menuId", id)));
    }

    private MenuNode toNode(
            MenuManagementMapper.MenuRecord menu,
            Map<UUID, List<MenuManagementMapper.MenuRecord>> children
    ) {
        return new MenuNode(
                menu.id(), menu.parentId(), menu.name(), menu.type(), menu.path(), menu.componentKey(),
                menu.permission(), menu.icon(), menu.sortOrder(), menu.visible(), menu.status(), menu.i18nKey(),
                menu.protectedNode(), menu.version(),
                children.getOrDefault(menu.id(), List.of()).stream()
                        .map(child -> toNode(child, children)).toList()
        );
    }

    private MenuListItem toListItem(MenuManagementMapper.MenuListRecord menu) {
        return new MenuListItem(
                menu.id(), menu.parentId(), menu.name(), menu.type(), menu.path(), menu.componentKey(),
                menu.permission(), menu.icon(), menu.sortOrder(), menu.visible(), menu.status(), menu.i18nKey(),
                menu.protectedNode(), menu.version(), menu.hasChildren()
        );
    }

    private Map<String, ?> summary(MenuManagementMapper.MenuRecord menu) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", menu.name());
        result.put("type", menu.type());
        result.put("status", menu.status());
        result.put("protectedNode", menu.protectedNode());
        if (menu.path() != null) result.put("path", menu.path());
        if (menu.componentKey() != null) result.put("componentKey", menu.componentKey());
        if (menu.permission() != null) result.put("permission", menu.permission());
        return Map.copyOf(result);
    }

    private MineException invalid() {
        return new MineException(MENU_INVALID, Map.of());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 菜单保存命令。
     */
    public record MenuSaveCommand(
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            String icon,
            int sortOrder,
            boolean visible,
            String status,
            String i18nKey
    ) {
    }

    /**
     * 菜单树节点。
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
            String status,
            String i18nKey,
            boolean protectedNode,
            long version,
            List<MenuNode> children
    ) {
        /**
         * 固化子节点只读快照。
         */
        public MenuNode {
            children = List.copyOf(children);
        }
    }

    /**
     * 菜单管理树表格的单层节点投影。
     */
    public record MenuListItem(
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
            String status,
            String i18nKey,
            boolean protectedNode,
            long version,
            boolean hasChildren
    ) {
    }

    /**
     * 前端页面组件白名单选项。
     */
    public record ComponentOption(String key, String name) {
    }

    private record NormalizedMenu(
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            String icon,
            int sortOrder,
            boolean visible,
            String status,
            String i18nKey
    ) {
    }
}
