package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.MerchantCenterMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.vo.MerchantOrderVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户中心聚合服务：工作台汇总与资金结算概览。
 * 金额口径：本商户商品在订单明细中的小计（不含平台运费与积分分摊）。
 */
@Service
public class MerchantCenterService {

    private final MerchantService merchantService;
    private final MerchantCenterMapper centerMapper;
    private final ProductMapper productMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final OrderService orderService;
    private final com.lanlink.shopping.service.MerchantChatService chatService;

    /**
     * @Lazy 防御性解耦：OrderService 依赖 MerchantMapper/ProductMapper 等同域组件，
     * 若后续有人在其构造器反向注入本服务会成环；@Lazy 代理打破潜在环，勿删。
     */
    public MerchantCenterService(MerchantService merchantService, MerchantCenterMapper centerMapper,
                                 ProductMapper productMapper, EnterpriseMapper enterpriseMapper,
                                 @Lazy OrderService orderService,
                                 com.lanlink.shopping.service.MerchantChatService chatService) {
        this.merchantService = merchantService;
        this.centerMapper = centerMapper;
        this.productMapper = productMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.orderService = orderService;
        this.chatService = chatService;
    }

    /** 工作台汇总：店铺信息 + 核心指标 + 待办 + 近 7 日趋势 + 最新订单 */
    public Map<String, Object> summary(Long userId) {
        Merchant m = mustMerchant(userId);
        Long merId = m.getMerId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("store", storeView(m));

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalProducts", centerMapper.countProducts(merId, null));
        kpis.put("onSaleProducts", countOnSale(merId));
        kpis.put("reviewingProducts", centerMapper.countProducts(merId, 0));
        kpis.put("rejectedProducts", centerMapper.countProducts(merId, 2));
        kpis.put("pendingShip", centerMapper.countPendingShip(merId));
        kpis.put("shipping", centerMapper.countShipping(merId));
        kpis.put("refundPending", centerMapper.countRefundPending(merId));
        Map<String, Object> recent = centerMapper.sumRecent(merId, 7);
        kpis.put("weekAmount", dec(recent.get("amount")));
        kpis.put("weekOrders", num(recent.get("orders")));
        // 客户消息未读（买家↔商家对话）：商户中心侧栏「客户消息」角标
        kpis.put("chatUnread", chatService.merchantUnread(userId));
        out.put("kpis", kpis);

        out.put("trend", fillTrend(centerMapper.trendByDay(merId, 7), 7));

        // 最新订单（商户视角：仅含本商户商品的订单）
        Page<MerchantOrderVO> latest = orderService.merchantOrders(userId, 1, 5, null, null, null, null);
        out.put("recentOrders", latest.getRecords());

        // 待办清单（仅非零项返回，前端按需渲染）
        List<Map<String, Object>> todos = new ArrayList<>();
        addTodo(todos, "ship", "待发货订单", kpis.get("pendingShip"), "/merchant/center/orders?tab=paid");
        addTodo(todos, "refund", "待处理退款申请", kpis.get("refundPending"), "/merchant/center/orders?tab=refund");
        addTodo(todos, "review", "审核中的商品", kpis.get("reviewingProducts"), "/merchant/center/products?reviewStatus=0");
        addTodo(todos, "reject", "被驳回的商品", kpis.get("rejectedProducts"), "/merchant/center/products?reviewStatus=2");
        out.put("todos", todos);
        return out;
    }

    /** 资金结算概览：累计收入/退款/待结算 + 近 30 日收入趋势 + 结算明细 */
    public Map<String, Object> finance(Long userId) {
        Merchant m = mustMerchant(userId);
        Long merId = m.getMerId();

        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal income = centerMapper.sumIncomeTotal(merId);
        BigDecimal refund = centerMapper.sumRefundTotal(merId);
        out.put("totalIncome", income);
        out.put("totalRefund", refund);
        out.put("netIncome", income.subtract(refund));
        out.put("pendingSettle", centerMapper.sumPendingSettle(merId));

        Map<String, Object> month = centerMapper.sumRecent(merId, 30);
        out.put("monthIncome", dec(month.get("amount")));
        out.put("monthOrders", num(month.get("orders")));

        out.put("trend", fillTrend(centerMapper.trendByDay(merId, 30), 30));
        out.put("settlements", centerMapper.settlementRows(merId, 50));
        return out;
    }

    /** 补零对齐的按日趋势 */
    private List<Map<String, Object>> fillTrend(List<Map<String, Object>> rows, int days) {
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) by.put(String.valueOf(r.get("date")), r);
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate cur = LocalDate.now().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            String k = cur.format(DateTimeFormatter.ISO_LOCAL_DATE);
            Map<String, Object> r = by.get(k);
            if (r == null) {
                r = new LinkedHashMap<>();
                r.put("date", k);
                r.put("amount", BigDecimal.ZERO);
                r.put("orders", 0L);
            }
            trend.add(r);
            cur = cur.plusDays(1);
        }
        return trend;
    }

    private Map<String, Object> storeView(Merchant m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merId", m.getMerId());
        s.put("regType", m.getRegType());
        s.put("joinType", m.getJoinType());
        s.put("reviewStatus", m.getReviewStatus());
        s.put("createTime", m.getCreateTime());
        s.put("taxRegNo", m.getTaxRegNo());
        s.put("licenseUrl", m.getLicenseUrl());
        s.put("taxProofUrls", m.getTaxProofUrls());
        s.put("hasSpecialPermit", m.getHasSpecialPermit());
        s.put("specialPermitName", m.getSpecialPermitName());
        s.put("specialPermitUrl", m.getSpecialPermitUrl());
        // 联系方式（加密存储、读取时已解密）：发货弹窗寄件人预填 / 店铺设置回显使用，仅商户本人可见
        s.put("contactPhone", m.getContactPhone());
        s.put("contactEmail", m.getContactEmail());
        s.put("businessAddress", m.getBusinessAddress());
        if (m.getEntId() != null) {
            Enterprise ent = enterpriseMapper.selectById(m.getEntId());
            s.put("entName", ent == null ? null : ent.getName());
            s.put("creditCode", ent == null ? null : ent.getCreditCode());
        }
        return s;
    }

    /** 在售商品数：可售(status=1) 且审核通过(review_status=1)，排除脏数据（如待审核却误置可售） */
    private long countOnSale(Long merId) {
        return productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getMerId, merId)
                .eq(Product::getStatus, 1)
                .eq(Product::getReviewStatus, 1));
    }

    private void addTodo(List<Map<String, Object>> list, String key, String label, Object count, String link) {
        if (count instanceof Long l && l > 0) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("key", key);
            t.put("label", label);
            t.put("count", l);
            t.put("link", link);
            list.add(t);
        }
    }

    private Merchant mustMerchant(Long userId) {
        Merchant m = merchantService.getByUser(userId);
        if (m == null) throw new BusinessException("仅入驻商户可访问商户中心");
        return m;
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o));
    }

    private static long num(Object o) {
        return o == null ? 0 : Long.parseLong(String.valueOf(o));
    }
}
