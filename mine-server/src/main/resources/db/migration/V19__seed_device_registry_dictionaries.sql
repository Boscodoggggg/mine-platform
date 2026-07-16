-- 为设备台账预置类型与状态字典，字典负责展示选项，业务状态机仍由域模型约束。
INSERT INTO sys_dict_type (
    id, code, name, status, description, built_in, created_by, updated_by
) VALUES
    ('019f6b00-0000-7000-8000-000000000001', 'device_type', '设备类型', 'ENABLED',
     '产品所属的设备分类，用于设备台账选择和展示', TRUE, NULL, NULL),
    ('019f6b00-0000-7000-8000-000000000002', 'product_status', '产品状态', 'ENABLED',
     '产品生命周期状态展示选项，允许值由产品状态机约束', TRUE, NULL, NULL),
    ('019f6b00-0000-7000-8000-000000000003', 'device_status', '设备状态', 'ENABLED',
     '设备管理状态展示选项，允许值由设备状态机约束', TRUE, NULL, NULL);

INSERT INTO sys_dict_item (
    id, type_id, value, label_zh, label_en, semantic_type,
    sort_order, default_item, status, created_by, updated_by
) VALUES
    ('019f6b00-0000-7000-8100-000000000001', '019f6b00-0000-7000-8000-000000000001',
     'VEHICLE', '车辆设备', 'Vehicle', 'primary', 10, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000002', '019f6b00-0000-7000-8000-000000000001',
     'CONVEYOR', '输送设备', 'Conveyor', 'info', 20, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000003', '019f6b00-0000-7000-8000-000000000001',
     'PUMP', '泵类设备', 'Pump', 'info', 30, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000004', '019f6b00-0000-7000-8000-000000000001',
     'FAN', '通风设备', 'Fan', 'info', 40, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000005', '019f6b00-0000-7000-8000-000000000001',
     'ELECTRICAL', '电气设备', 'Electrical', 'warning', 50, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000006', '019f6b00-0000-7000-8000-000000000001',
     'SENSOR', '传感设备', 'Sensor', 'success', 60, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8100-000000000007', '019f6b00-0000-7000-8000-000000000001',
     'OTHER', '其他设备', 'Other', 'default', 70, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8200-000000000001', '019f6b00-0000-7000-8000-000000000002',
     'DRAFT', '草稿', 'Draft', 'warning', 10, TRUE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8200-000000000002', '019f6b00-0000-7000-8000-000000000002',
     'ACTIVE', '已发布', 'Active', 'success', 20, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8200-000000000003', '019f6b00-0000-7000-8000-000000000002',
     'ARCHIVED', '已归档', 'Archived', 'default', 30, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8300-000000000001', '019f6b00-0000-7000-8000-000000000003',
     'ENABLED', '启用', 'Enabled', 'success', 10, TRUE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8300-000000000002', '019f6b00-0000-7000-8000-000000000003',
     'DISABLED', '停用', 'Disabled', 'warning', 20, FALSE, 'ENABLED', NULL, NULL),
    ('019f6b00-0000-7000-8300-000000000003', '019f6b00-0000-7000-8000-000000000003',
     'ARCHIVED', '已归档', 'Archived', 'default', 30, FALSE, 'ENABLED', NULL, NULL);
