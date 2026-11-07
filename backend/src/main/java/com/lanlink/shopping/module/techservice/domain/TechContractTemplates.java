package com.lanlink.shopping.module.techservice.domain;

import java.util.List;
import java.util.Optional;

/**
 * 六类技术合同模板（依据《技术合同认定登记管理办法》五类合同 + 技术中介合同）。
 *
 * <p>模板只提供条款框架与材料清单，不构成法律意见；正式签署前须由法务/律师审核，
 * 技术合同认定登记由登记机构办理，平台只做材料辅导。
 */
public final class TechContractTemplates {

    public record Template(String code, String contractType, String name, String description,
                           List<String> clauses, String defaultCommissionRate) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("DEV", "技术开发", "技术开发合同",
                    "委托开发或合作开发新技术、新产品、新工艺。",
                    List.of("开发内容与技术要求", "研究开发计划与节点", "经费与支付方式", "验收标准与方式",
                            "技术成果归属与实施", "保密与知识产权", "违约责任与争议解决"),
                    "15.00"),
            new Template("TRANSFER", "技术转让", "技术转让合同",
                    "专利权/专利申请权/技术秘密的转让。",
                    List.of("转让标的与权属证明", "转让价款与支付节点", "著录项目变更与登记", "技术资料交付",
                            "权利瑕疵担保", "保密与后续改进", "税费承担与争议解决"),
                    "15.00"),
            new Template("LICENSE", "技术许可", "技术许可合同",
                    "专利/技术秘密的实施许可，含独占、排他、普通许可。",
                    List.of("许可类型与地域期限", "许可使用费与提成", "实施范围与再许可", "备案与登记",
                            "侵权责任与维权分工", "改进成果归属", "终止与退出"),
                    "15.00"),
            new Template("CONSULT", "技术咨询", "技术咨询合同",
                    "就特定技术项目提供可行性论证、技术预测、专题调查等。",
                    List.of("咨询内容与交付形式", "履行期限与地点", "报酬与支付", "咨询意见的采用与责任",
                            "保密义务", "验收与争议解决"),
                    "12.00"),
            new Template("SERVICE", "技术服务", "技术服务合同",
                    "以技术知识解决特定技术问题，含技术诊断、检测、培训等。",
                    List.of("服务内容与标准", "服务期限与地点", "服务费与支付", "交付物与验收",
                            "双方义务与安全责任", "保密与知识产权", "违约与争议解决"),
                    "15.00"),
            new Template("INTERMEDIARY", "技术中介", "技术中介合同",
                    "平台作为居间方提供信息发布、需求诊断、撮合与合同辅导。",
                    List.of("委托事项与服务边界", "平台角色（居间方，不替代持牌机构）", "佣金标准与支付节点",
                            "双向收费披露", "禁止跳单与保密", "不承诺成交与免责", "争议解决"),
                    "15.00")
    );

    private TechContractTemplates() {
    }

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static Optional<Template> find(String code) {
        if (code == null) return Optional.empty();
        return TEMPLATES.stream().filter(t -> t.code().equalsIgnoreCase(code.trim())).findFirst();
    }
}
