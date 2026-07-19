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
      strategies: 'Strategy Workbench',
      monitor: 'Device Monitoring',
      governance: 'Data Governance',
      retention: 'Data Retention',
      aggregation: 'Numeric Aggregation',
      command: 'Command Management',
      commandCenter: 'Command Center',
      alarms: 'Alarm Management',
      alarmCenter: 'Alarm Center',
      overview: 'Operations Overview',
      mine: 'Mine Management',
      mineSites: 'Mine Sites',
      mineVehicles: 'Vehicles',
      minePeople: 'People',
      mineLocationTags: 'Location Tags',
      mineBindings: 'Bindings',
      mineLocation: 'Live Location',
      mineSpatial: 'Routes & Geofences'
    },
    context: {
      scopeRevoked: 'Your mine access changed. Select an authorized mine again.',
      invalidSelection: 'You are not authorized to access this mine. Select another mine.',
      loadFailed: 'Failed to load the authorized mine context.'
    },
    spatial: {
      title: 'Routes & Geofences Workbench',
      description: 'Manage immutable production routes, waypoints, and geofences, then trace runtime spatial events.',
      tab: {
        routes: 'Routes',
        waypoints: 'Waypoints',
        geofences: 'Geofences',
        events: 'Spatial Events'
      },
      object: {
        routes: { title: 'Route Objects' },
        waypoints: { title: 'Waypoint Objects' },
        geofences: { title: 'Geofence Objects' },
        create: 'New',
        createTitle: 'Create Spatial Object',
        code: 'Object Code',
        empty: 'No objects in this site',
        search: 'Search object code',
        loadFailed: 'Failed to load spatial objects',
        retry: 'Retry',
        archive: 'Archive',
        archiveTitle: 'Confirm Spatial Object Archive',
        archiveImpact: 'Archive “{code}”? Published versions and historical references remain permanently, but no new drafts can be created.',
        archiveAria: 'Archive spatial object {code}'
      },
      editor: {
        title: 'Draft & Geometry Editor',
        selectObject: 'Select an object from the left',
        noCoordinateTitle: 'Unified Coordinate System Required',
        noCoordinate: 'This site has no active unified coordinate system. Configure one under Location Tags / Coordinate Model first.',
        archivedTitle: 'Object Archived',
        archivedNotice: 'Archived objects and all historical versions are permanently read-only. Create a new stable object for further production configuration.',
        historyTitle: 'Historical Version Is Read-only',
        newDraftTitle: 'New Draft',
        newDraftNotice: 'A saved version remains a draft and does not affect production until published.'
      },
      field: {
        name: 'Version Name',
        coordinateSystem: 'Site Unified Coordinate System',
        versionNumber: 'Business Version',
        validFrom: 'Valid From',
        validTo: 'Valid To',
        isoTime: 'ISO 8601 time, e.g. 2026-07-19T08:00:00Z',
        optionalIsoTime: 'Optional; must be later than valid from',
        waypointType: 'Waypoint Type',
        geofenceType: 'Geofence Type',
        radiusMeters: 'Detection Radius (m)',
        z: 'Z Elevation (optional)',
        minZ: 'Minimum Elevation (optional)',
        maxZ: 'Maximum Elevation (optional)',
        direction: 'Route Direction',
        vehicleTypes: 'Applicable Vehicle Types'
      },
      version: {
        title: 'Versions & Audit',
        newDraft: 'New Draft',
        selectObject: 'Select an object to view versions',
        empty: 'No versions; create the first draft',
        status: { draft: 'Draft', published: 'Published' }
      },
      route: {
        sequenceTitle: 'Published Waypoint Sequence',
        addWaypoint: 'Add Waypoint',
        empty: 'A route requires at least two published waypoint versions',
        stopRequired: 'Stop Required',
        dwellSeconds: 'Expected Dwell Seconds'
      },
      policy: {
        title: 'Spatial Detection Policy',
        boundaryTolerance: 'Boundary Tolerance (m)',
        enterConfirm: 'Enter Confirmation Points',
        exitConfirm: 'Exit Confirmation Points',
        dwell: 'Dwell Threshold (s)',
        allowedLateness: 'Allowed Lateness (s)',
        idleFlush: 'Idle Flush (s)'
      },
      publish: {
        action: 'Publish Version',
        title: 'Confirm Spatial Version Publication',
        impact: '“{name}” v{version} passed topology checks, is valid from {validFrom} to {validTo}, and contains {references} spatial references. After publishing, it cannot be modified and the site runtime snapshot will refresh. Active spatial tracking will continue with the new runtime version. Continue?',
        confirm: 'Publish',
        noExpiry: 'No expiry',
        saveBeforePublish: 'The form contains unsaved changes. Save the draft before publishing.'
      },
      validation: {
        required: 'Complete all required fields',
        invalidDraft: 'Draft fields or geometry did not pass validation',
        invalidReplay: 'Provide a valid spatial version, reason, and historical window of no more than 31 days'
      },
      message: {
        objectCreated: 'Spatial object created; configure its first draft',
        draftSaved: 'Draft saved',
        published: 'Spatial version published; the runtime snapshot will refresh automatically',
        optimisticConflict: 'Another user changed this draft; the latest version has been loaded',
        archived: 'Spatial object archived; historical versions and references remain available'
      },
      event: {
        title: 'Spatial Event Stream',
        filterTitle: 'Event Filters',
        noPermissionTitle: 'Event Permission Required',
        noPermission: 'Ask an administrator to grant spatial event view permission.',
        zoneType: 'Spatial Object Type',
        zoneId: 'Spatial Object ID',
        subjectType: 'Business Subject Type',
        subjectId: 'Business Subject ID',
        sequence: 'Event Sequence',
        type: 'Event Type',
        zone: 'Spatial Object',
        subject: 'Business Subject',
        occurredAt: 'Occurred At',
        runtimeRevision: 'Runtime Revision',
        details: 'Evidence',
        loadMore: 'Load More',
        detailTitle: 'Spatial Event Evidence',
        classification: 'Point Classification',
        coordinate: 'Evaluated Coordinate',
        contentHash: 'Spatial Version Content Hash',
        loadFailed: 'Failed to load spatial events',
        stateTransition: 'State Transition',
        confirmationPoints: 'Confirmation Points',
        quality: 'Location Quality',
        anchorLocationEventId: 'Anchor Location Event'
      },
      replay: {
        action: 'Historical Replay',
        title: 'Register Spatial Event Replay',
        notice: 'Replays run asynchronously and never rewrite original events. Identical parameters are deduplicated with an idempotency key. A window cannot exceed 31 days.',
        zoneVersionId: 'Spatial Version ID',
        reason: 'Replay Reason',
        submit: 'Register Replay',
        created: 'Historical replay registered'
      },
      enum: {
        waypointType: { load: 'Load', unload: 'Unload', check: 'Check', wait: 'Wait', entry: 'Entry', exit: 'Exit' },
        geofenceType: { restricted: 'Restricted', work: 'Work', load: 'Load', unload: 'Unload', wait: 'Wait', safety: 'Safety', other: 'Other' },
        direction: { forward: 'Forward', reverse: 'Reverse', bidirectional: 'Bidirectional' },
        vehicleType: { haul_truck: 'Haul Truck', excavator: 'Excavator', loader: 'Loader', drill: 'Drill', dozer: 'Dozer', water_truck: 'Water Truck', light_vehicle: 'Light Vehicle', other: 'Other' },
        subjectType: { tag: 'Location Tag', vehicle: 'Vehicle', person: 'Person' },
        classification: { inside: 'Inside', outside: 'Outside', boundary: 'Boundary', height_unknown: 'Height Unknown' },
        quality: { good: 'Good', degraded: 'Degraded' },
        stableState: { outside: 'Outside', inside: 'Inside' },
        zoneType: { waypoint: 'Waypoint', geofence: 'Geofence' },
        eventType: { enter: 'Enter', exit: 'Exit', dwell: 'Dwell' }
      },
      geometry: {
        aria: {
          point: 'Waypoint geometry editor canvas',
          polyline: 'Route geometry editor canvas',
          polygon: 'Geofence geometry editor canvas'
        },
        xAxis: 'Unified X (m)',
        yAxis: 'Unified Y (m)',
        x: 'X',
        y: 'Y',
        empty: 'No coordinate points have been added',
        invalidTitle: 'Geometry validation is incomplete',
        invalid: {
          pointCount: 'A waypoint must contain exactly one coordinate point',
          polylineCount: 'A route requires at least two coordinate points',
          polygonCount: 'A polygon requires at least three vertices',
          finite: 'Coordinates must be valid finite numbers',
          duplicate: 'Duplicate coordinate points are not allowed',
          selfIntersection: 'Polygon edges must not intersect',
          area: 'Geofence area must be greater than 0.01 square metres'
        },
        historyReadonly: 'Published versions are permanently read-only. Create a new draft from history to edit.',
        coordinateAria: '{axis} coordinate for point {sequence}',
        moveUp: 'Move up',
        moveDown: 'Move down',
        removePoint: 'Remove',
        addPoint: 'Add coordinate point'
      }
    },
    assets: {
      context: {
        label: "Current mine",
        placeholder: "Select an authorized mine",
        refresh: "Refresh access",
        empty:
          "No mine is available. Ask an administrator to assign a data scope.",
      },
      common: {
        status: "Status",
        updatedAt: "Updated",
        version: "Data version",
      },
      status: { active: "Active", archived: "Archived" },
      action: { archive: "Archive" },
      validation: { required: "Complete all required fields." },
      message: {
        saved: "Saved",
        archived: "Archived",
        optimisticConflict:
          "Another user changed this record. The latest data has been loaded.",
      },
      organization: {
        code: "Organization code",
        name: "Organization name",
        search: "Search code or name",
        listTitle: "Mining organizations",
        create: "New organization",
        createTitle: "Create mining organization",
        editTitle: "Edit mining organization",
        empty: "No mining organizations",
        emptyGuide: "Create an organization before adding mine sites.",
        confirmArchive:
          "Archive “{name}”? Related mine sites must be handled first.",
      },
      site: {
        code: "Mine code",
        name: "Mine name",
        timezone: "Timezone",
        description: "Description",
        search: "Search mine code or name",
        listTitle: "Mine sites",
        create: "New mine",
        createTitle: "Create mine site",
        editTitle: "Edit mine site",
        empty: "No mine sites in this organization",
        selectOrganization: "Select organization",
        confirmArchive:
          "Archive “{name}”? New production data will no longer be accepted.",
      },
      vehicle: {
        code: "Vehicle code",
        name: "Vehicle name",
        typeLabel: "Vehicle type",
        mineNumber: "Mine number",
        licensePlate: "License plate",
        brand: "Brand",
        model: "Model",
        ratedLoad: "Rated load",
        tons: "{value} t",
        search: "Search vehicle code, name, or plate",
        listTitle: "Mine vehicles",
        create: "New vehicle",
        createTitle: "Create vehicle",
        editTitle: "Edit vehicle",
        empty: "No vehicles in this mine",
        emptyGuide:
          "Create a vehicle before binding devices, tags, and drivers.",
        confirmArchive: "Archive “{name}”?",
        status: {
          active: "Operating",
          maintenance: "Maintenance",
          outofservice: "Out of service",
          archived: "Archived",
        },
        type: {
          haultruck: "Haul truck",
          excavator: "Excavator",
          loader: "Loader",
          drill: "Drill",
          dozer: "Dozer",
          watertruck: "Water truck",
          lightvehicle: "Light vehicle",
          other: "Other",
        },
      },
      person: {
        code: "Person code",
        name: "Name",
        typeLabel: "Person type",
        phone: "Phone",
        certificate: "Certificate number",
        assignmentFrom: "Assignment start",
        assignmentTo: "Assignment end",
        search: "Search person code or name",
        listTitle: "Mine people",
        create: "New person",
        createTitle: "Create person",
        editTitle: "Edit person",
        empty: "No people in this mine",
        emptyGuide:
          "Create a person before managing qualifications and vehicle bindings.",
        confirmArchive: "Archive “{name}”?",
        sensitiveAction: "Sensitive data",
        sensitiveTitle: "Sensitive personal data",
        sensitiveNotice:
          "This access is audited. Do not copy the data to uncontrolled channels.",
        sensitiveCreateNotice:
          "Phone and certificate numbers are encrypted. Lists only show masked values by default.",
        qualificationAction: "Driver qualifications",
        qualificationTitle: "Driver qualifications",
        qualificationEmpty: "No driver qualifications",
        addQualification: "Add qualification",
        qualificationCode: "Qualification code",
        qualificationName: "Qualification name",
        allowedVehicleTypes: "Allowed vehicle types",
        qualificationValidFrom: "Valid from",
        qualificationValidTo: "Valid to",
        revokeQualification: "Revoke qualification",
        qualificationConfirmRevoke: "Revoke “{name}”?",
        qualificationExpired:
          "This qualification has expired and cannot authorize a driver binding.",
        type: {
          driver: "Driver",
          operator: "Operator",
          supervisor: "Supervisor",
          worker: "Worker",
          visitor: "Visitor",
          other: "Other",
        },
        status: {
          active: "Active",
          inactive: "Inactive",
          archived: "Archived",
        },
        qualificationStatus: {
          active: "Active",
          revoked: "Revoked",
          expired: "Expired",
        },
      },
      tag: {
        tab: "Location tags",
        code: "Tag code",
        vendor: "Vendor",
        model: "Model",
        battery: "Battery",
        batteryObservedAt: "Battery observed at",
        search: "Search tag code or vendor",
        create: "New tag",
        createTitle: "Create location tag",
        editTitle: "Edit location tag",
        empty: "No location tags in this mine",
        confirmArchive: "Archive tag “{code}”?",
        status: {
          active: "Active",
          lost: "Lost",
          retired: "Retired",
          archived: "Archived",
        },
      },
      coordinate: {
        tab: "Coordinate model",
        dualModelNotice:
          "Local engineering coordinates drive production calculations; unified coordinates support cross-system display. Published transforms are immutable.",
        systemList: "Coordinate systems",
        systemSearch: "Search coordinate system code or name",
        transformList: "Coordinate transform versions",
        createSystem: "New coordinate system",
        editSystem: "Edit coordinate system",
        createTransform: "New transform draft",
        editTransform: "Edit transform draft",
        code: "System code",
        name: "System name",
        typeLabel: "Coordinate type",
        unitLabel: "Unit",
        epsg: "EPSG code",
        range: "Valid range",
        rangeValue: "X {minX}–{maxX} · Y {minY}–{maxY}",
        unified: "Unified coordinate",
        unifiedYes: "Unified",
        unifiedNo: "Local",
        minX: "Minimum X",
        maxX: "Maximum X",
        minY: "Minimum Y",
        maxY: "Maximum Y",
        minZ: "Minimum Z (optional)",
        maxZ: "Maximum Z (optional)",
        source: "Source system",
        target: "Target system",
        version: "Version",
        algorithmLabel: "Algorithm",
        parameters: "Algorithm parameters JSON",
        validFrom: "Valid from",
        validTo: "Valid to (optional)",
        publish: "Publish",
        published: "Coordinate transform published",
        transformEmpty: "No coordinate transforms",
        invalidParameters: "Algorithm parameters must be valid JSON.",
        confirmArchive:
          "Archive “{name}”? Historical location references remain available.",
        confirmPublish:
          "Publishing permanently locks the transform parameters. Continue?",
        type: {
          localcartesian: "Local Cartesian",
          wgs84: "WGS84",
          enu: "East-North-Up",
        },
        unit: { meter: "Meter", millimeter: "Millimeter", degree: "Degree" },
        algorithm: {
          identity: "Identity",
          affine3d: "3D affine",
          wgs84enu: "WGS84/ENU",
        },
        transformStatus: { draft: "Draft", published: "Published" },
      },
      binding: {
        title: "Asset and person binding switch",
        description:
          "Preview the previous relation, qualifications, and impact before an atomic switch.",
        start: "Start switch",
        temporalNotice:
          "The system closes the previous relation and creates a new one atomically. Historical intervals cannot be edited directly.",
        drawerTitle: "Binding switch",
        typeLabel: "Binding type",
        resource: "Current resource",
        target: "New target",
        role: "Relation role",
        effectiveAt: "Effective at",
        reason: "Reason",
        preview: "Preview impact",
        execute: "Confirm switch",
        back: "Back",
        previous: "Previous relation",
        proposed: "New relation",
        none: "None",
        affectedObjects: "Affected objects",
        qualificationPassed: "Qualification passed",
        qualificationFailed: "Qualification failed",
        qualificationRequired:
          "This switch requires a valid driver qualification.",
        qualificationNotRequired:
          "This switch does not require a driver qualification.",
        required: "Select a resource and target, and enter a reason.",
        blocked: "The preview contains blockers and cannot be executed.",
        confirmSwitch:
          "This changes a production relation immediately. Execute the previewed switch?",
        switched: "Binding switch applied atomically",
        complete: "Switch complete",
        operationId: "Operation ID: {id}",
        type: {
          vehicledevice: "Vehicle—device",
          vehicletag: "Vehicle—tag",
          persontag: "Person—tag",
          personvehicle: "Person—vehicle",
        },
        impact: {
          resource: "Resource",
          previoustarget: "Previous target",
          newtarget: "New target",
        },
        object: {
          person: "Person",
          vehicle: "Vehicle",
          tag: "Location tag",
          device: "Device",
        },
        step: {
          configure: "Configure",
          preview: "Preview",
          complete: "Complete",
        },
      },
    },
    location: {
      title: "Live mine location",
      description: "View the latest vehicle, person, and tag positions in unified coordinates and inspect up to 24 hours of trajectory.",
      simulation: "Simulated location data",
      currentCount: "Current positions",
      degradedCount: "Degraded quality",
      staleCount: "Stale positions",
      source: "Snapshot source",
      sourceRedis: "Live cache",
      sourceTimescaledb: "History fallback",
      truncated: "The current-position limit was reached. The first 5,000 positions are shown.",
      status: { idle: "Select a mine", loading: "Loading current positions", connecting: "Connecting to live updates", live: "Live subscription active", reconnecting: "Network interrupted; reconnecting automatically", error: "Failed to load location data" },
      filter: { keyword: "Search tag, vehicle, or person", subject: "Subject type", quality: "Location quality", all: "All" },
      subject: { tag: "Tag", vehicle: "Vehicle", person: "Person", unbound: "Unbound tag" },
      quality: { good: "Good", degraded: "Degraded" },
      freshness: { fresh: "Fresh", stale: "Stale", offline: "Offline" },
      table: { tagCode: "Tag code", subject: "Business subject", coordinate: "Unified coordinate", speed: "Speed", accuracy: "Accuracy", occurredAt: "Occurred at", quality: "Quality", freshness: "Freshness", details: "Details" },
      detail: { title: "Location details", basic: "Current state", trajectory: "Trajectory", subject: "Trajectory subject", range: "Time range", load: "Load trajectory", noTrajectory: "No trajectory in this range", sampled: "The server sampled {count} source points for display", eventId: "Event ID", deviceId: "Source device", coordinateSystemId: "Coordinate system", sequence: "Ingest sequence" },
      range: { hour: "Last hour", sixHours: "Last 6 hours", day: "Last 24 hours" },
      spatial: {
        layerTitle: "Spatial runtime layers",
        regions: "Published regions",
        events: "Live events",
        degradedTitle: "Spatial layer degraded",
        degraded: "The spatial layer is unavailable. Current positions, trajectories, and asset functions are unaffected.",
        noticeTitle: "Latest spatial event",
        notice: "{subject} {type} {zone}",
        recentEvents: "Recent spatial events",
        loadingEvidence: "Loading persisted evaluation evidence",
        status: { disabled: "No permission", idle: "Select a mine", loading: "Loading", ready: "Ready", live: "Live", resyncing: "Recovering events", degraded: "Unavailable" }
      },
      canvas: { xAxis: "X / m", yAxis: "Y / m", current: "Current positions", trajectory: "Trajectory", events: "Live spatial events", empty: "No location data is available in this mine", ariaLabel: "2D mine location canvas" }
    },
    common: {
      action: 'Actions',
      edit: 'Edit',
      delete: 'Delete',
      empty: '—',
      allStatus: 'All statuses',
      enterPlaceholder: 'Enter {field}',
      selectPlaceholder: 'Select {field}',
      searchTitle: 'Search',
      search: 'Search',
      reset: 'Reset',
      cancel: 'Cancel',
      save: 'Save',
      saved: 'Saved',
      yes: 'Yes',
      no: 'No',
      fieldHelp: 'View help for “{field}”',
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
        column: { device: 'Device', status: 'Current status', lastReported: 'Last reported' },
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
      checking: 'Checking',
      checkProgress: '{completed}/{total} completed',
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
      listTitle: 'User List',
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
      listTitle: 'Role List',
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
        parent: 'Hierarchy Path',
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
      iconType: { iconify: 'Iconify Icon', local: 'Local Icon' },
      iconPicker: { choose: 'Select an icon', search: 'Search icon names', empty: 'No matching icons' },
      field: {
        name: 'Node Name',
        type: 'Node Type',
        parent: 'Parent Node',
        path: 'Route Path',
        component: 'Page Component',
        permission: 'Permission',
        iconType: 'Icon Type',
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
        iconType:
          'Use Iconify for general navigation or a bundled SVG local icon. Switching type clears the selection.',
        icon: 'Choose the navigation icon from the library. Button nodes usually need no icon; blank values use the fallback.',
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
      help: {
        code: 'Business APIs and pages use this code to load the dictionary. It must start with a lowercase letter, contain 3–64 lowercase letters, numbers, or underscores, and cannot be changed after creation.',
        name: 'Identifies this dictionary in administration pages. It can follow the terminology used by the business.',
        status: 'Disabled values are no longer offered by business forms. Existing stored values are not rewritten.',
        description: 'Documents the scope and maintenance rules for administrators. It does not affect business logic.',
        value: 'Stable business value submitted by APIs and stored in data. It cannot be changed after creation.',
        labelZh: 'Name shown in the Chinese interface. Keep it concise and consistent with business terminology.',
        labelEn: 'Preferred name in the English interface. The Chinese label is used when this is blank.',
        semantic: 'Controls the semantic theme color of labels. It does not change the value or business status.',
        sort: 'Items are ordered from the smallest number upward. Leave gaps of 10 for later insertions.',
        default:
          'Each dictionary type can have only one default item. Setting this item as default clears the previous default; each business form still decides whether to select it automatically.'
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
      listTitle: 'Product List',
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
      listTitle: 'Device List',
      subtitle: 'Maintain mine device identities and products used for access control and data tracing.',
      search: 'Search device name or code',
      empty: 'No devices match the current filters',
      column: {
        code: 'Device Code',
        name: 'Device Name',
        product: 'Product',
        status: 'Status',
        accessStatus: 'Access Status',
        lastReceivedAt: 'Last Report',
        updatedAt: 'Updated'
      },
      action: { create: 'Create Device', archive: 'Archive', connection: 'Connection' },
      modal: { create: 'Create Device', edit: 'Edit Device' },
      field: { code: 'Device Code', name: 'Device Name', product: 'Product', status: 'Status' },
      placeholder: { product: 'Select a published product', code: 'For example, truck-001' },
      status: { enabled: 'Enabled', disabled: 'Disabled', archived: 'Archived' },
      validation: { required: 'Select a product and enter a valid device code and name' },
      message: { created: 'Device created', updated: 'Device updated', archived: 'Device archived' },
      confirmArchive: 'Archive device “{name}”? Historical data will be retained.',
      connection: {
        title: '{name} · Device Onboarding',
        boundary:
          'Endpoint tests verify configuration, DNS, TCP, and TLS only. Inbound devices are not online until a real first message is received.',
        configuration: 'Connection Configuration',
        diagnostics: 'Layered Diagnostics',
        accessDiagnostics: 'Four-layer Access Check',
        accessNotTested:
          'Manually check endpoint reachability, transport session, valid first message, and data freshness.',
        observedAt: 'Observed at: {time}',
        notTested: 'Save the connection configuration to run a layered connectivity test.',
        validation: 'Enter a valid host, port, and connection parameters',
        saved: 'Device connection configuration saved',
        saveBeforeTest: 'Save the device connection configuration first',
        testCompleted: 'Endpoint diagnostics completed',
        result: {
          testpassed: 'The device onboarding path passed diagnostics',
          testpartial: 'The endpoint passed, but a real first device message is still required',
          testfailed: 'The endpoint test failed. Review the failed stage',
          configok: 'Connection configuration is valid',
          dnsok: 'DNS resolution succeeded',
          dnstargetblocked: 'The resolved address is outside the allowed mine network ranges',
          dnstimeout: 'DNS resolution timed out',
          dnsinterrupted: 'DNS resolution was interrupted',
          dnsnotfound: 'The domain does not exist or cannot be resolved from this node',
          dnsfailed: 'DNS resolution failed',
          tcpok: 'The TCP port accepted a connection',
          tcptimeout: 'The TCP connection timed out',
          tcprefused: 'The target port refused the connection',
          tcpfailed: 'The TCP connection failed',
          tlsok: 'The TLS handshake and hostname verification succeeded',
          tlstimeout: 'The TLS handshake timed out',
          tlsfailed: 'The TLS handshake or certificate verification failed',
          tlsdisabled: 'TLS is disabled for this connection',
          dependencyskipped: 'A prerequisite failed, so this stage was not run',
          firstmessagerequired: 'A real first device message is still required to confirm online status',
          activeendpointconfirmed: 'The platform connected to the device endpoint',
          accessonline: 'Device access is healthy and data is arriving',
          datastale: 'No valid data arrived within the offline threshold',
          transportonly: 'The transport session is connected, awaiting a valid first message',
          firstframewaiting: 'No valid first device message has been received',
          firstframeconfirmed: 'A valid first device message has been confirmed',
          datafresh: 'The latest data is within the offline threshold',
          accesscheckfailed: 'The device access check failed',
          accessnotconfigured: 'No access endpoint is configured for this device',
          accessunknown: 'The device access status cannot currently be determined',
          activitysourceunavailable: 'The telemetry activity source is temporarily unavailable',
          transportconnected: 'A Broker transport session was detected',
          transportwaiting: 'No Broker transport session was detected',
          transportsourceunavailable: 'No session probe is available for this connection mode',
          transportprobefailed: 'The transport session probe temporarily failed',
          credentialnotactive: 'The device credential is not active',
          probebusy: 'Too many diagnostics are running. Try again shortly',
          unknown: 'The diagnostic returned an unrecognized result'
        },
        action: { save: 'Save Configuration', test: 'Test Endpoint', checkAccess: 'Check Access Status' },
        accessStatus: {
          notconfigured: 'Not Configured',
          waitingfordevice: 'Waiting for Device',
          transportonly: 'Transport Only',
          online: 'Online',
          stale: 'Data Stale',
          failed: 'Access Failed',
          unknown: 'Unknown'
        },
        accessLayer: {
          endpoint: 'Endpoint Reachability',
          transport: 'Transport Session',
          activity: 'Valid First Message',
          data: 'Data Freshness'
        },
        accessStageStatus: {
          passed: 'Passed',
          failed: 'Failed',
          waiting: 'Waiting',
          unknown: 'Unknown',
          skipped: 'Skipped'
        },
        field: {
          mode: 'Connection Mode',
          host: 'Host or Domain',
          port: 'Port',
          tls: 'Validate TLS',
          tlsServerName: 'TLS Server Name',
          timeout: 'Connection Timeout (ms)',
          keepAlive: 'Keep Alive (seconds)',
          offlineThreshold: 'Offline Threshold (seconds)'
        },
        placeholder: { host: 'For example, mqtt.mine.local', tlsServerName: 'Uses the host when empty' },
        mode: {
          devicetoplatformmqtt: 'Device Connects to Platform MQTT',
          devicetoplatformtcp: 'Device Connects to Platform TCP',
          platformtodevicetcp: 'Platform Connects to Device TCP'
        },
        presentation: {
          devicetoplatformmqtt: {
            description:
              'The device connects to the platform MQTT Broker. A Broker session and valid first message are still required after the endpoint passes.',
            hostLabel: 'Broker Address',
            hostPlaceholder: 'For example, mqtt.mine.local',
            portLabel: 'MQTT Port',
            tlsLabel: 'Validate Broker TLS',
            tlsServerNameLabel: 'Broker TLS Server Name',
            timeoutLabel: 'Endpoint Test Timeout (ms)',
            keepAliveLabel: 'MQTT KeepAlive (seconds)'
          },
          devicetoplatformtcp: {
            description:
              'The device connects to the platform TCP listener. A real TCP session and valid first message are still required after the endpoint passes.',
            hostLabel: 'Platform TCP Listener Address',
            hostPlaceholder: 'For example, tcp.mine.local',
            portLabel: 'Platform Listener Port',
            tlsLabel: 'Validate TCP TLS',
            tlsServerNameLabel: 'Platform TLS Server Name',
            timeoutLabel: 'Endpoint Test Timeout (ms)',
            keepAliveLabel: 'Application Heartbeat (seconds)'
          },
          platformtodevicetcp: {
            description:
              'The platform connects to the device or edge gateway TCP endpoint. Confirm that its network, firewall, and certificate allow platform access.',
            hostLabel: 'Device IP or Domain',
            hostPlaceholder: 'For example, 10.20.30.40 or edge.mine.local',
            portLabel: 'Device TCP Port',
            tlsLabel: 'Validate Device TLS',
            tlsServerNameLabel: 'Device TLS Server Name',
            timeoutLabel: 'Device Connection Timeout (ms)',
            keepAliveLabel: 'Application Heartbeat (seconds)'
          }
        },
        stage: {
          config: 'Configuration',
          dns: 'DNS Resolution',
          tcp: 'TCP Connection',
          tls: 'TLS Handshake',
          deviceconfirmation: 'First Device Message'
        },
        testStatus: {
          notTested: 'Not Tested',
          passed: 'Passed',
          partial: 'Partially Passed',
          failed: 'Failed',
          skipped: 'Awaiting Confirmation'
        }
      }
    },
    strategy: {
      title: 'Strategy Workbench',
      subtitle:
        'Manage product, group, and device policies; verify target scope before release and explain each device’s final effective value.',
      type: {
        online_status: 'Online Status Policy',
        data_quality: 'Data Quality Policy',
        alarm: 'Alarm Policy',
        retention: 'Data Retention Policy',
        aggregation: 'Numeric Aggregation Policy'
      },
      productionHelp: {
        online:
          'A release immediately changes online-status evaluation. Verify the scope, final threshold, and conflict evidence, then provide an auditable business reason.',
        quality:
          'A release immediately changes telemetry quality evaluation and evidence. Verify time windows, property constraints, final sources, and provide an auditable business reason.',
        alarm:
          'An alarm release participates in real-time telemetry evaluation. Review scope, severity, durations, recovery hysteresis, and cooldown to prevent chatter or missed alarms.',
        retention:
          'A retention release lets the runtime delete expired data on schedule. Preview the current scope and verify the observation time and eligible rows first.',
        aggregation:
          'An aggregation release materializes long-lived numeric buckets. Verify fixed bucket widths, the late-correction window, and final scope before release.'
      },
      section: {
        context: 'Configuration Context',
        groups: 'Device Groups & Priority',
        members: 'Group Members',
        draft: 'Policy Draft',
        publish: 'Release Control',
        history: 'Immutable Release History',
        effective: 'Final Effective Device Policy',
        qualityRules: 'Property Quality Rules',
        alarmRules: 'Dual-source Alarm Rules',
        aggregationRules: 'Long-lived Aggregation Rules',
        retentionImpact: 'Deletion Impact Preview',
        retentionRollbackImpact: 'Rollback Deletion Impact',
        retentionExecutions: 'Recent Execution Facts'
      },
      field: {
        strategyType: 'Policy Type',
        product: 'Product',
        device: 'Validation Device',
        group: 'Device Group',
        groupKey: 'Group Key',
        groupName: 'Group Name',
        priority: 'Policy Priority',
        description: 'Description',
        threshold: 'Offline Threshold',
        scope: 'Release Scope',
        reason: 'Business Reason',
        members: 'Current Members',
        version: 'Policy Version',
        status: 'Status',
        hash: 'Content Hash',
        release: 'Release',
        source: 'Final Value Source',
        staleAfterSeconds: 'Stale Threshold',
        futureToleranceSeconds: 'Future Time Tolerance',
        propertyId: 'Thing Model Property',
        required: 'Required in Frame',
        minimum: 'Quality Minimum',
        maximum: 'Quality Maximum',
        alarmRuleKey: 'Rule Key',
        alarmRuleName: 'Rule Name',
        alarmSourceType: 'Alarm Source',
        alarmSeverity: 'Severity',
        alarmEnabled: 'Enabled',
        triggerDurationSeconds: 'Trigger Duration',
        recoverDurationSeconds: 'Recovery Duration',
        cooldownSeconds: 'Cooldown Window',
        alarmOperator: 'Comparison',
        alarmThreshold: 'Trigger Threshold',
        recoverThreshold: 'Recovery Threshold',
        qualityRuleCode: 'Quality Rule',
        optionalPropertyId: 'Thing Model Property (Optional)',
        retentionPolicy: 'Data Retention Policy',
        aggregationPolicy: 'Numeric Aggregation Policy',
        aggregationEnabled: 'Materialization Status',
        correctionWindowSeconds: 'Late-correction Window',
        bucketSeconds: 'Fixed Bucket Width',
        retentionEnabled: 'Execution Status',
        rawTelemetryDays: 'Raw Telemetry Retention',
        qualityEvidenceDays: 'Quality Evidence Retention',
        observedAt: 'Database Observation Time',
        targetDevices: 'Target Devices',
        affectedDevices: 'Affected Devices',
        enabledDevices: 'Deletion Still Enabled',
        earliestEligibleAt: 'Earliest Eligible Data',
        runtimeInstance: 'Runtime Instance'
      },
      action: {
        newGroup: 'New Group',
        editGroup: 'Edit Group',
        saveGroup: 'Save Group',
        archiveGroup: 'Archive Group',
        saveMembers: 'Save Membership',
        newDraft: 'New Draft',
        saveDraft: 'Save Draft',
        publish: 'Release Policy',
        rollback: 'Roll Back to This Release',
        refresh: 'Refresh Effective Value',
        addQualityRule: 'Add Property Rule',
        removeQualityRule: 'Remove',
        addNumericAlarmRule: 'Add Numeric Rule',
        addQualityAlarmRule: 'Add Quality Rule',
        removeAlarmRule: 'Remove Rule',
        copyAlarmRule: 'Copy',
        moveAlarmRuleUp: 'Move Up',
        moveAlarmRuleDown: 'Move Down',
        previewRetention: 'Refresh Impact Preview',
        previewRetentionRollback: 'Preview Rollback Impact',
        confirmRetentionRollback: 'Confirm Rollback',
        addAggregationRule: 'Add Aggregation Rule',
        removeAggregationRule: 'Remove Rule'
      },
      status: {
        draft: 'Draft',
        published: 'Published',
        valid: 'Valid',
        invalid: 'Invalid',
        active: 'Active',
        archived: 'Archived',
        publish: 'Publish',
        rollback: 'Rollback'
      },
      scope: {
        product: 'Product Default',
        group: 'Group Override',
        device: 'Device Override'
      },
      source: {
        product: 'Product Default',
        group: 'Group Override',
        device: 'Device Override',
        unknown: 'Unknown Source',
        priority: 'Priority {priority}',
        release: 'Release {release}'
      },
      empty: {
        products: 'No active products are available for configuration',
        groups: 'No device groups exist for this product',
        devices: 'No active devices exist for this product',
        versions: 'No online-status policy draft exists yet',
        releases: 'No policy releases exist yet',
        effective: 'Select a device to inspect its final effective policy',
        members: 'This group has no members',
        qualityRules: 'No property rules yet. Configure at least one time window or property rule.',
        alarmRules: 'No alarm rules yet. An alarm policy requires at least one rule.',
        retentionPreview: 'No impact preview exists for the current policy and scope.',
        retentionExecutions: 'The runtime has not reported any retention execution facts.',
        aggregationRules: 'Configure at least one property and fixed bucket-width rule.'
      },
      placeholder: {
        enter: 'Enter {field}',
        select: 'Select {field}',
        propertyId: 'For example, engine.rpm',
        alarmRuleKey: 'For example, engine-temperature-high',
        alarmRuleName: 'For example, Engine temperature high',
        optionalPropertyId: 'Leave empty for frame-level rules'
      },
      validation: {
        required: 'Complete all required fields',
        threshold: 'The offline threshold must be between 30 and 86400 seconds',
        scope: 'Select a target matching the release scope',
        reason: 'Enter at least 4 characters for the business reason',
        draft: 'Select a draft version first',
        stale: 'The stale threshold must be between 1 and 604800 seconds',
        future: 'Future time tolerance must be between 0 and 3600 seconds',
        property: 'A property identifier is invalid, duplicated, or missing',
        range: 'A property minimum cannot be greater than its maximum',
        qualityRequired: 'Configure at least one time window or property quality rule',
        alarmRuleCount: 'Configure between 1 and 100 alarm rules',
        alarmIdentity: 'A rule key is invalid or duplicated, or its name is empty',
        alarmDuration:
          'Durations must be integers; trigger and recovery cap at 86400 seconds and cooldown at 604800 seconds',
        alarmHysteresis:
          'A threshold is out of range, has more than 12 decimal places, or does not form valid recovery hysteresis',
        retentionEvidence: 'Quality evidence cannot expire before raw telemetry and cannot exceed 3650 days',
        retentionPolicy:
          'Retention days must be integers within the class range and quality evidence cannot be shorter than raw telemetry',
        retentionPreview: 'Preview the current scope before releasing a data retention policy',
        retentionSaveFirst: 'Save the modified retention draft before previewing or releasing it',
        aggregationCorrection: 'The correction window must be at least twice the largest bucket: {minimum} seconds',
        aggregationDuplicate: 'A property and bucket-width pair cannot be duplicated',
        aggregationPolicy: 'Configure 1 to 100 unique rules with a valid correction window and fixed bucket width'
      },
      message: {
        groupCreated: 'Device group created',
        groupUpdated: 'Device group updated',
        groupArchived: 'Device group archived',
        membersUpdated: 'Membership updated',
        draftCreated: 'Policy draft created',
        draftUpdated: 'Policy draft saved',
        published: 'Policy released and taking effect',
        rolledBack: 'A new rollback release was created',
        retentionPreviewLoaded: 'Point-in-time impact preview generated from the saved version and final overrides',
        retentionRollbackPreviewLoaded: 'Rollback impact is visible. Review it, then confirm the operation again.'
      },
      confirm: {
        archiveGroup:
          'Archive this device group? Current memberships will end while release history remains immutable.',
        rollback: 'Create a new rollback release from this historical release?',
        retentionPublish:
          'Preview observed at {observedAt}. Expired data will be permanently deleted and cannot be recovered. Continue?',
        retentionRollback:
          'Observed at {observedAt}: {target} targets, {affected} final changes, {enabled} with deletion enabled; {raw} raw rows and {quality} quality rows are eligible. Expired data will be permanently deleted and cannot be recovered. Continue?'
      },
      error: {
        title: 'Policy Operation Failed',
        conflictTitle: 'Policy Conflict Detected',
        invalidTitle: 'Final Policy Is Invalid',
        code: 'Error code: {code}',
        details: 'Conflict evidence: {details}'
      },
      help: {
        groupPriority: 'Higher numbers win. Conflicting values from groups at the same priority block the release.',
        scope:
          'A product default targets all devices. Select a group or device on the left before using narrower scopes.',
        members:
          'Members must belong to this product. Optimistic locking prevents overwriting another administrator’s update.',
        readonly: 'This account can inspect policies but cannot maintain or release them.',
        qualityTemplate:
          'Time windows apply to the whole telemetry frame. Property rules detect missing and out-of-range values. Unconfigured rules are ignored.',
        qualityRules:
          'Property identifiers must match the published thing model. Required and range rules produce auditable per-property quality evidence.',
        alarmTemplate:
          'Numeric rules compare telemetry properties directly; quality rules reuse data-quality evidence. Keep published rule keys stable for alarm correlation.',
        alarmRules:
          'Use duration to reject transients, a recovery threshold for hysteresis, and cooldown to limit repeated cycles.',
        alarmHysteresis:
          'High-value alarms require a recovery threshold no higher than the trigger; low-value alarms require one no lower than the trigger.',
        alarmAtomicOverride:
          'Alarm rules are released as one ordered set. A group or device override replaces the complete parent rule set; it does not append only this rule.'
      },
      text: {
        seconds: 'seconds',
        days: 'days',
        enabled: 'Enabled',
        disabled: 'Disabled',
        version: 'Version v{version}',
        sequence: 'Release #{sequence}',
        memberCount: '{count} devices',
        priority: 'Priority {priority}',
        onlineSummary: 'Offline after {seconds} seconds',
        qualitySummary: '{count} property rules',
        alarmSummary: '{count} alarm rules',
        retentionSummary: '{retentionClass} · Raw {rawDays} days · Quality {qualityDays} days',
        aggregationSummary: '{count} aggregation rules · Correct for {correction} seconds',
        affectedDeviceCount: '{count} devices',
        eligibleRows: '{count} eligible rows',
        eligibleRowsAtLeast: '{count}+ eligible rows',
        deletedRows: 'Raw {raw} rows · Quality {quality} rows',
        noEligibleTime: 'No eligible data'
      },
      retention: {
        groupChangeImpactTitle: 'Retention Impact of Group Change',
        groupChangePreviewLoaded: 'The group-change impact is visible. Review it, then submit again.',
        groupChangeSummary:
          '{action} affects {target} target devices at {observedAt}; {affected} final policies change and {enabled} remain deletion-enabled.',
        groupChangeConfirm:
          '{action} changes an active data-retention policy. Observed at {observedAt}: {target} targets, {affected} final changes, {enabled} remain deletion-enabled; {raw} raw rows and {quality} quality rows are eligible. Deleted expired data cannot be recovered. Continue?',
        presetTitle: 'Safe Retention Presets',
        presetHelp:
          'A preset atomically replaces the class and both retention windows. You can fine-tune within the class boundary.',
        class: { short: 'Short', standard: 'Standard', long: 'Long', archive: 'Archive' },
        classRange: 'This class allows raw telemetry retention from {minimum} to {maximum} days.',
        pauseHelp: 'Disabling only pauses future deletion runs and preserves policy history.',
        irreversibleRisk:
          'Expired data will be permanently deleted in batches. Deletion cannot be reversed; review the impact preview before release.',
        pausedRisk: 'Future deletion is paused, but data already deleted will not be restored.',
        rawSummary: 'Raw telemetry {days} days',
        qualitySummary: 'Quality evidence {days} days',
        executionStatus: { running: 'Running', succeeded: 'Succeeded', partial: 'Partial', failed: 'Failed' }
      },
      aggregation: {
        queryBoundary:
          'Complete cold buckets past the correction window use long-lived rollups; hot data and boundary buckets continue to use raw telemetry.',
        enabledHelp:
          'Disabling stops future materialization without deleting existing rollups or restoring cleaned raw data.',
        correctionHelp:
          'Late rows inside the window are included when a bucket first closes; older rows require explicit recomputation.',
        correctionSummary: 'Late-correction window: {seconds} seconds',
        ruleCount: '{count} rules. A narrower scope atomically replaces the parent rule set.',
        bucket: {
          '60': '1 minute',
          '300': '5 minutes',
          '900': '15 minutes',
          '3600': '1 hour',
          '21600': '6 hours',
          '86400': '1 day'
        }
      },
      qualityField: {
        required: 'Required · {property}',
        minimum: 'Minimum · {property}',
        maximum: 'Maximum · {property}'
      },
      alarm: {
        unnamedRule: 'Unnamed Rule',
        copySuffix: ' (Copy)',
        timing: 'Trigger {trigger}s · Recover {recover}s · Cooldown {cooldown}s',
        source: { numeric: 'Numeric Threshold', quality: 'Data Quality' },
        severity: { info: 'Info', warning: 'Warning', critical: 'Critical' },
        operator: { gt: 'Greater Than', gte: 'Greater or Equal', lt: 'Less Than', lte: 'Less or Equal' },
        qualityCode: {
          future_timestamp: 'Device Time Is in the Future',
          stale_timestamp: 'Device Time Is Stale',
          required_missing: 'Required Property Missing',
          below_minimum: 'Below Thing Model Minimum',
          above_maximum: 'Above Thing Model Maximum'
        }
      }
    },
    retentionGovernance: {
      title: 'Data Retention',
      subtitle:
        'Manage data lifecycle by product, group, or device; preview deletion impact before release and inspect runtime execution facts.'
    },
    aggregationGovernance: {
      title: 'Numeric Aggregation',
      subtitle:
        'Inspect versioned aggregation runtime facts and register auditable recomputation by device, property, and bounded time range.',
      boundary:
        'Telemetry schedules bounded regular aggregation. Explicit recomputation only creates a request fact; it does not calculate in the browser request. This page refreshes on demand and does not poll silently.',
      lastRefreshed: 'Refreshed at {time}',
      readonly: 'This account can inspect aggregation runtime facts but cannot register recomputation requests.',
      recomputeHelp:
        'Use only for late rows beyond the correction window or an evidenced gap. The request must match an active rule, span at most 31 days, and contain no more than 10,000 buckets.',
      executionSummary: '{rules} rules · {buckets} buckets generated · {late} late rows ignored',
      section: {
        recompute: 'Controlled Recomputation Request',
        latest: 'Latest Runtime Conclusion',
        executions: 'Automatic Aggregation Executions',
        requests: 'Explicit Recomputation Requests'
      },
      field: {
        product: 'Product',
        device: 'Device',
        propertyId: 'Numeric Property',
        bucket: 'Rule Bucket Width',
        range: 'Recomputation Range',
        reason: 'Recomputation Reason',
        status: 'Execution Status',
        startedAt: 'Started At',
        bucketCount: 'Buckets Generated',
        lateRowsIgnored: 'Late Rows Ignored',
        sourceWatermark: 'Source Ingestion Watermark',
        rollupWatermark: 'Rollup Version Watermark'
      },
      action: { refresh: 'Refresh Runtime Facts', submit: 'Register Recomputation' },
      placeholder: {
        propertyId: 'For example, engine.rpm',
        reason: 'Describe the gap, evidence, and intended repair range'
      },
      validation: {
        required: 'Select a device and provide a valid property, time range, and reason of at least 4 characters',
        range:
          'The range must align to UTC bucket boundaries, end in the past, span at most 31 days, and contain no more than 10,000 buckets'
      },
      confirm: {
        recompute:
          'Recomputation creates a higher-version fact and never overwrites history in place. Register this request?'
      },
      message: { accepted: 'Recomputation request registered; track its status in request history' },
      error: { title: 'Aggregation Governance Request Failed' },
      empty: {
        executions: 'No automatic aggregation executions exist yet',
        requests: 'No explicit recomputation requests exist yet'
      },
      status: { pending: 'Pending', running: 'Running', succeeded: 'Succeeded', partial: 'Partial', failed: 'Failed' },
      bucket: {
        PT1M: '1 minute',
        PT5M: '5 minutes',
        PT15M: '15 minutes',
        PT1H: '1 hour',
        PT6H: '6 hours',
        P1D: '1 day'
      }
    },
    models: {
      title: 'Thing Models and Protocol Mapping',
      subtitle:
        'Normalize vendor payloads into stable fields. Publish validated drafts without restarting site services.',
      product: 'Device Product',
      publishHelp:
        'Publish a thing model first, then map vendor MQTT/TCP fields. Publishing switches immutable versions and preserves history.',
      tab: { model: 'Unified Thing Model', protocol: 'Protocol Field Mapping', release: 'Release Control' },
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
      confirmPublishProtocol: 'Publish this protocol mapping? All target fields will be validated.',
      samples: {
        title: 'Protocol Golden Samples',
        subtitle:
          'Lock parsing behavior with real vendor payloads and replay them through the Gateway engine before release.',
        unsaved: 'Sample changes are not saved. Save them before running the release gate.',
        empty: 'Add the first real payload to establish a repeatable protocol baseline',
        editorTitle: 'Sample Draft',
        action: { add: 'Add Sample', save: 'Save Samples', run: 'Run Gate', apply: 'Apply to Draft' },
        column: {
          name: 'Sample',
          expectation: 'Expected Behavior',
          payload: 'Raw Payload',
          expected: 'Expected Result',
          result: 'Replay Result',
          errorCode: 'Actual Error Code'
        },
        expectation: { success: 'Must Parse', failure: 'Must Reject Safely' },
        result: { passed: 'Passed', failed: 'Failed' },
        field: {
          name: 'Sample Name',
          sortOrder: 'Display Order',
          rawPayload: 'Raw JSON Payload',
          expectedProperties: 'Expected Unified Properties JSON',
          expectedSuccess: 'Expect Successful Parse',
          expectedErrorCode: 'Expected Error Code'
        },
        gate: { passed: 'Release Gate Passed', failed: 'Release Gate Failed', summary: '{passed} / {total}' },
        validation: {
          invalid: 'Enter a sample name, valid JSON, and an expected error code for rejected samples',
          empty: 'Keep at least one golden sample',
          saveBeforeTest: 'Save the sample draft before running the release gate'
        },
        message: { saved: 'Golden samples saved' }
      },
      releases: {
        permissionMissing: 'Your account cannot view configuration release history.',
        publishTitle: 'Create Configuration Release',
        publishSubtitle: 'The server reruns every golden sample before producing an immutable configuration package.',
        gateReady: 'The selected protocol is published. Golden samples will be rerun on submission.',
        protocolNotPublished: 'Publish the selected protocol version before creating a runtime configuration.',
        publishReason: 'Enter the release reason, device batch, or change request number',
        publishAction: 'Validate and Release',
        evidenceTitle: 'Release Evidence',
        refresh: 'Refresh Status',
        refreshedAt: 'Last refreshed: {time}',
        notRefreshed: 'Release history has not been loaded',
        empty: 'This product has no configuration releases',
        selectProtocol: 'Select a protocol version to open release control',
        sequence: 'Release Sequence #{sequence}',
        detailTitle: 'Runtime Node Results',
        nodesTitle: 'Node Load Status',
        nodesCount: '{count} nodes reported',
        nodesEmpty: 'The release event exists and is waiting for runtime node reports',
        rollbackReason: 'Enter the rollback reason; rollback creates a higher release sequence',
        rollbackAction: 'Roll Back to This Configuration',
        rollbackConfirm: 'Confirm Rollback',
        rollbackWarning: 'A new release fact will reuse this historical package. Existing history remains immutable.',
        column: { sequence: 'Release Sequence', action: 'Action', reason: 'Reason', createdAt: 'Created' },
        action: { publish: 'Release', rollback: 'Rollback' },
        field: { releaseId: 'Release ID', contentHash: 'Content Hash', reason: 'Reason' },
        node: { id: 'Node', type: 'Node Type', status: 'Load Status', hash: 'Actual Hash', updatedAt: 'Last Report' },
        nodeType: { gateway: 'Gateway', telemetry: 'Telemetry', server: 'Management Server' },
        nodeStatus: { pending: 'Pending', loaded: 'Loaded', failed: 'Failed' },
        validation: {
          reason: 'Enter a release reason',
          rollbackReason: 'Enter a rollback reason',
          publishProtocol: 'Publish the protocol version first'
        },
        message: {
          published: 'Configuration release created; waiting for runtime nodes',
          rolledBack: 'Rollback release created'
        }
      }
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
    alarms: {
      title: 'Alarm Center',
      subtitle:
        'Review real-time alarm cycles, retain trigger and recovery evidence, and complete an auditable acknowledgement and closure loop.',
      listTitle: 'Alarm Cycles',
      pendingVisible: '{count} awaiting acknowledgement on this page',
      empty: 'No alarms match the current filters',
      column: {
        severity: 'Severity',
        status: 'Disposition Status',
        device: 'Device',
        rule: 'Alarm Rule',
        source: 'Source Property',
        raisedAt: 'Raised At'
      },
      action: { detail: 'Details', acknowledge: 'Acknowledge', close: 'Close Alarm' },
      placeholder: {
        status: 'All unclosed statuses',
        severity: 'All severities',
        device: 'Enter a device name or code',
        ruleKey: 'Rule key',
        from: 'Raised from',
        to: 'Raised before',
        note: 'Enter site contact, investigation result, or closure evidence'
      },
      field: {
        ruleKey: 'Rule Key',
        source: 'Alarm Source',
        raisedAt: 'Raised At',
        recoveredAt: 'Recovered At',
        acknowledgedAt: 'Acknowledged At',
        acknowledgement: 'Acknowledgement',
        closedAt: 'Closed At',
        closure: 'Closure',
        recoveryReason: 'Recovery Reason',
        policyRevision: 'Policy Runtime Revision',
        policyHash: 'Policy Content Hash',
        note: 'Disposition Note'
      },
      severity: { info: 'Info', warning: 'Warning', critical: 'Critical' },
      status: {
        active_unacknowledged: 'Active · Unacknowledged',
        active_acknowledged: 'Active · Acknowledged',
        recovered_unacknowledged: 'Recovered · Unacknowledged',
        recovered_acknowledged: 'Recovered · Acknowledged',
        closed: 'Closed'
      },
      source: { numeric: 'Numeric Threshold', quality: 'Data Quality' },
      recoveryReason: {
        condition_cleared: 'Condition Cleared',
        rule_disabled: 'Rule Disabled',
        rule_removed: 'Rule Removed',
        rule_changed: 'Rule Changed'
      },
      detail: {
        title: 'Complete Alarm Facts',
        triggerEvidence: 'Trigger Evidence',
        recoveryEvidence: 'Recovery Evidence',
        disposition: 'Disposition',
        noAction: 'No action is needed, or this account lacks the required disposition permission.',
        loading: 'Loading alarm details'
      },
      validation: { note: 'Enter at least 2 characters for the disposition note' },
      confirm: {
        ackTitle: 'Confirm Ownership',
        ackContent:
          'Acknowledgement permanently records the operator, time, and note without changing trigger or recovery facts.',
        closeTitle: 'Close Recovered Alarm',
        closeContent: 'Only recovered alarms can be closed. Complete evidence and disposition history remain available.'
      },
      message: { acknowledged: 'Alarm acknowledgement recorded', closed: 'Alarm closed' },
      error: { title: 'Alarm Operation Failed', code: 'Error code: {code}' }
    },
    commands: {
      title: 'Command Center',
      listTitle: 'Command List',
      subtitle: 'Send published low-risk device commands and track reliable delivery through device execution.',
      empty: 'No commands match the current filters',
      column: {
        product: 'Product',
        device: 'Device',
        template: 'Command Template',
        status: 'Status',
        createdAt: 'Created'
      },
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
        product: 'Product',
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
