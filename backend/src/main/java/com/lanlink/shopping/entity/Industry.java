package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_industry")
public class Industry {
    @TableId(type = IdType.AUTO)
    private Long indId;
    private String name;
    private String code;
    private String configJson;
    private Integer sort;
    private LocalDateTime createTime;
}
