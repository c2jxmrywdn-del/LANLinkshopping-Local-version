package com.lanlink.shopping.module.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.review.entity.ProductReview;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface ProductReviewMapper extends BaseMapper<ProductReview> {

    /**
     * 评分聚合原子累加：评价创建/审核通过时 +1，隐藏时传负值回退。
     * 用 upsert + 增量而不是"读出来算再写回"，避免并发评价丢计数。
     */
    @Insert("INSERT INTO t_product_rating_stat (prod_id, review_count, rating_sum, good_count, update_time) "
            + "VALUES (#{prodId}, #{delta}, #{rating} * #{delta}, #{good} * #{delta}, NOW()) "
            + "ON DUPLICATE KEY UPDATE review_count = review_count + #{delta}, "
            + "rating_sum = rating_sum + #{rating} * #{delta}, "
            + "good_count = good_count + #{good} * #{delta}, update_time = NOW()")
    int bumpStat(@Param("prodId") Long prodId, @Param("delta") int delta,
                 @Param("rating") int rating, @Param("good") int good);

    /** 各星级计数（含 0 星），用于详情页评分分布 */
    @Select("SELECT rating, COUNT(*) AS c FROM t_product_review "
            + "WHERE prod_id = #{prodId} AND status = 1 GROUP BY rating")
    List<Map<String, Object>> ratingDistribution(@Param("prodId") Long prodId);
}
