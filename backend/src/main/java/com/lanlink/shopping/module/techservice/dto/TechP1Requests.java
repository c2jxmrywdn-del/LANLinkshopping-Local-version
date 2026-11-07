package com.lanlink.shopping.module.techservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** P1 请求体：机构任务、履约评价、中试资源与预约。 */
public final class TechP1Requests {

    private TechP1Requests() {
    }

    public record OrgTaskCreate(Long orgId, Long projectId, Long assigneeUserId, String taskType,
                                String title, String scope, LocalDate dueDate) {
    }

    public record OrgTaskSubmit(String deliverableText) {
    }

    public record OrgTaskReview(String decision, String note) {
    }

    public record ReviewCreate(String targetType, Long targetId, Integer scoreResponse, Integer scoreProfessional,
                               Integer scoreDelivery, Integer scoreCommunication, Integer scoreResult,
                               String comment) {
    }

    public record PilotResourceUpsert(Long resourceId, String name, String orgName, String region,
                                      String resourceType, String capabilities, String equipment,
                                      String priceMode, BigDecimal price, String contact,
                                      Integer status, Integer sort) {
    }

    public record PilotBookingCreate(Long resourceId, Long projectId, String requirement,
                                     LocalDate scheduledDate) {
    }

    public record PilotBookingStatus(String status, String note) {
    }
}
