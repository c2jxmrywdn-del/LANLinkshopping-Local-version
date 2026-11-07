package com.lanlink.shopping.module.techservice.dto;

import java.math.BigDecimal;

/** 创建技术服务合同（从陪跑项目或交易生成） */
public record TechContractCreateRequest(Long projectId, Long dealId, String templateCode,
                                        String partyB, BigDecimal amount, BigDecimal commissionRate) {
}
