package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.SocialBinding;
import com.lanlink.shopping.mapper.SocialBindingMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 社交登录绑定读写。
 * (provider, subject) 上有唯一约束，绑定采取「先查后插 + 命中唯一键即回读」的幂等写法，
 * 避免并发首登时重复插入。
 */
@Service
public class SocialBindingService {

    private final SocialBindingMapper mapper;

    public SocialBindingService(SocialBindingMapper mapper) {
        this.mapper = mapper;
    }

    public SocialBinding find(String provider, String subject) {
        if (provider == null || subject == null) return null;
        return mapper.selectOne(Wrappers.<SocialBinding>lambdaQuery()
                .eq(SocialBinding::getProvider, provider)
                .eq(SocialBinding::getSubject, subject));
    }

    /** 幂等绑定：已存在则原样返回，不存在则插入（唯一键冲突时回读） */
    public SocialBinding bind(Long userId, String provider, String subject, String unionId,
                              String nickname, String avatar, String email) {
        SocialBinding existing = find(provider, subject);
        if (existing != null) return existing;
        SocialBinding row = new SocialBinding();
        row.setUserId(userId);
        row.setProvider(provider);
        row.setSubject(subject);
        row.setUnionId(unionId);
        row.setNickname(nickname);
        row.setAvatar(avatar);
        row.setEmail(email);
        try {
            mapper.insert(row);
            return row;
        } catch (Exception e) {
            // 并发首绑：唯一键冲突 → 回读已存在的绑定
            SocialBinding raced = find(provider, subject);
            if (raced != null) return raced;
            throw e;
        }
    }

    public List<SocialBinding> listByUser(Long userId) {
        return mapper.selectList(Wrappers.<SocialBinding>lambdaQuery()
                .eq(SocialBinding::getUserId, userId)
                .orderByAsc(SocialBinding::getId));
    }

    public boolean unbind(Long userId, Long id) {
        return mapper.delete(Wrappers.<SocialBinding>lambdaQuery()
                .eq(SocialBinding::getId, id)
                .eq(SocialBinding::getUserId, userId)) > 0;
    }
}
