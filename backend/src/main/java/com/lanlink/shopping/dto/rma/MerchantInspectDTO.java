package com.lanlink.shopping.dto.rma;

import lombok.Data;

@Data
public class MerchantInspectDTO {
    /** passed 合格入库 / damaged 货损异常 */
    private String inspectStatus;
    private String result;
}
