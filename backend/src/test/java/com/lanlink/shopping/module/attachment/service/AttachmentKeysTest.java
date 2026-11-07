package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件对象键名规则。
 *
 * <p>键名同时决定三件事：能否被 StorageUrls 当作裸 key 往返解析、会不会撞上
 * 桶名与目录同名的叠段缺陷（bucket=products + key=products/...）、以及本地盘回退时
 * 是否仍在 /api/uploads 白名单内。所以先在这里把形状钉死。
 */
class AttachmentKeysTest {

    private static final Pattern BARE_KEY_SHAPE = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final String MONTH = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

    @Test
    void newKeySitsUnderAttachmentsPrefixWithBizTypeAndMonth() {
        String key = AttachmentKeys.newKey(AttachmentBizType.TECH_EXPERT_PROFILE, "pdf");

        assertTrue(key.startsWith("attachments/tech-expert-profile/" + MONTH + "/"),
                "键名应以 attachments/{业务域}/{yyyy-MM}/ 开头，实际：" + key);
        assertTrue(key.endsWith(".pdf"), "键名应保留服务端判定的扩展名，实际：" + key);
    }

    @Test
    void newKeyIsBareKeyShapeAndNeverUsesProductsPrefix() {
        String key = AttachmentKeys.newKey(AttachmentBizType.MERCHANT_LICENSE, "jpg");

        assertTrue(BARE_KEY_SHAPE.matcher(key).matches(),
                "键名必须落在 StorageUrls 认可的裸 key 字符集内，实际：" + key);
        assertFalse(key.startsWith("products/"),
                "桶名已是 products，键名再带该前缀会拼出 /products/products/... 叠段：" + key);
        assertEquals(32, key.substring(key.lastIndexOf('/') + 1, key.length() - ".jpg".length()).length(),
                "文件名主体应为 32 位随机串，避免可枚举的自增 id");
    }

    @Test
    void rejectsExtensionsOutsideMaterialWhitelist() {
        for (String ext : new String[]{"php", "jsp", "html", "exe", "svg", ""}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> AttachmentKeys.newKey(AttachmentBizType.TECH_EXPERT_PROFILE, ext),
                    "非材料类扩展名必须拒绝: " + ext);
            assertEquals(400, e.getCode().intValue(), "扩展名不合法属于入参错误: " + ext);
        }
    }

    @Test
    void bizTypeResolvesFromStoredWireNameOnly() {
        assertEquals(AttachmentBizType.TECH_DEAL_DOCUMENT,
                AttachmentBizType.fromWireName("tech-deal-document"));
        assertThrows(BusinessException.class, () -> AttachmentBizType.fromWireName("TECH_DEAL_DOCUMENT"),
                "线名应严格匹配入库值，不接受大小写变体，避免出现两套口径");
        assertThrows(BusinessException.class, () -> AttachmentBizType.fromWireName("../etc"),
                "未知或可疑业务域必须拒绝");
    }
}
