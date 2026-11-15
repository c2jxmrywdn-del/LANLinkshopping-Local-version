package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.mapper.MerchantCenterMapper;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateBlocker;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateReport;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 退店六门禁（只读聚合）。进入公示期（approve）与正式关闭（close）两处强制。
 *
 * <p>「结算全结清」= 无 DRAFT/APPROVED 批次 且 无 AVAILABLE EARN：
 * 没有 AVAILABLE EARN 时可结算净额必 ≤ 0（只可能残留负向冲正），故无需再单独查净额。
 * PENDING 收益（持有期未满）不阻断退店：结算批次仍可在闭店后生成，商户应收不受损。
 */
@Service
public class MerchantExitGateService {

    private final MerchantCenterMapper centerMapper;
    private final MerchantExitMapper exitMapper;
    private final MerchantDepositService depositService;
    private final MerchantExitProperties props;

    public MerchantExitGateService(MerchantCenterMapper centerMapper, MerchantExitMapper exitMapper,
                                   MerchantDepositService depositService, MerchantExitProperties props) {
        this.centerMapper = centerMapper;
        this.exitMapper = exitMapper;
        this.depositService = depositService;
        this.props = props;
    }

    public GateReport check(Long merId) {
        List<GateBlocker> blockers = new ArrayList<>();

        long openOrders = centerMapper.countPendingShip(merId) + centerMapper.countShipping(merId);
        if (openOrders > 0) {
            blockers.add(new GateBlocker("OPEN_ORDERS", "存在未履约订单", openOrders, "请先完成发货/确认收货"));
        }
        long refunds = centerMapper.countRefundPending(merId);
        if (refunds > 0) {
            blockers.add(new GateBlocker("REFUND_PENDING", "存在进行中的退款/售后", refunds, "请先处理退款/售后"));
        }
        long openBatches = exitMapper.countOpenBatches(merId);
        long availableEarnings = exitMapper.countAvailableEarnings(merId);
        if (openBatches > 0 || availableEarnings > 0) {
            blockers.add(new GateBlocker("SETTLEMENT_OPEN", "结算尚未结清", openBatches + availableEarnings,
                    "请先完成结算批次审核与打款"));
        }
        long onSale = exitMapper.countOnSale(merId);
        if (onSale > 0) {
            blockers.add(new GateBlocker("PRODUCTS_ON_SALE", "仍有商品在售", onSale, "请先将所有商品下架"));
        }
        BigDecimal balance = depositService.balanceOf(merId);
        if (balance.signum() < 0) {
            blockers.add(new GateBlocker("DEPOSIT_ARREARS", "保证金存在欠款", 1, "请先结清保证金欠款"));
        }
        int minDays = props.getMinCompleteDays();
        if (minDays > 0) {
            LocalDateTime last = exitMapper.lastFinishTime(merId);
            if (last != null && last.plusDays(minDays).isAfter(LocalDateTime.now())) {
                long remain = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDateTime.now(), last.plusDays(minDays)) + 1);
                blockers.add(new GateBlocker("TIME_GATE", "订单完成观察期未满", remain,
                        "最近一笔订单需完成满 " + minDays + " 天后方可退店"));
            }
        }
        return new GateReport(blockers.isEmpty(), blockers);
    }
}
