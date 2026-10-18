package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.IdempotentToken;
import com.lanlink.shopping.mapper.IdempotentTokenMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 幂等校验服务（需求 2：核心接口防重复提交）。
 *
 * 实现方式：基于 t_idempotent_token 表 (biz_type, biz_key) 唯一约束，
 * 首次执行前抢占式插入令牌，插入失败即判定为重复提交/并发重放。
 * 与分布式锁等价的互斥语义：数据库唯一键在多实例并发下只有一个事务能插入成功，
 * 未引入 Redis 也满足核心接口幂等要求。
 *
 * 典型用法（下单/支付/退款）：
 * <pre>
 *   Long tokenId = idempotentService.reserve("checkout", dto.getIdempotentKey(), userId, null);
 *   try {
 *       Order o = doCheckout(...);
 *       idempotentService.bound(tokenId, o.getOrderNo());
 *       return o;
 *   } catch (Exception e) {
 *       idempotentService.release(tokenId);   // 失败释放，允许客户端重试
 *       throw e;
 *   }
 * </pre>
 */
@Service
public class IdempotentService {

    /** 令牌有效期：72 小时后可被清理（超长重复提交窗口） */
    private static final long TOKEN_TTL_HOURS = 72;

    private final IdempotentTokenMapper mapper;

    public IdempotentService(IdempotentTokenMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 抢占幂等令牌。成功返回令牌 ID；重复提交抛 BusinessException。
     *
     * @param bizType 业务类型（checkout/pay/refund/settle）
     * @param bizKey  幂等键（客户端传入或服务端生成，同一业务动作必须稳定不变）
     */
    public Long reserve(String bizType, String bizKey, Long userId, String resultRef) {
        if (bizKey == null || bizKey.isBlank()) {
            // 未提供幂等键时由服务端兜底生成（随机键 = 不拦截，等价于放弃幂等保护）
            bizKey = "auto:" + java.util.UUID.randomUUID();
        }
        IdempotentToken token = new IdempotentToken();
        token.setBizType(bizType);
        token.setBizKey(bizKey);
        token.setUserId(userId);
        token.setResultRef(resultRef);
        token.setCreateTime(LocalDateTime.now());
        token.setExpireTime(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));
        try {
            mapper.insert(token);
            return token.getId();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("请勿重复提交，该请求正在处理或已完成");
        }
    }

    /** 查询既有令牌（重复请求时返回首次结果引用，实现幂等返回） */
    public String findResultRef(String bizType, String bizKey) {
        if (bizKey == null || bizKey.isBlank()) return null;
        IdempotentToken t = mapper.selectOne(Wrappers.<IdempotentToken>lambdaQuery()
                .eq(IdempotentToken::getBizType, bizType)
                .eq(IdempotentToken::getBizKey, bizKey).last("LIMIT 1"));
        return t == null ? null : t.getResultRef();
    }

    /** 绑定首次执行结果（订单号等），供重复请求直接返回 */
    public void bound(Long tokenId, String resultRef) {
        if (tokenId == null) return;
        IdempotentToken t = new IdempotentToken();
        t.setId(tokenId);
        t.setResultRef(resultRef);
        mapper.updateById(t);
    }

    /** 业务执行失败时释放令牌，允许客户端携带同一幂等键重试 */
    public void release(Long tokenId) {
        if (tokenId == null) return;
        mapper.deleteById(tokenId);
    }
}
