package com.lanlink.shopping.module.techservice.dto;

import java.math.BigDecimal;

/** 平台服务费登记（只登记服务费，不涉及交易本金） */
public record TechServiceFeeCreateRequest(String feeType, BigDecimal amount) {
}
