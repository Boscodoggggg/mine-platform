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
      device: '设备管理',
      products: '产品管理',
      devices: '设备台账',
      overview: '运行总览'
    },
    common: {
      action: '操作',
      edit: '编辑',
      delete: '删除',
      empty: '—',
      allStatus: '全部状态',
      search: '查询',
      reset: '重置',
      cancel: '取消',
      save: '保存',
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
      title: '矿区运行总览',
      welcome: '{name}，这里将汇总设备接入、数据链路与现场告警。',
      localEnvironment: '矿区本地环境',
      pendingMetrics: '待接入运行指标',
      ready: '已就绪',
      pending: '待建设',
      metric: {
        onlineDevices: { label: '在线设备', description: '等待设备台账接入' },
        todayTelemetry: { label: '今日遥测', description: '等待遥测服务接入' },
        activeAlarms: { label: '活动告警', description: '等待告警规则接入' },
        weakNetworkBacklog: { label: '弱网积压', description: '等待边缘节点接入' }
      },
      pipeline: {
        title: '采集链路状态',
        devices: '设备与车辆',
        gateway: '接入网关',
        broker: '消息中枢',
        telemetry: '遥测处理',
        note: '接入真实设备后，本区域显示吞吐、延迟、离线缓存和失败重试情况。'
      },
      readiness: {
        title: 'Phase 0 基础能力',
        authentication: '管理端身份认证',
        passwordChange: '首次登录强制改密',
        rabbitMq: 'RabbitMQ 双虚拟主机',
        deviceModel: '设备与物模型管理'
      }
    },
    users: {
      title: '用户管理',
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
