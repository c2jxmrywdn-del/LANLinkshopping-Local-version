package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuditDTO {
    @NotBlank(message = "缺少操作类型")
    private String action;
    private String detail;
}