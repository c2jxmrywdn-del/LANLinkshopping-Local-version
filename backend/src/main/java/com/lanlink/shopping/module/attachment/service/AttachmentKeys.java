package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * 附件对象键名生成。
 *
 * <p>固定 attachments/ 前缀有两个原因：MinIO 整桶可匿名读，私有材料只能靠前缀划区间
 * 由后端中转取字节；桶名已是 products，键名再带 products/ 会拼出叠段 URL。
 */
public final class AttachmentKeys {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "png", "webp", "pdf");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AttachmentKeys() {
    }

    public static String newKey(AttachmentBizType bizType, String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(400, "不支持的文件类型");
        }
        String month = YearMonth.now(ZONE).toString();
        String name = UUID.randomUUID().toString().replace("-", "");
        return "attachments/" + bizType.wireName() + "/" + month + "/" + name + "." + ext;
    }

    /**
     * 缩略图键名：与原键同目录，插一段 thumb/ 并固定 .jpg。
     *
     * <p>由原键派生而不是新生成 UUID，是为了让重试落在同一个 key 上覆盖写：每次重投都产一个新对象的话，
     * 失败重试会在桶里堆出无人引用的孤儿缩略图，而桶里只留 object key、没有任何反查手段。
     */
    public static String thumbnailKey(String objectKey) {
        int slash = objectKey == null ? -1 : objectKey.lastIndexOf('/');
        if (slash < 0 || slash == objectKey.length() - 1) {
            throw new BusinessException(500, "对象键名不可解析，无法生成缩略图键");
        }
        String dir = objectKey.substring(0, slash);
        String name = objectKey.substring(slash + 1).replaceFirst("\\.[A-Za-z0-9]+$", "");
        return dir + "/thumb/" + name + ".jpg";
    }
}
