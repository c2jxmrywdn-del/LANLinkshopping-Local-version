package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.service.TechPilotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 中试 / 概念验证预约前台（登录 + CSRF）；资源目录走公开端点。 */
@RestController
@RequestMapping("/tech/pilot")
public class TechPilotController {

    private final TechPilotService pilotService;

    public TechPilotController(TechPilotService pilotService) {
        this.pilotService = pilotService;
    }

    @PostMapping("/bookings")
    public R<TechP1Views.PilotBooking> createBooking(@RequestBody TechP1Requests.PilotBookingCreate body,
                                                     HttpServletRequest request) {
        return R.ok("预约已提交，等待机构确认", TechP1Views.ofPilotBooking(
                pilotService.createBooking(UserContext.currentUserId(request), body)));
    }

    @GetMapping("/bookings/my")
    public R<List<TechP1Views.PilotBooking>> myBookings(HttpServletRequest request) {
        return R.ok(pilotService.myBookings(UserContext.currentUserId(request)).stream()
                .map(TechP1Views::ofPilotBooking).toList());
    }
}
