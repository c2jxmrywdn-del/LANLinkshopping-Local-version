package com.lanlink.shopping.module.sku.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SkuTreeSaveRequest {
    @NotNull(message = "规格不能为空") @Size(max = 3, message = "规格维度最多 3 个") @Valid
    private List<SpecIn> specs;

    @NotNull(message = "SKU 不能为空") @Size(max = 200, message = "SKU 最多 200 个") @Valid
    private List<SkuIn> skus;

    @Data
    public static class SpecIn {
        @NotBlank(message = "规格名不能为空") @Size(max = 20, message = "规格名需 1-20 字符")
        private String name;

        @NotNull(message = "规格值不能为空") @Size(min = 1, max = 30, message = "每维规格值需 1-30 个")
        private List<@NotBlank(message = "规格值不能为空") @Size(max = 40, message = "规格值需 1-40 字符") String> values;
    }

    @Data
    public static class SkuIn {
        @NotNull(message = "SKU 规格不能为空") @Size(min = 1, max = 3, message = "每个 SKU 须恰好每维一个值") @Valid
        private List<ValueRef> values;

        @NotNull(message = "价格不能为空") @DecimalMin(value = "0.01", message = "价格需 0.01-999999999999.99 且最多两位小数") @Digits(integer = 12, fraction = 2, message = "价格需 0.01-999999999999.99 且最多两位小数")
        private BigDecimal price;

        @NotNull(message = "库存不能为空") @Min(value = 0, message = "库存需为非负整数")
        private Integer stock;

        @Size(max = 64, message = "SKU 编码最多 64 字符")
        private String skuCode;
    }

    @Data
    public static class ValueRef {
        @Min(0)
        private int specIndex;

        @Min(0)
        private int valueIndex;
    }
}