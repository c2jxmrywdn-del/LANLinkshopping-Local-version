package com.lanlink.shopping.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 透明字段加解密 TypeHandler：写库自动 AES-GCM 加密，读库自动解密。
 * 在实体字段上通过 @TableField(typeHandler = EncryptTypeHandler.class) 启用。
 */
@MappedTypes(String.class)
public class EncryptTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String param, JdbcType type) throws SQLException {
        ps.setString(i, CryptoService.inst().encrypt(param));
    }

    @Override
    public String getNullableResult(ResultSet rs, String col) throws SQLException {
        return CryptoService.inst().decrypt(rs.getString(col));
    }

    @Override
    public String getNullableResult(ResultSet rs, int idx) throws SQLException {
        return CryptoService.inst().decrypt(rs.getString(idx));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int idx) throws SQLException {
        return CryptoService.inst().decrypt(cs.getString(idx));
    }
}
