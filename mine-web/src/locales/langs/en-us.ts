const local: App.I18n.Schema = {
  system: {
    title: 'Smart Mine Data Platform',
    updateTitle: 'System Version Update Notification',
    updateContent: 'A new version of the system has been detected. Do you want to refresh the page immediately?',
    updateConfirm: 'Refresh immediately',
    updateCancel: 'Later'
  },
  common: {
    action: 'Action',
    add: 'Add',
    addSuccess: 'Add Success',
    backToHome: 'Back to home',
    batchDelete: 'Batch Delete',
    cancel: 'Cancel',
    close: 'Close',
    check: 'Check',
    selectAll: 'Select All',
    expandColumn: 'Expand Column',
    columnSetting: 'Column Setting',
    config: 'Config',
    confirm: 'Confirm',
    delete: 'Delete',
    deleteSuccess: 'Delete Success',
    confirmDelete: 'Are you sure you want to delete?',
    edit: 'Edit',
    warning: 'Warning',
    error: 'Error',
    index: 'Index',
    keywordSearch: 'Please enter keyword',
    logout: 'Logout',
    logoutConfirm: 'Are you sure you want to log out?',
    lookForward: 'Coming soon',
    modify: 'Modify',
    modifySuccess: 'Modify Success',
    noData: 'No Data',
    operate: 'Operate',
    pleaseCheckValue: 'Please check whether the value is valid',
    refresh: 'Refresh',
    reset: 'Reset',
    search: 'Search',
    switch: 'Switch',
    tip: 'Tip',
    trigger: 'Trigger',
    update: 'Update',
    updateSuccess: 'Update Success',
    userCenter: 'User Center',
    yesOrNo: {
      yes: 'Yes',
      no: 'No'
    }
  },
  mine: {
    menu: {
      system: 'System Management',
      users: 'User Management',
      roles: 'Role Management',
      device: 'Device Management',
      products: 'Product Management',
      devices: 'Device Registry',
      monitor: 'Device Monitoring',
      overview: 'Operations Overview'
    },
    common: {
      action: 'Actions',
      edit: 'Edit',
      delete: 'Delete',
      empty: '—',
      allStatus: 'All statuses',
      search: 'Search',
      reset: 'Reset',
      cancel: 'Cancel',
      save: 'Save',
      requestFailed: 'The request could not be processed. Please try again later.',
      traceId: 'Trace ID: {traceId}',
      operationFailed: 'The operation failed. Please try again later.',
      status: { enabled: 'Enabled', disabled: 'Disabled', locked: 'Locked' }
    },
    login: {
      brand: 'Smart Mine Data Platform',
      slogan: 'Connected Devices · Data Insights · Production Collaboration',
      title: { login: 'Welcome', password: 'Change Initial Password', complete: 'Password Changed' },
      description: {
        login: 'Sign in with your mine management account. Repeated failures trigger security controls.',
        password: 'This account still uses its initial password. Sign in again after changing it.',
        complete: 'The password was updated and the previous session was closed safely.'
      },
      field: {
        username: 'Username',
        password: 'Password',
        currentPassword: 'Current password',
        newPassword: 'New password',
        confirmPassword: 'Confirm new password'
      },
      placeholder: { username: 'Enter management account', password: 'Enter password' },
      validation: {
        usernameRequired: 'Enter a username',
        passwordRequired: 'Enter a password',
        currentPasswordRequired: 'Enter the current password',
        newPasswordRequired: 'Enter a new password',
        passwordTooShort: 'The new password must contain at least 12 characters',
        passwordTooLong: 'The UTF-8 encoded password must not exceed 72 bytes',
        confirmPasswordRequired: 'Enter the new password again',
        passwordMismatch: 'The new passwords do not match'
      },
      security: {
        title: 'Password requirements',
        description: 'Use at least 12 characters and no more than 72 UTF-8 bytes. Do not reuse the current password.'
      },
      action: {
        processing: 'Processing…',
        login: 'Sign In',
        changePassword: 'Change Password',
        restart: 'Sign In Again'
      },
      complete: {
        title: 'Password changed. Sign in again.',
        description: 'The current session was closed to invalidate all previous tokens.'
      },
      service: { local: 'On-premises service', help: 'Contact the mine system administrator if the connection fails' }
    },
    home: {
      title: 'Mine Operations Overview',
      welcome: '{name}, this page summarizes device access, data pipelines, and site alarms.',
      localEnvironment: 'On-premises environment',
      pendingMetrics: 'Metrics pending',
      ready: 'Ready',
      pending: 'Pending',
      metric: {
        onlineDevices: { label: 'Online Devices', description: 'Waiting for the device registry' },
        todayTelemetry: { label: 'Telemetry Today', description: 'Waiting for the telemetry service' },
        activeAlarms: { label: 'Active Alarms', description: 'Waiting for alarm rules' },
        weakNetworkBacklog: { label: 'Weak-network Backlog', description: 'Waiting for edge nodes' }
      },
      pipeline: {
        title: 'Collection Pipeline',
        devices: 'Devices and Vehicles',
        gateway: 'Access Gateway',
        broker: 'Message Broker',
        telemetry: 'Telemetry Processing',
        note: 'After real devices connect, this area shows throughput, latency, offline buffering, and retry status.'
      },
      readiness: {
        title: 'Phase 0 Foundation',
        authentication: 'Management Authentication',
        passwordChange: 'Forced Initial Password Change',
        rabbitMq: 'RabbitMQ Virtual Hosts',
        deviceModel: 'Device and Thing Model Management'
      }
    },
    users: {
      title: 'User Management',
      subtitle: 'Manage mine administration accounts, status, and role assignments. Passwords are never displayed.',
      search: 'Search username or display name',
      neverLoggedIn: 'Never signed in',
      column: {
        username: 'Username',
        displayName: 'Display Name',
        status: 'Status',
        passwordChange: 'Initial Password',
        lastLogin: 'Last Sign-in'
      },
      passwordChange: { pending: 'Pending', completed: 'Completed' },
      action: {
        create: 'Create User',
        assignRole: 'Assign Roles',
        resetPassword: 'Reset Password',
        saveRole: 'Save Roles',
        confirmReset: 'Confirm Reset'
      },
      validation: {
        displayName: 'Enter a display name',
        createRequired: 'Enter a username and a temporary password of at least 12 characters',
        temporaryPassword: 'The temporary password must contain at least 12 characters'
      },
      message: {
        updated: 'User updated',
        created: 'User created',
        rolesUpdated: 'User roles updated',
        passwordReset: 'Temporary password reset and previous sessions invalidated'
      },
      modal: {
        edit: 'Edit User',
        create: 'Create User',
        assignRole: 'Assign User Roles',
        resetPassword: 'Reset Temporary Password'
      },
      field: {
        username: 'Username',
        displayName: 'Display Name',
        status: 'Status',
        temporaryPassword: 'Temporary Password'
      },
      tip: {
        assignRole: 'Assign roles to {name}. Changes take effect at the next permission check.',
        resetPassword:
          'Resetting the password invalidates sessions for {name}; a password change is required at the next sign-in.'
      },
      placeholder: { role: 'Select roles', temporaryPassword: 'Enter a temporary password of 12–72 characters' }
    },
    roles: {
      title: 'Roles and Permissions',
      subtitle:
        'Role codes remain stable after publication. Platform releases define permission nodes; administrators assign them.',
      search: 'Search role code or name',
      permissionCount: '{count} items',
      confirmDelete: 'Delete role “{name}”?',
      column: {
        code: 'Role Code',
        name: 'Role Name',
        status: 'Status',
        description: 'Description',
        permissions: 'Permission Nodes'
      },
      action: {
        create: 'Create Role',
        permissions: 'Configure Permissions',
        publishPermissions: 'Publish Permissions'
      },
      validation: { required: 'Enter a role code and name' },
      message: {
        updated: 'Role updated',
        created: 'Role created',
        deleted: 'Role deleted',
        permissionsUpdated: 'Role permissions updated; existing sessions use them on the next request'
      },
      modal: { edit: 'Edit Role', create: 'Create Role', permissions: 'Role Permission Configuration' },
      field: { code: 'Role Code', name: 'Role Name', status: 'Status', description: 'Role Description' },
      placeholder: { code: 'For example, DISPATCHER' },
      tip: {
        permissions:
          'Configuring {name}. Directory, page, and button nodes can be assigned; button nodes display stable permission codes.'
      }
    },
    products: {
      title: 'Product Management',
      subtitle: 'Maintain stable product identities for thing models, protocols, and policy releases.',
      search: 'Search product name or key',
      empty: 'Create a product before onboarding devices',
      emptyDescription: 'Products group similar devices. Product keys cannot be changed after publication.',
      column: { key: 'Product Key', name: 'Product Name', type: 'Device Type', status: 'Status', updatedAt: 'Updated' },
      action: { create: 'Create Product', archive: 'Archive' },
      modal: { create: 'Create Product', edit: 'Edit Product' },
      field: { key: 'Product Key', name: 'Product Name', type: 'Device Type', status: 'Status' },
      placeholder: { key: 'For example, haul-truck', type: 'For example, VEHICLE' },
      status: { draft: 'Draft', active: 'Published', archived: 'Archived' },
      validation: { required: 'Enter a valid product key, name, and device type' },
      message: { created: 'Product created', updated: 'Product updated', archived: 'Product archived' },
      confirmArchive: 'Archive product “{name}”? All devices under it must be archived first.'
    },
    devices: {
      title: 'Device Registry',
      subtitle: 'Maintain mine device identities and products used for access control and data tracing.',
      search: 'Search device name or code',
      empty: 'No devices match the current filters',
      column: { code: 'Device Code', name: 'Device Name', product: 'Product', status: 'Status', updatedAt: 'Updated' },
      action: { create: 'Create Device', archive: 'Archive' },
      modal: { create: 'Create Device', edit: 'Edit Device' },
      field: { code: 'Device Code', name: 'Device Name', product: 'Product', status: 'Status' },
      placeholder: { product: 'Select a published product', code: 'For example, truck-001' },
      status: { enabled: 'Enabled', disabled: 'Disabled', archived: 'Archived' },
      validation: { required: 'Select a product and enter a valid device code and name' },
      message: { created: 'Device created', updated: 'Device updated', archived: 'Device archived' },
      confirmArchive: 'Archive device “{name}”? Historical data will be retained.'
    },
    monitor: {
      title: 'Live Device Monitoring',
      subtitle: 'Subscribe only to required fields. Snapshots are reloaded only after reconnects or sequence gaps.',
      selectDevice: 'Select Device',
      selectPlaceholder: 'Select a device to monitor',
      propertyTitle: 'Monitored Fields',
      propertyHelp: 'Fields come from thing models or protocol mappings and can be changed for each vendor device.',
      noDevice: 'Select a device to view live data',
      noDeviceDescription: 'The platform loads one latest snapshot before opening the WebSocket subscription.',
      connected: 'Live subscription active',
      snapshot: 'Latest snapshot loaded',
      updatedAt: 'Updated: {time}',
      waiting: 'Waiting for device data',
      quality: {
        good: 'Trusted',
        uncertain: 'Uncertain',
        bad: 'Invalid',
        timeUntrusted: 'Device time untrusted',
        unknown: 'Waiting for quality'
      },
      property: {
        engineRpm: 'Engine Speed',
        coolantTemperature: 'Coolant Temperature',
        vehicleSpeed: 'Vehicle Speed',
        fuelLevel: 'Fuel Level'
      }
    },
    fallback: {
      unconfigured: 'Not configured',
      title: 'Module Not Available',
      description: 'The menu is active, but component key “{componentKey}” has no matching local page.',
      help: 'Ask the platform developers to register the component and publish a new frontend release.'
    }
  },
  request: {
    logout: 'Logout user after request failed',
    logoutMsg: 'User status is invalid, please log in again',
    logoutWithModal: 'Pop up modal after request failed and then log out user',
    logoutWithModalMsg: 'User status is invalid, please log in again',
    refreshToken: 'The requested token has expired, refresh the token',
    tokenExpired: 'The requested token has expired'
  },
  theme: {
    themeDrawerTitle: 'Theme Configuration',
    tabs: {
      appearance: 'Appearance',
      layout: 'Layout',
      general: 'General',
      preset: 'Preset'
    },
    appearance: {
      themeSchema: {
        title: 'Theme Schema',
        light: 'Light',
        dark: 'Dark',
        auto: 'Follow System'
      },
      grayscale: 'Grayscale',
      colourWeakness: 'Colour Weakness',
      themeColor: {
        title: 'Theme Color',
        primary: 'Primary',
        info: 'Info',
        success: 'Success',
        warning: 'Warning',
        error: 'Error',
        followPrimary: 'Follow Primary'
      },
      themeRadius: {
        title: 'Theme Radius'
      },
      recommendColor: 'Apply Recommended Color Algorithm',
      recommendColorDesc: 'The recommended color algorithm refers to',
      preset: {
        title: 'Theme Presets',
        apply: 'Apply',
        applySuccess: 'Preset applied successfully',
        default: {
          name: 'Default Preset',
          desc: 'Default theme preset with balanced settings'
        },
        dark: {
          name: 'Dark Preset',
          desc: 'Dark theme preset for night time usage'
        },
        compact: {
          name: 'Compact Preset',
          desc: 'Compact layout preset for small screens'
        },
        azir: {
          name: "Azir's Preset",
          desc: 'It is a cold and elegant preset that Azir likes'
        }
      }
    },
    layout: {
      layoutMode: {
        title: 'Layout Mode',
        vertical: 'Vertical Mode',
        horizontal: 'Horizontal Mode',
        'vertical-mix': 'Vertical Mix Mode',
        'vertical-hybrid-header-first': 'Left Hybrid Header-First',
        'top-hybrid-sidebar-first': 'Top-Hybrid Sidebar-First',
        'top-hybrid-header-first': 'Top-Hybrid Header-First',
        vertical_detail: 'Vertical menu layout, with the menu on the left and content on the right.',
        'vertical-mix_detail':
          'Vertical mix-menu layout, with the primary menu on the dark left side and the secondary menu on the lighter left side.',
        'vertical-hybrid-header-first_detail':
          'Left hybrid layout, with the primary menu at the top, the secondary menu on the dark left side, and the tertiary menu on the lighter left side.',
        horizontal_detail: 'Horizontal menu layout, with the menu at the top and content below.',
        'top-hybrid-sidebar-first_detail':
          'Top hybrid layout, with the primary menu on the left and the secondary menu at the top.',
        'top-hybrid-header-first_detail':
          'Top hybrid layout, with the primary menu at the top and the secondary menu on the left.'
      },
      tab: {
        title: 'Tab Settings',
        visible: 'Tab Visible',
        cache: 'Tag Bar Info Cache',
        cacheTip: 'Keep the tab bar information after leaving the page',
        height: 'Tab Height',
        mode: {
          title: 'Tab Mode',
          slider: 'Slider',
          chrome: 'Chrome',
          button: 'Button'
        },
        closeByMiddleClick: 'Close Tab by Middle Click',
        closeByMiddleClickTip: 'Enable closing tabs by clicking with the middle mouse button'
      },
      header: {
        title: 'Header Settings',
        height: 'Header Height',
        breadcrumb: {
          visible: 'Breadcrumb Visible',
          showIcon: 'Breadcrumb Icon Visible'
        }
      },
      sider: {
        title: 'Sider Settings',
        inverted: 'Dark Sider',
        width: 'Sider Width',
        collapsedWidth: 'Sider Collapsed Width',
        mixWidth: 'Mix Sider Width',
        mixCollapsedWidth: 'Mix Sider Collapse Width',
        mixChildMenuWidth: 'Mix Child Menu Width',
        autoSelectFirstMenu: 'Auto Select First Submenu',
        autoSelectFirstMenuTip:
          'When a first-level menu is clicked, the first submenu is automatically selected and navigated to the deepest level'
      },
      footer: {
        title: 'Footer Settings',
        visible: 'Footer Visible',
        fixed: 'Fixed Footer',
        height: 'Footer Height',
        right: 'Right Footer'
      },
      content: {
        title: 'Content Area Settings',
        scrollMode: {
          title: 'Scroll Mode',
          tip: 'The theme scroll only scrolls the main part, the outer scroll can carry the header and footer together',
          wrapper: 'Wrapper',
          content: 'Content'
        },
        page: {
          animate: 'Page Animate',
          mode: {
            title: 'Page Animate Mode',
            fade: 'Fade',
            'fade-slide': 'Slide',
            'fade-bottom': 'Fade Zoom',
            'fade-scale': 'Fade Scale',
            'zoom-fade': 'Zoom Fade',
            'zoom-out': 'Zoom Out',
            none: 'None'
          }
        },
        fixedHeaderAndTab: 'Fixed Header And Tab'
      }
    },
    general: {
      title: 'General Settings',
      watermark: {
        title: 'Watermark Settings',
        visible: 'Watermark Full Screen Visible',
        text: 'Custom Watermark Text',
        enableUserName: 'Enable User Name Watermark',
        enableTime: 'Show Current Time',
        timeFormat: 'Time Format'
      },
      multilingual: {
        title: 'Multilingual Settings',
        visible: 'Display multilingual button'
      },
      globalSearch: {
        title: 'Global Search Settings',
        visible: 'Display GlobalSearch button'
      }
    },
    configOperation: {
      copyConfig: 'Copy Config',
      copySuccessMsg: 'Copy Success, Please replace the variable "themeSettings" in "src/theme/settings.ts"',
      resetConfig: 'Reset Config',
      resetSuccessMsg: 'Reset Success'
    }
  },
  route: {
    login: 'Login',
    403: 'No Permission',
    404: 'Page Not Found',
    500: 'Server Error',
    'iframe-page': 'Iframe',
    home: 'Home'
  },
  page: {
    login: {
      common: {
        loginOrRegister: 'Login / Register',
        userNamePlaceholder: 'Please enter user name',
        phonePlaceholder: 'Please enter phone number',
        codePlaceholder: 'Please enter verification code',
        passwordPlaceholder: 'Please enter password',
        confirmPasswordPlaceholder: 'Please enter password again',
        codeLogin: 'Verification code login',
        confirm: 'Confirm',
        back: 'Back',
        validateSuccess: 'Verification passed',
        loginSuccess: 'Login successfully',
        welcomeBack: 'Welcome back, {userName} !'
      },
      pwdLogin: {
        title: 'Password Login',
        rememberMe: 'Remember me',
        forgetPassword: 'Forget password?',
        register: 'Register',
        otherAccountLogin: 'Other Account Login',
        otherLoginMode: 'Other Login Mode',
        superAdmin: 'Super Admin',
        admin: 'Admin',
        user: 'User'
      },
      codeLogin: {
        title: 'Verification Code Login',
        getCode: 'Get verification code',
        reGetCode: 'Reacquire after {time}s',
        sendCodeSuccess: 'Verification code sent successfully',
        imageCodePlaceholder: 'Please enter image verification code'
      },
      register: {
        title: 'Register',
        agreement: 'I have read and agree to',
        protocol: '《User Agreement》',
        policy: '《Privacy Policy》'
      },
      resetPwd: {
        title: 'Reset Password'
      },
      bindWeChat: {
        title: 'Bind WeChat'
      }
    },
    home: {
      branchDesc:
        'For the convenience of everyone in developing and updating the merge, we have streamlined the code of the main branch, only retaining the homepage menu, and the rest of the content has been moved to the example branch for maintenance. The preview address displays the content of the example branch.',
      greeting: 'Good morning, {userName}, today is another day full of vitality!',
      weatherDesc: 'Today is cloudy to clear, 20℃ - 25℃!',
      projectCount: 'Project Count',
      todo: 'Todo',
      message: 'Message',
      downloadCount: 'Download Count',
      registerCount: 'Register Count',
      schedule: 'Work and rest Schedule',
      study: 'Study',
      work: 'Work',
      rest: 'Rest',
      entertainment: 'Entertainment',
      visitCount: 'Visit Count',
      turnover: 'Turnover',
      dealCount: 'Deal Count',
      projectNews: {
        title: 'Project News',
        moreNews: 'More News',
        desc1: 'Soybean created the open source project soybean-admin on May 28, 2021!',
        desc2: 'Yanbowe submitted a bug to soybean-admin, the multi-tab bar will not adapt.',
        desc3: 'Soybean is ready to do sufficient preparation for the release of soybean-admin!',
        desc4: 'Soybean is busy writing project documentation for soybean-admin!',
        desc5: 'Soybean just wrote some of the workbench pages casually, and it was enough to see!'
      },
      creativity: 'Creativity'
    }
  },
  form: {
    required: 'Cannot be empty',
    userName: {
      required: 'Please enter user name',
      invalid: 'User name format is incorrect'
    },
    phone: {
      required: 'Please enter phone number',
      invalid: 'Phone number format is incorrect'
    },
    pwd: {
      required: 'Please enter password',
      invalid: '6-18 characters, including letters, numbers, and underscores'
    },
    confirmPwd: {
      required: 'Please enter password again',
      invalid: 'The two passwords are inconsistent'
    },
    code: {
      required: 'Please enter verification code',
      invalid: 'Verification code format is incorrect'
    },
    email: {
      required: 'Please enter email',
      invalid: 'Email format is incorrect'
    }
  },
  dropdown: {
    closeCurrent: 'Close Current',
    closeOther: 'Close Other',
    closeLeft: 'Close Left',
    closeRight: 'Close Right',
    closeAll: 'Close All',
    pin: 'Pin Tab',
    unpin: 'Unpin Tab'
  },
  icon: {
    themeConfig: 'Theme Configuration',
    themeSchema: 'Theme Schema',
    lang: 'Switch Language',
    fullscreen: 'Fullscreen',
    fullscreenExit: 'Exit Fullscreen',
    reload: 'Reload Page',
    collapse: 'Collapse Menu',
    expand: 'Expand Menu',
    pin: 'Pin',
    unpin: 'Unpin'
  },
  datatable: {
    itemCount: 'Total {total} items',
    fixed: {
      left: 'Left Fixed',
      right: 'Right Fixed',
      unFixed: 'Unfixed'
    }
  }
};

export default local;
