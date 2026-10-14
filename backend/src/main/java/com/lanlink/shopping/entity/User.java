package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long userId;
    private String phone;
    @JsonIgnore
    private String password;
    private String nickname;
    private Long roleId;
    private Long entId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}
