package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.LoginDTO;
import com.lanlink.shopping.dto.RegisterDTO;
import com.lanlink.shopping.entity.Role;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.RoleMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 用户与鉴权服务
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(RegisterDTO dto) {
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException("该手机号已注册");
        }
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getRoleCode, dto.getRoleCode()));
        if (role == null) {
            throw new BusinessException("角色不存在: " + dto.getRoleCode());
        }
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : "用户" + dto.getPhone().substring(7));
        user.setRoleId(role.getRoleId());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    public User login(LoginDTO dto) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, dto.getPhone()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }
        return user;
    }

    public Role findRole(Long roleId) {
        return roleMapper.selectById(roleId);
    }

    public User findById(Long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 修改密码：原密码校验 + 强规则（8-20 位，含大小写字母、数字、特殊字符）
     */
    public void changePassword(Long userId, String oldPw, String newPw) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        if (!passwordEncoder.matches(oldPw, user.getPassword())) throw new BusinessException("原密码错误");
        if (!newPw.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,20}$")) {
            throw new BusinessException("新密码需 8-20 位，包含大小写字母、数字和特殊字符");
        }
        if (newPw.equals(oldPw)) throw new BusinessException("新密码不能与原密码相同");
        user.setPassword(passwordEncoder.encode(newPw));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /** 更新昵称（个人资料合并视图的一部分） */
    public void updateNickname(Long userId, String nickname) {
        if (nickname == null || nickname.isBlank()) return;
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        String clean = nickname.trim();
        if (clean.length() > 32) throw new BusinessException("昵称不能超过 32 字");
        user.setNickname(clean);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 换绑手机号：新号唯一性校验 + 同步 t_user.phone（登录账号随之切换）
     */
    public void changePhone(Long userId, String newPhone) {
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, newPhone).ne(User::getUserId, userId)) > 0) {
            throw new BusinessException("该手机号已被占用");
        }
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setPhone(newPhone);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }
}
