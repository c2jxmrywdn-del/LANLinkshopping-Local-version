package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.SysConfig;
import com.lanlink.shopping.mapper.SysConfigMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时动态配置服务：DB 为准 + 内存缓存，改配置即时生效，无需重启。
 */
@Service
public class SysConfigService {

    private final SysConfigMapper mapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SysConfigService(SysConfigMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        cache.clear();
        for (SysConfig c : mapper.selectList(null)) {
            if (c.getCfgKey() != null) cache.put(c.getCfgKey(), c.getCfgValue());
        }
    }

    public String get(String key, String def) {
        String v = cache.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        try { return Integer.parseInt(cache.get(key)); } catch (Exception e) { return def; }
    }

    public boolean getBool(String key, boolean def) {
        String v = cache.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    /** 更新配置：写库 + 刷新缓存，立即生效 */
    public void set(String key, String value) {
        SysConfig exist = mapper.selectById(key);
        if (exist == null) {
            exist = new SysConfig();
            exist.setCfgKey(key);
            exist.setRemark("");
            exist.setUpdateTime(LocalDateTime.now());
            exist.setCfgValue(value);
            mapper.insert(exist);
        } else {
            exist.setCfgValue(value);
            exist.setUpdateTime(LocalDateTime.now());
            mapper.updateById(exist);
        }
        cache.put(key, value);
    }

    public List<SysConfig> list() {
        return mapper.selectList(Wrappers.<SysConfig>lambdaQuery().orderByAsc(SysConfig::getCfgKey));
    }

    public void refresh() { load(); }
}
