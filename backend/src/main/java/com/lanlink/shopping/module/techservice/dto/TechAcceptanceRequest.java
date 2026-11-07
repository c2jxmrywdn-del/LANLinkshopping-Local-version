package com.lanlink.shopping.module.techservice.dto;

/** 项目验收：result=PASS/REJECT；score 1-5 为满意度（不对外评级） */
public record TechAcceptanceRequest(String result, Integer score, String note) {
}
