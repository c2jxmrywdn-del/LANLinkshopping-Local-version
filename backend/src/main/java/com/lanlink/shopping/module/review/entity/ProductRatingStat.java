package com.lanlink.shopping.module.review.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评分聚合（独立表，不写进 t_product）。
 *
 * <p>t_product 是下单扣库存的高频写热点，把评分计数塞进去会与扣减争同一行锁，
 * 也会让商品列表的 SELECT * 多背字段；统计独立存储、按评价状态迁移原子增减。
 */
@Data
@TableName("t_product_rating_stat")
public class ProductRatingStat {
    @TableId
    private Long prodId;
    private Integer reviewCount;
    private Long ratingSum;
    /** 4-5 星数量，用于好评率 */
    private Integer goodCount;
    private LocalDateTime updateTime;
}
