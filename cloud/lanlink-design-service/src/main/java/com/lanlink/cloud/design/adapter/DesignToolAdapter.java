package com.lanlink.cloud.design.adapter;

import com.lanlink.cloud.design.model.DesignModels.AuthUrlVO;
import com.lanlink.cloud.design.model.DesignModels.CallbackResultVO;
import com.lanlink.cloud.design.model.DesignModels.DesignComponentVO;
import com.lanlink.cloud.design.model.DesignModels.DesignFileVO;
import com.lanlink.cloud.design.model.DesignModels.DesignTokenSet;
import com.lanlink.cloud.design.model.DesignModels.EmbedSpecVO;
import com.lanlink.cloud.design.model.DesignModels.PreviewVO;
import com.lanlink.cloud.design.model.DesignModels.SyncResultVO;
import com.lanlink.cloud.design.model.DesignModels.TemplateQuery;
import com.lanlink.cloud.design.model.DesignModels.TemplateVO;
import com.lanlink.cloud.design.model.DesignModels.TokenVO;

import java.util.List;

/**
 * 设计工具统一适配器接口（插件化架构核心）。
 *
 * 设计原则：
 *  1. 新工具接入（Sketch / Adobe XD / MasterGo ...）只需实现本接口并注册为 Spring Bean，
 *     AdapterRegistry 自动发现，其余模块零改动（低耦合、高内聚）；
 *  2. 能力模型（Capability）：不同工具能力不同（如 Canva 有模板库/在线编辑器，Figma 无），
 *     通过 {@link #capabilities()} 声明，调用方按能力路由，不支持的能力返回 UNSUPPORTED；
 *  3. mock/live 双模式：每个适配器内置演示数据（mock）与真实 REST/OAuth 实现（live），
 *     由 lanlink.design.mode 配置切换，保证无外部凭据时可完整演示。
 */
public interface DesignToolAdapter {

    /** 适配器唯一标识（如 figma / canva / mock） */
    String toolId();

    /** 适配器显示名（如 Figma / Canva） */
    String displayName();

    /** 声明本工具支持的能力集合 */
    java.util.Set<Capability> capabilities();

    /** 默认是否支持某能力 */
    default boolean supports(Capability capability) {
        return capabilities().contains(capability);
    }

    // ===== OAuth 2.0 授权 =====

    /** 构造授权跳转地址（authorization_code 流程） */
    AuthUrlVO buildAuthUrl(String state);

    /** 授权码换访问令牌 */
    TokenVO exchangeToken(String code);

    /** 刷新访问令牌（支持 refresh_token 的工具） */
    TokenVO refreshToken(String refreshToken);

    // ===== 设计文件与组件资源 =====

    /** 读取设计文件（文件树/页面/画板概要） */
    DesignFileVO getDesignFile(String accessToken, String fileKey);

    /** 批量获取组件资源（含导出图 URL） */
    List<DesignComponentVO> listComponents(String accessToken, String fileKey, List<String> componentIds);

    /** 解析设计令牌与样式变量（颜色/字体/间距/圆角/阴影 → 统一令牌模型） */
    DesignTokenSet extractTokens(String accessToken, String fileKey);

    // ===== 实时预览与增量更新 =====

    /** 获取设计稿预览（缩略图/渲染图 URL，供前端实时预览） */
    PreviewVO getPreview(String accessToken, String fileKey, String nodeId);

    /**
     * 增量同步：拉取上次同步版本之后的变更，要求正常网络下 3 秒内返回。
     * @param sinceVersion 上次同步版本游标（首次传 null 表示全量）
     */
    SyncResultVO syncChanges(String accessToken, String fileKey, String sinceVersion);

    // ===== 平台扩展能力（模板库 / 在线编辑器 / 成果回传） =====

    /** 模板库检索（能力 TEMPLATE_LIBRARY） */
    default List<TemplateVO> listTemplates(String accessToken, TemplateQuery query) {
        throw new UnsupportedOperationException(toolId() + " 不支持模板库能力");
    }

    /** 构造在线编辑器嵌入规格（能力 EMBED_EDITOR） */
    default EmbedSpecVO buildEmbedSpec(String accessToken, String designId) {
        throw new UnsupportedOperationException(toolId() + " 不支持在线编辑器嵌入能力");
    }

    /** 编辑成果回传（能力 DESIGN_CALLBACK） */
    default CallbackResultVO submitCallback(String accessToken, String designId, String exportUrl) {
        throw new UnsupportedOperationException(toolId() + " 不支持设计成果回传能力");
    }

    /** 适配器能力枚举：新工具接入时按需勾选 */
    enum Capability {
        /** OAuth 2.0 授权 */
        OAUTH,
        /** 设计文件读取 */
        FILE_READ,
        /** 组件资源批量导出 */
        COMPONENT_EXPORT,
        /** 设计令牌/样式变量解析 */
        TOKEN_PARSE,
        /** 实时预览 */
        PREVIEW,
        /** 增量同步（≤3s） */
        INCREMENTAL_SYNC,
        /** 公开模板库 */
        TEMPLATE_LIBRARY,
        /** 在线编辑器嵌入（二次创作） */
        EMBED_EDITOR,
        /** 设计成果回传（流程闭环） */
        DESIGN_CALLBACK
    }
}
