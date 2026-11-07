package com.lanlink.shopping.module.techservice.dto;

import java.math.BigDecimal;

/** 交易本金监管台账登记（外部存管/分账/交易所凭证） */
public record TechEscrowCreateRequest(Long dealId, Long contractId, BigDecimal amount,
                                      String direction, String provider, String externalRef) {
}
