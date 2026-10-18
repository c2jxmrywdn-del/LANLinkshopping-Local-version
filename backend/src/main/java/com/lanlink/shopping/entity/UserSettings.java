package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** 设置JSON（存储层内部格式，接口层解析为业务 Map 后外发，不直接序列化） */
    @JsonIgnore
    private String settingsJson;
    private LocalDateTime updateTime;
}
