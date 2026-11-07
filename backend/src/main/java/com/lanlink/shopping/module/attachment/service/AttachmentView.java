package com.lanlink.shopping.module.attachment.service;

/**
 * 附件对外视图：故意不含对象键与桶地址。
 *
 * <p>桶为匿名只读，把 objectKey 交给前端等于交出一条无需凭证即可下载的地址，
 * 所以这里用记录类型从结构上断绝泄漏，而不是靠调用方"记得不要打印"。
 */
public record AttachmentView(String no, String url, String fileName, long size, String contentType,
                               String status, String thumbStatus, String thumbUrl) {

    /**
     * 预览中转地址的查询串。
     *
     * <p>只能是查询串：{@code AttachmentService.contentUrl} 生成的是 …/attachment/{no}/content，
     * 已经在路径里带了 /content，此处再补一段就会拼出 …/content/content?variant=thumb，
     * 命中不到映射、预览图永远 0×0（真机浏览器实测踩到过）。
     */
    public static final String THUMB_URL_QUERY = "?variant=thumb";

    /** 兼容既有六参构造：不关心预览的调用点不必被迫补两个 null */
    public AttachmentView(String no, String url, String fileName, long size, String contentType, String status) {
        this(no, url, fileName, size, contentType, status, null, null);
    }
}
