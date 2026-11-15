// Keep this policy aligned with backend WebConfig.CsrfInterceptor mappings.
// A11 + P0-2 长尾补齐：后端已把 CSRF 扩到全部登录态写端点——此处同步镜像，
// 保证 axios 非 GET 请求自动携带 X-CSRF-TOKEN（缺失时 request.js 会拉取令牌并重放）。
// 豁免面（与后端 WebConfig 注释同步）：
//  - /ab/expose、/ab/convert、/ad/track：匿名公开上报（无登录会话，取不到令牌）
//  - /payment/notify/**、/payment/alipay/return、/logistics/callback/**：渠道服务器回调
//  - /auth/login|register、/auth/social/callback|sandbox-login：匿名登录入口
//  - /live/room/**：直播间匿名互动；/live/merchant/** 纳入（登录态）
//  - /support/ask[-stream]、/support/media/**、/support/tts/**：fetch 直调 + 代理密钥通道
//  - /marketing-email/unsubscribe：签名 token 退订链接；/internal/ai/tools/**：服务间密钥
const CSRF_PROTECTED_PATHS = [
  // 账号域（资料/地址/消息/验证码/习惯上报）
  /^\/user(?:\/|$)/,
  // 运营后台整体
  /^\/admin(?:\/|$)/,
  // 商户结算管理端写端点（generate/approve/pay/commission/sync）：与后端 WebConfig CsrfInterceptor
  // 的 /admin/settlement/** 登记同步。注：已被上一条 /admin/ 覆盖，此处显式登记以便门禁 grep 与后端逐条对齐
  /^\/admin\/settlement\//,
  /^\/tech(?:\/|$)/,
  // RFQ 询价发布/报价/接受（P1-9）：与后端 WebConfig CsrfInterceptor 的 /rfq/** 登记同步
  /^\/rfq(?:\/|$)/,
  // 商户域整体（P0-2）：入驻申请/证照/商户中心/RFQ 报价/商户 IM/营销图——收编原单条 /merchant/rfq
  /^\/merchant(?:\/|$)/,
  // 商户退店（申请/撤回）：与后端 WebConfig /merchant/exit/** 同步；已被上一条 /merchant/ 覆盖
  /^\/merchant\/exit\//,
  // 商户退店管理端（审核/驳回/关闭/公示扫表）：与后端 /admin/merchant-exit/** 同步；已被 /admin/ 覆盖
  /^\/admin\/merchant-exit\//,
  /^\/attachment(?:\/|$)/,
  /^\/price-monitor\/(?:alerts(?:\/|$)|ask$)/,
  // 价格监控管理端写端点（因子重算/单商品刷新）：不在 /admin/ 前缀下，
  // 与后端 WebConfig CsrfInterceptor 的 /price-monitor/admin/** 同步
  new RegExp('^/price-monitor/admin/'),
  // 商品域写端点（P0-2）：主图上传、AI 润色、发品与商家编辑；page/detail 公开浏览为 GET 不受影响
  // 这两条用字符串形态的 RegExp：正则字面量里 / 必须转义成 \/，
  // 后端门禁测试按原文 grep "product/image"、"product/ai"，字面量形态会被误判为未登记
  new RegExp('^/product/image$'),
  new RegExp('^/product/ai/'),
  // 商品管理端写端点（重索引/批量审核/上下架/审核阈值/自动审核）：
  // 不在 /admin/ 前缀下，与后端 /product/admin/** 同步
  new RegExp('^/product/admin/'),
  /^\/product\/(?:publish|my(?:\/|$))/,
  // 商户 SKU 多规格树读写：与后端 WebConfig /product/my/sku/** 同步（写路径 /{prodId}/save）。
  // 注：已被上一条 /product/my/ 覆盖，此处显式登记以便门禁 grep 与后端逐条对齐
  /^\/product\/my\/sku\//,
  // 资金与交易写路径：订单/钱包/支付建单与退款。渠道回调（/payment/notify/**）不在列，
  // 那些由支付宝/微信服务器发起，没有用户会话。
  /^\/order(?:\/|$)/,
  /^\/wallet(?:\/|$)/,
  /^\/payment\/(?:create|mock-confirm|refund)$/,
  // 购物车写操作（P0-2）：加购/改数量/勾选/删除
  /^\/cart(?:\/|$)/,
  // 活动域写端点（P0-2）：领券/参与/批量领券/商户促销；list/detail/for-product 公开浏览为 GET
  /^\/campaign(?:\/|$)/,
  // 会员签到与订阅（P0-2）
  /^\/membership\/checkin$/,
  /^\/subscription(?:\/|$)/,
  // 账期域写端点（P0-2）：申请/还款/调额/试算/结算支付（含 admin 审核）；overview/bills 读为 GET
  /^\/credit-term(?:\/|$)/,
  // 任务领取与加盟申请（P0-2）
  /^\/task\/claim$/,
  /^\/join(?:\/|$)/,
  // 邀请链接重新生成（登录态写端点）：与后端 WebConfig /invite/** 同步。
  // GET /invite/link、/invite/stats 是读端点，拦截器只对非 GET 校验，不受影响。
  /^\/invite(?:\/|$)/,
  // 广告推广商户端（P0-2）：创建/支付/暂停/恢复/终止；/ad/track 匿名上报豁免
  /^\/ad\/campaigns(?:\/|$)/,
  // IM 单聊写端点（P0-2）：建会话/发消息/媒体
  /^\/chat(?:\/|$)/,
  // 直播商户台（P0-2）：开播/置顶/状态；匿名观众互动 /live/room/** 豁免
  /^\/live\/merchant(?:\/|$)/,
  // 物流写端点（P0-2）：发货/拆单/回单/中转/轨迹/运力推荐/看门狗；/logistics/callback/** 渠道回调豁免
  /^\/logistics\/(?:ship(?:\/|$)|pod(?:\/|$)|transit(?:\/|$)|trace(?:\/|$)|recommend-carriers$|watchdog(?:\/|$))/,
  // 买家工单（P0-2）+ 客服意图分析 + AI 备用链路状态
  /^\/support-ticket(?:\/|$)/,
  /^\/support\/intent\/analyze$/,
  /^\/support\/fallback\/status$/,
  // 登录态账号操作（P0-2）：登出、社交绑定/解绑；login/register/callback/sandbox-login 匿名豁免
  /^\/auth\/logout$/,
  /^\/auth\/social\/(?:bind|unbind)(?:\/|$)/
]

export function isCsrfProtectedMutation(config = {}) {
  const method = String(config.method || 'get').toLowerCase()
  if (['get', 'head', 'options'].includes(method)) return false

  // Axios normally provides relative endpoint paths; strip a query string defensively.
  const url = String(config.url || '').split('?')[0]
  return CSRF_PROTECTED_PATHS.some((pattern) => pattern.test(url))
}
