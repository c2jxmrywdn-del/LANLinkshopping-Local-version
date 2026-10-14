package com.lanlink.shopping.dto;

import lombok.Data;

/** 用户敏感信息提交（写入时字段级加密） */
@Data
public class ProfileDTO {
    private String realName;
    private String idCard;
    private String bankAccount;
    private String address;
}
