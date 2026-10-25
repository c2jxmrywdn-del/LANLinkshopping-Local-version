package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.JoinApplyDTO;
import com.lanlink.shopping.dto.JoinReviewDTO;
import com.lanlink.shopping.entity.JoinApplication;
import com.lanlink.shopping.service.JoinApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * “加入我们”接口：用户提交/查询进度 + 运营审核（/join/admin/** 由 AuthInterceptor 做 admin 角色鉴权）。
 */
@RestController
@RequestMapping("/join")
public class JoinApplicationController {

    private final JoinApplicationService service;

    public JoinApplicationController(JoinApplicationService service) {
        this.service = service;
    }

    /** 提交求职/合作申请（需登录） */
    @PostMapping("/apply")
    public R<JoinApplication> apply(@Valid @RequestBody JoinApplyDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("申请已提交，请耐心等待审核", service.apply(userId, dto));
    }

    /** 我的申请（需登录） */
    @GetMapping("/my")
    public R<List<JoinApplication>> my(HttpServletRequest request) {
        return R.ok(service.myList(UserContext.currentUserId(request)));
    }

    /** 运营端：申请列表（按类型/状态筛选） */
    @GetMapping("/admin/list")
    public R<List<JoinApplication>> adminList(@RequestParam(required = false) String applyType,
                                              @RequestParam(required = false) String status) {
        return R.ok(service.adminList(applyType, status));
    }

    /** 运营端：审核 */
    @PostMapping("/admin/review/{id}")
    public R<JoinApplication> review(@PathVariable Long id, @Valid @RequestBody JoinReviewDTO dto,
                                     HttpServletRequest request) {
        Long adminUserId = UserContext.currentUserId(request);
        return R.ok(service.review(adminUserId, id, Boolean.TRUE.equals(dto.getApproved()), dto.getRemark()));
    }
}
