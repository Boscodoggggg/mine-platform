/** The global namespace for the app */
declare namespace App {
  /** Theme namespace */
  namespace Theme {
    type ColorPaletteNumber = import('@sa/color').ColorPaletteNumber;

    /** NaiveUI theme overrides that can be specified in preset */
    type NaiveUIThemeOverride = import('naive-ui').GlobalThemeOverrides;

    /** Theme setting */
    interface ThemeSetting {
      /** Theme scheme */
      themeScheme: UnionKey.ThemeScheme;
      /** grayscale mode */
      grayscale: boolean;
      /** colour weakness mode */
      colourWeakness: boolean;
      /** Whether to recommend color */
      recommendColor: boolean;
      /** Theme color */
      themeColor: string;
      /** Theme radius */
      themeRadius: number;
      /** Other color */
      otherColor: OtherColor;
      /** Whether info color is followed by the primary color */
      isInfoFollowPrimary: boolean;
      /** Layout */
      layout: {
        /** Layout mode */
        mode: UnionKey.ThemeLayoutMode;
        /** Scroll mode */
        scrollMode: UnionKey.ThemeScrollMode;
      };
      /** Page */
      page: {
        /** Whether to show the page transition */
        animate: boolean;
        /** Page animate mode */
        animateMode: UnionKey.ThemePageAnimateMode;
      };
      /** Header */
      header: {
        /** Header height */
        height: number;
        /** Header breadcrumb */
        breadcrumb: {
          /** Whether to show the breadcrumb */
          visible: boolean;
          /** Whether to show the breadcrumb icon */
          showIcon: boolean;
        };
        /** Multilingual */
        multilingual: {
          /** Whether to show the multilingual */
          visible: boolean;
        };
        globalSearch: {
          /** Whether to show the GlobalSearch */
          visible: boolean;
        };
      };
      /** Tab */
      tab: {
        /** Whether to show the tab */
        visible: boolean;
        /**
         * Whether to cache the tab
         *
         * If cache, the tabs will get from the local storage when the page is refreshed
         */
        cache: boolean;
        /** Tab height */
        height: number;
        /** Tab mode */
        mode: UnionKey.ThemeTabMode;
        /** Whether to close tab by middle click */
        closeTabByMiddleClick: boolean;
      };
      /** Fixed header and tab */
      fixedHeaderAndTab: boolean;
      /** Sider */
      sider: {
        /** Inverted sider */
        inverted: boolean;
        /** Sider width */
        width: number;
        /** Collapsed sider width */
        collapsedWidth: number;
        /** Sider width when the layout is 'vertical-mix', 'top-hybrid-sidebar-first', or 'top-hybrid-header-first' */
        mixWidth: number;
        /**
         * Collapsed sider width when the layout is 'vertical-mix', 'top-hybrid-sidebar-first', or
         * 'top-hybrid-header-first'
         */
        mixCollapsedWidth: number;
        /** Child menu width when the layout is 'vertical-mix', 'top-hybrid-sidebar-first', or 'top-hybrid-header-first' */
        mixChildMenuWidth: number;
        /** Whether to auto select the first submenu */
        autoSelectFirstMenu: boolean;
      };
      /** Footer */
      footer: {
        /** Whether to show the footer */
        visible: boolean;
        /** Whether fixed the footer */
        fixed: boolean;
        /** Footer height */
        height: number;
        /**
         * Whether float the footer to the right when the layout is 'top-hybrid-sidebar-first' or
         * 'top-hybrid-header-first'
         */
        right: boolean;
      };
      /** Watermark */
      watermark: {
        /** Whether to show the watermark */
        visible: boolean;
        /** Watermark text */
        text: string;
        /** Whether to use user name as watermark text */
        enableUserName: boolean;
        /** Whether to use current time as watermark text */
        enableTime: boolean;
        /** Time format for watermark text */
        timeFormat: string;
      };
      /** define some theme settings tokens, will transform to css variables */
      tokens: {
        light: ThemeSettingToken;
        dark?: {
          [K in keyof ThemeSettingToken]?: Partial<ThemeSettingToken[K]>;
        };
      };
    }

    interface OtherColor {
      info: string;
      success: string;
      warning: string;
      error: string;
    }

    interface ThemeColor extends OtherColor {
      primary: string;
    }

    type ThemeColorKey = keyof ThemeColor;

    type ThemePaletteColor = {
      [key in ThemeColorKey | `${ThemeColorKey}-${ColorPaletteNumber}`]: string;
    };

    type BaseToken = Record<string, Record<string, string>>;

    interface ThemeSettingTokenColor {
      /** the progress bar color, if not set, will use the primary color */
      nprogress?: string;
      container: string;
      layout: string;
      inverted: string;
      'base-text': string;
    }

    interface ThemeSettingTokenBoxShadow {
      header: string;
      sider: string;
      tab: string;
    }

    interface ThemeSettingToken {
      colors: ThemeSettingTokenColor;
      boxShadow: ThemeSettingTokenBoxShadow;
    }

    type ThemeTokenColor = ThemePaletteColor & ThemeSettingTokenColor;

    /** Theme token CSS variables */
    type ThemeTokenCSSVars = {
      colors: ThemeTokenColor & { [key: string]: string };
      boxShadow: ThemeSettingTokenBoxShadow & { [key: string]: string };
    };
  }

  /** Global namespace */
  namespace Global {
    type VNode = import('vue').VNode;
    type RouteLocationNormalizedLoaded = import('vue-router').RouteLocationNormalizedLoaded;
    type RouteKey = import('@elegant-router/types').RouteKey;
    type RouteMap = import('@elegant-router/types').RouteMap;
    type RoutePath = import('@elegant-router/types').RoutePath;
    type LastLevelRouteKey = import('@elegant-router/types').LastLevelRouteKey;

    /** The router push options */
    type RouterPushOptions = {
      query?: Record<string, string>;
      params?: Record<string, string>;
      force?: boolean;
    };

    /** The global header props */
    interface HeaderProps {
      /** Whether to show the logo */
      showLogo?: boolean;
      /** Whether to show the menu toggler */
      showMenuToggler?: boolean;
      /** Whether to show the menu */
      showMenu?: boolean;
    }

    /** The global menu */
    type Menu = {
      /**
       * The menu key
       *
       * Equal to the route key
       */
      key: string;
      /** The menu label */
      label: string;
      /** The menu i18n key */
      i18nKey?: I18n.I18nKey | null;
      /** The route key */
      routeKey: RouteKey;
      /** The route path */
      routePath: RoutePath;
      /** The menu icon */
      icon?: () => VNode;
      /** The menu children */
      children?: Menu[];
    };

    type Breadcrumb = Omit<Menu, 'children'> & {
      options?: Breadcrumb[];
    };

    /** Tab route */
    type TabRoute = Pick<RouteLocationNormalizedLoaded, 'name' | 'path' | 'meta'> &
      Partial<Pick<RouteLocationNormalizedLoaded, 'fullPath' | 'query' | 'matched'>>;

    /** The global tab */
    type Tab = {
      /** The tab id */
      id: string;
      /** The tab label */
      label: string;
      /**
       * The new tab label
       *
       * If set, the tab label will be replaced by this value
       */
      newLabel?: string;
      /**
       * The old tab label
       *
       * when reset the tab label, the tab label will be replaced by this value
       */
      oldLabel?: string;
      /** The tab route key */
      routeKey: LastLevelRouteKey;
      /** The tab route path */
      routePath: RouteMap[LastLevelRouteKey];
      /** The tab route full path */
      fullPath: string;
      /** The tab fixed index */
      fixedIndex?: number | null;
      /**
       * Tab icon
       *
       * Iconify icon
       */
      icon?: string;
      /**
       * Tab local icon
       *
       * Local icon
       */
      localIcon?: string;
      /** I18n key */
      i18nKey?: I18n.I18nKey | null;
    };

    /** Form rule */
    type FormRule = import('naive-ui').FormItemRule;

    /** The global dropdown key */
    type DropdownKey = 'closeCurrent' | 'closeOther' | 'closeLeft' | 'closeRight' | 'closeAll' | 'pin' | 'unpin';
  }

  /**
   * I18n namespace
   *
   * Locales type
   */
  namespace I18n {
    type RouteKey = import('@elegant-router/types').RouteKey;

    type LangType = 'en-US' | 'zh-CN';

    type LangOption = {
      label: string;
      key: LangType;
    };

    type I18nRouteKey = Exclude<RouteKey, 'root' | 'not-found'>;

    type FormMsg = {
      required: string;
      invalid: string;
    };

    type Schema = {
      system: {
        title: string;
        updateTitle: string;
        updateContent: string;
        updateConfirm: string;
        updateCancel: string;
      };
      common: {
        action: string;
        add: string;
        addSuccess: string;
        backToHome: string;
        batchDelete: string;
        cancel: string;
        close: string;
        check: string;
        selectAll: string;
        expandColumn: string;
        columnSetting: string;
        config: string;
        confirm: string;
        delete: string;
        deleteSuccess: string;
        confirmDelete: string;
        edit: string;
        warning: string;
        error: string;
        index: string;
        keywordSearch: string;
        logout: string;
        logoutConfirm: string;
        lookForward: string;
        modify: string;
        modifySuccess: string;
        noData: string;
        operate: string;
        pleaseCheckValue: string;
        refresh: string;
        reset: string;
        search: string;
        switch: string;
        tip: string;
        trigger: string;
        update: string;
        updateSuccess: string;
        userCenter: string;
        yesOrNo: {
          yes: string;
          no: string;
        };
      };
      mine: {
        menu: Record<
          | 'system'
          | 'users'
          | 'roles'
          | 'menus'
          | 'dictionaries'
          | 'operations'
          | 'device'
          | 'products'
          | 'devices'
          | 'models'
          | 'strategies'
          | 'monitor'
          | 'command'
          | 'commandCenter'
          | 'overview',
          string
        >;
        common: {
          action: string;
          edit: string;
          delete: string;
          empty: string;
          allStatus: string;
          search: string;
          reset: string;
          cancel: string;
          save: string;
          saved: string;
          yes: string;
          no: string;
          requestFailed: string;
          traceId: string;
          operationFailed: string;
          status: {
            enabled: string;
            disabled: string;
            locked: string;
          };
        };
        login: {
          brand: string;
          slogan: string;
          title: Record<'login' | 'password' | 'complete', string>;
          description: Record<'login' | 'password' | 'complete', string>;
          field: Record<'username' | 'password' | 'currentPassword' | 'newPassword' | 'confirmPassword', string>;
          placeholder: Record<'username' | 'password', string>;
          validation: Record<
            | 'usernameRequired'
            | 'passwordRequired'
            | 'currentPasswordRequired'
            | 'newPasswordRequired'
            | 'passwordTooShort'
            | 'passwordTooLong'
            | 'confirmPasswordRequired'
            | 'passwordMismatch',
            string
          >;
          security: Record<'title' | 'description', string>;
          action: Record<'processing' | 'login' | 'changePassword' | 'restart', string>;
          complete: Record<'title' | 'description', string>;
          service: Record<'local' | 'help', string>;
        };
        home: {
          title: string;
          subtitle: string;
          updatedAt: string;
          unavailable: string;
          never: string;
          loadFailed: string;
          unavailableTitle: string;
          unavailableDescription: string;
          runningState: Record<'loading' | 'normal' | 'attention' | 'empty' | 'unavailable', string>;
          metric: Record<'enabled' | 'reporting' | 'stale' | 'todayMessages', Record<'label' | 'description', string>>;
          attention: {
            title: string;
            count: string;
            empty: string;
            emptyHint: string;
            unavailable: string;
            unavailableHint: string;
            lastSeen: string;
            noTelemetry: string;
            state: Record<'stale' | 'neverReported', string>;
          };
          distribution: Record<'title' | 'reporting' | 'stale' | 'neverReported' | 'freshness' | 'empty', string>;
          telemetry: Record<
            'title' | 'latest' | 'empty' | 'zero' | 'emptySummary' | 'unavailableSummary' | 'summary',
            string
          >;
          quick: Record<
            | 'title'
            | 'monitor'
            | 'monitorDescription'
            | 'devices'
            | 'devicesDescription'
            | 'commands'
            | 'commandsDescription'
            | 'empty',
            string
          >;
        };
        operations: {
          title: string;
          subtitle: string;
          checkedAt: string;
          generatedAt: string;
          summary: string;
          latency: string;
          notAvailable: string;
          loadFailed: string;
          loadFailedDescription: string;
          historyLoadFailed: string;
          trendLoadFailed: string;
          historyUnavailableTitle: string;
          historyUnavailableDescription: string;
          onDemandNote: string;
          empty: string;
          historyTitle: string;
          immediateTitle: string;
          checking: string;
          checkProgress: string;
          trendTitle: string;
          trendLatency: string;
          trendEmpty: string;
          trendAriaLabel: string;
          action: Record<'checkNow' | 'viewTrend', string>;
          filter: Record<'search' | 'status' | 'category', string>;
          column: Record<
            'status' | 'item' | 'category' | 'availability' | 'p95Latency' | 'lastChanged' | 'action',
            string
          >;
          range: Record<'hour' | 'sixHours' | 'day' | 'week' | 'fifteenDays', string>;
          status: Record<'up' | 'degraded' | 'down' | 'unknown', string>;
          category: Record<'service' | 'message' | 'storage', string>;
          item: Record<
            'mineServer' | 'mineGateway' | 'mineTelemetry' | 'rabbitMq' | 'postgres' | 'redis' | 'timescale',
            string
          >;
          detail: Record<
            | 'serviceUp'
            | 'rabbitUp'
            | 'connectionUp'
            | 'connectionDown'
            | 'notEnabled'
            | 'healthFailed'
            | 'checkFailed'
            | 'checkTimeout'
            | 'checkInterrupted',
            string
          >;
        };
        users: {
          title: string;
          subtitle: string;
          search: string;
          neverLoggedIn: string;
          column: Record<'username' | 'displayName' | 'status' | 'passwordChange' | 'lastLogin', string>;
          passwordChange: Record<'pending' | 'completed', string>;
          action: Record<'create' | 'assignRole' | 'resetPassword' | 'saveRole' | 'confirmReset', string>;
          validation: Record<'displayName' | 'createRequired' | 'temporaryPassword', string>;
          message: Record<'updated' | 'created' | 'rolesUpdated' | 'passwordReset', string>;
          modal: Record<'edit' | 'create' | 'assignRole' | 'resetPassword', string>;
          field: Record<'username' | 'displayName' | 'status' | 'temporaryPassword', string>;
          tip: Record<'assignRole' | 'resetPassword', string>;
          placeholder: Record<'role' | 'temporaryPassword', string>;
        };
        roles: {
          title: string;
          subtitle: string;
          search: string;
          permissionCount: string;
          confirmDelete: string;
          column: Record<'code' | 'name' | 'status' | 'description' | 'permissions', string>;
          action: Record<'create' | 'permissions' | 'publishPermissions', string>;
          validation: Record<'required', string>;
          message: Record<'updated' | 'created' | 'deleted' | 'permissionsUpdated', string>;
          modal: Record<'edit' | 'create' | 'permissions', string>;
          field: Record<'code' | 'name' | 'status' | 'description', string>;
          placeholder: Record<'code', string>;
          tip: Record<'permissions', string>;
        };
        menus: {
          title: string;
          subtitle: string;
          listTitle: string;
          root: string;
          total: string;
          buttonPermissions: string;
          buttonPermissionTip: string;
          column: Record<
            'id' | 'name' | 'type' | 'icon' | 'path' | 'permission' | 'status' | 'visible' | 'parent' | 'sort',
            string
          >;
          type: Record<'directory' | 'menu' | 'button', string>;
          action: Record<'create' | 'batchDelete' | 'refresh' | 'columns' | 'addButton', string>;
          filter: Record<'name' | 'namePlaceholder' | 'type' | 'allTypes' | 'status' | 'allStatuses', string>;
          modal: Record<'create' | 'edit', string>;
          field: Record<
            | 'name'
            | 'type'
            | 'parent'
            | 'path'
            | 'component'
            | 'permission'
            | 'icon'
            | 'sort'
            | 'visible'
            | 'status'
            | 'i18nKey',
            string
          >;
          help: Record<
            | 'label'
            | 'name'
            | 'type'
            | 'parent'
            | 'sort'
            | 'path'
            | 'component'
            | 'permission'
            | 'icon'
            | 'i18nKey'
            | 'status'
            | 'visible'
            | 'buttonName'
            | 'buttonPermission',
            string
          >;
          validation: Record<'required' | 'buttonRequired', string>;
          confirmDelete: string;
          confirmBatchDelete: string;
          confirmDeleteButton: string;
          message: Record<
            'created' | 'updated' | 'deleted' | 'batchDeleted' | 'buttonCreated' | 'buttonUpdated' | 'buttonDeleted',
            string
          >;
        };
        dictionaries: {
          title: string;
          subtitle: string;
          typeList: string;
          selectType: string;
          builtIn: string;
          column: Record<
            'typeName' | 'code' | 'status' | 'labelZh' | 'value' | 'semantic' | 'sort' | 'default',
            string
          >;
          action: Record<'createType' | 'createItem' | 'items', string>;
          modal: Record<'createType' | 'editType' | 'createItem' | 'editItem', string>;
          field: Record<
            | 'code'
            | 'name'
            | 'status'
            | 'description'
            | 'value'
            | 'labelZh'
            | 'labelEn'
            | 'semantic'
            | 'sort'
            | 'default',
            string
          >;
          semantic: Record<'default' | 'primary' | 'info' | 'success' | 'warning' | 'error', string>;
          validation: Record<'required', string>;
          confirmDeleteType: string;
          confirmDeleteItem: string;
        };
        products: {
          title: string;
          subtitle: string;
          search: string;
          empty: string;
          emptyDescription: string;
          column: Record<'key' | 'name' | 'type' | 'status' | 'updatedAt', string>;
          action: Record<'create' | 'archive', string>;
          modal: Record<'create' | 'edit', string>;
          field: Record<'key' | 'name' | 'type' | 'status', string>;
          placeholder: Record<'key' | 'type', string>;
          status: Record<'draft' | 'active' | 'archived', string>;
          validation: Record<'required', string>;
          message: Record<'created' | 'updated' | 'archived', string>;
          confirmArchive: string;
        };
        devices: {
          title: string;
          subtitle: string;
          search: string;
          empty: string;
          column: Record<
            'code' | 'name' | 'product' | 'status' | 'accessStatus' | 'lastReceivedAt' | 'updatedAt',
            string
          >;
          action: Record<'create' | 'archive' | 'connection', string>;
          modal: Record<'create' | 'edit', string>;
          field: Record<'code' | 'name' | 'product' | 'status', string>;
          placeholder: Record<'product' | 'code', string>;
          status: Record<'enabled' | 'disabled' | 'archived', string>;
          validation: Record<'required', string>;
          message: Record<'created' | 'updated' | 'archived', string>;
          confirmArchive: string;
          connection: {
            title: string;
            boundary: string;
            configuration: string;
            diagnostics: string;
            accessDiagnostics: string;
            accessNotTested: string;
            observedAt: string;
            notTested: string;
            validation: string;
            saved: string;
            saveBeforeTest: string;
            testCompleted: string;
            result: Record<
              | 'testpassed'
              | 'testpartial'
              | 'testfailed'
              | 'configok'
              | 'dnsok'
              | 'dnstargetblocked'
              | 'dnstimeout'
              | 'dnsinterrupted'
              | 'dnsnotfound'
              | 'dnsfailed'
              | 'tcpok'
              | 'tcptimeout'
              | 'tcprefused'
              | 'tcpfailed'
              | 'tlsok'
              | 'tlstimeout'
              | 'tlsfailed'
              | 'tlsdisabled'
              | 'dependencyskipped'
              | 'firstmessagerequired'
              | 'activeendpointconfirmed'
              | 'accessonline'
              | 'datastale'
              | 'transportonly'
              | 'firstframewaiting'
              | 'firstframeconfirmed'
              | 'datafresh'
              | 'accesscheckfailed'
              | 'accessnotconfigured'
              | 'accessunknown'
              | 'activitysourceunavailable'
              | 'transportconnected'
              | 'transportwaiting'
              | 'transportsourceunavailable'
              | 'transportprobefailed'
              | 'credentialnotactive'
              | 'probebusy'
              | 'unknown',
              string
            >;
            action: Record<'save' | 'test' | 'checkAccess', string>;
            accessStatus: Record<
              'notconfigured' | 'waitingfordevice' | 'transportonly' | 'online' | 'stale' | 'failed' | 'unknown',
              string
            >;
            accessLayer: Record<'endpoint' | 'transport' | 'activity' | 'data', string>;
            accessStageStatus: Record<'passed' | 'failed' | 'waiting' | 'unknown' | 'skipped', string>;
            field: Record<
              'mode' | 'host' | 'port' | 'tls' | 'tlsServerName' | 'timeout' | 'keepAlive' | 'offlineThreshold',
              string
            >;
            placeholder: Record<'host' | 'tlsServerName', string>;
            mode: Record<'devicetoplatformmqtt' | 'devicetoplatformtcp' | 'platformtodevicetcp', string>;
            presentation: Record<
              'devicetoplatformmqtt' | 'devicetoplatformtcp' | 'platformtodevicetcp',
              Record<
                | 'description'
                | 'hostLabel'
                | 'hostPlaceholder'
                | 'portLabel'
                | 'tlsLabel'
                | 'tlsServerNameLabel'
                | 'timeoutLabel'
                | 'keepAliveLabel',
                string
              >
            >;
            stage: Record<'config' | 'dns' | 'tcp' | 'tls' | 'deviceconfirmation', string>;
            testStatus: Record<'notTested' | 'passed' | 'partial' | 'failed' | 'skipped', string>;
          };
        };
        strategy: {
          title: string;
          subtitle: string;
          policyName: string;
          productionHelp: string;
          section: Record<'context' | 'groups' | 'members' | 'draft' | 'publish' | 'history' | 'effective', string>;
          field: Record<
            | 'product'
            | 'device'
            | 'group'
            | 'groupKey'
            | 'groupName'
            | 'priority'
            | 'description'
            | 'threshold'
            | 'scope'
            | 'reason'
            | 'members'
            | 'version'
            | 'status'
            | 'hash'
            | 'release'
            | 'source',
            string
          >;
          action: Record<
            | 'newGroup'
            | 'editGroup'
            | 'saveGroup'
            | 'archiveGroup'
            | 'saveMembers'
            | 'newDraft'
            | 'saveDraft'
            | 'publish'
            | 'rollback'
            | 'refresh',
            string
          >;
          status: Record<'draft' | 'published' | 'valid' | 'invalid' | 'active' | 'archived' | 'publish' | 'rollback', string>;
          scope: Record<'product' | 'group' | 'device', string>;
          source: Record<'product' | 'group' | 'device' | 'unknown' | 'priority' | 'release', string>;
          empty: Record<'products' | 'groups' | 'devices' | 'versions' | 'releases' | 'effective' | 'members', string>;
          placeholder: Record<'enter' | 'select', string>;
          validation: Record<'required' | 'threshold' | 'scope' | 'reason' | 'draft', string>;
          message: Record<
            | 'groupCreated'
            | 'groupUpdated'
            | 'groupArchived'
            | 'membersUpdated'
            | 'draftCreated'
            | 'draftUpdated'
            | 'published'
            | 'rolledBack',
            string
          >;
          confirm: Record<'archiveGroup' | 'rollback', string>;
          error: Record<'title' | 'conflictTitle' | 'invalidTitle' | 'code' | 'details', string>;
          help: Record<'groupPriority' | 'scope' | 'members' | 'readonly', string>;
          text: Record<'seconds' | 'version' | 'sequence' | 'memberCount' | 'priority', string>;
        };
        models: {
          title: string;
          subtitle: string;
          product: string;
          publishHelp: string;
          tab: Record<'model' | 'protocol' | 'release', string>;
          version: string;
          selectVersion: string;
          selectProtocol: string;
          description: string;
          modelFields: string;
          protocolFields: string;
          status: Record<'draft' | 'published' | 'retired', string>;
          action: Record<'newModel' | 'addField' | 'publish' | 'newProtocol' | 'addMapping', string>;
          column: Record<
            'identifier' | 'name' | 'dataType' | 'unit' | 'access' | 'source' | 'sourceType' | 'target' | 'transform',
            string
          >;
          modal: Record<'field' | 'protocol' | 'mapping', string>;
          field: Record<
            | 'identifier'
            | 'nameZh'
            | 'dataType'
            | 'unit'
            | 'access'
            | 'required'
            | 'minimum'
            | 'maximum'
            | 'profileKey'
            | 'profileName'
            | 'protocolType'
            | 'messageType'
            | 'topicOrFrame'
            | 'sourcePath'
            | 'sourceType'
            | 'target'
            | 'transform'
            | 'scale'
            | 'offset'
            | 'expression',
            string
          >;
          dataType: Record<'integer' | 'decimal' | 'boolean' | 'string' | 'enum' | 'datetime' | 'geo_point', string>;
          access: Record<'read' | 'write' | 'read_write', string>;
          protocol: Record<'mqtt_json' | 'mqtt_binary' | 'tcp_binary', string>;
          messageType: Record<'telemetry' | 'state' | 'event' | 'command_reply', string>;
          transform: Record<'none' | 'scale' | 'offset' | 'scale_offset' | 'enum' | 'expression', string>;
          validation: Record<'required', string>;
          confirmPublishModel: string;
          confirmPublishProtocol: string;
          samples: {
            title: string;
            subtitle: string;
            unsaved: string;
            empty: string;
            editorTitle: string;
            action: Record<'add' | 'save' | 'run' | 'apply', string>;
            column: Record<'name' | 'expectation' | 'payload' | 'expected' | 'result' | 'errorCode', string>;
            expectation: Record<'success' | 'failure', string>;
            result: Record<'passed' | 'failed', string>;
            field: Record<
              | 'name'
              | 'sortOrder'
              | 'rawPayload'
              | 'expectedProperties'
              | 'expectedSuccess'
              | 'expectedErrorCode',
              string
            >;
            gate: Record<'passed' | 'failed' | 'summary', string>;
            validation: Record<'invalid' | 'empty' | 'saveBeforeTest', string>;
            message: Record<'saved', string>;
          };
          releases: {
            permissionMissing: string;
            publishTitle: string;
            publishSubtitle: string;
            gateReady: string;
            protocolNotPublished: string;
            publishReason: string;
            publishAction: string;
            evidenceTitle: string;
            refresh: string;
            refreshedAt: string;
            notRefreshed: string;
            empty: string;
            selectProtocol: string;
            sequence: string;
            detailTitle: string;
            nodesTitle: string;
            nodesCount: string;
            nodesEmpty: string;
            rollbackReason: string;
            rollbackAction: string;
            rollbackConfirm: string;
            rollbackWarning: string;
            column: Record<'sequence' | 'action' | 'reason' | 'createdAt', string>;
            action: Record<'publish' | 'rollback', string>;
            field: Record<'releaseId' | 'contentHash' | 'reason', string>;
            node: Record<'id' | 'type' | 'status' | 'hash' | 'updatedAt', string>;
            nodeType: Record<'gateway' | 'telemetry' | 'server', string>;
            nodeStatus: Record<'pending' | 'loaded' | 'failed', string>;
            validation: Record<'reason' | 'rollbackReason' | 'publishProtocol', string>;
            message: Record<'published' | 'rolledBack', string>;
          };
        };
        monitor: {
          title: string;
          subtitle: string;
          selectDevice: string;
          selectPlaceholder: string;
          propertyTitle: string;
          propertyHelp: string;
          noDevice: string;
          noDeviceDescription: string;
          connected: string;
          snapshot: string;
          updatedAt: string;
          waiting: string;
          quality: Record<'good' | 'uncertain' | 'bad' | 'timeUntrusted' | 'unknown', string>;
          property: Record<'engineRpm' | 'coolantTemperature' | 'vehicleSpeed' | 'fuelLevel', string>;
        };
        commands: {
          title: string;
          subtitle: string;
          empty: string;
          column: Record<'device' | 'template' | 'status' | 'createdAt', string>;
          action: Record<'create' | 'send' | 'confirmSend' | 'detail' | 'cancel' | 'confirmCancel', string>;
          modal: Record<'create' | 'cancel', string>;
          field: Record<
            | 'id'
            | 'device'
            | 'template'
            | 'status'
            | 'ttl'
            | 'timeout'
            | 'parameters'
            | 'reply'
            | 'errorCode'
            | 'reason'
            | 'workOrderNo'
            | 'cancelReason',
            string
          >;
          placeholder: Record<'device' | 'status' | 'template', string>;
          validation: Record<
            'required' | 'unsupportedParameters' | 'reasonRequired' | 'parametersRequired' | 'cancelReasonRequired',
            string
          >;
          confirm: Record<'title' | 'content', string>;
          message: Record<'created' | 'cancelled', string>;
          template: Record<'queryStatus' | 'setReportInterval' | 'requestLocation', string>;
          parameter: Record<'intervalSeconds', string>;
          detail: Record<'title' | 'timeline', string>;
          timeline: Record<'queued' | 'sent' | 'received', string>;
          status: Record<
            | 'created'
            | 'queued'
            | 'sent'
            | 'device_received'
            | 'succeeded'
            | 'refused'
            | 'failed'
            | 'expired'
            | 'timeout'
            | 'cancelled',
            string
          >;
        };
        fallback: Record<'unconfigured' | 'title' | 'description' | 'help', string>;
      };
      request: {
        logout: string;
        logoutMsg: string;
        logoutWithModal: string;
        logoutWithModalMsg: string;
        refreshToken: string;
        tokenExpired: string;
      };
      theme: {
        themeDrawerTitle: string;
        tabs: {
          appearance: string;
          layout: string;
          general: string;
          preset: string;
        };
        appearance: {
          themeSchema: { title: string } & Record<UnionKey.ThemeScheme, string>;
          grayscale: string;
          colourWeakness: string;
          themeColor: {
            title: string;
            followPrimary: string;
          } & Record<Theme.ThemeColorKey, string>;
          recommendColor: string;
          recommendColorDesc: string;
          themeRadius: {
            title: string;
          };
          preset: {
            title: string;
            apply: string;
            applySuccess: string;
            [key: string]:
              | {
                  name: string;
                  desc: string;
                }
              | string;
          };
        };
        layout: {
          layoutMode: { title: string } & Record<UnionKey.ThemeLayoutMode, string> & {
              [K in `${UnionKey.ThemeLayoutMode}_detail`]: string;
            };
          tab: {
            title: string;
            visible: string;
            cache: string;
            cacheTip: string;
            height: string;
            mode: { title: string } & Record<UnionKey.ThemeTabMode, string>;
            closeByMiddleClick: string;
            closeByMiddleClickTip: string;
          };
          header: {
            title: string;
            height: string;
            breadcrumb: {
              visible: string;
              showIcon: string;
            };
          };
          sider: {
            title: string;
            inverted: string;
            width: string;
            collapsedWidth: string;
            mixWidth: string;
            mixCollapsedWidth: string;
            mixChildMenuWidth: string;
            autoSelectFirstMenu: string;
            autoSelectFirstMenuTip: string;
          };
          footer: {
            title: string;
            visible: string;
            fixed: string;
            height: string;
            right: string;
          };
          content: {
            title: string;
            scrollMode: { title: string; tip: string } & Record<UnionKey.ThemeScrollMode, string>;
            page: {
              animate: string;
              mode: { title: string } & Record<UnionKey.ThemePageAnimateMode, string>;
            };
            fixedHeaderAndTab: string;
          };
        };
        general: {
          title: string;
          watermark: {
            title: string;
            visible: string;
            text: string;
            enableUserName: string;
            enableTime: string;
            timeFormat: string;
          };
          multilingual: {
            title: string;
            visible: string;
          };
          globalSearch: {
            title: string;
            visible: string;
          };
        };
        configOperation: {
          copyConfig: string;
          copySuccessMsg: string;
          resetConfig: string;
          resetSuccessMsg: string;
        };
      };
      route: Record<I18nRouteKey, string>;
      page: {
        login: {
          common: {
            loginOrRegister: string;
            userNamePlaceholder: string;
            phonePlaceholder: string;
            codePlaceholder: string;
            passwordPlaceholder: string;
            confirmPasswordPlaceholder: string;
            codeLogin: string;
            confirm: string;
            back: string;
            validateSuccess: string;
            loginSuccess: string;
            welcomeBack: string;
          };
          pwdLogin: {
            title: string;
            rememberMe: string;
            forgetPassword: string;
            register: string;
            otherAccountLogin: string;
            otherLoginMode: string;
            superAdmin: string;
            admin: string;
            user: string;
          };
          codeLogin: {
            title: string;
            getCode: string;
            reGetCode: string;
            sendCodeSuccess: string;
            imageCodePlaceholder: string;
          };
          register: {
            title: string;
            agreement: string;
            protocol: string;
            policy: string;
          };
          resetPwd: {
            title: string;
          };
          bindWeChat: {
            title: string;
          };
        };
        home: {
          branchDesc: string;
          greeting: string;
          weatherDesc: string;
          projectCount: string;
          todo: string;
          message: string;
          downloadCount: string;
          registerCount: string;
          schedule: string;
          study: string;
          work: string;
          rest: string;
          entertainment: string;
          visitCount: string;
          turnover: string;
          dealCount: string;
          projectNews: {
            title: string;
            moreNews: string;
            desc1: string;
            desc2: string;
            desc3: string;
            desc4: string;
            desc5: string;
          };
          creativity: string;
        };
      };
      form: {
        required: string;
        userName: FormMsg;
        phone: FormMsg;
        pwd: FormMsg;
        confirmPwd: FormMsg;
        code: FormMsg;
        email: FormMsg;
      };
      dropdown: Record<Global.DropdownKey, string>;
      icon: {
        themeConfig: string;
        themeSchema: string;
        lang: string;
        fullscreen: string;
        fullscreenExit: string;
        reload: string;
        collapse: string;
        expand: string;
        pin: string;
        unpin: string;
      };
      datatable: {
        itemCount: string;
        fixed: {
          left: string;
          right: string;
          unFixed: string;
        };
      };
    };

    type GetI18nKey<T extends Record<string, unknown>, K extends keyof T = keyof T> = K extends string
      ? T[K] extends Record<string, unknown>
        ? `${K}.${GetI18nKey<T[K]>}`
        : K
      : never;

    type I18nKey = GetI18nKey<Schema>;

    type TranslateOptions<Locales extends string> = import('vue-i18n').TranslateOptions<Locales>;

    interface $T {
      (key: I18nKey): string;
      (key: I18nKey, plural: number, options?: TranslateOptions<LangType>): string;
      (key: I18nKey, defaultMsg: string, options?: TranslateOptions<I18nKey>): string;
      (key: I18nKey, list: unknown[], options?: TranslateOptions<I18nKey>): string;
      (key: I18nKey, list: unknown[], plural: number): string;
      (key: I18nKey, list: unknown[], defaultMsg: string): string;
      (key: I18nKey, named: Record<string, unknown>, options?: TranslateOptions<LangType>): string;
      (key: I18nKey, named: Record<string, unknown>, plural: number): string;
      (key: I18nKey, named: Record<string, unknown>, defaultMsg: string): string;
    }
  }

  /** Service namespace */
  namespace Service {
    /** Other baseURL key */
    type OtherBaseURLKey = 'demo';

    interface ServiceConfigItem {
      /** The backend service base url */
      baseURL: string;
      /** The proxy pattern of the backend service base url */
      proxyPattern: string;
    }

    interface OtherServiceConfigItem extends ServiceConfigItem {
      key: OtherBaseURLKey;
    }

    /** The backend service config */
    interface ServiceConfig extends ServiceConfigItem {
      /** Other backend service config */
      other: OtherServiceConfigItem[];
    }

    interface SimpleServiceConfig extends Pick<ServiceConfigItem, 'baseURL'> {
      other: Record<OtherBaseURLKey, string>;
    }

    /** The backend service response data */
    type Response<T = unknown> = {
      /** The backend service response code */
      code: string;
      /** The backend service response message */
      msg: string;
      /** The backend service response data */
      data: T;
    };

    /** The demo backend service response data */
    type DemoResponse<T = unknown> = {
      /** The backend service response code */
      status: string;
      /** The backend service response message */
      message: string;
      /** The backend service response data */
      result: T;
    };
  }
}
