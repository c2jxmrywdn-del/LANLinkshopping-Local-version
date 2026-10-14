package com.lanlink.shopping.dto;

import lombok.Data;

/**
 * 个人资料视图（merge：基础字段来自 t_user + 资料字段来自 t_user_profile）
 * 敏感字段（phone/email）出参时已由加密 TypeHandler 解密为明文。
 */
@Data
public class ProfileVO {
    private Long userId;
    private String realName;
    private String nickname;
    private String gender;
    private String birthday;
    private String avatar;
    private String bio;
    private String phone;
    private String email;
}