package com.lanlink.shopping.common;

import java.util.Map;
import java.util.Set;

/**
 * 前后端同步校验规则常量（契约源）。
 *
 * <p>需求 3：本类是校验规则的【唯一权威定义】，与前端
 * {@code frontend/src/utils/validationRules.js} 一一对应，
 * 任何一侧修改规则必须同步修改另一侧，并在篡改攻击测试中补充回归用例。
 * 契约文档 {@code docs/数据校验规则契约.md} 尚未建立（原引用指向不存在的文件，已移除悬空引用），
 * 重建文档与是否提升标题 2-100 常量均为 P2 决策（out/ai-polish-design.md §8）。
 *
 * <p>敏感字段分级：
 * <ul>
 *   <li>L1 服务端权威重算（禁止信任前端）：价格、订单金额、运费、积分抵扣金额</li>
 *   <li>L2 状态机原子流转（禁止前端指定目标状态）：支付状态、退款状态、订单状态</li>
 *   <li>L3 形式校验（前后端同规则双重校验）：收货人、电话、地址、备注、支付方式</li>
 * </ul>
 */
public final class ValidationRules {

    private ValidationRules() {}

    // ===== L3 字段长度（前后端同口径，@Size 与前端表单共用本常量） =====
    public static final int RECEIVER_MAX = 32;
    public static final int PHONE_MAX = 20;
    public static final int ADDRESS_MAX = 255;
    public static final int REMARK_MAX = 255;
    public static final int REFUND_REASON_MAX = 255;

    // ===== 退款凭证证据（与 RefundEvidenceValidator / frontend/src/utils/validationRules.js 同口径） =====
    /** 单次申请证据数量上限（≥1 必填由 validator 与 DTO 注解双层拦截） */
    public static final int REFUND_EVIDENCE_MAX_COUNT = 9;
    /** 证据原始文件名回显上限（与 ChatMediaService MAX_NAME 一致） */
    public static final int REFUND_EVIDENCE_NAME_MAX = 200;
    /** 证据 URL 长度上限 */
    public static final int REFUND_EVIDENCE_URL_MAX = 300;
    /**
     * 证据 URL 前缀：只收聊天媒体上传的本地盘域 URL（服务端生成名，杜绝外链）。
     * 字面量唯一持有方是 common/storage 豁免区（UploadPathGuardrailTest 门禁），此处引用而非复刻。
     */
    public static final String REFUND_EVIDENCE_URL_PREFIX = com.lanlink.shopping.common.storage.StorageUrls.LOCAL_UPLOAD_PREFIX;
    /** 证据类型白名单（前端 refundEvidence.js 同步） */
    public static final Set<String> ALLOWED_REFUND_EVIDENCE_TYPES = Set.of("image", "video", "file");
    /** type ↔ 落盘扩展名一致（落盘形态由 ChatMediaService 魔数嗅探固定） */
    public static final Map<String, Set<String>> ALLOWED_REFUND_EVIDENCE_EXT = Map.of(
            "image", Set.of("jpg", "png", "webp", "gif"),
            "video", Set.of("mp4", "mov"),
            "file", Set.of("pdf", "txt", "csv", "bin")
    );

    // ===== RMA 原因分类白名单（reason_category，前端暂不传值 → 服务端默认 other） =====
    public static final Set<String> ALLOWED_RMA_REASON_CATEGORIES = Set.of(
            "quality", "logistics", "description", "other"
    );

    // ===== 支付方式白名单（前端 radio 可选项与后端校验必须一致） =====
    public static final Set<String> ALLOWED_PAY_TYPES = Set.of(
            "balance", "corporate", "term", "alipay", "wechat", "stripe", "apple_pay", "google_pay"
    );

    // ===== 数值边界 =====
    /** 单笔下单商品行数量上限（防超大请求体/恶意刷单） */
    public static final int MAX_CART_ITEMS = 200;
    /** 单行购买数量上限 */
    public static final int MAX_ITEM_QUANTITY = 9999;
    /** 积分抵现：使用积分数非负整数 */
    public static final int MIN_USE_POINTS = 0;

    // ===== 幂等键 =====
    public static final int IDEMPOTENT_KEY_MAX = 64;
}
