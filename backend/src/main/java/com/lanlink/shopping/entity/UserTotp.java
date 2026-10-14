package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户 TOTP 两步验证：密钥 AES 密文存储
 */
@Data
@TableName("t_user_totp")
public class UserTotp {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String secret;
    private Integer enabled;
    private LocalDateTime firstVerifyTime;
    private LocalDateTime updateTime;
}