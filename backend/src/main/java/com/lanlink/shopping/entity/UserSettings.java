package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户系统设置：settings_json 整存 notify/appearance/privacy
 */
@Data
@TableName("t_user_settings")
public class UserSettings {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String settingsJson;
    private LocalDateTime updateTime;
}