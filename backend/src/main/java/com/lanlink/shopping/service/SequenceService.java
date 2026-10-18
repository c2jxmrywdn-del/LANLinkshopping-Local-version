package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.mapper.SequenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务单号序列服务：跨实例安全的取号器。
 *
 * <p>问题背景：运单号/结算单号/售后单号原用 {@code selectCount() + 1} 取序列，
 * 并发下两个请求会取到同一序号，撞唯一约束导致业务失败（高影响缺陷）。
 *
 * <p>方案：t_sys_sequence 每个序列键一行，{@code UPDATE ... LAST_INSERT_ID(current_val + step)}
 * 单条语句在行锁内原子自增；@Transactional 保证三条语句同一连接（LAST_INSERT_ID 连接级作用域）。
 * 序列键内含周期（如 waybill:20261016），天然按日/月重置，单号格式与原实现一致。
 */
@Service
public class SequenceService {

    private final SequenceMapper mapper;

    public SequenceService(SequenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取下一个序列值（从 1 开始，单调递增，并发安全）。
     *
     * @param seqKey 序列键（含周期，如 waybill:20261016 / settlement:202610 / aftersale:20261016）
     */
    @Transactional
    public long next(String seqKey) {
        mapper.insertIgnore(seqKey);
        if (mapper.increment(seqKey, 1) == 0) {
            throw new BusinessException("序列初始化失败: " + seqKey);
        }
        return mapper.lastInsertId();
    }

    /** 取号并格式化为定长序号（如 1 -> "0001"） */
    @Transactional
    public String nextNo(String seqKey, int width) {
        return String.format("%0" + width + "d", next(seqKey));
    }
}
