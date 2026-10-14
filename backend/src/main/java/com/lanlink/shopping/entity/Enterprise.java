package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_enterprise")
public class Enterprise {
    @TableId(type = IdType.AUTO)
    private Long entId;
    private String name;
    private String creditCode;
    private Integer memberLevel;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
