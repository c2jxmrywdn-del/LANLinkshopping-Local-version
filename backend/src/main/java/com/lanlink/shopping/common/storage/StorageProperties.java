package com.lanlink.shopping.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（out/security-hardening-plan.md P1-1，绑定 application.yml 的 lanlink.storage.* 段）。
 *
 * <p>核心约定：库内一律存 object key，出站统一拼公开绝对 URL（{@link StorageUrls} /
 * {@link StorageUrlSerializer}）；切腾讯 COS / 阿里 OSS 只改 endpoint/region/AK/bucket/
 * path-style/public-base-url 等 env，代码零改动。enabled=false 为全链路回退开关
 * （规划 A5 回退第一层：上传落本地盘、出站原样）。
 *
 * <p>前缀统一为 lanlink.storage（与 lanlink.payment/demo-data 同族）：env
 * LANLINK_STORAGE_ENABLED 经 Boot 宽松绑定直达本开关（旧 app.storage 前缀下
 * LANLINK_* 变量根本绑不上，enabled=false 回退形同虚设）。
 * 凭据字段无任何默认值：空白或 minioadmin 由 {@link StorageConfig} 创建 Bean 前校验，
 * 缺失即 fail-fast（对齐 AI_OUTBOUND_KEY_SECRET「无默认兜底」纪律）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lanlink.storage")
public class StorageProperties {

    /** 总开关：false = 全链路回退现状（上传落本地盘、出站原样输出；且不创建 S3Client Bean） */
    private boolean enabled = true;

    /** S3 端点：MinIO http://localhost:9000；容器网络内由 compose 注入 http://minio:9000 */
    private String endpoint = "http://localhost:9000";

    private String region = "us-east-1";

    /** 无默认值：空白/弱凭据在 StorageConfig 处 fail-fast，不允许静默回落 minioadmin */
    private String accessKey = "";

    private String secretKey = "";

    private String bucket = "products";

    /** MinIO 必须 true（path-style）；切腾讯 COS/阿里 OSS 时 env 置 false */
    private boolean pathStyle = true;

    /** 出站公开 URL 基址（path-style 下含桶路径，如 http://localhost:9000/products），不带尾部斜杠 */
    private String publicBaseUrl = "http://localhost:9000/products";

    /** enabled=false 时的本地盘回退分支（与 WebConfig 的 /uploads/** 静态映射等价） */
    private Local local = new Local();

    @Data
    public static class Local {
        /** 落盘根目录：与 WebConfig.addResourceHandlers 的 file:{user.dir}/uploads/ 保持一致 */
        private String root = System.getProperty("user.dir") + "/uploads";
        /** 对外 URL 前缀：/api/uploads + "/" + key == 历史商品图 URL 形态（如 /api/uploads/products/prod_x.webp） */
        private String urlPrefix = "/api/uploads";
    }
}
