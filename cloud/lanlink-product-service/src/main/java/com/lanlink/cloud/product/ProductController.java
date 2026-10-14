package com.lanlink.cloud.product;

import com.lanlink.cloud.api.dto.ProductDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品契约实现（ProductApi 提供方）。阶段 1 内存演示数据，阶段 2 接入 t_product/t_category/t_industry。
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    @GetMapping("/{id}")
    public R<ProductDTO> getById(@PathVariable Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "商品ID不合法");
        ProductDTO p = new ProductDTO();
        p.setProdId(id);
        p.setMerId(1L);
        p.setTitle("演示商品-" + id);
        p.setPrice(new BigDecimal("800.00"));
        p.setStock(998);
        p.setStatus(1); // 1=已上架可售
        return R.ok(p);
    }

    /** 首页行业列表（阶段 2 接入 t_industry） */
    @GetMapping("/industries")
    public R<List<String>> industries() {
        return R.ok(List.of("建筑行业", "纺织行业", "石化行业", "电子行业"));
    }
}
