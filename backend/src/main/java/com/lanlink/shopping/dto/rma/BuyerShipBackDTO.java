package com.lanlink.shopping.dto.rma;

import lombok.Data;

@Data
public class BuyerShipBackDTO {
    private String carrierName;
    private String waybillNo;
    private String remark;
}
