package com.lanlink.shopping.support.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 客服媒体上传配额：Redis + Lua 原子滑窗，三个维度各自独立。
 *
 * <p>为什么用 Lua：三维度要「全成功才算预占」，普通 INCR 序列在第二维超限时已经把第一维
 * 加上去了，回滚又不在同一原子上下文里，并发下会漏掉一部分计数。脚本内失败即反向 DECR，
 * 对外只看到一次成功或一次拒绝。
 *
 * <p>窗口口径：
 * <ul>
 *   <li>小时维度按 IP 哈希计数，TTL 3600s；</li>
 *   <li>会话维度按 访客哈希 + 会话哈希 计数，TTL 600s；</li>
 *   <li>单消息维度按消息键哈希计数，TTL 600s。</li>
 * </ul>
 * 会话与单消息的 10 分钟预占窗足以覆盖「选文件-上传-确认」全过程，到期自动回收，
 * 不必依赖业务侧显式释放。
 *
 * <p>Redis 不可用时按 {@code app.support-media.redis-fail-closed} 决策（默认拒绝），
 * 只影响媒体上传，文本客服不经过本类。
 */
@Service
public class SupportMediaQuotaService {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaQuotaService.class);

    private static final long HOUR_TTL_SECONDS = 3600L;
    private static final long SHORT_TTL_SECONDS = 600L;
    /** 预占凭证保留 1 小时：覆盖小时窗口，供释放与运维排查 */
    private static final Duration RESERVATION_TTL = Duration.ofHours(1);

    private static final String SCRIPT_RESERVE = """
            local function bump(key, limit, ttl)
              local v = redis.call('INCR', key)
              if v == 1 then redis.call('EXPIRE', key, ttl) end
              return v <= limit
            end
            if not bump(KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[4])) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            if not bump(KEYS[2], tonumber(ARGV[2]), tonumber(ARGV[5])) then
              redis.call('DECR', KEYS[1])
              redis.call('DECR', KEYS[2])
              return 0
            end
            if not bump(KEYS[3], tonumber(ARGV[3]), tonumber(ARGV[6])) then
              redis.call('DECR', KEYS[1])
              redis.call('DECR', KEYS[2])
              redis.call('DECR', KEYS[3])
              return 0
            end
            return 1
            """;

    private static final String SCRIPT_RELEASE = """
            for i = 1, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                local v = redis.call('DECR', KEYS[i])
                if v < 0 then redis.call('SET', KEYS[i], 0, 'KEEPTTL') end
              end
            end
            return 1
            """;

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>(SCRIPT_RESERVE, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(SCRIPT_RELEASE, Long.class);

    private final StringRedisTemplate redis;
    private final SupportMediaProperties props;

    public SupportMediaQuotaService(StringRedisTemplate redis, SupportMediaProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 三维度预占。任一维度超限即整单拒绝（已加的计数在脚本内回滚）。
     *
     * @param visitorToken 访客令牌（UUID），用于会话维度
     * @param ip           客户端地址（可信代理头已在上层解析），用于小时维度；为空则跳过该维度
     * @param conversationNo 会话标识，可为空（空则计入固定桶）
     * @param messageKey   单条消息标识
     */
    public boolean reserve(String visitorToken, String ip, String conversationNo, String messageKey) {
        return reserve(null, visitorToken, ip, conversationNo, messageKey);
    }

    /**
     * 带媒体单号的预占：额外记录「这次预占用了哪几个键」，供回收/失败补偿时精确释放。
     */
    public boolean reserve(String mediaNo, String visitorToken, String ip, String conversationNo,
                           String messageKey) {
        List<String> keys = keysOf(visitorToken, ip, conversationNo, messageKey);
        try {
            Long result = redis.execute(RESERVE_SCRIPT, keys,
                    String.valueOf(props.getHourlyLimit()),
                    String.valueOf(props.getSessionLimit()),
                    String.valueOf(props.getMessageLimit()),
                    String.valueOf(HOUR_TTL_SECONDS),
                    String.valueOf(SHORT_TTL_SECONDS),
                    String.valueOf(SHORT_TTL_SECONDS));
            boolean allowed = result != null && result == 1L;
            if (allowed && mediaNo != null && !mediaNo.isBlank()) {
                rememberReservation(mediaNo, keys);
            }
            return allowed;
        } catch (RuntimeException e) {
            boolean reject = props.isRedisFailClosed();
            log.warn("[support-media] 配额检查失败，fail-closed={} visitor={} err={}",
                    reject, hash(visitorToken), e.getMessage());
            return !reject;
        }
    }

    /** 显式释放（与 reserve 参数一致）；用于业务失败回滚 */
    public void release(String visitorToken, String ip, String conversationNo, String messageKey) {
        releaseKeys(keysOf(visitorToken, ip, conversationNo, messageKey));
    }

    /** 按媒体单号释放：回收到期未上传的 INIT 行时使用（此时已拿不到原始请求参数） */
    public void releaseByMediaNo(String mediaNo) {
        if (mediaNo == null || mediaNo.isBlank()) {
            return;
        }
        try {
            String stored = redis.opsForValue().get(reservationKey(mediaNo));
            if (stored == null || stored.isBlank()) {
                return;
            }
            releaseKeys(List.of(stored.split("\\|")));
            redis.delete(reservationKey(mediaNo));
        } catch (RuntimeException e) {
            log.warn("[support-media] 预占释放失败 mediaNo={} err={}", mediaNo, e.getMessage());
        }
    }

    private void releaseKeys(List<String> keys) {
        try {
            redis.execute(RELEASE_SCRIPT, keys);
        } catch (RuntimeException e) {
            log.warn("[support-media] 配额释放失败 err={}", e.getMessage());
        }
    }

    private void rememberReservation(String mediaNo, List<String> keys) {
        try {
            redis.opsForValue().set(reservationKey(mediaNo), String.join("|", keys), RESERVATION_TTL);
        } catch (RuntimeException e) {
            // 记录失败不影响预占本身：只是后续无法精确释放，交给 TTL 自然回收
            log.warn("[support-media] 预占凭证写入失败 mediaNo={} err={}", mediaNo, e.getMessage());
        }
    }

    private List<String> keysOf(String visitorToken, String ip, String conversationNo, String messageKey) {
        String ipHash = hash(ip == null || ip.isBlank() ? "unknown" : ip);
        String visitorHash = hash(visitorToken);
        String convHash = hash(conversationNo == null || conversationNo.isBlank() ? "na" : conversationNo);
        String messageHash = hash(messageKey == null || messageKey.isBlank() ? visitorHash : messageKey);
        List<String> keys = new ArrayList<>(3);
        keys.add("support:media:q:hour:" + ipHash);
        keys.add("support:media:q:sess:" + visitorHash + ":" + convHash);
        keys.add("support:media:q:msg:" + messageHash);
        return keys;
    }

    private static String reservationKey(String mediaNo) {
        return "support:media:q:resv:" + mediaNo;
    }

    /** 所有键只放摘要：访客令牌/IP 一旦原样进 Redis，排障导出的键名就成了隐私清单 */
    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
