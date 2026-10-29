package com.lanlink.shopping.module.pricemonitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface PriceAlertMapper extends BaseMapper<PriceAlert> {

    /** 商户总览聚合：每商品生效预警订阅数（一条 SQL） */
    @Select("<script>SELECT prod_id AS prodId, COUNT(*) AS cnt FROM t_price_alert " +
            "WHERE status = 1 AND prod_id IN " +
            "<foreach collection='prodIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY prod_id</script>")
    List<Map<String, Object>> countByProducts(@Param("prodIds") List<Long> prodIds);
}
