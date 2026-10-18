package com.lanlink.shopping.service;

import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.dto.ProfileUpdateDTO;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 个人资料读写：敏感字段经 EncryptTypeHandler 自动 AES-GCM 加解密。
 * 保存时对入参做反向脱敏保护，防止带星号的脱敏数据覆盖数据库原真实值。
 */
@Service
public class UserProfileService {

    private final UserProfileMapper mapper;

    public UserProfileService(UserProfileMapper mapper) {
        this.mapper = mapper;
    }

    public UserProfile get(Long userId) {
        return mapper.selectById(userId);
    }

    public void save(Long userId, ProfileUpdateDTO dto) {
        UserProfile p = mapper.selectById(userId);
        boolean isNew = (p == null);
        if (isNew) p = new UserProfile();
        p.setUserId(userId);
        if (dto.getRealName() != null && (!isNew || !DesensitizeUtil.isMasked(dto.getRealName()))) {
            if (!DesensitizeUtil.isMasked(dto.getRealName())) p.setRealName(dto.getRealName());
        }
        if (dto.getGender() != null) p.setGender(dto.getGender());
        if (dto.getBirthday() != null) p.setBirthday(dto.getBirthday());
        if (dto.getAvatar() != null) p.setAvatar(dto.getAvatar());
        if (dto.getBio() != null) p.setBio(dto.getBio());
        if (dto.getPhone() != null && !DesensitizeUtil.isMasked(dto.getPhone())) {
            p.setPhone(dto.getPhone());
        }
        if (dto.getEmail() != null && !DesensitizeUtil.isMasked(dto.getEmail())) {
            p.setEmail(dto.getEmail());
        }
        if (dto.getIdCard() != null && !DesensitizeUtil.isMasked(dto.getIdCard())) {
            p.setIdCard(dto.getIdCard());
        }
        if (dto.getBankAccount() != null && !DesensitizeUtil.isMasked(dto.getBankAccount())) {
            p.setBankAccount(dto.getBankAccount());
        }
        if (dto.getAddress() != null && !DesensitizeUtil.isMasked(dto.getAddress())) {
            p.setAddress(dto.getAddress());
        }
        p.setUpdateTime(LocalDateTime.now());
        if (isNew) mapper.insert(p); else mapper.updateById(p);
    }
}
