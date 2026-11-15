package com.lanlink.shopping.support.tts;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.support.SupportRequestGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服回答语音播报端点：把文本转成 MP3 音频交给前端播放。
 *
 * <p>鉴权与限流复用 {@link SupportRequestGuard}，底层合成委托给 {@link SupportTtsService}。
 */
@RestController
@RequestMapping("/support/tts")
public class SupportTtsController {

    private final SupportRequestGuard guard;
    private final SupportTtsService ttsService;

    @Autowired
    public SupportTtsController(
            SupportRequestGuard guard,
            SupportTtsService ttsService) {
        this.guard = guard;
        this.ttsService = ttsService;
    }

    /** 兼容构造器：保持单测无需 Mock 外部 Service 也能跑通 */
    public SupportTtsController(
            SupportRequestGuard guard,
            @Value("${AI_GATEWAY_BASE_URL:http://lanlink-ai-gateway:8090/v1}") String gatewayBaseUrl,
            @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String gatewayApiKey,
            @Value("${SUPPORT_TTS_VOICE:茉莉}") String voice,
            @Value("${SUPPORT_TTS_STYLE:" + SupportTtsService.DEFAULT_STYLE + "}") String style) {
        this(guard, new SupportTtsService(gatewayBaseUrl, gatewayApiKey, voice, style));
    }

    /** 单测/兼容构造器：沿用固定音色与默认风格 */
    SupportTtsController(SupportRequestGuard guard, String gatewayBaseUrl, String gatewayApiKey) {
        this(guard, gatewayBaseUrl, gatewayApiKey, "茉莉", SupportTtsService.DEFAULT_STYLE);
    }

    public record TtsRequest(String text, String voice, String format) {
    }

    public record TtsResult(String audioBase64, String format, int sampleRate, String engine, String voice) {
    }

    @PostMapping
    public R<TtsResult> synthesize(@RequestBody(required = false) TtsRequest body,
                                   HttpServletRequest request) {
        authorize(request);

        String text = body == null || body.text() == null ? "" : body.text().trim();
        String format = body == null ? null : body.format();

        SupportTtsService.TtsResult svcResult = ttsService.synthesize(text, format);
        return R.ok(new TtsResult(
                svcResult.audioBase64(),
                svcResult.format(),
                svcResult.sampleRate(),
                svcResult.engine(),
                svcResult.voice()));
    }

    private void authorize(HttpServletRequest request) {
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
    }
}
