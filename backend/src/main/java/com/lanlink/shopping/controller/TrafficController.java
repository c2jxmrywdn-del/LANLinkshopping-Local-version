package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.TrafficService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/merchant/traffic")
public class TrafficController {
    private final TrafficService service;
    public TrafficController(TrafficService service){this.service=service;}
    @GetMapping("/overview") @RequirePerm("merchant:manage")
    public R<Map<String,Object>> overview(HttpServletRequest r){return R.ok(service.overview(UserContext.currentUserId(r)));}
    @GetMapping("/trend") @RequirePerm("merchant:manage")
    public R<List<Map<String,Object>>> trend(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.trend(UserContext.currentUserId(r),days));}
    @GetMapping("/channels") @RequirePerm("merchant:manage")
    public R<List<Map<String,Object>>> channels(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.channels(UserContext.currentUserId(r),days));}
    @GetMapping("/products") @RequirePerm("merchant:manage")
    public R<List<Map<String,Object>>> products(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.productRanking(UserContext.currentUserId(r),days));}
    @GetMapping("/sources") @RequirePerm("merchant:manage")
    public R<List<Map<String,Object>>> sources(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.sources(UserContext.currentUserId(r),days));}
    @GetMapping("/conversion") @RequirePerm("merchant:manage")
    public R<Map<String,Object>> conversion(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.conversion(UserContext.currentUserId(r),days));}
    @PostMapping("/events")
    public R<Void> track(@RequestBody Map<String,Object> event){ service.track(event); return R.ok(null); }

    @GetMapping("/diagnosis") @RequirePerm("merchant:manage")
    public R<List<Map<String,Object>>> diagnosis(@RequestParam(required=false)Integer days,HttpServletRequest r){return R.ok(service.diagnosis(UserContext.currentUserId(r),days));}
}