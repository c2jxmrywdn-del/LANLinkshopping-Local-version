package com.lanlink.shopping.module.pricemonitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 行业行情基准价（每日一条，应对市场波动的对标基准）
 * 由调度任务按行业商品现价均值聚合生成（幂等），异常波动即与基准的偏离。
 */
@Data
@TableName("t_market_benchmark")
public class MarketBenchmark {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long indId;
    private LocalDate day;
    private BigDecimal benchmark;
    private BigDecimal changePct;
}
