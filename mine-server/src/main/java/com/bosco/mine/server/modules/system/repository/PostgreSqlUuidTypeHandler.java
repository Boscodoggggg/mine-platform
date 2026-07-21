package com.bosco.mine.server.modules.system.repository;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * 显式处理 PostgreSQL 原生 UUID 与 Java UUID 的双向转换。
 *
 * <p>该处理器主要用于 MyBatis 无法从集合元素或单列结果推断 UUID 类型的场景。</p>
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class PostgreSqlUuidTypeHandler extends BaseTypeHandler<UUID> {

    /**
     * 将 Java UUID 写入 PostgreSQL 原生 UUID 参数。
     *
     * @param statement 预编译语句
     * @param index     参数位置
     * @param parameter UUID 参数
     * @param jdbcType  JDBC 类型
     * @throws SQLException 数据库访问异常
     */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            UUID parameter,
            JdbcType jdbcType
    ) throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    /**
     * 按列名读取 UUID。
     *
     * @param resultSet 结果集
     * @param columnName 列名
     * @return UUID，数据库值为空时返回空
     * @throws SQLException 数据库访问异常
     */
    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return toUuid(resultSet.getObject(columnName));
    }

    /**
     * 按列序号读取 UUID。
     *
     * @param resultSet 结果集
     * @param columnIndex 列序号
     * @return UUID，数据库值为空时返回空
     * @throws SQLException 数据库访问异常
     */
    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return toUuid(resultSet.getObject(columnIndex));
    }

    /**
     * 从存储过程结果读取 UUID。
     *
     * @param statement 存储过程调用
     * @param columnIndex 列序号
     * @return UUID，数据库值为空时返回空
     * @throws SQLException 数据库访问异常
     */
    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return toUuid(statement.getObject(columnIndex));
    }

    /**
     * 将驱动返回值规范化为 UUID。
     *
     * @param value 驱动返回值
     * @return UUID 或空
     */
    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
