package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.support.SupportRequestGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服多模态媒体端点：预签名直传 → 确认核验 → 状态查询 → 中转读取。
 *
 * <p>鉴权复用 {@link SupportRequestGuard}：只有经可信反代（代理密钥）或平台 API Key 的请求放行，
 * 再走同一套按 IP 的频率限制；确认与读取另外强制校验 visitorToken 归属——媒体单号是随机串，
 * 但「随机」不等于「凭证」，不能只凭单号放行。
 *
 * <p>路径都在 /support/media 下，与客服文本接口同域同源；CORS/Origin 白名单在预签名侧校验。
 */
@RestController
@RequestMapping("/support/media")
public class SupportMediaController {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaController.class);

    private final SupportMediaService mediaService;
    private final SupportRequestGuard guard;

    public SupportMediaController(SupportMediaService mediaService, SupportRequestGuard guard) {
        this.mediaService = mediaService;
        this.guard = guard;
    }

    public record PresignRequest(String visitorToken, String conversationNo, String messageKey,
                                 String kind, String fileName, String contentType, Long size) {
    }

    public record ConfirmRequest(String visitorToken) {
    }

    /** 申请直传凭证：校验类型/大小/配额后返回 action + 表单字段 */
    @PostMapping("/presign")
    public R<SupportMediaPresignService.PresignResponse> presign(@RequestBody PresignRequest body,
                                                                 HttpServletRequest request) {
        String clientIp = authorize(request);
        return R.ok(mediaService.presign(new SupportMediaService.PresignCommand(
                        body.visitorToken(), body.conversationNo(), body.messageKey(),
                        body.kind(), body.fileName(), body.contentType(), body.size()),
                resolveOrigin(request), clientIp));
    }

    /** 直传完成后确认：HEAD + 摘要 + 魔数复核，通过即入解析队列 */
    @PostMapping("/{mediaNo}/confirm")
    public R<SupportMediaService.Detail> confirm(@PathVariable("mediaNo") String mediaNo,
                                                 @RequestBody ConfirmRequest body,
                                                 HttpServletRequest request) {
        authorize(request);
        return R.ok(mediaService.confirm(mediaNo, body == null ? null : body.visitorToken()));
    }

    /** 状态与解析结果（P1 解析完成后 ocrText/asrText/summary 才有值） */
    @GetMapping("/{mediaNo}")
    public R<SupportMediaService.Detail> detail(@PathVariable("mediaNo") String mediaNo,
                                                @RequestParam("visitorToken") String visitorToken,
                                                HttpServletRequest request) {
        authorize(request);
        return R.ok(mediaService.detail(mediaNo, visitorToken));
    }

    /** 客户端主动移除/放弃待传附件，即刻释放对应配额 */
    @DeleteMapping("/{mediaNo}")
    public R<Void> discard(@PathVariable("mediaNo") String mediaNo,
                           @RequestParam("visitorToken") String visitorToken,
                           HttpServletRequest request) {
        authorize(request);
        mediaService.discard(mediaNo, visitorToken);
        return R.ok();
    }

    /**
     * 字节中转：媒体是资源型地址（img/audio/video 直接引用），错误走真实 HTTP 状态码，
     * 避免「200 + JSON」被浏览器渲染成破图；同时带 no-store 与 nosniff。
     */
    @GetMapping("/{mediaNo}/content")
    public ResponseEntity<byte[]> content(@PathVariable("mediaNo") String mediaNo,
                                          @RequestParam("visitorToken") String visitorToken,
                                          HttpServletRequest request) {
        authorize(request);
        SupportMediaService.Content content;
        try {
            content = mediaService.content(mediaNo, visitorToken);
        } catch (BusinessException e) {
            if (e.getCode() != null && e.getCode() >= 500) {
                log.error("[support-media] 媒体中转失败 mediaNo={}", mediaNo, e);
            }
            return ResponseEntity.status(e.getCode()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }

    /** 复用 SupportRequestGuard：代理密钥/平台 Key + 频率限制，与文本客服同一口径 */
    private String authorize(HttpServletRequest request) {
        SupportRequestGuard.ProxyChannel channel = guard.authorizedChannel(request);
        if (channel == SupportRequestGuard.ProxyChannel.NONE) {
            throw new BusinessException(403, "无权访问客服接口");
        }
        String clientIp;
        if (channel == SupportRequestGuard.ProxyChannel.PROXY_SECRET) {
            String headerIp = request.getHeader("X-Support-Client-IP");
            clientIp = (headerIp == null || headerIp.isBlank()) ? request.getRemoteAddr() : headerIp;
        } else {
            clientIp = request.getRemoteAddr();
        }
        if (!guard.allowRequest(clientIp)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        return clientIp;
    }

    /**
     * 浏览器来源：优先 Origin（跨域/同源 fetch 都会带），其次反代注入的 X-Forwarded-*。
     * 取不到时用请求自身的 scheme/host 兜底；是否放行由预签名侧的白名单裁决。
     */
    static String resolveOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        String forwardedOrigin = request.getHeader("X-Forwarded-Origin");
        if (forwardedOrigin != null && !forwardedOrigin.isBlank()) {
            return firstValue(forwardedOrigin);
        }
        String proto = firstValue(request.getHeader("X-Forwarded-Proto"));
        String host = firstValue(request.getHeader("X-Forwarded-Host"));
        if (proto != null && host != null) {
            return proto + "://" + host;
        }
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static String firstValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = comma >= 0 ? value.substring(0, comma) : value;
        return first.trim().isEmpty() ? null : first.trim();
    }
}
