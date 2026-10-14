package com.lanlink.shopping.dto;

import lombok.Data;

/** 个人资料更新（含敏感字段，写库时按 TypeHandler 加密；nickname 属于 t_user 走 UserService） */
@Data
public class ProfileUpdateDTO {
    private String realName;
    private String nickname;
    private String gender;
    private String birthday;
    private String avatar;
    private String bio;
    private String phone;
    private String email;
    private String idCard;
    private String bankAccount;
    private String address;
}
