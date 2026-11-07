package com.lanlink.shopping.module.techservice.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 技术服务业务单号生成（前缀 + 秒级时间戳 + 3 位随机数）。
 *
 * <p>仅用于本模块的业务编号展示，不作为并发唯一的数据库主键；
 * 若后续要求强唯一，应改用项目已有的序列服务（t_sys_sequence）。
 */
public final class TechNoGenerator {

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private TechNoGenerator() {
    }

    public static String next(String prefix) {
        return prefix + NO_FORMAT.format(LocalDateTime.now())
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
