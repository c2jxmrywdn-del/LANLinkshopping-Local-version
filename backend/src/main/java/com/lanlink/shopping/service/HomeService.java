package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 首页 / 行业解决方案服务
 */
@Service
public class HomeService {

    private final IndustryMapper industryMapper;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;

    public HomeService(IndustryMapper industryMapper, CategoryMapper categoryMapper, ProductMapper productMapper) {
        this.industryMapper = industryMapper;
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
    }

    public List<Industry> industries() {
        return industryMapper.selectList(Wrappers.<Industry>lambdaQuery().orderByAsc(Industry::getSort));
    }

    public List<Category> categories(Long indId) {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .eq(indId != null, Category::getIndId, indId)
                .orderByAsc(Category::getSort));
    }

    /** 首页热销/推荐 */
    public List<Product> hot(int limit) {
        List<Product> all = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getSales)
                .last("limit " + limit));
        return all;
    }
}
