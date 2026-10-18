# 安全策略与公告 / Security Policy & Advisory

本仓库 **LANLinkshopping** 是一个聚合型 B2B 电商平台的**教学 / 毕业设计演示项目**
（Spring Boot 3 + Vue 3 + MySQL），并非面向公众的生产系统。我们欢迎并重视安全问题的
负责任披露，但请将响应预期设定为"尽力而为"，而非 7×24 服务等级。

---

## 📢 安全公告 / Security Advisories

### 公告 2026-10-15 · 仓库开源发布前敏感信息审计

本仓库在以 **GPL-3.0** 开源发布前，已完成一次全面的敏感信息与供应链安全审计：

| 审计项 | 状态 |
|--------|------|
| `.env`（数据库口令 / API Key） | ✅ 已被 `.gitignore` 排除，未入库 |
| `application-payment.yml`（支付私钥 / 证书） | ✅ 已被 `.gitignore` 排除，未入库 |
| `application-payment.yml.example`（模板） | ✅ 已脱敏（商户号 / 证书路径置空） |
| 证书 / 密钥类文件（`*.pem` `*.p12` `*.key` 等） | ✅ `.gitignore` 全量排除 |
| 前端依赖锁定 `frontend/package-lock.json` | ✅ 已提交（`npm ci` 可复现安装） |
| Maven Wrapper `maven-wrapper.properties` | ✅ 已提交（锁定构建工具版本） |
| GitHub Actions（CI / CodeQL / Dependabot） | ✅ 已配置 |

**⚠️ 使用者须知**：若你从早期版本克隆过本项目并配置过真实支付商户号、SMTP 授权码
或其他凭据，请自行轮换这些凭据后再使用。开源发布后的历史提交中不应包含真实凭据，
但出于纵深防御原则，仍建议定期轮换。

---

## 受支持的版本 / Supported Versions

项目以单一版本迭代，仅维护 `main` 分支。

| 版本 | 是否支持安全更新 |
| ---- | ---------------- |
| 1.0.x | ✅ 支持 |
| < 1.0 | ❌ 不再维护 |

## 报告漏洞 / Reporting a Vulnerability

请通过以下渠道之一报告安全问题，**不要在公开的 Issue / PR / 讨论区直接披露**
可被利用的漏洞细节、凭据或 PoC：

1. **首选**：GitHub 私有漏洞报告 —— 进入本仓库 **Security** 标签页
   → **Report a vulnerability**，提交私有报告。
2. **加密邮件**：若你希望以邮件披露并保护内容，请用本仓库公钥
   [`security-contact.asc`](./security-contact.asc) 加密后发送至
   `250803872+c2jxmrywdn-del@users.noreply.github.com`。
   使用前请核对公钥指纹：

   ```
   A78E 255D 092C ED59 92D8  7979 2C73 A7EC 9270 148D
   ```

   （导入并校验：`gpg --import security-contact.asc && gpg --fingerprint A78E255D092CED5992D879792C73A7EC9270148D`）

报告时如能包含以下信息，将帮助我们更快定位与修复：
受影响模块或接口、复现步骤、潜在影响，以及（可选）修复建议。

**响应预期**：本项目为学术演示，维护者会在业余时间尽力在合理时间内确认与修复；
无法承诺固定的响应或修复 SLA。对于已被确认的问题，修复后会通过提交记录、
Release 说明以及 GitHub Security Advisory 同步。

## 已知的安全设计（供判断影响范围参考）

- **认证与会话**：登录口令使用 BCrypt 哈希存储；支持 TOTP 两步验证；Session Cookie
  启用 `HttpOnly` / `SameSite` 保护；登录接口令牌桶限流。
- **数据保护**：个人敏感字段（身份证、银行卡、手机号、邮箱等）经 AES-256-GCM
  透明加密入库；客服会话记录自动脱敏 PII。
- **访问控制**：账号中心写操作受 CSRF 令牌校验保护；管理端接口经登录态 + 角色
  （RBAC 五级身份矩阵）双重鉴权；越权访问记录审计日志。
- **支付安全**：真实密钥与证书仅存于 `.gitignore` 排除的本地配置，绝不入库；
  异步通知做签名校验 + 金额比对 + 幂等结算；退款需运营角色鉴权；默认 mock 模式
  与真实渠道隔离，避免误触真实资金。
- **客服备用链路策略守卫**：知识问答强制走本地知识库，外部模型（DeepSeek / Mimosa）
  仅限寒暄润色，反代 / 绕过知识库 / 提示注入一律拦截并计数告警。
- **供应链安全**：第三方依赖漏洞通过 GitHub Dependabot 跟踪与修复；CI 含 CodeQL
  静态应用安全测试（SAST）；前端依赖使用 `package-lock.json` 锁定版本。

## 范围 / Scope

本策略仅覆盖本仓库内的源代码与演示配置。前端所依赖第三方库、以及运行宿主环境的问题，
请按对应上游项目的披露流程处理；若你认为是本项目集成方式导致的问题，欢迎按上文方式报告。

> **提示**：这是演示项目，请勿用于真实交易、真实用户数据或对外提供服务。
> 如需用于生产环境，请先完成独立的安全评估与合规审查。
