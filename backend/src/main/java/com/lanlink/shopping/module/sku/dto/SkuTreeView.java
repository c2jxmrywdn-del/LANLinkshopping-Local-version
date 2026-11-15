package com.lanlink.shopping.module.sku.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SkuTreeView {
    private List<SpecOut> specs;
    private List<SkuOut> skus;

    @Data
    public static class SpecOut {
        private Long specId;
        private String name;
        private List<ValueOut> values;
    }

    @Data
    public static class ValueOut {
        private Long valueId;
        private String value;
    }

    @Data
    public static class SkuOut {
        private Long skuId;
        private String skuCode;
        private String specValueIds;
        private String specText;
        private BigDecimal price;
        private Integer stock;
        private Integer status;
    }
}