package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 营业执照查看验证门：图形验证码签发/校验 + 短时效查看票据。
 *
 * <p>存储对齐 {@link VerifyCodeService} 的内存形态（无 Redis 依赖）：
 * 验证码 5 分钟过期、错 5 次作废、单次使用；票据 5 分钟 TTL、绑定
 * userId+merId、单次使用（一次验证一次查看，重放/过期/错商户/错用户均拒绝）。
 */
@Service
public class LicenseViewGateService {

    static final long CAPTCHA_TTL_MS = 5 * 60 * 1000L;
    static final long TICKET_TTL_MS = 5 * 60 * 1000L;
    /** 验证码最多错 5 次，超限作废防爆破 */
    static final int MAX_CAPTCHA_ATTEMPTS = 5;
    /** 去掉易混淆字符（0/O、1/I/L）的验证码字母表 */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 4;

    private final Map<String, CaptchaBox> captchaStore = new ConcurrentHashMap<>();
    private final Map<String, TicketBox> ticketStore = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long captchaTtlMs;
    private final long ticketTtlMs;
    private final LongSupplier clock;

    public LicenseViewGateService() {
        this(CAPTCHA_TTL_MS, TICKET_TTL_MS, System::currentTimeMillis);
    }

    /** 测试用：可注入 TTL 与时钟，保证过期/重放语义可确定性断言 */
    LicenseViewGateService(long captchaTtlMs, long ticketTtlMs, LongSupplier clock) {
        this.captchaTtlMs = captchaTtlMs;
        this.ticketTtlMs = ticketTtlMs;
        this.clock = clock;
    }

    private static class CaptchaBox {
        String code;
        Long merId;
        long exp;
        int failedAttempts;
        /** 单次使用标记：成功换票后置位，重放校验直接判「已被使用」 */
        final AtomicBoolean used = new AtomicBoolean(false);
    }

    private static class TicketBox {
        Long userId;
        Long merId;
        long exp;
        /** 单次使用标记：CAS 置位成功才算消费，并发双取只能成功一次 */
        final AtomicBoolean used = new AtomicBoolean(false);
    }

    /** 验证码签发结果：captchaId 供 POST 校验回传，imageBase64 为 PNG data URL 前端直接展示 */
    public record CaptchaIssue(String captchaId, String imageBase64) {
    }

    /**
     * 生成图形验证码并入库。merId 必传：校验时票据只签给同一个商户，防止拿 A 商户验证码换 B 商户票据。
     */
    public CaptchaIssue newCaptcha(Long merId) {
        if (merId == null) {
            throw new BusinessException(400, "缺少商户参数");
        }
        long now = clock.getAsLong();
        expireCaptchas(now);
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        CaptchaBox box = new CaptchaBox();
        box.code = code.toString();
        box.merId = merId;
        box.exp = now + captchaTtlMs;
        captchaStore.put(captchaId, box);
        try {
            return new CaptchaIssue(captchaId, toDataUrl(renderCaptchaPng(code.toString())));
        } catch (IOException ex) {
            captchaStore.remove(captchaId);
            throw new BusinessException(500, "验证码生成失败，请稍后重试");
        }
    }

    /**
     * 校验验证码（单次使用）并签发票据。验证码与商户绑定不一致、过期、错码超限均拒绝。
     */
    public String issueTicket(Long userId, Long merId, String captchaId, String captchaCode) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        if (merId == null) throw new BusinessException(400, "缺少商户参数");
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(400, "请输入验证码");
        }
        long now = clock.getAsLong();
        expireCaptchas(now);
        expireTickets(now);
        CaptchaBox box = captchaStore.get(captchaId.trim());
        if (box == null) {
            throw new BusinessException(403, "验证码已失效，请重新获取");
        }
        if (box.used.get()) {
            throw new BusinessException(403, "验证码已被使用，请重新获取");
        }
        if (box.exp < now) {
            captchaStore.remove(captchaId, box);
            throw new BusinessException(403, "验证码已过期，请重新获取");
        }
        if (!merId.equals(box.merId)) {
            throw new BusinessException(403, "验证码与商户不匹配，请重新获取");
        }
        if (!box.code.equalsIgnoreCase(captchaCode.trim())) {
            box.failedAttempts++;
            if (box.failedAttempts >= MAX_CAPTCHA_ATTEMPTS) {
                captchaStore.remove(captchaId, box);
                throw new BusinessException(403, "验证码错误次数超限已作废，请重新获取");
            }
            throw new BusinessException(403, "验证码错误，还剩 " + (MAX_CAPTCHA_ATTEMPTS - box.failedAttempts) + " 次尝试机会");
        }
        // CAS 置位成功才算消费成功：并发双取只有一个能换到票
        if (!box.used.compareAndSet(false, true)) {
            throw new BusinessException(403, "验证码已被使用，请重新获取");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        TicketBox tb = new TicketBox();
        tb.userId = userId;
        tb.merId = merId;
        tb.exp = now + ticketTtlMs;
        ticketStore.put(ticket, tb);
        return ticket;
    }

    /**
     * 消费票据：仅当票据存在、未过期、未使用且绑定 userId+merId 一致时放行；
     * 放行即从存储移除（单次使用）。任何不满足都拒绝，供水印端点映射为 HTTP 403。
     */
    public void consumeTicket(Long userId, Long merId, String ticket) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(403, "缺少查看凭证，请先完成人机验证");
        }
        long now = clock.getAsLong();
        expireTickets(now);
        TicketBox box = ticketStore.get(ticket.trim());
        if (box == null) {
            throw new BusinessException(403, "查看凭证无效，请重新完成人机验证");
        }
        if (box.used.get()) {
            throw new BusinessException(403, "查看凭证已被使用，请重新完成人机验证");
        }
        if (box.exp < now) {
            ticketStore.remove(ticket, box);
            throw new BusinessException(403, "查看凭证已过期，请重新完成人机验证");
        }
        if (!userId.equals(box.userId) || !merId.equals(box.merId)) {
            throw new BusinessException(403, "查看凭证与当前账号或商户不匹配");
        }
        // CAS 置位即消费：并发重放只有一个请求能拿到水印图
        if (!box.used.compareAndSet(false, true)) {
            throw new BusinessException(403, "查看凭证已被使用，请重新完成人机验证");
        }
    }

    private void expireCaptchas(long now) {
        captchaStore.values().removeIf(box -> box.exp < now);
    }

    private void expireTickets(long now) {
        ticketStore.values().removeIf(box -> box.exp < now);
    }

    /**
     * 水印文字模板：「仅供{查看方}对外展示时使用，最终解释权归属{商户企业名}所有」。
     * 查看方为空回退占位名，商户名为空回退占位名——模板必须始终完整，不能出现空洞。
     */
    public static String[] buildWatermarkLines(String viewerDisplayName, String merchantDisplayName) {
        String viewer = (viewerDisplayName == null || viewerDisplayName.isBlank())
                ? "LANLink 平台运营" : viewerDisplayName.trim();
        String merchant = (merchantDisplayName == null || merchantDisplayName.isBlank())
                ? "对应商户" : merchantDisplayName.trim();
        return new String[]{
                "仅供" + viewer + "对外展示时使用",
                "最终解释权归属" + merchant + "所有"
        };
    }

    /**
     * 绘制 4 位验证码 PNG（干扰线 + 字符随机微旋转），返回 PNG 字节。
     * 仅 JDK 自带 Java2D，零新依赖。
     */
    private byte[] renderCaptchaPng(String code) throws IOException {
        int width = 128;
        int height = 44;
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(new Color(0xf3, 0xf6, 0xfa));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // 干扰线：验证码可读性与自动化识别之间的折中
            for (int i = 0; i < 5; i++) {
                g.setColor(new Color(100 + random.nextInt(80), 120 + random.nextInt(80), 140 + random.nextInt(80)));
                g.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }
            Font font = LicenseImageWatermark.cjkFont(Font.BOLD, 26);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int totalW = fm.stringWidth(code);
            int x = Math.max(6, (width - totalW) / 2 - 8);
            for (int i = 0; i < code.length(); i++) {
                String ch = String.valueOf(code.charAt(i));
                int charW = fm.stringWidth(ch);
                g.setColor(new Color(30 + random.nextInt(60), 60 + random.nextInt(60), 110 + random.nextInt(70)));
                double theta = (random.nextInt(21) - 10) * Math.PI / 180;
                g.rotate(theta, x + charW / 2.0, height / 2.0);
                g.drawString(ch, x, (height - fm.getHeight()) / 2 + fm.getAscent());
                g.rotate(-theta, x + charW / 2.0, height / 2.0);
                x += charW + 2;
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    /** 供前端直接渲染的 data URL 形态 */
    public String toDataUrl(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    /** 测试钩子：读回验证码明文供单测走通签发票据链路（生产代码不得依赖） */
    String codeOf(String captchaId) {
        CaptchaBox box = captchaStore.get(captchaId);
        return box == null ? null : box.code;
    }
}
