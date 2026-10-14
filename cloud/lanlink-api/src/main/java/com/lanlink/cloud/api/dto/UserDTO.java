package com.lanlink.cloud.api.dto;

/**
 * 跨服务用户信息（UserApi 返回）。
 */
public class UserDTO {
    private Long userId;
    private String phone;
    private String nickname;
    private Long roleId;
    private Long entId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getEntId() { return entId; }
    public void setEntId(Long entId) { this.entId = entId; }
}
