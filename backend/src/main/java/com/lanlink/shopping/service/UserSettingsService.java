package com.lanlink.shopping.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.UserSettings;
import com.lanlink.shopping.mapper.UserSettingsMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户系统设置：settings_json 单列整存，GET/PUT 均返回完整对象。
 * PUT 收到部分 patch 后与当前值深合并，写回整列（增量同步由前端保证只提交改动字段）。
 */
@Service
public class UserSettingsService {

    /** 平台全局默认设置（与前端 settings store 默认外观对齐） */
    public static final String DEFAULT_SETTINGS_JSON =
            "{\"notify\":{\"email\":true,\"push\":true,\"sms\":false,\"groups\":{\"order\":true,\"promotion\":false,\"system\":true}},"
          + "\"appearance\":{\"theme\":\"system\",\"language\":\"zh-CN\",\"fontSize\":\"standard\"},"
          + "\"privacy\":{\"recommend\":true,\"ads\":false}}";

    private final UserSettingsMapper mapper;
    private final ObjectMapper om = new ObjectMapper();

    public UserSettingsService(UserSettingsMapper mapper) {
        this.mapper = mapper;
    }

    /** 查询（深拷贝返回，避免调用方改写内部缓存） */
    public Map<String, Object> get(Long userId) {
        UserSettings row = mapper.selectById(userId);
        if (row == null || row.getSettingsJson() == null || row.getSettingsJson().isBlank()) {
            return parse(DEFAULT_SETTINGS_JSON);
        }
        return parse(row.getSettingsJson());
    }

    /** 部分更新：与当前值深合并后整存 */
    public Map<String, Object> patch(Long userId, Map<String, Object> patch) {
        Map<String, Object> current = get(userId);
        Map<String, Object> merged = deepMerge(current, patch);
        save(userId, merged);
        return merged;
    }

    public Map<String, Object> reset(Long userId) {
        Map<String, Object> def = parse(DEFAULT_SETTINGS_JSON);
        save(userId, def);
        return def;
    }

    private void save(Long userId, Map<String, Object> merged) {
        try {
            String json = om.writeValueAsString(merged);
            UserSettings row = mapper.selectById(userId);
            if (row == null) {
                row = new UserSettings();
                row.setUserId(userId);
                row.setSettingsJson(json);
                row.setUpdateTime(LocalDateTime.now());
                mapper.insert(row);
            } else {
                row.setSettingsJson(json);
                row.setUpdateTime(LocalDateTime.now());
                mapper.updateById(row);
            }
        } catch (Exception e) {
            throw new RuntimeException("设置序列化失败", e);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return om.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return parse(DEFAULT_SETTINGS_JSON);
        }
    }

    /** 纯对象深合并：基础类型/数组直接覆盖 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object pv = e.getValue();
            Object bv = out.get(e.getKey());
            if (pv instanceof Map && bv instanceof Map) {
                out.put(e.getKey(), deepMerge((Map<String, Object>) bv, (Map<String, Object>) pv));
            } else {
                out.put(e.getKey(), pv);
            }
        }
        return out;
    }
}