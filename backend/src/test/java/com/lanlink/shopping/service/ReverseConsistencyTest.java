package com.lanlink.shopping.service;

import com.lanlink.shopping.dto.ProfileUpdateDTO;
import com.lanlink.shopping.entity.Address;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.AddressMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReverseConsistencyTest {

    @Test
    void userProfileServiceDoesNotOverwriteWithMaskedValues() {
        UserProfileMapper mapper = mock(UserProfileMapper.class);
        UserProfile existing = new UserProfile();
        existing.setUserId(100L);
        existing.setRealName("张大千");
        existing.setPhone("13812345678");
        existing.setEmail("artist@example.com");
        existing.setIdCard("110101199001011234");
        existing.setBankAccount("6222021234567891234");
        existing.setAddress("北京市海淀区中关村南大街1号");

        when(mapper.selectById(100L)).thenReturn(existing);

        UserProfileService service = new UserProfileService(mapper);

        // 前端更新，传入脱敏数据与新简介
        ProfileUpdateDTO dto = new ProfileUpdateDTO();
        dto.setRealName("张*");
        dto.setPhone("138****5678");
        dto.setEmail("a***t@example.com");
        dto.setIdCard("110101********1234");
        dto.setBankAccount("6222***********1234");
        dto.setAddress("北京市海淀区中关村南大街********");
        dto.setBio("新个人简介");

        service.save(100L, dto);

        // 验证 updateById 保存的对象中，敏感字段保持真实值，未被星号覆盖
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(mapper).updateById(captor.capture());
        UserProfile saved = captor.getValue();

        assertEquals("张大千", saved.getRealName());
        assertEquals("13812345678", saved.getPhone());
        assertEquals("artist@example.com", saved.getEmail());
        assertEquals("110101199001011234", saved.getIdCard());
        assertEquals("6222021234567891234", saved.getBankAccount());
        assertEquals("北京市海淀区中关村南大街1号", saved.getAddress());
        assertEquals("新个人简介", saved.getBio());
    }

    @Test
    void addressServiceUpdatePreservesOriginalWhenMasked() {
        AddressMapper mapper = mock(AddressMapper.class);
        Address existing = new Address();
        existing.setId(5L);
        existing.setUserId(1L);
        existing.setReceiver("诸葛孔明");
        existing.setPhone("13912345678");
        existing.setDetail("卧龙岗1号");

        when(mapper.selectById(5L)).thenReturn(existing);

        AddressService service = new AddressService(mapper);

        // 前端送回脱敏名字和电话，更新门牌号
        Address form = new Address();
        form.setReceiver("诸*明");
        form.setPhone("139****5678");
        form.setDetail("卧龙岗2号");

        service.update(1L, 5L, form);

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(mapper).updateById(captor.capture());
        Address saved = captor.getValue();

        assertEquals("诸葛孔明", saved.getReceiver());
        assertEquals("13912345678", saved.getPhone());
        assertEquals("卧龙岗2号", saved.getDetail());
    }

    @Test
    void logisticsAdminServicePreservesApiKeyWhenMasked() {
        LogisticsProviderMapper mapper = mock(LogisticsProviderMapper.class);
        LogisticsProvider existing = new LogisticsProvider();
        existing.setProviderId(1L);
        existing.setProviderCode("SF");
        existing.setProviderName("顺丰速运");
        existing.setApiKey("real_sf_secret_api_key_xyz");

        when(mapper.selectById(1L)).thenReturn(existing);

        LogisticsAdminService service = new LogisticsAdminService(
                mapper, mock(com.lanlink.shopping.mapper.LogisticsTemplateMapper.class),
                mock(com.lanlink.shopping.mapper.LogisticsAlertMapper.class),
                mock(com.lanlink.shopping.mapper.LogisticsAftersaleMapper.class),
                mock(AuditService.class), mock(SequenceService.class));

        // 编辑服务商，apiKey 包含脱敏星号
        LogisticsProvider edit = new LogisticsProvider();
        edit.setProviderId(1L);
        edit.setProviderCode("SF");
        edit.setProviderName("顺丰速运特快");
        edit.setApiKey("sk-****xyz");

        service.saveProvider(edit, 99L, null);

        ArgumentCaptor<LogisticsProvider> captor = ArgumentCaptor.forClass(LogisticsProvider.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("real_sf_secret_api_key_xyz", captor.getValue().getApiKey());
        assertEquals("顺丰速运特快", captor.getValue().getProviderName());
    }
}
