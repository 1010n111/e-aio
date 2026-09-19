package com.eaio.platform.infrastructure.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * {@code JSONB} 列 ↔ Java {@code String} 的映射（P1 册 4.3.4 的 {@code dict_item.ext_json}）。
 *
 * <p><b>为什么必须有它</b>：PG 不会把 {@code varchar} 隐式转成 {@code jsonb}，普通 String 绑定在
 * 写入时直接失败（{@code column "ext_json" is of type jsonb but expression is of type character varying}）。
 * 这里用 {@link Types#OTHER} 让驱动以"未知类型"发送文本，由 PG 按目标列自行定型——**不引 PG 专有类**
 * （{@code PGobject} 在 postgresql 驱动里，而 platform 模块编译期不依赖驱动，只能由应用壳提供）。
 *
 * <p>刻意**不**用 {@code @MappedTypes(String.class)} 全局注册：那会把所有 String 列都变成 jsonb 绑定。
 * 它只通过 {@code @TableField(typeHandler = ...)} 绑定到 {@code extJson} 一个字段。
 *
 * <p>覆写 {@code setParameter} 是必要的：MyBatis 的 {@link BaseTypeHandler} 在"值为 null 且
 * jdbcType 未指定"时抛 {@code TypeException}，而 MyBatis-Plus 生成的插入语句正是这种形态——
 * 不覆写会让"不填 extJson 的新增"直接失败。
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        if (parameter == null) {
            ps.setNull(i, Types.OTHER);
            return;
        }
        setNonNullParameter(ps, i, parameter, jdbcType);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
