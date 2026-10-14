# Agent Loadout 邮件代理接入

本文说明如何将 Agent Loadout 接入 LANLinkshopping 的验证码发送和营销邮件工作流。

## 1. Railway 环境变量

在 **Railway → 后端服务 → Variables** 中设置：

- `MAIL_PROVIDER=agent-loadout`
- `AGENT_LOADOUT_MCP_URL=https://agent-loadout.com/api/mcp`
- `AGENT_LOADOUT_API_KEY`：填写在 Agent Loadout 后台重新生成的 API Key；只放入部署变量，不提交到 Git。
- `AGENT_LOADOUT_INBOX_ID`：已授权 Agent 的发件箱 ID（当前连接曾返回 `inb_1k4jbtj1763b8e8sdkcc0brd0`，如有变化以 `list_inboxes` 的结果为准）。
- `MARKETING_UNSUBSCRIBE_SECRET`：至少 32 个字符的高熵随机密钥，专用于签名退订令牌；不要与 Agent Loadout API Key 共用。
- `LANLINK_PUBLIC_API_URL=https://lanlinkshopping-production.up.railway.app/api`（若绑定自定义域名，改为该域名的公开 API 基址）。
- `lanlink.verify-code.demo-mode=false`（生产必须关闭演示模式）。

设置变量后重新部署后端。Agent Loadout 发件人由配置的 inbox 决定；`MAIL_FROM` 不用于覆盖它。若 `MAIL_PROVIDER=auto`，代码会在 Agent Loadout 已完整配置时优先使用它，否则继续按现有 Resend/SMTP 方式选择。

**密钥说明：** API Key 曾被粘贴在聊天中，部署前应在 Agent Loadout 中撤销旧密钥并生成新密钥。不要把密钥写入 `.env.local`、源码、提交、日志或前端变量。

## 2. 验证码邮件

现有账号中心接口 `POST /api/user/email-code` 会继续生成 6 位验证码并由后端发送。Agent Loadout 通道不会将验证码返回给前端；验证码 5 分钟过期、成功校验后立即失效，并保留现有 60 秒冷却规则。

该接口当前服务于已登录用户的邮箱绑定/验证流程，不等于已经实现“登录注册全流程邮箱 OTP”。验证码存储仍是应用进程内存，生产多副本部署前应迁移到共享存储（例如 Redis），否则跨副本校验和重启恢复不可靠。

## 3. 营销邮件

新增管理员接口：

`POST /api/admin/marketing-email/send`

它要求已登录的平台运营账号，并通过 CSRF 令牌保护。先调用 `GET /api/user/csrf-token` 获取当前会话令牌，然后以请求头 `X-CSRF-TOKEN` 发送。

请求示例：

```json
{
  "recipients": ["subscribed-customer@example.com"],
  "subject": "LANLinkshopping 新供应商活动",
  "text": "查看本周的新供应商与采购机会。",
  "unsubscribeUrl": "https://lanlinkshopping-production.up.railway.app/api/marketing-email/unsubscribe",
  "idempotencyKey": "campaign-2026-10-10-v1",
  "consentConfirmed": true
}
```

限制与行为：

- 一次最多 20 位收件人，逐人单独发送，避免泄露收件人地址。
- 需要管理员明确确认本批收件人已订阅；后端还会核对收件人属于已登记账号，且账号设置中的 `notify.email=true` 与 `notify.groups.promotion=true`。未匹配到账号或未开启营销通知的地址会跳过，不会发送。
- 主题会加上“商业推广”标识。
- 每位收件人的幂等键独立生成；接口返回排队数与失败数，不返回邮箱地址。
- `unsubscribeUrl` 必须与 `LANLINK_PUBLIC_API_URL` 对应的系统退订地址完全一致。系统为每个已订阅收件人生成不含邮箱地址的 HMAC-SHA256 签名链接；打开链接只展示确认页，不会被邮件安全扫描器预取时误退订，用户点击确认后才关闭该账号的促销通知。验证码、订单和安全邮件不受影响。
- 服务端仍会核验邮箱属于已登记账号，并要求 `notify.email=true` 与 `notify.groups.promotion=true`；未匹配到账号或未开启营销通知的地址会跳过，不会发送。`consentConfirmed=true` 只是管理员对本批次合规性的再次确认，不会覆盖用户偏好。

## 4. 验证

部署后先只给自己发一封测试邮件，确认 Agent Loadout inbox 处于 `ready`、API Key 具有 `email:send` 权限，再测试 `/api/user/email-code`。不要用营销接口做大批量首发测试。CI 单元测试会覆盖 MCP 初始化/会话、单收件人发送、幂等键和营销同意校验。
