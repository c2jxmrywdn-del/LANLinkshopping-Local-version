package com.lanlink.shopping.module.settlement.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.settlement.service.SettlementBatchService;
import com.lanlink.shopping.module.settlement.service.SettlementQueryService;
import com.lanlink.shopping.module.settlement.service.SettlementSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端结算运营（对账 / 生成批次 / 审核 / 打款登记 / 佣金率 / 手动对账）。
 *
 * <p>登录 + {@code @RequirePerm("admin:all")} + CSRF + 限流（{@code /admin/settlement/**}，
 * 见 WebConfig 的 CsrfInterceptor/AuthRateLimitInterceptor 与 AuthRateLimitInterceptor.POLICIES）。
 *
 * <p><b>二清红线</b>：pay 只登记线下对公打款的凭证号（{@code pay_voucher}），平台不代付、
 * 不调用任何支付渠道出金接口。
 */
@RestController
@RequestMapping("/admin/settlement")
public class AdminSettlementController {

    private final SettlementBatchService batch;
    private final SettlementQueryService query;
    private final SettlementSyncService sync;
    private final JdbcTemplate jdbc;

    public AdminSettlementController(SettlementBatchService batch, SettlementQueryService query,
                                     SettlementSyncService sync, JdbcTemplate jdbc) {
        this.batch = batch;
        this.query = query;
        this.sync = sync;
        this.jdbc = jdbc;
    }

    /** 手动触发一轮对账（与每日定时任务共用同一入口，全幂等）。 */
    @PostMapping("/sync")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> syncNow() {
        sync.syncAll();
        return R.ok(Map.of("ok", true));
    }

    /**
     * 生成批次：聚合该商户 AVAILABLE 台账为 DRAFT 批次并冻结明细（含已结转冲正的负向净额）。
     *
     * @param periodStart 账期起（ISO-8601，如 {@code 2026-11-01T00:00:00}），可空
     * @param periodEnd   账期止（ISO-8601），可空
     */
    @PostMapping("/generate")
    @RequirePerm("admin:all")
    public R<Object> generate(@RequestParam Long merId,
                              @RequestParam(required = false) String periodStart,
                              @RequestParam(required = false) String periodEnd) {
        LocalDateTime ps = periodStart == null ? null : LocalDateTime.parse(periodStart);
        LocalDateTime pe = periodEnd == null ? null : LocalDateTime.parse(periodEnd);
        return R.ok((Object) batch.generate(merId, ps, pe, jdbc));
    }

    /** 批次分页（merId / status 可选过滤）。 */
    @GetMapping("/list")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) Long merId,
                                       @RequestParam(required = false) String status) {
        return R.ok(query.adminList(current, size, merId, status));
    }

    /** 批次详情（批次摘要 + 明细快照）。批次不存在 404。 */
    @GetMapping("/detail/{settleId}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> detail(@PathVariable Long settleId) {
        return R.ok(query.adminDetail(settleId));
    }

    /** 审核：DRAFT → APPROVED（非法流转 400）。 */
    @PostMapping("/{settleId}/approve")
    @RequirePerm("admin:all")
    public R<Object> approve(@PathVariable Long settleId) {
        return R.ok((Object) batch.approve(settleId));
    }

    /** 打款登记：APPROVED → PAID，body {@code {payVoucher}}（线下对公打款凭证号，必填）。 */
    @PostMapping("/{settleId}/pay")
    @RequirePerm("admin:all")
    public R<Object> pay(@PathVariable Long settleId, @RequestBody Map<String, String> body) {
        return R.ok((Object) batch.pay(settleId, body.get("payVoucher")));
    }

    /** 设置/清除商户佣金率：body {@code {commissionRate}}，null 表示回落到全局默认。 */
    @PutMapping("/commission/{merId}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> setCommission(@PathVariable Long merId,
                                                @RequestBody Map<String, Object> body) {
        Object v = body.get("commissionRate");
        BigDecimal rate = v == null ? null : new BigDecimal(String.valueOf(v));
        batch.setCommissionRate(merId, rate);
        return R.ok(Map.of("ok", true));
    }
}
