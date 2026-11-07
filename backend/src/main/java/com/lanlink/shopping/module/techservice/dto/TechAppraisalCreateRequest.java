package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TechAppraisalCreateRequest {
    private Long achievementId;
    @NotBlank(message = "请填写鉴定/评价对象名称")
    private String title;
    @NotNull(message = "请选择合作评价机构")
    private Long institutionId;
    private String expectedUse;
    private String scope;
}
