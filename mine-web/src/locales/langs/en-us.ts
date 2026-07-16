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
      menus: 'Menu Management',
      dictionaries: 'Dictionary Management',
      operations: 'Operations Monitor',
      device: 'Device Management',
      products: 'Product Management',
      devices: 'Device Registry',
      models: 'Thing Models & Protocols',
      monitor: 'Device Monitoring',
      command: 'Command Management',
      commandCenter: 'Command Center',
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
      saved: 'Saved',
      yes: 'Yes',
      no: 'No',
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
      title: 'Mine Operations',
      subtitle: 'Monitor continuous device reporting and prioritize equipment requiring attention.',
      updatedAt: 'Updated {time}',
      unavailable: 'Unavailable',
      never: 'No record',
      loadFailed: 'Failed to load operations overview',
      unavailableTitle: 'Operations data is unavailable',
      unavailableDescription: 'The device registry remains available, but telemetry statistics cannot be confirmed.',
      runningState: {
        loading: 'Updating',
        normal: 'Operating normally',
        attention: 'Attention required',
        empty: 'No devices connected',
        unavailable: 'Data unavailable'
      },
      metric: {
        enabled: { label: 'Enabled Devices', description: 'Included in production monitoring' },
        reporting: { label: 'Reporting', description: 'Data received within the freshness window' },
        stale: { label: 'Data Interrupted', description: 'Previously reported but now stale' },
        todayMessages: { label: 'Received Today', description: 'Messages durably stored in telemetry' }
      },
      attention: {
        title: 'Needs Attention',
        count: '{count} devices',
        empty: 'No devices need attention',
        emptyHint: 'If a device stops reporting, investigate it from the device registry',
        unavailable: 'Attention status cannot be confirmed',
        unavailableHint: 'Devices requiring attention will appear when operations data recovers',
        lastSeen: 'Last report: {time}',
        noTelemetry: 'No telemetry received',
        state: { stale: 'Data interrupted', neverReported: 'Never reported' }
      },
      distribution: {
        title: 'Device Reporting',
        reporting: 'Reporting',
        stale: 'Data interrupted',
        neverReported: 'Never reported',
        freshness: 'Reporting is current when data arrives within {seconds} seconds.',
        empty: 'No device reporting status is available'
      },
      telemetry: {
        title: 'Received in the Last 24 Hours',
        latest: 'Latest: {time}',
        empty: 'No data',
        zero: 'No telemetry messages were received in the last 24 hours',
        emptySummary: 'No telemetry data is available for the last 24 hours',
        unavailableSummary: 'Telemetry data for the last 24 hours is unavailable',
        summary: '{total} telemetry messages in the last 24 hours, with a peak of {peak} per hour'
      },
      quick: {
        title: 'Quick Actions',
        monitor: 'Open Live Monitoring',
        monitorDescription: 'View live data and device status',
        devices: 'View Device Registry',
        devicesDescription: 'Manage devices and connection settings',
        commands: 'Open Command Center',
        commandsDescription: 'Issue commands and view command history',
        empty: 'No production actions are available for this account'
      }
    },
    operations: {
      title: 'Operations Monitor',
      subtitle: 'Review current health and historical changes across platform services, messaging, and storage.',
      checkedAt: 'Checked {time}',
      generatedAt: 'Metrics updated {time}',
      summary: '{healthy} healthy · {down} unhealthy · {unknown} unknown',
      latency: '{value} ms',
      notAvailable: '—',
      loadFailed: 'Runtime check failed',
      loadFailedDescription: 'This check could not be completed. Use the trace ID to inspect service logs.',
      historyLoadFailed: 'Failed to load historical runtime metrics',
      trendLoadFailed: 'Failed to load runtime trend',
      historyUnavailableTitle: 'Historical monitoring is unavailable',
      historyUnavailableDescription: 'Prometheus is not connected. You can still run an immediate dependency check.',
      onDemandNote: 'Checks only run when this page opens or Refresh is selected.',
      empty: 'No checks are registered for this category',
      historyTitle: 'Runtime Status',
      immediateTitle: 'Immediate Check Results',
      trendTitle: '{item} · {range} Trend',
      trendLatency: 'Probe Latency',
      trendEmpty: 'No historical samples are available for this range',
      trendAriaLabel: '{item} status and latency trend',
      action: { checkNow: 'Check Now', viewTrend: 'View Trend' },
      filter: { search: 'Search checks', status: 'All statuses', category: 'All categories' },
      column: {
        status: 'Status',
        item: 'Check',
        category: 'Category',
        availability: 'Availability',
        p95Latency: 'P95 Latency',
        lastChanged: 'Last Change',
        action: 'Action'
      },
      range: { hour: '1 Hour', sixHours: '6 Hours', day: '24 Hours', week: '7 Days', fifteenDays: '15 Days' },
      status: { up: 'Healthy', degraded: 'Degraded', down: 'Down', unknown: 'Unknown' },
      category: { service: 'Platform Services', message: 'Messaging', storage: 'Storage and Cache' },
      item: {
        mineServer: 'Management Service',
        mineGateway: 'Device Gateway',
        mineTelemetry: 'Telemetry Service',
        rabbitMq: 'RabbitMQ',
        postgres: 'Business PostgreSQL',
        redis: 'Redis',
        timescale: 'TimescaleDB'
      },
      detail: {
        serviceUp: 'Service responded normally',
        rabbitUp: 'Management API responded normally',
        connectionUp: 'Connection is healthy',
        connectionDown: 'Connection failed',
        notEnabled: 'This check is not enabled in the current deployment',
        healthFailed: 'Service health check failed',
        checkFailed: 'Check did not complete; inspect service logs',
        checkTimeout: 'Check timed out',
        checkInterrupted: 'Check interrupted'
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
    menus: {
      title: 'Menu Management',
      subtitle: 'Maintain navigation directories and pages. Button permissions are configured inside each page.',
      listTitle: 'Menu List',
      root: 'Root',
      total: '{count} items',
      buttonPermissions: 'Button Permissions',
      buttonPermissionTip: 'Button permissions stay out of the menu list and only control page actions and API access.',
      column: {
        id: 'ID',
        name: 'Menu Name',
        type: 'Menu Type',
        icon: 'Icon',
        path: 'Route Path',
        permission: 'Permission',
        status: 'Menu Status',
        visible: 'Hidden',
        parent: 'Parent Menu',
        sort: 'Sort'
      },
      type: { directory: 'Directory', menu: 'Page', button: 'Button' },
      action: {
        create: 'Add Menu',
        batchDelete: 'Delete Selected ({count})',
        refresh: 'Refresh',
        columns: 'Columns',
        addButton: 'Add Button Permission'
      },
      filter: {
        name: 'Menu Name',
        namePlaceholder: 'Search menu name',
        type: 'Menu Type',
        allTypes: 'All Types',
        status: 'Status',
        allStatuses: 'All Statuses'
      },
      modal: { create: 'Add Menu', edit: 'Edit Menu' },
      field: {
        name: 'Node Name',
        type: 'Node Type',
        parent: 'Parent Node',
        path: 'Route Path',
        component: 'Page Component',
        permission: 'Permission',
        icon: 'Icon Key',
        sort: 'Sort Order',
        visible: 'Visible in Navigation',
        status: 'Status',
        i18nKey: 'I18n Resource Key'
      },
      help: {
        label: 'View field help',
        name: 'Name shown in navigation, the menu tree, and role assignment. Keep sibling names short and distinct.',
        type: 'Directories group pages; pages map to routes; buttons represent API or UI permissions and create no route.',
        parent: 'Directories may be roots; pages belong to directories; button permissions belong to pages.',
        sort: 'Siblings are ordered from the smallest number upward. Leave gaps of 10 for later insertions.',
        path: 'Frontend route starting with /, for example /system/menus. It must be unique across the platform.',
        component: 'Choose from the frontend compile-time component registry. Arbitrary file paths are not allowed.',
        permission: 'Must match backend Sa-Token annotations and frontend action checks, such as system:menu:view.',
        icon: 'Icon key used by navigation. Button nodes usually need no icon; blank values use the platform fallback.',
        i18nKey: 'Stable key present in both locale files, such as mine.menu.menus. The node name is used when blank.',
        status:
          'Disabled nodes are excluded from navigation and authorization results, making descendants unavailable.',
        visible:
          'Controls sidebar visibility only. A hidden page can still be opened when its route and permission allow it.',
        buttonName: 'Name shown in role assignment and operation audit, such as “Create Product” or “Send Command”.',
        buttonPermission:
          'Must exactly match backend Sa-Token annotations and frontend action checks, such as device:product:create.'
      },
      validation: {
        required: 'Complete all fields required by this menu type',
        buttonRequired: 'Enter the button name and permission code'
      },
      confirmDelete: 'Delete menu “{name}”?',
      confirmBatchDelete:
        'Delete the selected {count} menus? The entire batch is validated first and nothing is deleted on failure.',
      confirmDeleteButton: 'Delete button permission “{name}”?',
      message: {
        created: 'Menu created',
        updated: 'Menu updated',
        deleted: 'Menu deleted',
        batchDeleted: '{count} menus deleted',
        buttonCreated: 'Button permission created',
        buttonUpdated: 'Button permission updated',
        buttonDeleted: 'Button permission deleted'
      }
    },
    dictionaries: {
      title: 'Common Dictionary Management',
      subtitle:
        'Maintain low-frequency enums such as statuses and categories. Device fields, thing models, and protocol mappings remain separate.',
      typeList: 'Dictionary Types',
      selectType: 'Select a dictionary type to view its items',
      builtIn: 'Built-in',
      column: {
        typeName: 'Type Name',
        code: 'Stable Code',
        status: 'Status',
        labelZh: 'Chinese Label',
        value: 'Value',
        semantic: 'Semantic Style',
        sort: 'Sort',
        default: 'Default'
      },
      action: { createType: 'Add Dictionary Type', createItem: 'Add Item', items: 'Items' },
      modal: {
        createType: 'Add Dictionary Type',
        editType: 'Edit Dictionary Type',
        createItem: 'Add Dictionary Item',
        editItem: 'Edit Dictionary Item'
      },
      field: {
        code: 'Stable Code',
        name: 'Type Name',
        status: 'Status',
        description: 'Description',
        value: 'Stable Value',
        labelZh: 'Chinese Label',
        labelEn: 'English Label',
        semantic: 'Theme Semantic',
        sort: 'Sort Order',
        default: 'Set as Default'
      },
      semantic: {
        default: 'Default',
        primary: 'Primary',
        info: 'Info',
        success: 'Success',
        warning: 'Warning',
        error: 'Error'
      },
      validation: { required: 'Complete all required fields' },
      confirmDeleteType: 'Delete dictionary type “{name}”? Only empty non-built-in types can be deleted.',
      confirmDeleteItem: 'Delete dictionary item “{label}”?'
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
    models: {
      title: 'Thing Models and Protocol Mapping',
      subtitle:
        'Normalize vendor payloads into stable fields. Publish validated drafts without restarting site services.',
      product: 'Device Product',
      publishHelp:
        'Publish a thing model first, then map vendor MQTT/TCP fields. Publishing switches immutable versions and preserves history.',
      tab: { model: 'Unified Thing Model', protocol: 'Protocol Field Mapping' },
      version: 'Thing Model v{version}',
      selectVersion: 'Select a thing model version',
      selectProtocol: 'Select a protocol version',
      description: 'Change description',
      modelFields: 'Unified Field Definitions',
      protocolFields: 'Vendor Source Field Mapping',
      status: { draft: 'Draft', published: 'Published', retired: 'Retired' },
      action: {
        newModel: 'New Model Draft',
        addField: 'Add Field',
        publish: 'Publish Version',
        newProtocol: 'New Protocol Draft',
        addMapping: 'Add Mapping'
      },
      column: {
        identifier: 'Identifier',
        name: 'Field Name',
        dataType: 'Data Type',
        unit: 'Unit',
        access: 'Access',
        source: 'Vendor Field Path',
        sourceType: 'Source Type',
        target: 'Unified Field',
        transform: 'Transform'
      },
      modal: { field: 'Add Thing Model Field', protocol: 'New Protocol Draft', mapping: 'Add Field Mapping' },
      field: {
        identifier: 'Field Identifier',
        nameZh: 'Chinese Name',
        dataType: 'Data Type',
        unit: 'Engineering Unit',
        access: 'Access Mode',
        required: 'Required Field',
        minimum: 'Minimum',
        maximum: 'Maximum',
        profileKey: 'Profile Key',
        profileName: 'Profile Name',
        protocolType: 'Protocol Type',
        messageType: 'Message Type',
        topicOrFrame: 'MQTT Topic Template / TCP Frame Type',
        sourcePath: 'Source Field Path',
        sourceType: 'Source Data Type',
        target: 'Target Unified Field',
        transform: 'Transform',
        scale: 'Scale Factor',
        offset: 'Offset',
        expression: 'Restricted Expression'
      },
      dataType: {
        integer: 'Integer',
        decimal: 'Decimal',
        boolean: 'Boolean',
        string: 'String',
        enum: 'Enum',
        datetime: 'Date Time',
        geo_point: 'Geo Point'
      },
      access: { read: 'Read', write: 'Write', read_write: 'Read/Write' },
      protocol: { mqtt_json: 'MQTT JSON', mqtt_binary: 'MQTT Binary', tcp_binary: 'TCP Binary' },
      messageType: { telemetry: 'Telemetry', state: 'State', event: 'Event', command_reply: 'Command Reply' },
      transform: {
        none: 'None',
        scale: 'Scale',
        offset: 'Offset',
        scale_offset: 'Scale and Offset',
        enum: 'Enum Mapping',
        expression: 'Restricted Expression'
      },
      validation: { required: 'Complete the field identifier, name, and mapping target' },
      confirmPublishModel: 'Publish this thing model? The previous published version will be retired.',
      confirmPublishProtocol: 'Publish this protocol mapping? All target fields will be validated.'
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
    commands: {
      title: 'Command Center',
      subtitle: 'Send published low-risk device commands and track reliable delivery through device execution.',
      empty: 'No commands match the current filters',
      column: { device: 'Device', template: 'Command Template', status: 'Status', createdAt: 'Created' },
      action: {
        create: 'Send Command',
        send: 'Review Command',
        confirmSend: 'Confirm Send',
        detail: 'Details',
        cancel: 'Cancel',
        confirmCancel: 'Confirm Cancel'
      },
      modal: { create: 'Send Device Command', cancel: 'Cancel Queued Command' },
      field: {
        id: 'Command ID',
        device: 'Target Device',
        template: 'Command Template',
        status: 'Current Status',
        ttl: 'Queue TTL (seconds)',
        timeout: 'Device Reply Timeout (seconds)',
        parameters: 'Command Parameters',
        reply: 'Device Reply (Redacted)',
        errorCode: 'Error Code',
        reason: 'Dispatch Reason',
        workOrderNo: 'Work Order',
        cancelReason: 'Cancellation Reason'
      },
      placeholder: { device: 'Select a device', status: 'All command statuses', template: 'All command templates' },
      validation: {
        required: 'Select a target device and command template',
        unsupportedParameters: 'This page only supports published low-risk templates',
        reasonRequired: 'This command changes device configuration; enter the site dispatch reason',
        parametersRequired: 'Complete all parameters required by the command template',
        cancelReasonRequired: 'Enter a cancellation reason'
      },
      confirm: {
        title: 'Confirm Command',
        content: 'Send “{template}” to “{device}”? Confirm the site and operating conditions are safe.'
      },
      message: {
        created: 'Command entered the reliable delivery queue',
        cancelled: 'Command cancelled before dispatch'
      },
      template: {
        queryStatus: 'Status Query',
        setReportInterval: 'Set Reporting Interval',
        requestLocation: 'Request Location Now'
      },
      parameter: { intervalSeconds: 'Reporting Interval (seconds)' },
      detail: { title: 'Command Details', timeline: 'Status Timeline' },
      timeline: { queued: 'Queued', sent: 'Sent', received: 'Device Received' },
      status: {
        created: 'Created',
        queued: 'Queued',
        sent: 'Sent',
        device_received: 'Device Received',
        succeeded: 'Succeeded',
        refused: 'Device Refused',
        failed: 'Failed',
        expired: 'Queue Expired',
        timeout: 'Reply Timeout',
        cancelled: 'Cancelled'
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
