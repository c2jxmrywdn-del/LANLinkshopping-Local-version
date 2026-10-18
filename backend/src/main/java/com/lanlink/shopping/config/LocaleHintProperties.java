package com.lanlink.shopping.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 语言自动识别的服务端地理提示配置（app.i18n.geo.*，见 application-i18n.yml）。
 * 默认开启，但仅在离线信号不明确时才被前端调用；置 false 后识别完全依赖离线信号。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.i18n")
public class LocaleHintProperties {

    private Geo geo = new Geo();

    @Data
    public static class Geo {
        private boolean enabled = true;
        /** 支持 {ip} 占位符 */
        private String endpoint = "https://ipapi.co/{ip}/json/";
        private long timeoutMs = 900L;
    }
}
