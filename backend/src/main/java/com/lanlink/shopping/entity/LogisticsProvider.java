package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

/** 物流服务商 */
@Data
@TableName("t_logistics_provider")
public class LogisticsProvider {
    @TableId(type = IdType.AUTO)
    private Long providerId;
    private String providerName;
    private String providerCode;
    private String contactName;
    private String contactPhone;
    /** mock=沙箱模拟 api=真实接口 manual=手工录入 */
    private String apiType;
    private String apiEndpoint;
    /** 渠道密钥（数据安全：仅接收写入，永不序列化返回前端） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;
    /** 能力标记 JSON（存储层内部格式，不外发） */
    @JsonIgnore
    private String capabilityJson;
    /** 0停用 1启用 */
    private Integer status;
    /** 结算周期 monthly/weekly/realtime */
    private String settleCycle;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}
