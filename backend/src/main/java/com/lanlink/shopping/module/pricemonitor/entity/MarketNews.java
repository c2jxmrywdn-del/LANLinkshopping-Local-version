package com.lanlink.shopping.module.pricemonitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** B2B 市场资讯（价格情报台信息流）。 */
@Data
@TableName("t_market_news")
public class MarketNews {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String summary;
    /** policy/supply/demand/price/tech/market/international */
    private String category;
    private String source;
    private String url;
    /** 原始来源 URL（重定向链首跳，版权溯源；资讯管线回填） */
    @TableField("origin_url")
    private String originUrl;
    /** 正文 sha256 指纹（网关 news 管线判重；uk_news_fingerprint 唯一键兜底） */
    @TableField("fingerprint")
    private String fingerprint;
    /** 逗号分隔标签 */
    private String tags;
    /** 1普通 2重要 3头条 */
    private Integer importance;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
