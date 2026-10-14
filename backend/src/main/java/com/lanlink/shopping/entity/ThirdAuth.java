package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 第三方应用授权
 */
@Data
@TableName("t_third_auth")
public class ThirdAuth {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String appName;
    private String appIcon;
    private String scopes;
    private LocalDateTime authTime;
    private LocalDateTime createTime;
}