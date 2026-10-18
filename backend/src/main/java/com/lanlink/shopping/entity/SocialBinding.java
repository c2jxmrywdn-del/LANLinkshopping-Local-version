package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 社交登录绑定：渠道内唯一标识 → 平台账号。
 * 与 t_third_auth（授权管理展示）不同，本表承载「登录」所需的稳定映射。
 */
@Data
@TableName("t_social_binding")
public class SocialBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** wechat / alipay / apple / google */
    private String provider;
    /** 渠道内唯一标识：微信 openid / 支付宝 user_id / Apple、Google 的 sub */
    private String subject;
    /** 微信开放平台 unionid（可为空） */
    private String unionId;
    private String nickname;
    private String avatar;
    private String email;
    /** 自动填充（插入时），由 MybatisMetaObjectHandler 统一赋值 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 自动填充（插入/更新时），由 MybatisMetaObjectHandler 统一赋值 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
