package com.lanlink.shopping.module.techservice.domain;

/**
 * 陪跑项目阶段推进策略（根据项目类型与完成进度推导阶段）。
 *
 * <p>阶段只用于展示与运营跟进，不作为交易或评价的合规依据。
 */
public final class TechProjectStagePolicy {

    private TechProjectStagePolicy() {
    }

    public static String stageFor(String projectType, int progress) {
        if (progress >= 100) return "CLOSED";
        if ("TRANSACTION".equals(projectType)) {
            return progress >= 75 ? "EXECUTION" : progress >= 50 ? "CONTRACT"
                    : progress >= 25 ? "DUE_DILIGENCE" : "MATCHING";
        }
        if ("APPRAISAL".equals(projectType)) {
            return progress >= 75 ? "ACCEPTANCE" : progress >= 50 ? "EXECUTION"
                    : progress >= 25 ? "MATCHING" : "INTAKE";
        }
        return progress >= 75 ? "EXECUTION" : progress >= 50 ? "MATCHING"
                : progress >= 25 ? "DIAGNOSIS" : "INTAKE";
    }
}
