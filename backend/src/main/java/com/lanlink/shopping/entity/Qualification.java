package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_qualification")
public class Qualification {
    @TableId(type = IdType.AUTO)
    private Long qId;
    private Long merId;
    private String qType;
    private String qName;
    private String fileUrl;
    private LocalDate expireDate;
    private LocalDateTime createTime;
}
