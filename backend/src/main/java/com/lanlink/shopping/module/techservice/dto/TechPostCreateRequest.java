package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TechPostCreateRequest {
    @NotBlank(message = "请选择内容类型")
    private String postType;
    @NotBlank(message = "请填写标题")
    @Size(max = 160, message = "标题不能超过 160 个字符")
    private String title;
    @NotBlank(message = "请填写内容")
    @Size(max = 4000, message = "内容不能超过 4000 个字符")
    private String content;
    private String tags;
    private String industry;
}
