package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.IdempotentToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotentTokenMapper extends BaseMapper<IdempotentToken> {
}
