package com.lanlink.shopping.service;

import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 用户身份识别服务（实时计算）。
 *
 * 识别机制：
 *  - 未登录 → GUEST（访客）
 *  - role_id=3 → ADMIN；role_id=2 → MERCHANT；role_id=1 → 查累计已支付金额，≥VIP阈值 → VIP，否则 BUYER
 *
 * 实时性：每次请求由 AuthInterceptor 调用 identify() 现场计算。
 * 平滑切换：身份不写入会话快照，角色变更（如商户审核通过）或 VIP 达标后，下一次请求即返回新身份，无需重新登录。
 */
@Service
public class IdentityService {

    /** VIP 达标阈值：累计已支付订单金额 ≥ 5000 元 */
    public static final BigDecimal VIP_THRESHOLD = new BigDecimal("5000.00");

    private static final long ROLE_BUYER = 1L;
    private static final long ROLE_MERCHANT = 2L;
    private static final long ROLE_ADMIN = 3L;

    private final OrderMapper orderMapper;
    private final UserMapper userMapper;

    @Autowired
    public IdentityService(OrderMapper orderMapper, UserMapper userMapper) {
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
    }

    public IdentityService(OrderMapper orderMapper) {
        this(orderMapper, null);
    }

    /**
     * 实时识别当前请求的用户身份。
     */
    public UserIdentity identify(HttpServletRequest request) {
        User u = UserContext.current(request);
        if (u == null) return UserIdentity.GUEST;
        if (u.getUserId() != null && userMapper != null) {
            User freshUser = userMapper.selectById(u.getUserId());
            if (freshUser != null) {
                // 若数据库中角色或企业发生变更，同步刷新 session 中的用户对象，使后续流程/拦截器生效
                if (!Objects.equals(u.getRoleId(), freshUser.getRoleId())
                        || !Objects.equals(u.getEntId(), freshUser.getEntId())) {
                    u.setRoleId(freshUser.getRoleId());
                    u.setEntId(freshUser.getEntId());
                    if (request.getSession(false) != null) {
                        request.getSession().setAttribute(UserContext.SESSION_KEY, u);
                    }
                }
                return identify(freshUser.getUserId(), freshUser.getRoleId());
            }
        }
        return identify(u.getUserId(), u.getRoleId());
    }

    /**
     * 按 用户ID+角色ID 识别（供登录等场景直接使用）。
     */
    public UserIdentity identify(Long userId, Long roleId) {
        if (roleId != null && roleId == ROLE_ADMIN) return UserIdentity.ADMIN;
        if (roleId != null && roleId == ROLE_MERCHANT) return UserIdentity.MERCHANT;
        // 采购方（普通用户）判定 VIP：累计已支付金额达到阈值
        if (isVip(userId)) return UserIdentity.VIP;
        return UserIdentity.BUYER;
    }

    /** 累计已支付订单金额（pay_status=1） */
    public BigDecimal paidAmount(Long userId) {
        if (userId == null) return BigDecimal.ZERO;
        BigDecimal v = orderMapper.sumPaidAmount(userId);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** VIP 达标判断：累计已支付金额 ≥ 阈值 */
    public boolean isVip(Long userId) {
        return paidAmount(userId).compareTo(VIP_THRESHOLD) >= 0;
    }

    /**
     * 身份详情视图（供 /auth/me 与前端展示）：
     * code / name / serviceScope / permissions / vipThreshold / vipPaidAmount（仅登录时）
     */
    public Map<String, Object> identityView(HttpServletRequest request) {
        UserIdentity id = identify(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", id.getCode());
        out.put("name", id.getName());
        out.put("serviceScope", id.getServiceScope());
        out.put("permissions", id.getPermissions());
        out.put("vipThreshold", VIP_THRESHOLD);
        User u = UserContext.current(request);
        if (u != null) {
            out.put("vipPaidAmount", paidAmount(u.getUserId()));
            out.put("isVip", id == UserIdentity.VIP);
        }
        return out;
    }
}
