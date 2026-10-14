package com.lanlink.shopping.service;

import com.lanlink.shopping.dto.ProfileUpdateDTO;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 个人资料读写：敏感字段经 EncryptTypeHandler 自动 AES-GCM 加解密。
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
        if (dto.getRealName() != null) p.setRealName(dto.getRealName());
        if (dto.getGender() != null) p.setGender(dto.getGender());
        if (dto.getBirthday() != null) p.setBirthday(dto.getBirthday());
        if (dto.getAvatar() != null) p.setAvatar(dto.getAvatar());
        if (dto.getBio() != null) p.setBio(dto.getBio());
        if (dto.getPhone() != null) p.setPhone(dto.getPhone());
        if (dto.getEmail() != null) p.setEmail(dto.getEmail());
        if (dto.getIdCard() != null) p.setIdCard(dto.getIdCard());
        if (dto.getBankAccount() != null) p.setBankAccount(dto.getBankAccount());
        if (dto.getAddress() != null) p.setAddress(dto.getAddress());
        p.setUpdateTime(LocalDateTime.now());
        if (isNew) mapper.insert(p); else mapper.updateById(p);
    }
}
