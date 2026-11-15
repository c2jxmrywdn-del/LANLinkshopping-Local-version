package com.lanlink.shopping.support.media;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 客服多模态媒体配置（app.support-media.*）。
 *
 * <p>单文件上限按类型分档而不是一个全局值：一段 20MB 的视频与一张 20MB 的图片
 * 对解析链路的代价完全不同，共用上限只能取最保守的一档，反而卡住合理场景。
 *
 * <p>配额三维度（小时/会话/单消息）在 Redis 里各占一个滑窗键，任一维度超限即拒绝。
 * {@code redisFailClosed=true} 表示 Redis 不可用时拒绝新媒体上传（fail-closed）；
 * 这只影响媒体上传接口，文本客服不依赖本配置，不会被连带拖垮。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.support-media")
public class SupportMediaProperties {

    /** 媒体行保留天数（软删），到期置 EXPIRED，再过 7 天物理删除对象与行 */
    private int retentionDays = 30;

    private int maxImageMb = 10;
    private int maxAudioMb = 10;
    private int maxVideoMb = 20;
    private int maxDocMb = 20;

    /** 同一 IP 每小时最多新建媒体数 */
    private int hourlyLimit = 20;
    /** 同一访客同一会话最多媒体数 */
    private int sessionLimit = 30;
    /** 同一条消息最多媒体数 */
    private int messageLimit = 5;

    /** Redis 异常时是否拒绝新媒体（默认 true）；false 只用于演示环境排障 */
    private boolean redisFailClosed = true;
}
