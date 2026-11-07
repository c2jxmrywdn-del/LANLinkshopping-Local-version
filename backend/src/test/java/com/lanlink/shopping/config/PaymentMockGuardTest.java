package com.lanlink.shopping.config;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.controller.OrderController;
import com.lanlink.shopping.payment.wallet.controller.WalletController;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模拟支付口子的生产门禁。
 *
 * <p>这两条是"凭空造钱"的入口：/order/pay 不走渠道直接置已付、/wallet/recharge 直接加余额。
 * 它们只在 lanlink.payment.mock=true（本地联调）时可用；一旦生产配置下仍能调用，
 * 任何人都可以零成本下单。这里把「非 mock 环境必须拒绝」钉成断言。
 */
class PaymentMockGuardTest {

    private static void setMockPayment(Object target, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField("mockPayment");
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void orderPayIsRejectedWhenMockPaymentIsOff() throws Exception {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);
        setMockPayment(controller, false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.pay("AT-ORDER-1", mock(HttpServletRequest.class)));

        assertEquals(403, e.getCode());
        verify(service, never()).pay(any(), anyString());
    }

    @Test
    void orderPayStillWorksInMockEnvironment() throws Exception {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);
        setMockPayment(controller, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);

        controller.pay("AT-ORDER-1", request);

        verify(service).pay(any(), org.mockito.ArgumentMatchers.eq("AT-ORDER-1"));
    }

    @Test
    void walletRechargeIsRejectedWhenMockPaymentIsOff() throws Exception {
        WalletService service = mock(WalletService.class);
        WalletController controller = new WalletController(service);
        setMockPayment(controller, false);
        WalletController.RechargeDTO dto = new WalletController.RechargeDTO();
        dto.setAmount(new BigDecimal("50000"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recharge(dto, mock(HttpServletRequest.class)));

        assertEquals(403, e.getCode());
        verify(service, never()).recharge(any(), any(), any());
    }
}
