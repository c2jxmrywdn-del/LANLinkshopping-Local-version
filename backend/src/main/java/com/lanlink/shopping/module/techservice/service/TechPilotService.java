package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.entity.TechPilotBooking;
import com.lanlink.shopping.module.techservice.entity.TechPilotResource;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechPilotBookingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPilotResourceMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 概念验证 / 中试资源库与预约（P1）。
 *
 * <p>平台只做资源展示与预约撮合，验证/中试由机构线下实施并出具结果；
 * 预约不构成服务承诺，费用与周期以机构报价与双方协议为准。
 */
@Service
public class TechPilotService {

    private static final Set<String> RESOURCE_TYPES = Set.of("PILOT", "VERIFICATION", "LAB");
    private static final Set<String> PRICE_MODES = Set.of("QUOTE", "FIXED", "NEGOTIABLE", "FREE");
    private static final Set<String> BOOKING_STATUSES = Set.of("APPLIED", "ACCEPTED", "COMPLETED", "REJECTED");

    private final TechPilotResourceMapper resourceMapper;
    private final TechPilotBookingMapper bookingMapper;
    private final TechProjectMapper projectMapper;

    public TechPilotService(TechPilotResourceMapper resourceMapper,
                            TechPilotBookingMapper bookingMapper,
                            TechProjectMapper projectMapper) {
        this.resourceMapper = resourceMapper;
        this.bookingMapper = bookingMapper;
        this.projectMapper = projectMapper;
    }

    /** 公开资源列表：仅上架资源，按运营排序 */
    public List<TechPilotResource> publicResources(String resourceType, String region) {
        String type = text(resourceType);
        String area = text(region);
        return resourceMapper.selectList(Wrappers.<TechPilotResource>lambdaQuery()
                .eq(TechPilotResource::getStatus, 1)
                .eq(type != null, TechPilotResource::getResourceType, type)
                .eq(area != null, TechPilotResource::getRegion, area)
                .orderByAsc(TechPilotResource::getSort)
                .orderByDesc(TechPilotResource::getResourceId)
                .last("LIMIT 50"));
    }

    public List<TechPilotResource> adminResources() {
        return resourceMapper.selectList(Wrappers.<TechPilotResource>lambdaQuery()
                .orderByAsc(TechPilotResource::getSort)
                .orderByDesc(TechPilotResource::getResourceId));
    }

    @Transactional
    public TechPilotResource upsertResource(TechP1Requests.PilotResourceUpsert req) {
        if (req == null) {
            throw new BusinessException(400, "请填写资源信息");
        }
        String name = text(req.name());
        String orgName = text(req.orgName());
        if (name == null || orgName == null) {
            throw new BusinessException(400, "资源名称与所属机构不能为空");
        }
        String resourceType = text(req.resourceType());
        resourceType = resourceType == null ? "PILOT" : resourceType.toUpperCase();
        if (!RESOURCE_TYPES.contains(resourceType)) {
            throw new BusinessException(400, "资源类型只能是 PILOT / VERIFICATION / LAB");
        }
        String priceMode = text(req.priceMode());
        priceMode = priceMode == null ? "QUOTE" : priceMode.toUpperCase();
        if (!PRICE_MODES.contains(priceMode)) {
            throw new BusinessException(400, "计价方式只能是 QUOTE / FIXED / NEGOTIABLE / FREE");
        }
        BigDecimal price = req.price();
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "价格不能为负数");
        }

        TechPilotResource resource;
        if (req.resourceId() != null) {
            resource = resourceMapper.selectById(req.resourceId());
            if (resource == null) {
                throw new BusinessException(404, "中试资源不存在");
            }
        } else {
            resource = new TechPilotResource();
            resource.setStatus(1);
            resource.setSort(0);
        }
        resource.setName(name.length() > 120 ? name.substring(0, 120) : name);
        resource.setOrgName(orgName.length() > 120 ? orgName.substring(0, 120) : orgName);
        resource.setRegion(text(req.region()));
        resource.setResourceType(resourceType);
        resource.setCapabilities(text(req.capabilities()));
        resource.setEquipment(text(req.equipment()));
        resource.setPriceMode(priceMode);
        resource.setPrice(price);
        resource.setContact(text(req.contact()));
        if (req.status() != null) resource.setStatus(req.status() == 0 ? 0 : 1);
        if (req.sort() != null) resource.setSort(req.sort());
        if (resource.getResourceId() == null) {
            resourceMapper.insert(resource);
        } else {
            resourceMapper.updateById(resource);
        }
        return resource;
    }

    @Transactional
    public TechPilotResource setResourceStatus(Long resourceId, Integer status) {
        TechPilotResource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(404, "中试资源不存在");
        }
        resource.setStatus(Integer.valueOf(0).equals(status) ? 0 : 1);
        resourceMapper.updateById(resource);
        return resource;
    }

    @Transactional
    public TechPilotBooking createBooking(Long userId, TechP1Requests.PilotBookingCreate req) {
        if (req == null || req.resourceId() == null) {
            throw new BusinessException(400, "请选择中试资源");
        }
        TechPilotResource resource = resourceMapper.selectById(req.resourceId());
        if (resource == null || !Integer.valueOf(1).equals(resource.getStatus())) {
            throw new BusinessException(404, "中试资源不存在或已下架");
        }
        String requirement = text(req.requirement());
        if (requirement == null) {
            throw new BusinessException(400, "请填写验证需求");
        }
        if (requirement.length() > 1000) {
            throw new BusinessException(400, "验证需求不能超过 1000 字");
        }
        if (req.scheduledDate() != null && req.scheduledDate().isBefore(LocalDate.now())) {
            throw new BusinessException(400, "预约日期不能早于今天");
        }
        Long projectId = req.projectId();
        if (projectId != null) {
            TechProject project = projectMapper.selectById(projectId);
            if (project == null) {
                throw new BusinessException(404, "陪跑项目不存在");
            }
            if (!Objects.equals(project.getUserId(), userId)) {
                throw new BusinessException(403, "无权关联该陪跑项目");
            }
        }

        TechPilotBooking booking = new TechPilotBooking();
        booking.setBookingNo(TechNoGenerator.next("PB"));
        booking.setResourceId(resource.getResourceId());
        booking.setUserId(userId);
        booking.setProjectId(projectId);
        booking.setRequirement(requirement);
        booking.setScheduledDate(req.scheduledDate());
        booking.setStatus("APPLIED");
        bookingMapper.insert(booking);
        booking.setResourceName(resource.getName());
        return booking;
    }

    public List<TechPilotBooking> myBookings(Long userId) {
        List<TechPilotBooking> list = bookingMapper.selectList(Wrappers.<TechPilotBooking>lambdaQuery()
                .eq(TechPilotBooking::getUserId, userId)
                .orderByDesc(TechPilotBooking::getCreateTime));
        enrich(list);
        return list;
    }

    public List<TechPilotBooking> adminBookings(String status) {
        String st = text(status);
        List<TechPilotBooking> list = bookingMapper.selectList(Wrappers.<TechPilotBooking>lambdaQuery()
                .eq(st != null, TechPilotBooking::getStatus, st)
                .orderByDesc(TechPilotBooking::getCreateTime)
                .last("LIMIT 200"));
        enrich(list);
        return list;
    }

    @Transactional
    public TechPilotBooking updateBookingStatus(String bookingNo, TechP1Requests.PilotBookingStatus req) {
        TechPilotBooking booking = bookingMapper.selectOne(Wrappers.<TechPilotBooking>lambdaQuery()
                .eq(TechPilotBooking::getBookingNo, bookingNo).last("LIMIT 1"));
        if (booking == null) {
            throw new BusinessException(404, "预约不存在");
        }
        String status = text(req == null ? null : req.status());
        status = status == null ? "" : status.toUpperCase();
        if (!BOOKING_STATUSES.contains(status)) {
            throw new BusinessException(400, "不支持的预约状态");
        }
        String note = text(req.note());
        if (note != null && note.length() > 500) {
            throw new BusinessException(400, "处理意见不能超过 500 字");
        }
        booking.setStatus(status);
        booking.setAdminNote(note);
        bookingMapper.updateById(booking);
        enrich(List.of(booking));
        return booking;
    }

    private void enrich(List<TechPilotBooking> bookings) {
        Collection<Long> ids = bookings.stream().map(TechPilotBooking::getResourceId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, TechPilotResource> resources = resourceMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(TechPilotResource::getResourceId, Function.identity(), (a, b) -> a));
        for (TechPilotBooking booking : bookings) {
            TechPilotResource resource = resources.get(booking.getResourceId());
            booking.setResourceName(resource == null ? "资源 #" + booking.getResourceId() : resource.getName());
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
