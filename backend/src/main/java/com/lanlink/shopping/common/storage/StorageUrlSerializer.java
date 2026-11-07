package com.lanlink.shopping.common.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * coverUrl 出站兼容层（out/storage-mq-plan.md 任务 A4：不直改存量 DB，出站统一渲染）。
 *
 * <p>挂在 Product.coverUrl 的 @JsonSerialize 上：库存 key（如 1001.webp、products/prod_ts.webp）
 * 出站拼公开绝对 URL；存量相对路径 / 本地盘域 / 外部 URL 原样兜底，所有直接序列化
 * Product 的接口（列表/详情/AI 工具响应等）自动生效，漏一处风险最低。
 */
public class StorageUrlSerializer extends JsonSerializer<String> {

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(StorageUrls.publicUrlOf(value));
    }
}
