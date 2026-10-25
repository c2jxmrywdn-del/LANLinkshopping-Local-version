package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.ValidationRules;
import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.IdempotentToken;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.TamperAlert;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 篡改攻击测试（需求 5）：模拟第三方工具（Postman/curl）绕过前端校验直接改参数。
 * 覆盖：非法支付方式、负积分、幂等重放、支付/退款状态机并发篡改、金额一致性比对、
 * 前后端白名单同步守护。每个场景断言"请求被拒绝 + 篡改告警落库"。
 */
class TamperAttackSimulationTest {

    private OrderMapper orderMapper;
    private ProductMapper productMapper;
    private TamperAlertMapper tamperAlertMapper;
    private List<TamperAlert> capturedAlerts;
    private TamperGuardService tamperGuardService;
    private IdempotentTokenMapper idempotentTokenMapper;
    private IdempotentService idempotentService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        productMapper = mock(ProductMapper.class);
        tamperAlertMapper = mock(TamperAlertMapper.class);
        capturedAlerts = new ArrayList<>();
        // 真实篡改告警服务 + 捕获落库告警（断言告警可追溯，需求 4）
        when(tamperAlertMapper.insert(any(TamperAlert.class))).thenAnswer(inv -> {
            capturedAlerts.add(inv.getArgument(0));
            return 1;
        });
        tamperGuardService = new TamperGuardService(tamperAlertMapper, mock(AuditService.class));
        idempotentTokenMapper = mock(IdempotentTokenMapper.class);
        idempotentService = new IdempotentService(idempotentTokenMapper);

        orderService = new OrderService(
                mock(CartMapper.class), productMapper, orderMapper, mock(OrderItemMapper.class),
                mock(UserMapper.class), mock(MessageService.class), mock(IdentityService.class),
                mock(AuditService.class), mock(PricingFacade.class), mock(ApplicationEventPublisher.class),
                mock(MembershipService.class), mock(CreditTermService.class),
                idempotentService, tamperGuardService,
                mock(com.lanlink.shopping.module.campaign.service.CampaignService.class));
    }

    private void assertAlertOn(String fieldName) {
        assertTrue(capturedAlerts.stream().anyMatch(a -> fieldName.equals(a.getFieldName())),
                "应产生字段 " + fieldName + " 的篡改告警落库");
    }

    // ===== 攻击 1：篡改 payType 为白名单外值 =====

    @Test
    void attackIllegalPayTypeRejectedAndAlerted() {
        CheckoutDTO dto = new CheckoutDTO();
        dto.setPayType("hack_channel"); // 绕过前端 radio 直接构造请求
        dto.setReceiver("张三"); dto.setPhone("13800000000"); dto.setAddress("北京市海淀区");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto));
        assertTrue(ex.getMessage().contains("非法支付方式"));
        assertAlertOn("payType");
    }

    // ===== 攻击 2：篡改 usePoints 为负数（试图反向加积分/减金额） =====

    @Test
    void attackNegativePointsRejectedAndAlerted() {
        CheckoutDTO dto = new CheckoutDTO();
        dto.setPayType("corporate");
        dto.setReceiver("张三"); dto.setPhone("13800000000"); dto.setAddress("北京市海淀区");
        dto.setUsePoints(-9999);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto));
        assertTrue(ex.getMessage().contains("积分"));
        assertAlertOn("usePoints");
    }

    // ===== 攻击 3：重放同一 idempotentKey 重复下单 =====

    @Test
    void attackIdempotentKeyReplayRejected() {
        // 模拟数据库唯一约束 uk_biz(biz_type, biz_key)：同键第二次插入冲突
        Set<String> keys = new HashSet<>();
        when(idempotentTokenMapper.insert(any(IdempotentToken.class))).thenAnswer(inv -> {
            IdempotentToken t = inv.getArgument(0);
            if (!keys.add(t.getBizType() + ":" + t.getBizKey())) {
                throw new DuplicateKeyException("uk_biz");
            }
            return 1;
        });

        assertDoesNotThrow(() -> idempotentService.reserve("checkout", "ATTACK-KEY-1", 7L, null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> idempotentService.reserve("checkout", "ATTACK-KEY-1", 7L, null));
        assertTrue(ex.getMessage().contains("重复提交"));
    }

    // ===== 攻击 4：并发/重放支付（payStatus 状态机非法流转） =====

    @Test
    void attackPayStatusRaceRejectedAndAlerted() {
        Order o = new Order();
        o.setOrderNo("ORD-P1"); o.setUserId(7L); o.setPayStatus(0);
        when(orderMapper.selectById("ORD-P1")).thenReturn(o);
        when(orderMapper.markPaidAtomic("ORD-P1")).thenReturn(0); // 并发已被抢先支付

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(7L, "ORD-P1"));
        assertTrue(ex.getMessage().contains("支付"));
        assertAlertOn("payStatus");
    }

    // ===== 攻击 5：并发重复退款申请（refundStatus 状态机非法流转） =====

    @Test
    void attackRefundStatusRaceRejectedAndAlerted() {
        Order o = new Order();
        o.setOrderNo("ORD-R1"); o.setUserId(7L);
        o.setPayStatus(1); o.setPayType("corporate"); o.setRefundStatus("none");
        o.setTotalAmount(new BigDecimal("100.00"));
        when(orderMapper.selectById("ORD-R1")).thenReturn(o);
        when(orderMapper.applyRefundAtomic("ORD-R1", "重复申请")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD-R1", "重复申请"));
        assertTrue(ex.getMessage().contains("重复"));
        assertAlertOn("refundStatus");
    }

    // ===== 攻击 6：金额一致性比对（checkNumeric 不一致即告警拒绝） =====

    @Test
    void attackAmountMismatchDetected() {
        boolean ok = tamperGuardService.checkNumeric(7L, "order", "totalAmount",
                new BigDecimal("0.01"), new BigDecimal("2000.00"), null);
        assertFalse(ok, "客户端传入金额与后端重算值不一致应判定为篡改");
        assertAlertOn("totalAmount");
        // 一致时放行且不告警
        capturedAlerts.clear();
        assertTrue(tamperGuardService.checkNumeric(7L, "order", "totalAmount",
                new BigDecimal("2000.00"), new BigDecimal("2000.00"), null));
        assertTrue(capturedAlerts.isEmpty());
    }

    // ===== 攻击 7：前后端校验规则同步守护（需求 3） =====

    @Test
    void frontendBackendValidationRulesInSync() throws Exception {
        Path js = Path.of("..", "frontend", "src", "utils", "validationRules.js");
        assumeTrue(Files.exists(js), "前端契约文件不存在时跳过（独立后端构建环境）");
        String src = Files.readString(js);

        // 支付方式白名单两端一致
        Set<String> frontPays = new HashSet<>();
        var m = java.util.regex.Pattern
                .compile("ALLOWED_PAY_TYPES\\s*=\\s*\\[([^\\]]*)\\]").matcher(src);
        assertTrue(m.find(), "前端应定义 ALLOWED_PAY_TYPES");
        for (String s : m.group(1).split(",")) {
            String v = s.trim().replace("'", "").replace("\"", "");
            if (!v.isEmpty()) frontPays.add(v);
        }
        assertEquals(ValidationRules.ALLOWED_PAY_TYPES, frontPays,
                "前后端支付方式白名单不一致：请同步 ValidationRules.java 与 validationRules.js 及契约文档");

        // 长度上限两端一致
        assertRuleSynced(src, "RECEIVER_MAX", ValidationRules.RECEIVER_MAX);
        assertRuleSynced(src, "PHONE_MAX", ValidationRules.PHONE_MAX);
        assertRuleSynced(src, "ADDRESS_MAX", ValidationRules.ADDRESS_MAX);
        assertRuleSynced(src, "REMARK_MAX", ValidationRules.REMARK_MAX);
    }

    private void assertRuleSynced(String jsSrc, String name, int backendValue) {
        var m = java.util.regex.Pattern
                .compile(name + "\\s*=\\s*(\\d+)").matcher(jsSrc);
        assertTrue(m.find(), "前端应定义 " + name);
        assertEquals(backendValue, Integer.parseInt(m.group(1)),
                name + " 前后端不一致：请同步 ValidationRules.java 与 validationRules.js");
    }

    // ===== 正向：正常请求不产生告警（防误报） =====

    @Test
    void legitCheckoutDoesNotRaiseAlert() {
        // 攻击面之外：合法支付方式 + 非负积分不应触发篡改告警（校验继续走业务流程，此处仅验证告警静默）
        CheckoutDTO dto = new CheckoutDTO();
        dto.setPayType("corporate");
        dto.setReceiver("张三"); dto.setPhone("13800000000"); dto.setAddress("北京市海淀区");
        dto.setUsePoints(0);
        try {
            orderService.checkout(7L, dto); // 购物车为空会走业务异常，但不应有篡改告警
        } catch (BusinessException ignored) {
            // 业务校验失败（如购物车为空）不算篡改
        }
        assertTrue(capturedAlerts.isEmpty(), "合法请求不应触发篡改告警");
    }
}
