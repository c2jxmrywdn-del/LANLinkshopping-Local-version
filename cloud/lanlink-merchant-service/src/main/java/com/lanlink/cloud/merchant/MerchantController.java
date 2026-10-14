package com.lanlink.cloud.merchant;

import com.lanlink.cloud.api.dto.MerchantDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 商户契约实现（MerchantApi 提供方）。阶段 1 内存演示数据，阶段 2 接入 t_merchant + 筛选规则。
 */
@RestController
@RequestMapping("/merchants")
public class MerchantController {

    @GetMapping("/{id}")
    public R<MerchantDTO> getById(@PathVariable Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "商户ID不合法");
        MerchantDTO m = new MerchantDTO();
        m.setMerId(id);
        m.setUserId(3L);
        m.setName("示范供应商-" + id);
        m.setStatus(1); // 1=已通过审核
        return R.ok(m);
    }

    /** 阶段 2 占位：入驻申请（筛选规则：公司或个体户注资>10万且纳税稳定→通过） */
    @PostMapping("/apply")
    public R<MerchantDTO> apply(@RequestBody ApplyRequest req) {
        MerchantDTO m = new MerchantDTO();
        m.setMerId(999L);
        m.setName(req.name());
        m.setStatus(1);
        return R.ok("入驻申请已受理（骨架演示）", m);
    }

    public record ApplyRequest(String name, String companyType, BigDecimal registeredCapital) {}
}
