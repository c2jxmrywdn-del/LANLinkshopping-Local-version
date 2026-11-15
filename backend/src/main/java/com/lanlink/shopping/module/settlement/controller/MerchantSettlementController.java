package com.lanlink.shopping.module.settlement.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.settlement.service.SettlementQueryService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商户端结算查询（收益汇总 / 收益台账 / 结算批次）。
 *
 * <p><b>越权隔离</b>：merId 一律来自会话（{@code MerchantService.getByUser(UserContext.currentUserId)}），
 * 不接受前端传参，因此不存在「改个 merId 读别人台账」的路径。未完成入驻返回 {@code R.fail}。
 *
 * <p>本切片商户端全为只读 GET（平台只记账不对私划转，二清红线），故无需 CSRF；
 * 权限由 {@code @RequirePerm("merchant:manage")} 把守。
 */
@RestController
@RequestMapping("/merchant/settlement")
public class MerchantSettlementController {

    private final SettlementQueryService query;
    private final MerchantService merchantService;

    public MerchantSettlementController(SettlementQueryService query, MerchantService merchantService) {
        this.query = query;
        this.merchantService = merchantService;
    }

    /** 当前登录用户所属商户（未入驻为 null，由各端点统一返回 R.fail）。 */
    private Merchant me(HttpServletRequest req) {
        return merchantService.getByUser(UserContext.currentUserId(req));
    }

    /** 四池汇总：待结算 / 可结算 / 已结算 / 平台累计佣金。 */
    @GetMapping("/summary")
    @RequirePerm("merchant:manage")
    public R<Object> summary(HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok((Object) query.merchantSummary(m.getMerId()));
    }

    /** 收益台账分页（EARN / REVERSE，status 可选过滤）。 */
    @GetMapping("/earnings")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> earnings(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String status,
                                           HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok(query.merchantEarnings(m.getMerId(), current, size, status));
    }

    /** 结算批次分页（settleNo / 账期 / totalNet / commissionTotal / status / payVoucher）。 */
    @GetMapping("/batches")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> batches(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok(query.merchantBatches(m.getMerId(), current, size));
    }
}
