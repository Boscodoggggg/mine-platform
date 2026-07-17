const local: App.I18n.Schema = {
  system: {
    title: '智慧矿山数据平台',
    updateTitle: '系统版本更新通知',
    updateContent: '检测到系统有新版本发布，是否立即刷新页面？',
    updateConfirm: '立即刷新',
    updateCancel: '稍后再说'
  },
  common: {
    action: '操作',
    add: '新增',
    addSuccess: '添加成功',
    backToHome: '返回首页',
    batchDelete: '批量删除',
    cancel: '取消',
    close: '关闭',
    check: '勾选',
    selectAll: '全选',
    expandColumn: '展开列',
    columnSetting: '列设置',
    config: '配置',
    confirm: '确认',
    delete: '删除',
    deleteSuccess: '删除成功',
    confirmDelete: '确认删除吗？',
    edit: '编辑',
    warning: '警告',
    error: '错误',
    index: '序号',
    keywordSearch: '请输入关键词搜索',
    logout: '退出登录',
    logoutConfirm: '确认退出登录吗？',
    lookForward: '敬请期待',
    modify: '修改',
    modifySuccess: '修改成功',
    noData: '无数据',
    operate: '操作',
    pleaseCheckValue: '请检查输入的值是否合法',
    refresh: '刷新',
    reset: '重置',
    search: '搜索',
    switch: '切换',
    tip: '提示',
    trigger: '触发',
    update: '更新',
    updateSuccess: '更新成功',
    userCenter: '个人中心',
    yesOrNo: {
      yes: '是',
      no: '否'
    }
  },
  mine: {
    menu: {
      system: '系统管理',
      users: '用户管理',
      roles: '角色管理',
      menus: '菜单管理',
      dictionaries: '字典管理',
      operations: '运行监控',
      device: '设备管理',
      products: '产品管理',
      devices: '设备台账',
      models: '物模型与协议',
      strategies: '策略工作台',
      monitor: '设备监控',
      command: '命令管理',
      commandCenter: '命令中心',
      overview: '运行总览'
    },
    common: {
      action: '操作',
      edit: '编辑',
      delete: '删除',
      empty: '—',
      allStatus: '全部状态',
      enterPlaceholder: '请输入{field}',
      selectPlaceholder: '请选择{field}',
      searchTitle: '搜索',
      search: '查询',
      reset: '重置',
      cancel: '取消',
      save: '保存',
      saved: '保存成功',
      yes: '是',
      no: '否',
      fieldHelp: '查看“{field}”字段说明',
      requestFailed: '请求处理失败，请稍后重试',
      traceId: '链路编号：{traceId}',
      operationFailed: '操作失败，请稍后重试',
      status: {
        enabled: '启用',
        disabled: '停用',
        locked: '锁定'
      }
    },
    login: {
      brand: '智慧矿山数据平台',
      slogan: '设备智联 · 数据洞察 · 生产协同',
      title: {
        login: '欢迎登录',
        password: '修改初始密码',
        complete: '密码修改成功'
      },
      description: {
        login: '使用矿区管理账号登录。连续失败将触发安全限制。',
        password: '当前账号仍在使用初始密码，完成修改后需要重新登录。',
        complete: '密码已更新，旧会话已安全退出。'
      },
      field: {
        username: '用户名',
        password: '密码',
        currentPassword: '当前密码',
        newPassword: '新密码',
        confirmPassword: '确认新密码'
      },
      placeholder: {
        username: '请输入管理账号',
        password: '请输入登录密码'
      },
      validation: {
        usernameRequired: '请输入用户名',
        passwordRequired: '请输入密码',
        currentPasswordRequired: '请输入当前密码',
        newPasswordRequired: '请输入新密码',
        passwordTooShort: '新密码至少需要 12 个字符',
        passwordTooLong: '新密码的 UTF-8 编码不能超过 72 字节',
        confirmPasswordRequired: '请再次输入新密码',
        passwordMismatch: '两次输入的新密码不一致'
      },
      security: {
        title: '密码安全要求',
        description: '至少 12 个字符，UTF-8 编码不超过 72 字节，不得与当前密码相同。'
      },
      action: {
        processing: '正在处理…',
        login: '登录平台',
        changePassword: '确认修改',
        restart: '重新登录'
      },
      complete: {
        title: '密码修改成功，请重新登录',
        description: '为确保旧令牌全部失效，系统已安全退出当前会话。'
      },
      service: {
        local: '矿区本地服务',
        help: '连接异常时请联系矿区系统管理员'
      }
    },
    home: {
      title: '矿区运行态势',
      subtitle: '关注设备数据是否持续上报，以及当前需要优先处理的异常设备。',
      updatedAt: '更新于 {time}',
      unavailable: '暂不可用',
      never: '暂无记录',
      loadFailed: '运行态势加载失败',
      unavailableTitle: '运行数据暂不可用',
      unavailableDescription: '设备台账仍可查看，但遥测统计暂时无法确认。请稍后刷新或联系系统管理员。',
      runningState: {
        loading: '正在更新',
        normal: '运行平稳',
        attention: '存在需关注设备',
        empty: '尚未接入设备',
        unavailable: '数据不可用'
      },
      metric: {
        enabled: { label: '启用设备', description: '已纳入生产运行统计' },
        reporting: { label: '正常上报', description: '新鲜度窗口内持续有数据' },
        stale: { label: '数据中断', description: '曾上报但已超过新鲜度窗口' },
        todayMessages: { label: '今日接收', description: '已可靠写入遥测库的报文' }
      },
      attention: {
        title: '需要关注',
        count: '{count} 台设备',
        column: { device: '设备信息', status: '当前状态', lastReported: '最后上报' },
        empty: '当前没有异常设备',
        emptyHint: '如发现未上报的设备，可前往设备台账进行排查',
        unavailable: '异常状态暂不可确认',
        unavailableHint: '运行数据恢复后将自动显示需要关注的设备',
        lastSeen: '最后上报：{time}',
        noTelemetry: '尚未收到遥测数据',
        state: { stale: '数据中断', neverReported: '从未上报' }
      },
      distribution: {
        title: '设备接入态势',
        reporting: '正常上报',
        stale: '数据中断',
        neverReported: '从未上报',
        freshness: '判定口径：最近 {seconds} 秒内收到数据视为正常上报。',
        empty: '暂无可计算的设备上报状态'
      },
      telemetry: {
        title: '最近 24 小时接收情况',
        latest: '最近一条：{time}',
        empty: '暂无数据',
        zero: '最近 24 小时未收到遥测报文',
        emptySummary: '最近 24 小时暂无遥测数据',
        unavailableSummary: '最近 24 小时遥测数据暂不可用',
        summary: '最近 24 小时遥测报文总计 {total} 条，峰值每小时 {peak} 条'
      },
      quick: {
        title: '常用操作',
        monitor: '进入实时监控',
        monitorDescription: '查看实时数据与设备状态',
        devices: '查看设备台账',
        devicesDescription: '管理设备与接入配置',
        commands: '打开命令中心',
        commandsDescription: '下发与查看命令记录',
        empty: '当前账号没有可用的生产操作入口'
      }
    },
    operations: {
      title: '运行监控',
      subtitle: '查看平台服务、消息中枢和存储依赖的当前状态与历史变化。',
      checkedAt: '检查于 {time}',
      generatedAt: '指标更新于 {time}',
      summary: '{healthy} 项正常 · {down} 项异常 · {unknown} 项未知',
      latency: '{value} ms',
      notAvailable: '—',
      loadFailed: '运行状态检查失败',
      loadFailedDescription: '无法完成本次检查，请根据链路编号查询服务日志。',
      historyLoadFailed: '历史运行指标加载失败',
      trendLoadFailed: '运行趋势加载失败',
      historyUnavailableTitle: '历史监控暂不可用',
      historyUnavailableDescription: '当前未连接 Prometheus，仍可执行一次即时检查确认依赖状态。',
      onDemandNote: '页面只在进入或点击刷新时执行检查，不会定时扫描数据库或依赖服务。',
      empty: '当前部署没有注册此类检查项',
      historyTitle: '运行状态',
      immediateTitle: '即时检查结果',
      checking: '检查中',
      checkProgress: '{completed}/{total} 项已完成',
      trendTitle: '{item} · {range}趋势',
      trendLatency: '探测耗时',
      trendEmpty: '所选范围没有可展示的历史样本',
      trendAriaLabel: '{item} 运行状态与耗时趋势',
      action: { checkNow: '立即检查', viewTrend: '查看趋势' },
      filter: { search: '搜索检查项', status: '全部状态', category: '全部分类' },
      column: {
        status: '状态',
        item: '检查项',
        category: '分类',
        availability: '历史可用率',
        p95Latency: 'P95 耗时',
        lastChanged: '最近变化',
        action: '操作'
      },
      range: { hour: '1 小时', sixHours: '6 小时', day: '24 小时', week: '7 天', fifteenDays: '15 天' },
      status: { up: '正常', degraded: '部分异常', down: '异常', unknown: '未知' },
      category: { service: '平台服务', message: '消息中枢', storage: '存储与缓存' },
      item: {
        mineServer: '管理服务',
        mineGateway: '设备接入网关',
        mineTelemetry: '遥测处理服务',
        rabbitMq: 'RabbitMQ',
        postgres: '业务 PostgreSQL',
        redis: 'Redis',
        timescale: 'TimescaleDB'
      },
      detail: {
        serviceUp: '服务响应正常',
        rabbitUp: '管理接口响应正常',
        connectionUp: '连接正常',
        connectionDown: '连接异常',
        notEnabled: '当前部署未启用此检查',
        healthFailed: '服务健康检查未通过',
        checkFailed: '探测未完成，请检查服务日志',
        checkTimeout: '探测超时',
        checkInterrupted: '探测被中断'
      }
    },
    users: {
      title: '用户管理',
      listTitle: '用户列表',
      subtitle: '维护矿区管理端账号、启停状态和角色分配。用户密码不会在页面回显。',
      search: '搜索登录名或显示名称',
      neverLoggedIn: '从未登录',
      column: {
        username: '登录名',
        displayName: '显示名称',
        status: '状态',
        passwordChange: '首次改密',
        lastLogin: '最近登录'
      },
      passwordChange: { pending: '待完成', completed: '已完成' },
      action: {
        create: '创建用户',
        assignRole: '分配角色',
        resetPassword: '重置密码',
        saveRole: '保存角色',
        confirmReset: '确认重置'
      },
      validation: {
        displayName: '请输入显示名称',
        createRequired: '请填写登录名和至少 12 位临时密码',
        temporaryPassword: '临时密码至少 12 位'
      },
      message: {
        updated: '用户已更新',
        created: '用户已创建',
        rolesUpdated: '用户角色已更新',
        passwordReset: '临时密码已重置，用户旧会话已失效'
      },
      modal: { edit: '编辑用户', create: '创建用户', assignRole: '分配用户角色', resetPassword: '重置临时密码' },
      field: { username: '登录名', displayName: '显示名称', status: '状态', temporaryPassword: '临时密码' },
      tip: {
        assignRole: '正在为 {name} 分配角色，保存后下一次权限校验立即生效。',
        resetPassword: '重置后 {name} 的旧会话立即失效，下次登录必须修改密码。'
      },
      placeholder: { role: '请选择角色', temporaryPassword: '输入 12～72 位临时密码' }
    },
    roles: {
      title: '角色与权限',
      listTitle: '角色列表',
      subtitle: '角色编码发布后保持不变；权限节点由平台版本维护，管理员只负责勾选授权。',
      search: '搜索角色编码或名称',
      permissionCount: '{count} 项',
      confirmDelete: '确认删除角色“{name}”吗？',
      column: { code: '角色编码', name: '角色名称', status: '状态', description: '说明', permissions: '授权节点' },
      action: { create: '创建角色', permissions: '权限配置', publishPermissions: '发布权限' },
      validation: { required: '请填写角色编码和名称' },
      message: {
        updated: '角色已更新',
        created: '角色已创建',
        deleted: '角色已删除',
        permissionsUpdated: '角色权限已更新，现有会话下一次请求即生效'
      },
      modal: { edit: '编辑角色', create: '创建角色', permissions: '角色权限配置' },
      field: { code: '角色编码', name: '角色名称', status: '状态', description: '角色说明' },
      placeholder: { code: '例如 DISPATCHER' },
      tip: { permissions: '正在配置 {name}。目录、页面和按钮节点均可授权；按钮节点会显示稳定权限码。' }
    },
    menus: {
      title: '菜单管理',
      subtitle: '维护导航目录和页面；按钮权限在对应页面中独立配置，发布后无需重启。',
      listTitle: '菜单列表',
      root: '根目录',
      total: '共 {count} 条',
      buttonPermissions: '按钮权限',
      buttonPermissionTip: '按钮权限不显示在菜单主列表中，只控制页面操作和后端接口访问。',
      column: {
        id: 'ID',
        name: '菜单名称',
        type: '菜单类型',
        icon: '图标',
        path: '路由路径',
        permission: '权限码',
        status: '菜单状态',
        visible: '隐藏菜单',
        parent: '层级路径',
        sort: '排序'
      },
      type: { directory: '目录', menu: '页面', button: '按钮' },
      action: {
        create: '新增菜单',
        batchDelete: '批量删除（{count}）',
        refresh: '刷新',
        columns: '列设置',
        addButton: '新增按钮权限'
      },
      filter: {
        name: '菜单名称',
        namePlaceholder: '搜索菜单名称',
        type: '菜单类型',
        allTypes: '全部类型',
        status: '状态',
        allStatuses: '全部状态'
      },
      modal: { create: '新增菜单', edit: '编辑菜单' },
      iconType: { iconify: 'Iconify 图标', local: '本地图标' },
      iconPicker: { choose: '请选择图标', search: '搜索图标名称', empty: '没有匹配的图标' },
      field: {
        name: '节点名称',
        type: '节点类型',
        parent: '上级节点',
        path: '路由地址',
        component: '页面组件',
        permission: '权限码',
        iconType: '图标类型',
        icon: '图标键',
        sort: '排序',
        visible: '导航可见',
        status: '状态',
        i18nKey: '国际化资源键'
      },
      help: {
        label: '查看字段说明',
        name: '显示在侧边栏、菜单树和角色授权中的名称，应简短并能区分同级节点。',
        type: '目录用于组织页面；页面对应一个可访问路由；按钮只表示接口或页面操作权限，不生成路由。',
        parent: '目录可作为根节点；页面只能放在目录下；按钮权限只能放在页面下。',
        sort: '同一上级节点下按数字从小到大排列，建议以 10 为间隔，便于后续插入。',
        path: '页面的前端访问地址，必须以 / 开头，例如 /system/menus，并在全平台保持唯一。',
        component: '从前端编译期注册的组件白名单中选择，不能填写任意文件路径。',
        permission: '与后端 Sa-Token 权限注解及前端按钮权限一致，例如 system:menu:view；目录不填写。',
        iconType: 'Iconify 图标适合平台通用导航；本地图标来自项目内置 SVG 资源。切换类型会清空原选择。',
        icon: '从图标库中选择侧边导航图标；按钮节点通常无需图标，留空时使用平台默认展示。',
        i18nKey: '对应中英文资源中的稳定键，例如 mine.menu.menus；留空时使用节点名称。',
        status: '停用后该节点不会进入用户的导航和授权结果，子节点也将无法正常访问。',
        visible: '只控制是否出现在侧边导航，不等于权限停用；隐藏页面仍可通过路由和权限访问。',
        buttonName: '显示在角色授权和操作审计中的按钮名称，例如“创建产品”或“下发命令”。',
        buttonPermission: '必须与后端 Sa-Token 注解及前端操作权限完全一致，例如 device:product:create。'
      },
      validation: {
        required: '请完整填写当前菜单类型要求的配置',
        buttonRequired: '请填写按钮名称和权限码'
      },
      confirmDelete: '确认删除菜单“{name}”吗？',
      confirmBatchDelete: '确认删除选中的 {count} 个菜单吗？该操作会整批校验，失败时不会删除任何菜单。',
      confirmDeleteButton: '确认删除按钮权限“{name}”吗？',
      message: {
        created: '菜单已创建',
        updated: '菜单已更新',
        deleted: '菜单已删除',
        batchDeleted: '已删除 {count} 个菜单',
        buttonCreated: '按钮权限已创建',
        buttonUpdated: '按钮权限已更新',
        buttonDeleted: '按钮权限已删除'
      }
    },
    dictionaries: {
      title: '通用字典管理',
      subtitle: '维护状态、类别等低频枚举；设备字段、物模型和协议映射不放入通用字典。',
      typeList: '字典类型',
      selectType: '选择左侧字典类型查看数据项',
      builtIn: '系统内置',
      column: {
        typeName: '类型名称',
        code: '稳定编码',
        status: '状态',
        labelZh: '中文标签',
        value: '字典值',
        semantic: '语义样式',
        sort: '排序',
        default: '默认'
      },
      action: { createType: '新增字典类型', createItem: '新增数据项', items: '数据项' },
      modal: { createType: '新增字典类型', editType: '编辑字典类型', createItem: '新增字典项', editItem: '编辑字典项' },
      field: {
        code: '稳定编码',
        name: '类型名称',
        status: '状态',
        description: '用途说明',
        value: '稳定值',
        labelZh: '中文标签',
        labelEn: '英文标签',
        semantic: '主题语义',
        sort: '排序',
        default: '设为默认项'
      },
      help: {
        code: '业务接口和前端通过此编码读取整组字典。必须以小写字母开头，长度为 3～64 位，后续只能使用小写字母、数字和下划线；创建后不可修改。',
        name: '显示在字典管理页面，帮助管理员识别这组字典；可按业务称谓调整。',
        status: '停用后不会继续作为业务页面的可选值提供，已经保存的历史数据不会被改写。',
        description: '说明这组字典的适用范围和维护约束，仅供管理员查看，不参与业务判断。',
        value: '接口提交和数据库保存的稳定业务值。创建后不可修改，避免历史数据含义发生变化。',
        labelZh: '中文界面展示给用户的名称，应简短、明确，并与业务术语保持一致。',
        labelEn: '英文界面优先展示此名称；留空时回退到中文标签。',
        semantic: '控制标签的主题语义颜色，只影响视觉提示，不改变字典值或业务状态。',
        sort: '同一字典类型内按数字从小到大排列，建议以 10 为间隔，便于后续插入。',
        default: '每个字典类型只能有一个默认项；设为默认会取消原默认项。具体表单是否自动选中仍由业务页面决定。'
      },
      semantic: { default: '默认', primary: '主要', info: '信息', success: '成功', warning: '警告', error: '错误' },
      validation: { required: '请填写必填字段' },
      confirmDeleteType: '确认删除字典类型“{name}”吗？只有空的非内置类型可以删除。',
      confirmDeleteItem: '确认删除字典项“{label}”吗？'
    },
    products: {
      title: '产品管理',
      listTitle: '产品列表',
      subtitle: '按设备族维护稳定产品编码，为后续物模型、协议和策略发布提供边界。',
      search: '搜索产品名称或编码',
      empty: '先创建产品，再接入设备',
      emptyDescription: '产品用于归集同类设备。产品编码发布后不可修改。',
      column: { key: '产品编码', name: '产品名称', type: '设备类型', status: '状态', updatedAt: '更新时间' },
      action: { create: '创建产品', archive: '归档' },
      modal: { create: '创建产品', edit: '编辑产品' },
      field: { key: '产品编码', name: '产品名称', type: '设备类型', status: '状态' },
      placeholder: { key: '例如 haul-truck', type: '例如 VEHICLE' },
      status: { draft: '草稿', active: '已发布', archived: '已归档' },
      validation: { required: '请填写合法的产品编码、名称和设备类型' },
      message: { created: '产品已创建', updated: '产品已更新', archived: '产品已归档' },
      confirmArchive: '确认归档产品“{name}”吗？产品下的设备需先全部归档。'
    },
    devices: {
      title: '设备台账',
      listTitle: '设备列表',
      subtitle: '维护矿区设备身份与所属产品；设备编码用于接入鉴权和数据追踪。',
      search: '搜索设备名称或编码',
      empty: '当前筛选条件下没有设备',
      column: {
        code: '设备编码',
        name: '设备名称',
        product: '所属产品',
        status: '状态',
        accessStatus: '接入状态',
        lastReceivedAt: '最后上报',
        updatedAt: '更新时间'
      },
      action: { create: '创建设备', archive: '归档', connection: '接入配置' },
      modal: { create: '创建设备', edit: '编辑设备' },
      field: { code: '设备编码', name: '设备名称', product: '所属产品', status: '状态' },
      placeholder: { product: '请选择已发布产品', code: '例如 truck-001' },
      status: { enabled: '已启用', disabled: '已停用', archived: '已归档' },
      validation: { required: '请选择产品并填写合法的设备编码和名称' },
      message: { created: '设备已创建', updated: '设备已更新', archived: '设备已归档' },
      confirmArchive: '确认归档设备“{name}”吗？历史数据仍会保留。',
      connection: {
        title: '{name} · 设备接入',
        boundary: '端点测试只验证配置、DNS、TCP 和 TLS。设备主动接入时，仍需收到真实设备首帧才能确认上线。',
        configuration: '连接配置',
        diagnostics: '分层诊断',
        accessDiagnostics: '四层接入检查',
        accessNotTested: '手动检查端点可达、传输会话、有效首帧和数据新鲜度。',
        observedAt: '观测时间：{time}',
        notTested: '保存接入配置后，可以执行一次分层连通性诊断。',
        validation: '请填写合法的主机、端口和连接参数',
        saved: '设备接入配置已保存',
        saveBeforeTest: '请先保存设备接入配置',
        testCompleted: '接入端点诊断已完成',
        result: {
          testpassed: '设备接入链路诊断通过',
          testpartial: '网络端点检查通过，仍需设备首帧确认',
          testfailed: '设备接入端点检查失败，请查看失败阶段',
          configok: '接入配置校验通过',
          dnsok: 'DNS 解析成功',
          dnstargetblocked: '解析地址不在允许的矿区网段内',
          dnstimeout: 'DNS 解析超时',
          dnsinterrupted: 'DNS 解析被中断',
          dnsnotfound: '域名不存在或当前节点无法解析',
          dnsfailed: 'DNS 解析失败',
          tcpok: 'TCP 端口连接成功',
          tcptimeout: 'TCP 连接超时',
          tcprefused: '目标端口拒绝连接',
          tcpfailed: 'TCP 连接失败',
          tlsok: 'TLS 握手和证书主机名校验成功',
          tlstimeout: 'TLS 握手超时',
          tlsfailed: 'TLS 握手或证书校验失败',
          tlsdisabled: '当前接入配置未启用 TLS',
          dependencyskipped: '前置阶段未通过，本阶段未执行',
          firstmessagerequired: '仍需等待真实设备首帧确认上线',
          activeendpointconfirmed: '平台已成功连接设备端点',
          accessonline: '设备接入正常，数据持续上报',
          datastale: '已超过离线阈值未收到有效数据',
          transportonly: '传输会话已建立，仍在等待有效首帧',
          firstframewaiting: '尚未收到设备有效首帧',
          firstframeconfirmed: '已确认设备有效首帧',
          datafresh: '最近数据未超过离线阈值',
          accesscheckfailed: '设备接入检查失败',
          accessnotconfigured: '设备尚未配置接入地址',
          accessunknown: '暂时无法确认设备接入状态',
          activitysourceunavailable: '遥测活动数据源暂不可用',
          transportconnected: '已检测到 Broker 传输会话',
          transportwaiting: '尚未检测到 Broker 传输会话',
          transportsourceunavailable: '当前接入方式暂无会话探针',
          transportprobefailed: '传输会话探测暂时失败',
          credentialnotactive: '设备接入凭据未生效',
          probebusy: '当前诊断任务较多，请稍后重试',
          unknown: '诊断返回了未识别的结果'
        },
        action: { save: '保存配置', test: '测试端点', checkAccess: '检查接入状态' },
        accessStatus: {
          notconfigured: '未配置',
          waitingfordevice: '等待设备',
          transportonly: '仅传输连接',
          online: '在线',
          stale: '数据超时',
          failed: '接入失败',
          unknown: '未知'
        },
        accessLayer: { endpoint: '端点可达', transport: '传输会话', activity: '有效首帧', data: '数据新鲜度' },
        accessStageStatus: { passed: '通过', failed: '失败', waiting: '等待中', unknown: '未知', skipped: '未执行' },
        field: {
          mode: '接入方式',
          host: '主机或域名',
          port: '端口',
          tls: '启用 TLS 校验',
          tlsServerName: 'TLS 服务名称',
          timeout: '连接超时（毫秒）',
          keepAlive: '保活间隔（秒）',
          offlineThreshold: '离线阈值（秒）'
        },
        placeholder: { host: '例如 mqtt.mine.local', tlsServerName: '为空时使用主机名' },
        mode: {
          devicetoplatformmqtt: '设备主动连接平台 MQTT',
          devicetoplatformtcp: '设备主动连接平台 TCP',
          platformtodevicetcp: '平台主动连接设备 TCP'
        },
        presentation: {
          devicetoplatformmqtt: {
            description: '设备将主动连接平台 MQTT Broker；端点通过后仍需 Broker 会话和有效首帧确认上线。',
            hostLabel: 'Broker 地址',
            hostPlaceholder: '例如 mqtt.mine.local',
            portLabel: 'MQTT 端口',
            tlsLabel: '启用 Broker TLS 校验',
            tlsServerNameLabel: 'Broker TLS 服务名称',
            timeoutLabel: '端点测试超时（毫秒）',
            keepAliveLabel: 'MQTT KeepAlive（秒）'
          },
          devicetoplatformtcp: {
            description: '设备将主动连接平台 TCP 监听地址；端点通过后仍需真实 TCP 会话和有效首帧确认上线。',
            hostLabel: '平台 TCP 监听地址',
            hostPlaceholder: '例如 tcp.mine.local',
            portLabel: '平台监听端口',
            tlsLabel: '启用 TCP TLS 校验',
            tlsServerNameLabel: '平台 TLS 服务名称',
            timeoutLabel: '端点测试超时（毫秒）',
            keepAliveLabel: '应用心跳间隔（秒）'
          },
          platformtodevicetcp: {
            description: '平台将主动连接设备或边缘网关 TCP 端点；请确认设备网段、防火墙和证书允许平台访问。',
            hostLabel: '设备 IP 或域名',
            hostPlaceholder: '例如 10.20.30.40 或 edge.mine.local',
            portLabel: '设备 TCP 端口',
            tlsLabel: '启用设备端 TLS 校验',
            tlsServerNameLabel: '设备 TLS 服务名称',
            timeoutLabel: '连接设备超时（毫秒）',
            keepAliveLabel: '应用心跳间隔（秒）'
          }
        },
        stage: {
          config: '配置校验',
          dns: 'DNS 解析',
          tcp: 'TCP 连接',
          tls: 'TLS 握手',
          deviceconfirmation: '设备首帧确认'
        },
        testStatus: {
          notTested: '未测试',
          passed: '通过',
          partial: '部分通过',
          failed: '失败',
          skipped: '待确认'
        }
      }
    },
    strategy: {
      title: '策略工作台',
      subtitle: '管理产品、设备组和单设备三级策略，发布前验证范围，发布后解释每台设备的最终有效值。',
      type: { online_status: '在线状态策略', data_quality: '数据质量策略' },
      productionHelp: {
        online: '策略发布会立即影响设备在线状态判定。请先核对目标范围、最终阈值和冲突证据，并填写可审计的业务原因。',
        quality:
          '策略发布会立即影响遥测质量判定和质量证据。请先核对时间窗口、属性约束、最终来源，并填写可审计的业务原因。'
      },
      section: {
        context: '配置上下文',
        groups: '设备组与优先级',
        members: '设备组成员',
        draft: '策略草稿',
        publish: '发布控制',
        history: '不可变发布历史',
        effective: '设备最终有效策略',
        qualityRules: '属性质量规则'
      },
      field: {
        strategyType: '策略类型',
        product: '产品',
        device: '验证设备',
        group: '设备组',
        groupKey: '设备组编码',
        groupName: '设备组名称',
        priority: '策略优先级',
        description: '说明',
        threshold: '离线判定阈值',
        scope: '发布范围',
        reason: '业务原因',
        members: '当前成员',
        version: '策略版本',
        status: '状态',
        hash: '内容哈希',
        release: '发布记录',
        source: '最终值来源',
        staleAfterSeconds: '陈旧阈值',
        futureToleranceSeconds: '未来时间容差',
        propertyId: '物模型属性标识',
        required: '本帧必填',
        minimum: '质量下限',
        maximum: '质量上限'
      },
      action: {
        newGroup: '新建设备组',
        editGroup: '编辑设备组',
        saveGroup: '保存设备组',
        archiveGroup: '归档设备组',
        saveMembers: '保存成员关系',
        newDraft: '新建草稿',
        saveDraft: '保存草稿',
        publish: '发布策略',
        rollback: '回滚到此发布',
        refresh: '刷新有效值',
        addQualityRule: '添加属性规则',
        removeQualityRule: '移除'
      },
      status: {
        draft: '草稿',
        published: '已发布',
        valid: '有效',
        invalid: '无效',
        active: '生效中',
        archived: '已归档',
        publish: '发布',
        rollback: '回滚'
      },
      scope: { product: '产品默认', group: '设备组覆盖', device: '单设备覆盖' },
      source: {
        product: '产品默认',
        group: '设备组覆盖',
        device: '单设备覆盖',
        unknown: '来源未知',
        priority: '优先级 {priority}',
        release: '发布 {release}'
      },
      empty: {
        products: '暂无可配置的有效产品',
        groups: '当前产品尚未建设备组',
        devices: '当前产品暂无有效设备',
        versions: '尚未创建在线状态策略草稿',
        releases: '尚无策略发布记录',
        effective: '选择设备后查看最终有效策略',
        members: '该设备组暂无成员',
        qualityRules: '尚未添加属性规则；至少配置一个时间窗口或属性规则。'
      },
      placeholder: {
        enter: '请输入{field}',
        select: '请选择{field}',
        propertyId: '例如 engine.rpm'
      },
      validation: {
        required: '请完整填写必填项',
        threshold: '离线判定阈值必须在 30 至 86400 秒之间',
        scope: '请选择与发布范围匹配的目标',
        reason: '业务原因至少填写 4 个字符',
        draft: '请先选择一个草稿版本',
        stale: '陈旧阈值必须在 1 至 604800 秒之间',
        future: '未来时间容差必须在 0 至 3600 秒之间',
        property: '属性标识格式不正确、存在重复项，或未填写属性标识',
        range: '同一属性的质量下限不能大于上限',
        qualityRequired: '至少配置一个时间窗口或属性质量规则'
      },
      message: {
        groupCreated: '设备组已创建',
        groupUpdated: '设备组已更新',
        groupArchived: '设备组已归档',
        membersUpdated: '成员关系已更新',
        draftCreated: '策略草稿已创建',
        draftUpdated: '策略草稿已保存',
        published: '策略已发布并开始生效',
        rolledBack: '已创建新的回滚发布'
      },
      confirm: {
        archiveGroup: '确认归档此设备组？当前成员关系将结束，历史发布事实仍会保留。',
        rollback: '确认基于此历史发布创建新的回滚发布？'
      },
      error: {
        title: '策略操作失败',
        conflictTitle: '策略存在冲突',
        invalidTitle: '最终策略无效',
        code: '错误码：{code}',
        details: '冲突证据：{details}'
      },
      help: {
        groupPriority: '数值越大优先级越高；同优先级设备组对同一字段给出不同值时将阻止发布。',
        scope: '产品默认会覆盖全部设备；设备组和单设备范围需要先在左侧选中目标。',
        members: '成员只能来自当前产品，保存时使用乐观锁防止覆盖其他管理员的修改。',
        readonly: '当前账号可查看策略，但没有维护或发布权限。',
        qualityTemplate: '时间窗口适用于整帧遥测；属性规则用于判定缺失值和数值越界。未配置的规则不会参与判定。',
        qualityRules: '属性标识应与已发布物模型一致；必填、下限和上限会生成可审计的逐属性质量证据。'
      },
      text: {
        seconds: '秒',
        enabled: '启用',
        disabled: '停用',
        version: '版本 v{version}',
        sequence: '发布序号 #{sequence}',
        memberCount: '{count} 台设备',
        priority: '优先级 {priority}',
        onlineSummary: '离线阈值 {seconds} 秒',
        qualitySummary: '{count} 个属性规则'
      },
      qualityField: {
        required: '必填 · {property}',
        minimum: '下限 · {property}',
        maximum: '上限 · {property}'
      }
    },
    models: {
      title: '物模型与协议映射',
      subtitle: '用统一字段屏蔽不同厂商报文差异；草稿验证通过后发布，现场服务无需重启。',
      product: '设备产品',
      publishHelp: '先发布物模型，再配置厂商 MQTT/TCP 字段映射。发布只切换不可变版本，不会修改历史版本。',
      tab: { model: '统一物模型', protocol: '协议字段映射', release: '发布控制' },
      version: '物模型 v{version}',
      selectVersion: '选择物模型版本',
      selectProtocol: '选择协议配置版本',
      description: '本次变更说明',
      modelFields: '统一字段定义',
      protocolFields: '厂商原始字段映射',
      status: { draft: '草稿', published: '已发布', retired: '已退役' },
      action: {
        newModel: '新建物模型草稿',
        addField: '新增字段',
        publish: '发布版本',
        newProtocol: '新建协议草稿',
        addMapping: '新增映射'
      },
      column: {
        identifier: '字段标识',
        name: '字段名称',
        dataType: '数据类型',
        unit: '单位',
        access: '读写模式',
        source: '厂商字段路径',
        sourceType: '原始类型',
        target: '统一字段',
        transform: '转换方式'
      },
      modal: { field: '新增物模型字段', protocol: '新建协议配置草稿', mapping: '新增字段映射' },
      field: {
        identifier: '字段标识',
        nameZh: '中文名称',
        dataType: '数据类型',
        unit: '工程单位',
        access: '读写模式',
        required: '必填字段',
        minimum: '最小值',
        maximum: '最大值',
        profileKey: '协议配置编码',
        profileName: '协议配置名称',
        protocolType: '协议类型',
        messageType: '消息类型',
        topicOrFrame: 'MQTT Topic 模板 / TCP 帧类型',
        sourcePath: '原始字段路径',
        sourceType: '原始数据类型',
        target: '目标统一字段',
        transform: '转换方式',
        scale: '缩放系数',
        offset: '偏移量',
        expression: '受限表达式'
      },
      dataType: {
        integer: '整数',
        decimal: '小数',
        boolean: '布尔',
        string: '字符串',
        enum: '枚举',
        datetime: '时间',
        geo_point: '地理坐标'
      },
      access: { read: '只读', write: '只写', read_write: '读写' },
      protocol: { mqtt_json: 'MQTT JSON', mqtt_binary: 'MQTT 二进制', tcp_binary: 'TCP 二进制' },
      messageType: { telemetry: '遥测', state: '状态', event: '事件', command_reply: '命令回复' },
      transform: {
        none: '不转换',
        scale: '缩放',
        offset: '偏移',
        scale_offset: '缩放并偏移',
        enum: '枚举映射',
        expression: '受限表达式'
      },
      validation: { required: '请填写字段标识、名称和映射目标' },
      confirmPublishModel: '确认发布当前物模型吗？旧发布版本会自动退役。',
      confirmPublishProtocol: '确认发布当前协议映射吗？系统会校验所有目标字段。',
      samples: {
        title: '协议黄金样本',
        subtitle: '用真实厂商报文锁定解析行为；发布前由 Gateway 同源引擎逐条回放。',
        unsaved: '样本草稿尚未保存。保存后才能运行发布门禁。',
        empty: '添加第一条真实报文，建立可重复验证的协议基线',
        editorTitle: '样本草稿',
        action: { add: '添加样本', save: '保存样本', run: '运行门禁', apply: '应用到草稿' },
        column: {
          name: '样本名称',
          expectation: '预期行为',
          payload: '原始报文',
          expected: '期望结果',
          result: '回放结果',
          errorCode: '实际错误码'
        },
        expectation: { success: '应解析成功', failure: '应安全拒绝' },
        result: { passed: '通过', failed: '未通过' },
        field: {
          name: '样本名称',
          sortOrder: '展示顺序',
          rawPayload: '原始 JSON 报文',
          expectedProperties: '期望统一属性 JSON',
          expectedSuccess: '预期解析成功',
          expectedErrorCode: '期望错误码'
        },
        gate: { passed: '发布门禁已通过', failed: '发布门禁未通过', summary: '{passed} / {total}' },
        validation: {
          invalid: '请填写样本名称、合法 JSON 和失败样本的期望错误码',
          empty: '至少保留一条黄金样本',
          saveBeforeTest: '请先保存样本草稿，再运行发布门禁'
        },
        message: { saved: '黄金样本已保存' }
      },
      releases: {
        permissionMissing: '当前账号没有查看配置发布历史的权限。',
        publishTitle: '创建配置发布',
        publishSubtitle: '服务端会重新运行全部黄金样本，成功后生成不可变配置包。',
        gateReady: '所选协议已发布；提交时将再次执行黄金样本门禁。',
        protocolNotPublished: '请先发布所选协议版本，再创建运行配置。',
        publishReason: '填写本次发布原因，例如接入的设备批次或变更单号',
        publishAction: '验证并发布配置',
        evidenceTitle: '发布证据链',
        refresh: '刷新状态',
        refreshedAt: '最后刷新：{time}',
        notRefreshed: '尚未读取发布历史',
        empty: '当前产品还没有配置发布',
        selectProtocol: '选择一个协议版本后查看发布控制',
        sequence: '发布序号 #{sequence}',
        detailTitle: '运行节点落地结果',
        nodesTitle: '节点加载状态',
        nodesCount: '{count} 个节点已回报',
        nodesEmpty: '配置事件已创建，等待运行节点回报',
        rollbackReason: '填写回滚原因，回滚会创建更高序号的新发布',
        rollbackAction: '回滚到此配置',
        rollbackConfirm: '确认回滚',
        rollbackWarning: '系统将复用当前历史配置包创建一个新的发布事实，不会修改历史。',
        column: { sequence: '发布序号', action: '动作', reason: '变更原因', createdAt: '创建时间' },
        action: { publish: '发布', rollback: '回滚' },
        field: { releaseId: '发布标识', contentHash: '内容哈希', reason: '变更原因' },
        node: { id: '节点', type: '节点类型', status: '加载状态', hash: '实际哈希', updatedAt: '最后回报' },
        nodeType: { gateway: '接入网关', telemetry: '遥测服务', server: '管理服务' },
        nodeStatus: { pending: '等待加载', loaded: '已加载', failed: '加载失败' },
        validation: { reason: '请填写发布原因', rollbackReason: '请填写回滚原因', publishProtocol: '请先发布协议版本' },
        message: { published: '配置发布已创建，正在等待运行节点加载', rolledBack: '回滚发布已创建' }
      }
    },
    monitor: {
      title: '设备实时监控',
      subtitle: '按设备订阅所需字段；正常运行只接收实时推送，弱网重连或序号断档时才同步快照。',
      selectDevice: '选择设备',
      selectPlaceholder: '请选择需要监控的设备',
      propertyTitle: '监控字段',
      propertyHelp: '字段来自物模型或协议映射；当前可直接增删字段，适配不同厂商设备。',
      noDevice: '选择设备后查看实时数据',
      noDeviceDescription: '平台会先读取一次最新快照，再建立 WebSocket 订阅。',
      connected: '实时订阅中',
      snapshot: '已同步最新快照',
      updatedAt: '数据更新：{time}',
      waiting: '等待设备上报',
      quality: {
        good: '数据可信',
        uncertain: '数据存疑',
        bad: '数据异常',
        timeUntrusted: '设备时间不可信',
        unknown: '等待质量信息'
      },
      property: {
        engineRpm: '发动机转速',
        coolantTemperature: '冷却液温度',
        vehicleSpeed: '运行速度',
        fuelLevel: '燃油液位'
      }
    },
    commands: {
      title: '命令中心',
      listTitle: '命令列表',
      subtitle: '下发已发布的低风险设备命令，跟踪消息从排队到设备执行的可靠状态。',
      empty: '当前筛选条件下没有命令',
      column: { device: '设备', template: '命令模板', status: '状态', createdAt: '下发时间' },
      action: {
        create: '下发命令',
        send: '准备下发',
        confirmSend: '确认下发',
        detail: '详情',
        cancel: '取消',
        confirmCancel: '确认取消'
      },
      modal: { create: '下发设备命令', cancel: '取消待发送命令' },
      field: {
        id: '命令编号',
        device: '目标设备',
        template: '命令模板',
        status: '当前状态',
        ttl: '排队存活时间（秒）',
        timeout: '设备回复超时（秒）',
        parameters: '命令参数',
        reply: '设备回复（已脱敏）',
        errorCode: '错误码',
        reason: '下发原因',
        workOrderNo: '作业单号',
        cancelReason: '取消原因'
      },
      placeholder: { device: '请选择设备', status: '全部命令状态', template: '全部命令模板' },
      validation: {
        required: '请选择目标设备和命令模板',
        unsupportedParameters: '当前页面只允许下发已发布的低风险模板',
        reasonRequired: '该命令会改变设备配置，请填写现场下发原因',
        parametersRequired: '请填写命令模板要求的参数',
        cancelReasonRequired: '请填写取消原因'
      },
      confirm: {
        title: '确认下发命令',
        content: '即将向“{device}”下发“{template}”。请确认现场设备和作业条件安全。'
      },
      message: { created: '命令已进入可靠下发队列', cancelled: '命令已在发送前取消' },
      template: { queryStatus: '状态查询', setReportInterval: '设置上报周期', requestLocation: '立即请求位置' },
      parameter: { intervalSeconds: '上报周期（秒）' },
      detail: { title: '命令详情', timeline: '状态时间线' },
      timeline: { queued: '已排队', sent: '已发送', received: '设备已接收' },
      status: {
        created: '已创建',
        queued: '已排队',
        sent: '已发送',
        device_received: '设备已接收',
        succeeded: '执行成功',
        refused: '设备拒绝',
        failed: '执行失败',
        expired: '排队过期',
        timeout: '回复超时',
        cancelled: '已取消'
      }
    },
    fallback: {
      unconfigured: '未配置',
      title: '功能模块尚未接入',
      description: '当前菜单配置已生效，但组件标识“{componentKey}”没有对应的本地页面。',
      help: '请联系平台开发人员完成组件注册后重新发布前端版本。'
    }
  },
  request: {
    logout: '请求失败后登出用户',
    logoutMsg: '用户状态失效，请重新登录',
    logoutWithModal: '请求失败后弹出模态框再登出用户',
    logoutWithModalMsg: '用户状态失效，请重新登录',
    refreshToken: '请求的token已过期，刷新token',
    tokenExpired: 'token已过期'
  },
  theme: {
    themeDrawerTitle: '主题配置',
    tabs: {
      appearance: '外观',
      layout: '布局',
      general: '通用',
      preset: '预设'
    },
    appearance: {
      themeSchema: {
        title: '主题模式',
        light: '亮色模式',
        dark: '暗黑模式',
        auto: '跟随系统'
      },
      grayscale: '灰色模式',
      colourWeakness: '色弱模式',
      themeColor: {
        title: '主题颜色',
        primary: '主色',
        info: '信息色',
        success: '成功色',
        warning: '警告色',
        error: '错误色',
        followPrimary: '跟随主色'
      },
      themeRadius: {
        title: '主题圆角'
      },
      recommendColor: '应用推荐算法的颜色',
      recommendColorDesc: '推荐颜色的算法参照',
      preset: {
        title: '主题预设',
        apply: '应用',
        applySuccess: '预设应用成功',
        default: {
          name: '默认预设',
          desc: 'Soybean 默认主题预设'
        },
        dark: {
          name: '暗色预设',
          desc: '适用于夜间使用的暗色主题预设'
        },
        compact: {
          name: '紧凑型',
          desc: '适用于小屏幕的紧凑布局预设'
        },
        azir: {
          name: 'Azir的预设',
          desc: '是 Azir 比较喜欢的莫兰迪色系冷淡风'
        }
      }
    },
    layout: {
      layoutMode: {
        title: '布局模式',
        vertical: '左侧菜单模式',
        'vertical-mix': '左侧菜单混合模式',
        'vertical-hybrid-header-first': '左侧混合-顶部优先',
        horizontal: '顶部菜单模式',
        'top-hybrid-sidebar-first': '顶部混合-侧边优先',
        'top-hybrid-header-first': '顶部混合-顶部优先',
        vertical_detail: '左侧菜单布局，菜单在左，内容在右。',
        'vertical-mix_detail': '左侧双菜单布局，一级菜单在左侧深色区域，二级菜单在左侧浅色区域。',
        'vertical-hybrid-header-first_detail':
          '左侧混合布局，一级菜单在顶部，二级菜单在左侧深色区域，三级菜单在左侧浅色区域。',
        horizontal_detail: '顶部菜单布局，菜单在顶部，内容在下方。',
        'top-hybrid-sidebar-first_detail': '顶部混合布局，一级菜单在左侧，二级菜单在顶部。',
        'top-hybrid-header-first_detail': '顶部混合布局，一级菜单在顶部，二级菜单在左侧。'
      },
      tab: {
        title: '标签栏设置',
        visible: '显示标签栏',
        cache: '标签栏信息缓存',
        cacheTip: '离开页面后仍然保留标签栏信息',
        height: '标签栏高度',
        mode: {
          title: '标签栏风格',
          slider: '滑块风格',
          chrome: '谷歌风格',
          button: '按钮风格'
        },
        closeByMiddleClick: '鼠标中键关闭标签页',
        closeByMiddleClickTip: '启用后可以使用鼠标中键点击标签页进行关闭'
      },
      header: {
        title: '头部设置',
        height: '头部高度',
        breadcrumb: {
          visible: '显示面包屑',
          showIcon: '显示面包屑图标'
        }
      },
      sider: {
        title: '侧边栏设置',
        inverted: '深色侧边栏',
        width: '侧边栏宽度',
        collapsedWidth: '侧边栏折叠宽度',
        mixWidth: '混合布局侧边栏宽度',
        mixCollapsedWidth: '混合布局侧边栏折叠宽度',
        mixChildMenuWidth: '混合布局子菜单宽度',
        autoSelectFirstMenu: '自动选择第一个子菜单',
        autoSelectFirstMenuTip: '点击一级菜单时，自动选择并导航到第一个子菜单的最深层级'
      },
      footer: {
        title: '底部设置',
        visible: '显示底部',
        fixed: '固定底部',
        height: '底部高度',
        right: '底部居右'
      },
      content: {
        title: '内容区域设置',
        scrollMode: {
          title: '滚动模式',
          tip: '主题滚动仅 main 部分滚动，外层滚动可携带头部底部一起滚动',
          wrapper: '外层滚动',
          content: '主体滚动'
        },
        page: {
          animate: '页面切换动画',
          mode: {
            title: '页面切换动画类型',
            'fade-slide': '滑动',
            fade: '淡入淡出',
            'fade-bottom': '底部消退',
            'fade-scale': '缩放消退',
            'zoom-fade': '渐变',
            'zoom-out': '闪现',
            none: '无'
          }
        },
        fixedHeaderAndTab: '固定头部和标签栏'
      }
    },
    general: {
      title: '通用设置',
      watermark: {
        title: '水印设置',
        visible: '显示全屏水印',
        text: '自定义水印文本',
        enableUserName: '启用用户名水印',
        enableTime: '显示当前时间',
        timeFormat: '时间格式'
      },
      multilingual: {
        title: '多语言设置',
        visible: '显示多语言按钮'
      },
      globalSearch: {
        title: '全局搜索设置',
        visible: '显示全局搜索按钮'
      }
    },
    configOperation: {
      copyConfig: '复制配置',
      copySuccessMsg: '复制成功，请替换 src/theme/settings.ts 中的变量 themeSettings',
      resetConfig: '重置配置',
      resetSuccessMsg: '重置成功'
    }
  },
  route: {
    login: '登录',
    403: '无权限',
    404: '页面不存在',
    500: '服务器错误',
    'iframe-page': '外链页面',
    home: '首页'
  },
  page: {
    login: {
      common: {
        loginOrRegister: '登录 / 注册',
        userNamePlaceholder: '请输入用户名',
        phonePlaceholder: '请输入手机号',
        codePlaceholder: '请输入验证码',
        passwordPlaceholder: '请输入密码',
        confirmPasswordPlaceholder: '请再次输入密码',
        codeLogin: '验证码登录',
        confirm: '确定',
        back: '返回',
        validateSuccess: '验证成功',
        loginSuccess: '登录成功',
        welcomeBack: '欢迎回来，{userName} ！'
      },
      pwdLogin: {
        title: '密码登录',
        rememberMe: '记住我',
        forgetPassword: '忘记密码？',
        register: '注册账号',
        otherAccountLogin: '其他账号登录',
        otherLoginMode: '其他登录方式',
        superAdmin: '超级管理员',
        admin: '管理员',
        user: '普通用户'
      },
      codeLogin: {
        title: '验证码登录',
        getCode: '获取验证码',
        reGetCode: '{time}秒后重新获取',
        sendCodeSuccess: '验证码发送成功',
        imageCodePlaceholder: '请输入图片验证码'
      },
      register: {
        title: '注册账号',
        agreement: '我已经仔细阅读并接受',
        protocol: '《用户协议》',
        policy: '《隐私权政策》'
      },
      resetPwd: {
        title: '重置密码'
      },
      bindWeChat: {
        title: '绑定微信'
      }
    },
    home: {
      branchDesc:
        '为了方便大家开发和更新合并，我们对main分支的代码进行了精简，只保留了首页菜单，其余内容已移至example分支进行维护。预览地址显示的内容即为example分支的内容。',
      greeting: '早安，{userName}, 今天又是充满活力的一天!',
      weatherDesc: '今日多云转晴，20℃ - 25℃!',
      projectCount: '项目数',
      todo: '待办',
      message: '消息',
      downloadCount: '下载量',
      registerCount: '注册量',
      schedule: '作息安排',
      study: '学习',
      work: '工作',
      rest: '休息',
      entertainment: '娱乐',
      visitCount: '访问量',
      turnover: '成交额',
      dealCount: '成交量',
      projectNews: {
        title: '项目动态',
        moreNews: '更多动态',
        desc1: 'Soybean 在2021年5月28日创建了开源项目 soybean-admin!',
        desc2: 'Yanbowe 向 soybean-admin 提交了一个bug，多标签栏不会自适应。',
        desc3: 'Soybean 准备为 soybean-admin 的发布做充分的准备工作!',
        desc4: 'Soybean 正在忙于为soybean-admin写项目说明文档！',
        desc5: 'Soybean 刚才把工作台页面随便写了一些，凑合能看了！'
      },
      creativity: '创意'
    }
  },
  form: {
    required: '不能为空',
    userName: {
      required: '请输入用户名',
      invalid: '用户名格式不正确'
    },
    phone: {
      required: '请输入手机号',
      invalid: '手机号格式不正确'
    },
    pwd: {
      required: '请输入密码',
      invalid: '密码格式不正确，6-18位字符，包含字母、数字、下划线'
    },
    confirmPwd: {
      required: '请输入确认密码',
      invalid: '两次输入密码不一致'
    },
    code: {
      required: '请输入验证码',
      invalid: '验证码格式不正确'
    },
    email: {
      required: '请输入邮箱',
      invalid: '邮箱格式不正确'
    }
  },
  dropdown: {
    closeCurrent: '关闭',
    closeOther: '关闭其它',
    closeLeft: '关闭左侧',
    closeRight: '关闭右侧',
    closeAll: '关闭所有',
    pin: '固定标签',
    unpin: '取消固定'
  },
  icon: {
    themeConfig: '主题配置',
    themeSchema: '主题模式',
    lang: '切换语言',
    fullscreen: '全屏',
    fullscreenExit: '退出全屏',
    reload: '刷新页面',
    collapse: '折叠菜单',
    expand: '展开菜单',
    pin: '固定',
    unpin: '取消固定'
  },
  datatable: {
    itemCount: '共 {total} 条',
    fixed: {
      left: '左固定',
      right: '右固定',
      unFixed: '取消固定'
    }
  }
};

export default local;
