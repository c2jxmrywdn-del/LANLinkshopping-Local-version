package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanlink.shopping.crypto.EncryptTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户敏感信息：realName/idCard/bankAccount/address 字段级 AES-GCM 加密存储。
 */
@Data
@TableName(value = "t_user_profile", autoResultMap = true)
public class UserProfile {
    @TableId(type = IdType.INPUT)
    private Long userId;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String realName;
    private String gender;
    private String birthday;
    private String avatar;
    private String bio;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String phone;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String email;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String idCard;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String bankAccount;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String address;
    private LocalDateTime updateTime;
}
