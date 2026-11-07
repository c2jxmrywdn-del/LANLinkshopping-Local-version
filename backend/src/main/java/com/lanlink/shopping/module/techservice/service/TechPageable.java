package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页参数收敛。收在一处而不是每个查询服务各写一遍：`size` 直接透传给 MyBatis-Plus 时，
 * `?size=100000` 就是全表导出，公开目录还额外把整库资源画像一次拉走。
 */
final class TechPageable {

    /** 公开目录列表上限（游客可访问，收紧） */
    static final int PUBLIC_MAX_SIZE = 50;

    /** 运营台与台账列表上限（批量视图放宽） */
    static final int ADMIN_MAX_SIZE = 200;

    private TechPageable() {
    }

    static <T> Page<T> of(long page, long size, int maxSize) {
        return new Page<>(Math.max(1, page), Math.min(Math.max(1, size), maxSize));
    }
}
