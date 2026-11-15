package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
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

    /** 自助注册只允许采购方；商户角色必须走入驻审核（MerchantService），管理员由后台分配。 */
    private static final String SELF_REGISTER_ROLE = "buyer";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(RegisterDTO dto) {
        return register(dto, null);
    }

    /**
     * 注册并指定角色。
     *
     * <p>{@code roleId} 为 null 时走自助注册默认角色（buyer）。非空时只允许
     * {@code buyer}/{@code merchant}——调用方（AuthController）已按邀请码校验过身份，
     * 这里再做一次白名单兜底，避免任何路径把 admin 写进新用户。
     *
     * @throws BusinessException 角色不在白名单内
     */
    public User register(RegisterDTO dto, Long roleId) {
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException("该手机号已注册");
        }
        // 不信任客户端传入的 roleCode：否则任何人可直接注册为 admin。
        // 商户角色由入驻申请通过后由 MerchantService 升级，注册阶段一律 buyer。
        String targetCode = SELF_REGISTER_ROLE;
        if (roleId != null) {
            if (roleId.equals(UserRoles.MERCHANT_ROLE_ID)) {
                targetCode = "merchant";
            } else if (!roleId.equals(UserRoles.BUYER_ROLE_ID)) {
                // admin 及其它任何角色一律拒绝：身份只能来自受控的邀请/入驻审核
                throw new BusinessException("不支持通过邀请注册该身份");
            }
        }
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getRoleCode, targetCode));
        if (role == null) {
            throw new BusinessException("系统角色未初始化，请联系管理员");
        }
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
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

    public User findByPhone(String phone) {
        if (!StringUtils.hasText(phone)) return null;
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone.trim()));
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
     * 忘记密码重置（匿名链路，调用方必须先校验短信验证码）。
     *
     * <p>与 {@link #changePassword} 的区别：不校验原密码，但强制同一套强度规则。
     * 手机号不存在时抛业务异常由上层统一转换为"账号不存在"，避免在这里泄露存在性。
     */
    public void resetPasswordByPhone(String phone, String newPw) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
        if (user == null) throw new BusinessException("账号不存在或未绑定手机号");
        if (newPw == null || !newPw.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,20}$")) {
            throw new BusinessException("新密码需 8-20 位，包含大小写字母、数字和特殊字符");
        }
        if (passwordEncoder.matches(newPw, user.getPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPw));
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
