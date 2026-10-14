package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Address;
import com.lanlink.shopping.mapper.AddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收货地址服务：默认地址置顶、默认唯一（事务清其余）
 */
@Service
public class AddressService {

    private final AddressMapper mapper;

    public AddressService(AddressMapper mapper) {
        this.mapper = mapper;
    }

    public List<Address> list(Long userId) {
        return mapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)
                .orderByDesc(Address::getIsDefault)
                .orderByAsc(Address::getId));
    }

    @Transactional
    public Address add(Long userId, Address form) {
        validate(form);
        if (form.getIsDefault() == null) form.setIsDefault(0);
        if (form.getIsDefault() == 1) clearDefault(userId);
        form.setId(null);
        form.setUserId(userId);
        form.setCreateTime(LocalDateTime.now());
        mapper.insert(form);
        return form;
    }

    @Transactional
    public Address update(Long userId, Long id, Address form) {
        Address exist = mustOwn(userId, id);
        validate(form);
        if (form.getReceiver() != null) exist.setReceiver(form.getReceiver());
        if (form.getPhone() != null) exist.setPhone(form.getPhone());
        if (form.getRegion() != null) exist.setRegion(form.getRegion());
        if (form.getDetail() != null) exist.setDetail(form.getDetail());
        if (form.getIsDefault() != null) {
            if (form.getIsDefault() == 1) clearDefault(userId);
            exist.setIsDefault(form.getIsDefault());
        }
        mapper.updateById(exist);
        return exist;
    }

    /** 删除（归属校验） */
    public void delete(Long userId, Long id) {
        mustOwn(userId, id);
        mapper.deleteById(id);
    }

    /** 设为默认（事务清其余默认） */
    @Transactional
    public void setDefault(Long userId, Long id) {
        mustOwn(userId, id);
        clearDefault(userId);
        Address a = mapper.selectById(id);
        a.setIsDefault(1);
        mapper.updateById(a);
    }

    private Address mustOwn(Long userId, Long id) {
        Address exist = mapper.selectById(id);
        if (exist == null || !exist.getUserId().equals(userId)) throw new BusinessException("地址不存在");
        return exist;
    }

    private void clearDefault(Long userId) {
        mapper.update(null, Wrappers.<Address>lambdaUpdate()
                .eq(Address::getUserId, userId).eq(Address::getIsDefault, 1)
                .set(Address::getIsDefault, 0));
    }

    private void validate(Address a) {
        if (a.getReceiver() == null || a.getReceiver().isBlank()) throw new BusinessException("请输入收货人");
        if (a.getPhone() == null || !a.getPhone().matches("^1[3-9]\\d{9}$")) throw new BusinessException("联系电话格式不正确");
        if (a.getDetail() == null || a.getDetail().isBlank()) throw new BusinessException("请输入详细地址");
    }
}