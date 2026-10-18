package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_industry")
public class Industry {
    @TableId(type = IdType.AUTO)
    private Long indId;
    private String name;
    private String code;
    /** 行业配置JSON（存储层内部格式，不外发） */
    @JsonIgnore
    private String configJson;
    private Integer sort;
    private LocalDateTime createTime;
}
