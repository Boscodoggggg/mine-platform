package com.bosco.mine.telemetry.alarm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * 为告警策略存在性和版本切换提供跨表、跨事务的设备级 PostgreSQL 咨询锁。
 */
final class AlarmPolicyAdvisoryLock {

    private static final long NAMESPACE = 0x414C41524D504F4CL;

    private AlarmPolicyAdvisoryLock() {
    }

    /** 在当前事务内取得设备告警策略共享锁。 */
    static void lockShared(Connection connection, UUID deviceId) throws SQLException {
        lock(connection, "SELECT pg_advisory_xact_lock_shared(?)", deviceId);
    }

    /** 在当前事务内取得设备告警策略排他锁。 */
    static void lockExclusive(Connection connection, UUID deviceId) throws SQLException {
        lock(connection, "SELECT pg_advisory_xact_lock(?)", deviceId);
    }

    private static void lock(Connection connection, String sql, UUID deviceId)
            throws SQLException {
        Objects.requireNonNull(connection, "告警策略锁连接不能为空");
        Objects.requireNonNull(deviceId, "告警策略锁设备标识不能为空");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key(deviceId));
            statement.executeQuery().close();
        }
    }

    private static long key(UUID deviceId) {
        return NAMESPACE
                ^ deviceId.getMostSignificantBits()
                ^ Long.rotateLeft(deviceId.getLeastSignificantBits(), 23);
    }
}
