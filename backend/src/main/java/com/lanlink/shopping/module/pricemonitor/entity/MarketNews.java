package com.lanlink.shopping.module.pricemonitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    /** 逗号分隔标签 */
    private String tags;
    /** 1普通 2重要 3头条 */
    private Integer importance;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
