package com.lanlink.shopping.common.storage;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 对象存储门面（out/storage-mq-plan.md 任务 A2）：
 * put / delete / exists / getObject（视觉审核回源）+ publicUrlOf。
 *
 * <p>lanlink.storage.enabled=true 走 S3 API（MinIO/COS/OSS 仅 env 差异，代码零改动）；
 * false 回退本地盘——落盘路径与 URL 形态和改造前 ProductController 的
 * {user.dir}/uploads/products + /api/uploads/products/** 行为逐字节等价，
 * 保证关闭开关即回到现状（规划 A5 回退第一层）。
 *
 * <p>键名防越界沿用字符串层校验 + 归一化复查：Windows 把 "..\..\x" 当路径分隔而
 * Linux 当合法文件名，只在归一化层面检查会在两个平台得出相反判定。
 */
@Service
public class StorageService {

    private final StorageProperties props;
    private final S3Client s3;

    /** enabled=false 时 S3Client Bean 不创建（StorageConfig 条件化），注入 null */
    public StorageService(StorageProperties props, @Nullable S3Client s3) {
        this.props = props;
        this.s3 = s3;
        StorageUrls.init(props);
    }

    /** S3 通道是否启用（视觉审核回源等调用方据此分支） */
    public boolean s3Enabled() {
        return props.isEnabled();
    }

    /**
     * 落对象并返回对外 URL：
     * S3 → 公开绝对 URL（publicBaseUrl + "/" + key）；
     * 本地盘 → /api/uploads/{key}，与历史商品图 URL 形态一致。
     */
    public String put(String key, byte[] content, String contentType) {
        checkKey(key);
        if (props.isEnabled()) {
            s3Client().putObject(PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
            return StorageUrls.publicUrlOf(key);
        }
        localWrite(key, content);
        return props.getLocal().getUrlPrefix() + "/" + key;
    }

    public void delete(String key) {
        checkKey(key);
        if (!props.isEnabled()) {
            localDelete(key);
            return;
        }
        s3Client().deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket()).key(key).build());
    }

    public boolean exists(String key) {
        checkKey(key);
        if (!props.isEnabled()) {
            return Files.isRegularFile(safeResolve(key));
        }
        try {
            s3Client().headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /** 视觉审核回源（规划 A6）：S3 GetObject 直读字节流，不经 HTTP 域名（绕开 SSRF 白名单问题） */
    public byte[] getObject(String key) {
        checkKey(key);
        if (!props.isEnabled()) {
            return localRead(key);
        }
        return s3Client().getObjectAsBytes(GetObjectRequest.builder()
                .bucket(props.getBucket()).key(key).build()).asByteArray();
    }

    /**
     * 键名规则双 provider 归一（SR-9）：本地分支 safeResolve 拒绝的键名
     * （空白/前导 /、反斜杠、.. 段），S3 分支同样拒绝——S3 键虽无目录穿越逃逸，
     * 但两个 provider 对同一 key 判定分叉会让护栏测试与展示层各说各话。
     */
    private static void checkKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")
                || key.contains("..")) {
            throw new BusinessException(400, "非法的对象键名");
        }
    }

    /** 出站渲染：key / 存量相对路径 / 本存储域绝对 URL → 公开绝对 URL（详见 {@link StorageUrls}） */
    public String publicUrlOf(String keyOrUrl) {
        return StorageUrls.publicUrlOf(keyOrUrl);
    }

    private S3Client s3Client() {
        if (s3 == null) {
            throw new IllegalStateException("对象存储已启用但 S3Client 缺失（检查 lanlink.storage 配置）");
        }
        return s3;
    }

    // ==================== 本地盘回退分支（与历史 /api/uploads/** 行为等价） ====================

    private Path safeResolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")
                || key.contains("..")) {
            throw new BusinessException(400, "非法的对象键名");
        }
        Path root = Paths.get(props.getLocal().getRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(400, "非法的对象键名");
        }
        return target;
    }

    private void localWrite(String key, byte[] content) {
        Path target = safeResolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] localRead(String key) {
        try {
            return Files.readAllBytes(safeResolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void localDelete(String key) {
        try {
            Files.deleteIfExists(safeResolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
