package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分页收敛：size 不能当成全表导出参数用。
 */
class TechPageableTest {

    @Test
    void capsOversizedRequests() {
        Page<Object> p = TechPageable.of(1, 100_000, TechPageable.PUBLIC_MAX_SIZE);
        assertEquals(50, p.getSize(), "公开目录被 ?size=100000 拉全表是真实风险面");

        assertEquals(200, TechPageable.of(1, 9999, TechPageable.ADMIN_MAX_SIZE).getSize());
    }

    @Test
    void normalizesIllegalParameters() {
        assertEquals(1, TechPageable.of(0, 12, TechPageable.PUBLIC_MAX_SIZE).getCurrent(), "page=0 不应翻到非法页");
        assertEquals(1, TechPageable.of(-5, 12, TechPageable.PUBLIC_MAX_SIZE).getCurrent());
        assertEquals(1, TechPageable.of(1, 0, TechPageable.PUBLIC_MAX_SIZE).getSize(), "size=0 会返回空页且 total 仍算全表");
        assertEquals(1, TechPageable.of(1, -20, TechPageable.PUBLIC_MAX_SIZE).getSize());
    }

    @Test
    void leavesReasonableRequestsAlone() {
        Page<Object> p = TechPageable.of(3, 12, TechPageable.PUBLIC_MAX_SIZE);
        assertEquals(3, p.getCurrent());
        assertEquals(12, p.getSize());
    }
}
