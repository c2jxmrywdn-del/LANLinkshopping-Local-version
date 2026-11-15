# InviteLinkCard 邀请链接卡片

登录态下展示专属邀请链接与二维码，支持一键复制、保存二维码 PNG、重新生成、有效期展示与使用次数限制。组件自身不写死宽度，可放进任意宽度容器。

邀请身份由服务端判定，组件只负责展示徽标，**不提供身份选择控件**。

## 身份分型规则

| 生成方 | 生成位置 | `inviteType` | 被邀请人最终身份 |
|:---|:---|:---|:---|
| 普通用户 / 商户 / 平台运营 | 前台（个人中心、活动中心） | `buyer` | **买家**（注册即生效） |
| 平台运营 | 运营后台「商家入驻邀约」 | `merchant_apply` | **商户**（须提交入驻申请并审核通过） |

要点：

- `POST /api/auth/register` **恒为买家**；即使把 `merchant_apply` 码填进注册表单，也只建买家且不会绑定注册邀请关系。
- 商户身份只能由 `POST /api/merchant/apply`（入驻方式=邀约，`joinCode` 填该码）+ 审核通过后升级。
- 同一用户可同时拥有两条关系：注册邀请（`buyer`）与入驻邀约（`merchant_apply`），唯一键为 `(invitee_id, invite_type)`。

## 落地页

| 场景 | 链接 | 行为 |
|:---|:---|:---|
| 注册邀请 | `/register?invite=CODE` | `Register.vue` 读取 query，自动带入 `inviteCode` 并显示邀请提示 |
| 注册邀请 | `/login?invite=CODE` | `Login.vue` 自动切到注册标签页并透传 `inviteCode` |
| 入驻邀约 | `/merchant?invite=CODE` | `MerchantApply.vue` 自动切「邀约」并预填 `joinCode`；旧 `/merchant-apply` 由路由 alias 兼容 |

后端默认链接由 `lanlink.invite.register-url` / `lanlink.invite.merchant-apply-url` 配置，生产环境分别用 `LANLINK_INVITE_REGISTER_URL` / `LANLINK_INVITE_MERCHANT_APPLY_URL` 覆盖。

## Props

| Prop | 类型 | 默认值 | 说明 |
|:---|:---|:---|:---|
| `size` | `Number` | `180` | 二维码边长（px），变更后自动重绘 |
| `dark` | `String` | `'#13233A'` | 二维码深色模块颜色 |
| `light` | `String` | `'#FFFFFF'` | 二维码浅色底 |
| `autoLoad` | `Boolean` | `true` | 挂载后是否自动请求 `GET /invite/link` |
| `initialLink` | `Object` | `null` | 父级已拿到的链接数据；传入后不再请求 `/invite/link` |
| `showRotate` | `Boolean` | `true` | 是否显示组件内「重新生成」按钮；父级自管按钮时设为 `false` |
| `showCode` | `Boolean` | `false` | 是否展示邀请码明文；仅运营后台显式开启 |

## Events

| 事件 | 载荷 | 触发时机 |
|:---|:---|:---|
| `loaded` | `link` | 首次/手动加载成功 |
| `load-error` | `Error` | 加载失败（同时渲染内联重试入口） |
| `rotated` | `link` | 重新生成成功 |
| `rotate-error` | `Error` | 重新生成失败（toast 由请求拦截器弹出，卡片内另有持久文案） |
| `copy-success` | `String` | 复制成功，载荷为被复制的 URL |
| `copy-error` | `Error` | 剪贴板与 `execCommand` 回退均失败 |
| `qr-error` | `Error` | `QRCode.toCanvas` 抛错，已降级为文案提示 |
| `save-success` | `String` | 二维码保存成功，载荷为下载文件名 |
| `save-error` | `Error` | `canvas.toBlob` 失败或浏览器不支持 |

## Slots

| 名称 | 说明 |
|:---|:---|
| `title` | 卡片标题，默认「邀请好友」 |
| `extra` | 标题右侧操作区（如统计入口） |
| `footer` | 卡片底部附加内容（如邀请规则说明） |

## 基本用法

```vue
<template>
  <InviteLinkCard :size="200" @rotated="onRotated" />
</template>

<script setup>
import InviteLinkCard from '@/components/invite/InviteLinkCard.vue'

function onRotated(link) {
  console.log('new invite code:', link.code)
}
</script>
```

挂载后自动调用 `GET /api/invite/link`。父级需要控制时机时传 `:auto-load="false"`，再通过 `ref.reload()` 手动加载：

```vue
<InviteLinkCard ref="cardRef" :auto-load="false" />
```

```js
const cardRef = ref(null)
await cardRef.value.reload()
```

运营后台使用 `merchant_apply` 码时，由页面自行请求并传入：

```vue
<InviteLinkCard
  :auto-load="false"
  :initial-link="link"
  :show-rotate="false"
  show-code
/>
```

## 接口依赖

封装在 `src/api/invite.js`，复用 `src/api/request.js` 的 axios 实例（`baseURL=/api`、`withCredentials=true`，响应拦截器已解包 `R.data`）：

| 方法 | 接口 | 用途 |
|:---|:---|:---|
| `getInviteLink()` | `GET /invite/link` | 取当前用户邀请链接，无有效码时后端自动生成 |
| `rotateInviteLink(maxUses?)` | `POST /invite/link/rotate?maxUses=N` | 重新生成，旧 code 立即失效 |
| `getInviteStats()` | `GET /invite/stats` | 邀请统计（组件未内置 UI，供父级使用） |
| `issueMerchantApplyCode(maxUses?, rotate?)` | `POST /invite/admin/merchant-apply` | 仅运营：签发商家入驻邀约码 |

链接响应示例：

```json
{"code":"INV8F3A2C9D1B4E5F6","url":"http://localhost:5173/register?invite=INV8F3A2C9D1B4E5F6",
 "inviteType":"buyer","grantedRoleCode":"buyer",
 "expiresAt":"2026-12-14T10:00:00","expiresInSeconds":2592000,
 "maxUses":0,"usedCount":2,"remainingUses":-1,"channel":"link"}
```

`rotateInviteLink(maxUses)` 把 `maxUses` 放 **query**（后端是 `@RequestParam`，放 body 会 400）。传 `undefined`/空值时不带该参数；传 `0` 或负数时后端回落到 `lanlink.invite.max-uses` 配置默认值（默认 0 = 不限次数）。

写端点 `/invite/**` 已登记到 `src/api/csrfPolicy.js`，两个 POST 自动携带 `X-CSRF-TOKEN`；后端 `WebConfig` 的 `CsrfInterceptor` 保持同名路径登记。`/invite/link`、`/invite/link/rotate`、`/invite/admin/merchant-apply` 同时登记了限流策略。

失败 toast 统一由 `request.js` 拦截器按后端 `message` 弹出（并保留 401 跳登录）；组件只把错误文案落到卡片内并上抛 `rotate-error` / `load-error`。

## 次数限制语义

| 字段 | 取值 | 含义 |
|:---|:---|:---|
| `maxUses` | `0` | 不限次数（或后端配置默认值） |
| `maxUses` | `>0` | 该链接最多可被使用 N 次 |
| `remainingUses` | `-1` | 不限次数 |
| `remainingUses` | `0` 且 `maxUses>0` | 已用尽，链接不可再用 |
| `usedCount` | `>=0` | 已被使用的次数（绑定成功计数） |

展示规则：`maxUses=0` 显示「使用次数：不限次数」；否则显示「使用次数：已用 usedCount / maxUses，剩余 remainingUses」。用尽与过期同等处理——禁用复制与保存，并提示重新生成，但「重新生成」始终可用。

「重新生成」区提供次数上限输入（`0`~`10000` 整数，留空 = 不传参）。输入值提交前夹到该区间并取整；空值传 `undefined`，`0` 原样传 `0`。

> `N<=0` 的语义是「使用后端 `lanlink.invite.max-uses` 配置默认值」。默认配置为 `0`（不限）；若部署时改成非 0，则填 `0`/留空得到的是该配置值，而不是严格不限。

## 边界与降级

- **有效期**：优先解析 `expiresAt` 绝对时间；缺失时用 `expiresInSeconds` 换算。`expiresAt` 是后端 `LocalDateTime`（无时区），按浏览器本地时间解析；`expiresInSeconds=-1` 视为永久，`0` 视为已过期。
- **过期**：复制与保存禁用，展示「邀请链接已过期，请重新生成」；「重新生成」始终可用。每分钟刷新一次剩余天数。
- **次数用尽**：`maxUses>0 && remainingUses===0` 时与过期同权处理；若 `remainingUses` 缺失或为负（脏数据），按 `maxUses-usedCount` 兜底。
- **二维码失败**：`QRCode.toCanvas` 抛错时隐藏 canvas，显示「二维码生成失败，可复制链接分享」，复制能力不受影响。
- **复制降级**：优先 `navigator.clipboard.writeText`；不可用或拒绝时走离屏 `textarea + document.execCommand('copy')`，两者都失败才提示手动复制。
- **保存 PNG**：`canvas.toBlob('image/png')` → `URL.createObjectURL` → `<a download>`，文件名 `invite-<邀请码>.png`，下一轮事件循环 `revokeObjectURL`。
- **首帧性能**：`mounted` 之前不请求接口、不碰 canvas；`load()` 在 `nextTick` 后执行，二维码在 `loading` 落地、canvas 挂载后异步绘制。
- **加载失败**：卡片内保留重试入口；业务失败展示后端 `message`，网络/超时等 axios 原始错误回退中文文案。
- **并发绘制**：`size/dark/light` 变化会重绘，内部用递增序号丢弃过期结果，避免旧码覆盖新码。
- **布局**：≥768px 左右两栏（左信息右二维码），<768px 单列堆叠；次数输入在窄屏占满一行；根节点 `width:100%`。

## 测试

```bash
npx vitest run src/components/invite src/api/invite.test.js
npx vitest run src/views/Register.invite.test.js
npx vitest run src/views/merchantApplyInvite.test.js
```

测试 mock 了 `qrcode` 与 `src/api/invite.js`，不触网。仓库未安装 `@vue/test-utils`，用例沿用现有 `createApp` + render 函数手挂载方式。
