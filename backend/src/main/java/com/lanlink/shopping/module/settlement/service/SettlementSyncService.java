package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.settlement.config.SettlementProperties;
import com.lanlink.shopping.module.settlement.entity.MerchantEarning;
import com.lanlink.shopping.module.settlement.mapper.MerchantEarningMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结算账本同步：一轮对账 = 记入 EARN → 持有期满转 AVAILABLE → 退款冲正 REVERSE。
 *
 * <p>平台侧只读账本（不做资金二清）：本服务只写 t_merchant_earning，不触碰 t_order 的资金字段。
 *
 * <p>幂等三重保险：
 * <ol>
 *   <li>DB 唯一键 {@code uk_earning_line(order_no, item_id, type, refund_seq)}；</li>
 *   <li>插入前 {@code existsEarn} 预检，减少无谓写；</li>
 *   <li>插入包 {@link DuplicateKeyException}，并发下已插入即视为成功（静默跳过）。</li>
 * </ol>
 *
 * <p>订单/订单行只做只读查询，走 {@link JdbcTemplate} 而非 OrderMapper/OrderItemMapper，
 * 避免与订单模块产生编译期耦合。
 *
 * <p>金额口径：全程 BigDecimal + HALF_UP scale 2，落库 DECIMAL(14,2)。
 * 货值分摊（A2）：行货值 = round(subtotal × goods_amount / Σsubtotal, 2)，最后一行吸收尾差，
 * 保证 Σ行货值 == order.goods_amount（货款，不含运费）。
 *
 * <p><b>佣金/结算基数（P1-5）</b>：佣金与结算一律以 {@code t_order.goods_amount}（货款，不含运费）
 * 为基数，**禁止用含运费的 total_amount 代替**；存量单 {@code goods_amount IS NULL} 时回落
 * {@code total_amount}（存量单无独立运费，两者等价）。订单 total_amount 仍作为快照写入
 * {@code order_total} 列供对账。已记 EARN 行不重算（幂等见 {@code existsEarn}/唯一键），
 * 因此本次口径变更只影响上线后新完成订单，历史台账按入账时点口径保留、不回溯刷数。
 */
@Service
public class SettlementSyncService {

    /** 单轮同步最多处理的订单数：避免一次拉全表导致长事务持锁（配合幂等可多轮补齐）。 */
    static final int BATCH_ORDERS = 500;

    private final MerchantEarningMapper earningMapper;
    /** 保留以固定契约，Task 3 使用（批次服务需要写批次明细）。 */
    private final MerchantSettlementItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final SettlementProperties props;
    private final JdbcTemplate jdbc;

    public SettlementSyncService(MerchantEarningMapper earningMapper,
                                 MerchantSettlementItemMapper itemMapper,
                                 ProductMapper productMapper,
                                 MerchantMapper merchantMapper,
                                 SettlementProperties props,
                                 JdbcTemplate jdbc) {
        this.earningMapper = earningMapper;
        this.itemMapper = itemMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** 商户佣金率(%)：商户级覆盖（非 NULL）优先，否则回落全局默认。 */
    public BigDecimal resolveRate(Long merId) {
        Merchant m = merchantMapper.selectById(merId);
        if (m != null && m.getCommissionRate() != null) {
            return m.getCommissionRate();
        }
        return props.getDefaultCommissionRate();
    }

    /** 平台佣金 = round(gross × rate/100, 2)，HALF_UP。 */
    public BigDecimal computeCommission(BigDecimal gross, BigDecimal rate) {
        return gross.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * 行级货值分摊（A2）：round(subtotal × base / sumSubtotal, 2)；
     * 最后一行用「基数 − 已分摊」吸收尾差，使 Σ行货值 == base。
     *
     * @param subtotal      本行小计
     * @param base          分摊基数（订单货款 {@code goods_amount}；存量单回落 total_amount）
     * @param sumSubtotal   订单行小计之和
     * @param isLast        是否最后一行（尾差校正）
     * @param allocatedSoFar 此前各行已分摊之和
     */
    public BigDecimal allocateOrderGross(BigDecimal subtotal, BigDecimal base, BigDecimal sumSubtotal,
                                         boolean isLast, BigDecimal allocatedSoFar) {
        if (sumSubtotal == null || sumSubtotal.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (isLast) {
            return base.subtract(allocatedSoFar).setScale(2, RoundingMode.HALF_UP);
        }
        return subtotal.multiply(base).divide(sumSubtotal, 2, RoundingMode.HALF_UP);
    }

    /**
     * 行级退款冲正分摊（B1）：按行货值占比（等价于 subtotal/Σsubtotal）分摊本次退款增量；
     * 最后一行吸收尾差，使 Σ冲正 == 本次增量。
     */
    public BigDecimal allocateRefund(BigDecimal delta, BigDecimal lineGross, BigDecimal sumEarnGross,
                                     boolean isLast, BigDecimal allocatedSoFar) {
        if (sumEarnGross == null || sumEarnGross.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (isLast) {
            return delta.subtract(allocatedSoFar).setScale(2, RoundingMode.HALF_UP);
        }
        return delta.multiply(lineGross).divide(sumEarnGross, 2, RoundingMode.HALF_UP);
    }

    /** 一轮对账：记 EARN → 转 AVAILABLE → 冲正。幂等靠唯一键。 */
    @Transactional
    public void syncAll() {
        recordEarnings();
        promoteAvailable();
        reverseRefunds();
    }

    /**
     * 记入 EARN：已完成(order_status=2)且已支付(pay_status=1)的订单，逐行按分摊**货款**记一笔。
     *
     * <p>佣金基数 = {@code COALESCE(goods_amount, total_amount)}（货款不含运费；存量回落实付总额）。
     * 已记过的行（{@code existsEarn}）不会再记，故本口径只对新完成订单生效、历史不回溯重算。
     *
     * <p>available_at = NOW() + holdingDays（C1）；create_time 为不可变的持有期起点，
     * 不使用 order.update_time（订单后续改地址等操作不得延长/缩短持有期）。
     */
    private void recordEarnings() {
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT o.order_no, o.total_amount, "
                        + "COALESCE(o.goods_amount, o.total_amount) AS goods_amount "
                        + "FROM t_order o "
                        + "WHERE o.order_status=2 AND o.pay_status=1 "
                        + "AND EXISTS(SELECT 1 FROM t_order_item i WHERE i.order_no=o.order_no) "
                        // 限批：原实现一次拉全部历史已完成订单，订单表长大后单次同步会长时间持锁。
                        // 按时间正序取批，配合 existsEarn 幂等，多轮即可把积压全部记完。
                        + "ORDER BY o.create_time ASC LIMIT " + BATCH_ORDERS);

        if (orders.isEmpty()) return;

        /*
         * 批量预取，消除 N+1。
         *
         * 原实现对每单查一次订单行、对每行再查一次商品 + 一次幂等预检，
         * 总查询次数 = 订单数 + 2×行数（外加商户佣金率每次一查）。
         * 现在改为：订单行一次批量取回内存分组，商品与佣金率各一次批量查，
         * 已记 EARN 的行一次批量查。查询次数从 O(N) 降到常数级。
         */
        List<String> orderNos = new ArrayList<>(orders.size());
        for (Map<String, Object> row : orders) {
            orderNos.add((String) row.get("order_no"));
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderNos.size(), "?"));
        Object[] orderNoArgs = orderNos.toArray();

        // 1) 所有订单行一次取回，按订单号分组（保持 item_id 升序，尾差归属确定）
        Map<String, List<Map<String, Object>>> itemsByOrder = new HashMap<>();
        Set<Long> prodIds = new HashSet<>();
        for (Map<String, Object> it : jdbc.queryForList(
                "SELECT i.order_no, i.item_id, i.prod_id, COALESCE(i.subtotal,0) AS subtotal "
                        + "FROM t_order_item i WHERE i.order_no IN (" + placeholders + ") ORDER BY i.item_id",
                orderNoArgs)) {
            String no = (String) it.get("order_no");
            itemsByOrder.computeIfAbsent(no, k -> new ArrayList<>()).add(it);
            prodIds.add(((Number) it.get("prod_id")).longValue());
        }

        // 2) 商品 → 商户归属（一次批量）
        Map<Long, Long> merIdByProd = new HashMap<>();
        if (!prodIds.isEmpty()) {
            String prodPh = String.join(",", java.util.Collections.nCopies(prodIds.size(), "?"));
            for (Map<String, Object> p : jdbc.queryForList(
                    "SELECT prod_id, mer_id FROM t_product WHERE prod_id IN (" + prodPh + ")",
                    prodIds.toArray())) {
                Object merId = p.get("mer_id");
                if (merId != null) {
                    merIdByProd.put(((Number) p.get("prod_id")).longValue(), ((Number) merId).longValue());
                }
            }
        }

        // 3) 商户佣金率（一次批量；NULL 表示回落全局默认）
        Map<Long, BigDecimal> rateByMer = new HashMap<>();
        if (!merIdByProd.isEmpty()) {
            String merPh = String.join(",", java.util.Collections.nCopies(merIdByProd.size(), "?"));
            for (Map<String, Object> m : jdbc.queryForList(
                    "SELECT mer_id, commission_rate FROM t_merchant WHERE mer_id IN (" + merPh + ")",
                    merIdByProd.values().toArray())) {
                Object rate = m.get("commission_rate");
                if (rate != null) {
                    rateByMer.put(((Number) m.get("mer_id")).longValue(), toMoney(rate));
                }
            }
        }

        // 4) 已记 EARN 的行（一次批量），键为 "orderNo#itemId"
        Set<String> alreadyEarned = new HashSet<>();
        for (Map<String, Object> e : jdbc.queryForList(
                "SELECT order_no, item_id FROM t_merchant_earning "
                        + "WHERE type='EARN' AND refund_seq=0 AND order_no IN (" + placeholders + ")",
                orderNoArgs)) {
            alreadyEarned.add(e.get("order_no") + "#" + ((Number) e.get("item_id")).longValue());
        }

        for (Map<String, Object> row : orders) {
            String orderNo = (String) row.get("order_no");
            BigDecimal total = toMoney(row.get("total_amount"));
            // 佣金/结算基数：货款（不含运费）。SQL 已 COALESCE 兜底存量单；Java 侧再兜一层，
            // 兼容旧库/测试桩只返回 total_amount 的情况（回落实付总额）。
            BigDecimal goodsBase = nullableMoney(row.get("goods_amount"));
            if (goodsBase == null) {
                goodsBase = total;
            }
            // subtotal 列可空（DECIMAL(14,2) 无 NOT NULL），脏数据 NULL 会在 reduce 里 NPE 并回滚整轮同步，
            // 故 SQL 侧 COALESCE 兜底 + 读取侧 null 再兜一层；ORDER BY item_id 保证尾差归属确定。
            List<Map<String, Object>> items = itemsByOrder.getOrDefault(orderNo, List.of());
            if (items.isEmpty()) continue;
            BigDecimal sumSub = items.stream()
                    .map(r -> toMoney(r.get("subtotal")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal allocated = BigDecimal.ZERO;
            for (int k = 0; k < items.size(); k++) {
                Map<String, Object> it = items.get(k);
                Long itemId = ((Number) it.get("item_id")).longValue();
                Long prodId = ((Number) it.get("prod_id")).longValue();
                BigDecimal sub = toMoney(it.get("subtotal"));
                boolean last = (k == items.size() - 1);
                BigDecimal gross = allocateOrderGross(sub, goodsBase, sumSub, last, allocated);
                allocated = allocated.add(gross);
                Long merId = merIdByProd.get(prodId);
                if (merId == null) {
                    continue; // 无商户归属的商品不记入（平台自营等）
                }
                if (alreadyEarned.contains(orderNo + "#" + itemId)) {
                    continue; // 幂等（DB 唯一键兜底）
                }
                BigDecimal rate = rateByMer.getOrDefault(merId, props.getDefaultCommissionRate());
                BigDecimal commission = computeCommission(gross, rate);
                MerchantEarning e = new MerchantEarning();
                e.setMerId(merId);
                e.setOrderNo(orderNo);
                e.setItemId(itemId);
                e.setProdId(prodId);
                e.setType("EARN");
                e.setRefundSeq(0);
                e.setGrossAmount(gross);
                e.setCommissionRate(rate);
                e.setCommissionAmount(commission);
                e.setNetAmount(gross.subtract(commission));
                e.setStatus("PENDING");
                e.setAvailableAt(LocalDateTime.now().plusDays(props.getHoldingDays()));
                e.setOrderSubtotal(sumSub);
                e.setOrderTotal(total); // 快照保留订单实付总额（含运费），供对账；佣金基数已排除运费
                e.setCreateTime(LocalDateTime.now());
                try {
                    earningMapper.insert(e);
                } catch (DuplicateKeyException dup) {
                    // 并发下已插入同一行，唯一键兜底，忽略
                }
            }
        }
    }

    /** 该订单行是否已记过 EARN（refund_seq=0）。 */
    private boolean existsEarn(String orderNo, Long itemId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_merchant_earning WHERE order_no=? AND item_id=? AND type='EARN' AND refund_seq=0",
                Long.class, orderNo, itemId);
        return c != null && c > 0;
    }

    /**
     * 金额列读取兜底：可空 DECIMAL 读成 NULL 时按 0 处理。
     * 单行 NULL 若直接参与 BigDecimal 运算会 NPE，进而回滚整个 {@code @Transactional} 同步。
     */
    private static BigDecimal toMoney(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return (v instanceof BigDecimal) ? (BigDecimal) v : new BigDecimal(v.toString());
    }

    /** 可空金额读取：NULL 保留 null 供调用方回落（与 {@link #toMoney} 的 0 语义区分）。 */
    private static BigDecimal nullableMoney(Object v) {
        if (v == null) {
            return null;
        }
        return (v instanceof BigDecimal) ? (BigDecimal) v : new BigDecimal(v.toString());
    }

    /** 持有期满（C1）：PENDING 且 available_at<=NOW() 的 EARN 行转 AVAILABLE。 */
    private void promoteAvailable() {
        jdbc.update("UPDATE t_merchant_earning SET status='AVAILABLE' "
                + "WHERE type='EARN' AND status='PENDING' AND available_at<=NOW()");
    }

    /**
     * 退款冲正（B1）：订单级累计退款额 vs 已冲正总额，增量按行货值占比分摊；
     * refund_seq 逐次递增，支持多次部分退；全额退时所有 EARN 置 REVERSED。
     *
     * <p>佣金率口径（#2）：REVERSE 行必须沿用被冲 EARN 行的 {@code commission_rate} 快照，
     * 不得回查商户当前费率。否则商户费率在「完成 → 退款」之间被改过时
     * Σ(REVERSE.commission) != Σ(EARN.commission)，全额退后商户净额不为零。
     *
     * <p>状态机（#1/#3）：全额退的状态迁移在增量分配之前独立执行，且只作用于
     * PENDING/AVAILABLE，绝不覆盖已 SETTLED（已进结算批次）的行。
     */
    private void reverseRefunds() {
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT o.order_no, o.total_amount, "
                        + "COALESCE(o.goods_amount, o.total_amount) AS goods_amount, "
                        + "o.refund_amount FROM t_order o "
                        + "WHERE o.refund_amount IS NOT NULL AND o.refund_amount > 0 "
                        + "AND EXISTS(SELECT 1 FROM t_merchant_earning e "
                        + "WHERE e.order_no=o.order_no AND e.type='EARN')");
        for (Map<String, Object> row : orders) {
            String orderNo = (String) row.get("order_no");
            BigDecimal refund = toMoney(row.get("refund_amount"));
            BigDecimal total = toMoney(row.get("total_amount"));
            // 全额退判定用「货款」基数（P1-5）：已发货订单运费不退（OrderAmounts.refundableTotal），
            // refund_amount == goods_amount 即货款全退，必须把 EARN 置 REVERSED，否则会继续结算给商户。
            BigDecimal goodsBase = nullableMoney(row.get("goods_amount"));
            if (goodsBase == null) {
                goodsBase = total;
            }
            BigDecimal alreadyReversed = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(gross_amount),0) FROM t_merchant_earning "
                            + "WHERE order_no=? AND type='REVERSE'",
                    BigDecimal.class, orderNo);
            if (alreadyReversed == null) {
                alreadyReversed = BigDecimal.ZERO;
            }
            // commission_rate 一并取出：REVERSE 佣金必须复用 EARN 的费率快照（见方法注释 #2）。
            List<Map<String, Object>> earnRows = jdbc.queryForList(
                    "SELECT earning_id, item_id, mer_id, gross_amount, commission_rate FROM t_merchant_earning "
                            + "WHERE order_no=? AND type='EARN' ORDER BY earning_id", orderNo);
            BigDecimal sumEarnGross = earnRows.stream()
                    .map(r -> toMoney(r.get("gross_amount")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal delta = refund.subtract(alreadyReversed);
            // B1 上界：累计冲正不得超过已记入货值。全额退（refund_amount>=goods_amount）时
            // 即便退款额含未发货退回的运费等溢出部分，也保证 Σ(REVERSE.gross) == Σ(EARN.gross)，不会多冲。
            BigDecimal reversable = sumEarnGross.subtract(alreadyReversed);
            if (delta.compareTo(reversable) > 0) {
                delta = reversable;
            }
            // 全额退（货款全退）→ EARN 置 REVERSED。此迁移必须独立于 delta>0 的判断：
            // 若某订单行在 recordEarnings 被跳过（商品无商户归属 / 已软删），Σ(EARN.gross) < goods_amount，
            // 全额退时 alreadyReversed 已等于 ΣEARN ⇒ delta 收敛为 0；若把本 UPDATE 放在 delta 守卫之后，
            // 订单会永远停在 PENDING/AVAILABLE 并被结算付款给商户（Critical #1）。
            // 状态守卫排除 SETTLED：已进批次的钱不能被事后改状态（Important #3）。该 UPDATE 幂等，可重复执行。
            if (refund.compareTo(goodsBase) >= 0) {
                jdbc.update("UPDATE t_merchant_earning SET status='REVERSED' "
                        + "WHERE order_no=? AND type='EARN' AND status IN ('PENDING','AVAILABLE')", orderNo);
            }
            if (delta.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // 本轮无新增退款（但全额退的状态迁移已在上面执行）
            }
            BigDecimal alloc = BigDecimal.ZERO;
            for (int k = 0; k < earnRows.size(); k++) {
                Map<String, Object> er = earnRows.get(k);
                Long earningId = ((Number) er.get("earning_id")).longValue();
                Long itemId = ((Number) er.get("item_id")).longValue();
                Long merId = ((Number) er.get("mer_id")).longValue();
                BigDecimal eg = toMoney(er.get("gross_amount"));
                boolean last = (k == earnRows.size() - 1);
                BigDecimal lineRev = allocateRefund(delta, eg, sumEarnGross, last, alloc);
                alloc = alloc.add(lineRev);
                if (lineRev.signum() <= 0) {
                    continue;
                }
                // EARN 行落库时的佣金率快照；NULL 才回落当前费率（历史脏数据兜底）
                BigDecimal rate = er.get("commission_rate") != null
                        ? toMoney(er.get("commission_rate")) : resolveRate(merId);
                // 该行已冲正次数 → 本次 refund_seq（支持多次部分退，配合唯一键幂等）
                Integer maxSeq = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(refund_seq),0) FROM t_merchant_earning "
                                + "WHERE order_no=? AND item_id=? AND type='REVERSE'",
                        Integer.class, orderNo, itemId);
                BigDecimal commission = computeCommission(lineRev, rate);
                MerchantEarning rev = new MerchantEarning();
                rev.setMerId(merId);
                rev.setOrderNo(orderNo);
                rev.setItemId(itemId);
                rev.setProdId(0L); // 冲正行无商品归属，prod_id 置 0（满足 NOT NULL）
                rev.setType("REVERSE");
                rev.setRefundSeq((maxSeq == null ? 0 : maxSeq) + 1);
                rev.setGrossAmount(lineRev);
                rev.setCommissionRate(rate);
                rev.setCommissionAmount(commission.negate());
                rev.setNetAmount(lineRev.subtract(commission).negate());
                rev.setStatus("REVERSED");
                rev.setRefEarningId(earningId);
                rev.setOrderSubtotal(sumEarnGross);
                rev.setOrderTotal(total);
                rev.setCreateTime(LocalDateTime.now());
                try {
                    earningMapper.insert(rev);
                } catch (DuplicateKeyException dup) {
                    // 并发下已冲正，唯一键兜底，忽略
                }
            }
        }
    }
}
