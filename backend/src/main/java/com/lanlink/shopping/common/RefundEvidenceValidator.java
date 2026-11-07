package com.lanlink.shopping.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款凭证证据权威校验与归一化（out/refund-map-design.md §1.4 契约）。
 *
 * <p>纯静态工具（无 Spring 依赖，便于单测）。传输/落库同构：JSON 字符串，元素 {type,url,name}。
 * 任何违规抛 {@link BusinessException}，文案回显前端。规则（设计拍板：统一必填 ≥1、上限 9）：
 * <ol>
 *   <li>数量 ≥1 必填、≤ {@link ValidationRules#REFUND_EVIDENCE_MAX_COUNT}；</li>
 *   <li>type ∈ ALLOWED_REFUND_EVIDENCE_TYPES；</li>
 *   <li>url 以 {@link ValidationRules#REFUND_EVIDENCE_URL_PREFIX} 开头且不含 ".."（防外链 + 防穿越双保险）；</li>
 *   <li>type ↔ 落盘扩展名一致（落盘形态由 ChatMediaService 魔数嗅探固定，零成本校验）；</li>
 *   <li>URL 归属当前用户：落盘名固定 {userId}_{ts}.{ext}，文件名首段 userId 必须等于当前登录用户；</li>
 *   <li>文件真实存在：对 ≤9 个 url 做 File.exists stat（落盘在上传根目录下，成本可忽略；
 *       未来迁对象存储改为 HEAD 探测或异步巡检）；</li>
 *   <li>url ≤300、name 非空 ≤200；</li>
 *   <li>解析失败 / 非数组 / 元素缺字段 → 整体拒绝；</li>
 *   <li>归一化输出：只保留 type/url/name 三字段重新序列化，防多余字段注入。</li>
 * </ol>
 *
 * <p>路径门禁说明：本类不得持有本地盘 URL 前缀与工作目录拼路径字面量
 * （UploadPathGuardrailTest 冻结清单只允许 LEGACY 文件持有，注释内出现同样命中扫描）。
 * 前缀取自 {@link ValidationRules#REFUND_EVIDENCE_URL_PREFIX}（底层为 storage 豁免区常量）；
 * 本地落盘根目录由 ChatMediaService（LEGACY）经 {@code chatUploadRoot()} 下发。
 */
public final class RefundEvidenceValidator {

    private RefundEvidenceValidator() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 校验并归一化证据 JSON。
     *
     * @param rawJson     前端提交的证据 JSON 字符串（可为 null/空 → 按未提交处理，必填拒绝）
     * @param currentUserId 当前登录用户 ID（URL 归属校验基准）
     * @param uploadsRoot 本地上传根目录（URL 前缀剥离后的相对路径据此 stat，通常为 ChatMediaService.chatUploadRoot()）
     * @return 规范 JSON 字符串（仅含 type/url/name 三字段，顺序稳定）
     */
    public static String normalize(String rawJson, Long currentUserId, File uploadsRoot) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new BusinessException("请至少上传 1 项退款凭证");
        }
        List<Map<String, Object>> items;
        try {
            items = MAPPER.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new BusinessException("退款凭证格式不正确，请重新上传");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException("请至少上传 1 项退款凭证");
        }
        if (items.size() > ValidationRules.REFUND_EVIDENCE_MAX_COUNT) {
            throw new BusinessException("退款凭证最多上传 " + ValidationRules.REFUND_EVIDENCE_MAX_COUNT + " 项");
        }

        List<Map<String, String>> normalized = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            normalized.add(normalizeItem(items.get(i), i + 1, currentUserId, uploadsRoot));
        }
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            // 归一化元素均为基础字符串，序列化不会失败；兜底拒绝而非落库脏数据
            throw new BusinessException("退款凭证格式不正确，请重新上传");
        }
    }

    private static Map<String, String> normalizeItem(Map<String, Object> item, int seq, Long currentUserId, File uploadsRoot) {
        if (item == null) {
            throw new BusinessException("第 " + seq + " 项凭证格式不正确");
        }
        String type = str(item.get("type"));
        String url = str(item.get("url"));
        String name = str(item.get("name"));

        if (!ValidationRules.ALLOWED_REFUND_EVIDENCE_TYPES.contains(type)) {
            throw new BusinessException("第 " + seq + " 项凭证类型不支持");
        }
        if (url.isBlank()) {
            throw new BusinessException("第 " + seq + " 项凭证缺少文件地址");
        }
        if (url.length() > ValidationRules.REFUND_EVIDENCE_URL_MAX) {
            throw new BusinessException("第 " + seq + " 项凭证地址超出长度限制");
        }
        if (!url.startsWith(ValidationRules.REFUND_EVIDENCE_URL_PREFIX)) {
            throw new BusinessException("第 " + seq + " 项凭证必须来自本平台上传文件");
        }
        if (url.contains("..")) {
            throw new BusinessException("第 " + seq + " 项凭证地址不合法");
        }
        if (name.isBlank()) {
            throw new BusinessException("第 " + seq + " 项凭证缺少文件名");
        }
        if (name.length() > ValidationRules.REFUND_EVIDENCE_NAME_MAX) {
            throw new BusinessException("第 " + seq + " 项凭证文件名超出长度限制");
        }

        // type ↔ 落盘扩展名一致（URL 末段为服务端生成名 {userId}_{ts}.{ext}）
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        if (!ValidationRules.ALLOWED_REFUND_EVIDENCE_EXT.get(type).contains(ext)) {
            throw new BusinessException("第 " + seq + " 项凭证文件类型与扩展名不一致");
        }

        // URL 归属当前用户：落盘名首段为上传者 userId，杜绝把他人文件 URL 塞进自己的凭证
        int underscore = fileName.indexOf('_');
        if (underscore <= 0 || currentUserId == null) {
            throw new BusinessException("第 " + seq + " 项凭证地址不合法");
        }
        Long owner;
        try {
            owner = Long.parseLong(fileName.substring(0, underscore));
        } catch (NumberFormatException e) {
            throw new BusinessException("第 " + seq + " 项凭证地址不合法");
        }
        if (!owner.equals(currentUserId)) {
            throw new BusinessException("第 " + seq + " 项凭证不属于当前用户，请重新上传");
        }

        // 文件真实存在：剥离 URL 前缀后对本地落盘 stat
        if (uploadsRoot != null) {
            String relPath = url.substring(ValidationRules.REFUND_EVIDENCE_URL_PREFIX.length());
            if (!new File(uploadsRoot, relPath).exists()) {
                throw new BusinessException("第 " + seq + " 项凭证文件不存在或已过期，请重新上传");
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("url", url);
        out.put("name", name);
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
