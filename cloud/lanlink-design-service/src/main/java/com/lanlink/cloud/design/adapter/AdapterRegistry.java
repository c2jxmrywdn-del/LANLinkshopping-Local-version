package com.lanlink.cloud.design.adapter;

import com.lanlink.cloud.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适配器注册中心：Spring 自动收集所有 DesignToolAdapter Bean。
 * 新工具接入 = 新增一个实现类（@Component），此处零改动 → 插件化扩展。
 */
@Component
public class AdapterRegistry {

    private final Map<String, DesignToolAdapter> adapters;

    public AdapterRegistry(List<DesignToolAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toUnmodifiableMap(DesignToolAdapter::toolId, Function.identity()));
    }

    /** 按工具标识取适配器 */
    public DesignToolAdapter get(String toolId) {
        DesignToolAdapter adapter = adapters.get(toolId);
        if (adapter == null) {
            throw new BusinessException(404, "未找到设计工具适配器: " + toolId
                    + "，已接入: " + adapters.keySet());
        }
        return adapter;
    }

    /** 全部适配器（能力矩阵展示用） */
    public List<DesignToolAdapter> all() {
        return List.copyOf(adapters.values());
    }
}
