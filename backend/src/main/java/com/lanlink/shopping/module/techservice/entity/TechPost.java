package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_tech_post")
public class TechPost {
    @TableId(type = IdType.AUTO)
    private Long postId;
    private Long userId;
    private String postType;
    private String title;
    private String content;
    private String tags;
    private String industry;
    private String authorName;
    private String authorTitle;
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
