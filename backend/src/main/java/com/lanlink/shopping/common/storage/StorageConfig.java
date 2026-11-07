package com.lanlink.shopping.common.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * 对象存储 S3 客户端（out/storage-mq-plan.md 任务 A2 + security-hardening-plan.md P1-1）。
 *
 * <p>关键三件套：
 * <ol>
 *   <li>forcePathStyle(true)：MinIO 必须（COS/OSS 经 path-style 配置切换）；</li>
 *   <li>chunkedEncodingEnabled(false)：阿里 OSS 必需，对 MinIO/COS 无害；</li>
 *   <li>endpointOverride + StaticCredentialsProvider + UrlConnectionHttpClient：
 *       轻量常驻，不引 Netty/Apache 重客户端。</li>
 * </ol>
 *
 * <p>lanlink.storage.enabled=false 时不创建本 Bean（回退分支完全不需要 S3 通道），
 * {@link StorageService} 以 @Nullable 注入降级为本地盘实现。
 *
 * <p>凭据 fail-fast（P1-1，对齐 AiToolGuard「默认密钥即拒绝启动」与 ProdConfigGuard
 * 启动期校验两个既有模式）：enabled=true 而 access/secret 空白或仍为经典弱凭据
 * minioadmin 时直接抛 IllegalStateException 拒绝启动，禁止静默以弱凭据连上存储。
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    /** 经典弱凭据：历史上 MinIO 出厂默认值，出现即视为未完成凭据配置 */
    static final String WEAK_CREDENTIAL = "minioadmin";

    @Bean
    @ConditionalOnProperty(name = "lanlink.storage.enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        validateCredentials(props);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .chunkedEncodingEnabled(false)
                        .build())
                .httpClient(UrlConnectionHttpClient.create());
        if (props.isPathStyle()) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }

    /** Bean 创建前校验（SG-12）：空白=缺配置，minioadmin=弱凭据，两者都拒绝启动。 */
    static void validateCredentials(StorageProperties props) {
        String access = props.getAccessKey() == null ? "" : props.getAccessKey().trim();
        String secret = props.getSecretKey() == null ? "" : props.getSecretKey().trim();
        if (access.isEmpty() || secret.isEmpty()) {
            throw new IllegalStateException(
                    "对象存储已启用但 LANLINK_S3_ACCESS_KEY / LANLINK_S3_SECRET_KEY 为空，"
                            + "拒绝以缺失凭据启动。请在 .env 填写专用应用账号（参考 .env.example 对象存储段），"
                            + "或设 LANLINK_STORAGE_ENABLED=false 回退本地盘。");
        }
        if (WEAK_CREDENTIAL.equalsIgnoreCase(access) || WEAK_CREDENTIAL.equalsIgnoreCase(secret)) {
            throw new IllegalStateException(
                    "对象存储凭据仍为出厂弱默认值 minioadmin，拒绝启动。"
                            + "请在 .env 配置 LANLINK_S3_ACCESS_KEY/SECRET_KEY 为专用最小权限账号"
                            + "（minio-init 已幂等创建 lanlink-app），root 凭据只留在 MINIO_ROOT_*。");
        }
    }
}
