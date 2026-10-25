package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 运营端“加入我们”审核入参：approved=true 通过；false 驳回（驳回必须填备注）。 */
@Data
public class JoinReviewDTO {
    @NotNull(message = "请选择审核结果")
    private Boolean approved;

    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
