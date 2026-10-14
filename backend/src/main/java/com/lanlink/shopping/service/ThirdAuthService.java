package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.ThirdAuth;
import com.lanlink.shopping.mapper.ThirdAuthMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 第三方授权管理：仅本人可见、可撤回
 */
@Service
public class ThirdAuthService {

    private final ThirdAuthMapper mapper;

    public ThirdAuthService(ThirdAuthMapper mapper) {
        this.mapper = mapper;
    }

    public List<ThirdAuth> listByUser(Long userId) {
        return mapper.selectList(
                Wrappers.<ThirdAuth>lambdaQuery().eq(ThirdAuth::getUserId, userId).orderByAsc(ThirdAuth::getId));
    }

    /** 撤回授权：校验归属，防止越权 */
    public void revoke(Long userId, Long id) {
        ThirdAuth row = mapper.selectById(id);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BusinessException("授权记录不存在");
        }
        mapper.deleteById(id);
    }
}