package com.lanlink.cloud.auth;

import com.lanlink.cloud.api.dto.UserDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

/**
 * 用户契约实现（UserApi 提供方）。阶段 1 使用内存演示数据，阶段 2 接入 MyBatis-Plus + t_user。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/{id}")
    public R<UserDTO> getById(@PathVariable Long id) {
        // 演示用户：2=采购方（阶段 2 替换为数据库查询）
        if (id == null || id <= 0) throw new BusinessException(400, "用户ID不合法");
        UserDTO u = new UserDTO();
        u.setUserId(id);
        u.setPhone("1390000000" + (id % 10));
        u.setNickname("采购方-" + id);
        u.setRoleId(1L);
        return R.ok(u);
    }

    /** 阶段 2 占位：登录（真实鉴权在阶段 2 以 JWT/Session 落地） */
    @PostMapping("/login")
    public R<UserDTO> login(@RequestBody LoginRequest req) {
        UserDTO u = new UserDTO();
        u.setUserId(2L);
        u.setPhone(req.phone());
        u.setNickname("采购方");
        u.setRoleId(1L);
        return R.ok("登录成功（骨架演示）", u);
    }

    public record LoginRequest(String phone, String password) {}
}
