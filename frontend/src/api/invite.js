// 邀请链接 / 二维码接口封装。
// 统一走 src/api/request.js 的 axios 实例：baseURL 已含 /api，响应拦截器直接返回 R.data，
// 业务失败会 reject（message 为后端文案），调用方无需再解一层 code/data。
import request from './request'

/**
 * 取当前登录用户的专属邀请链接（无有效码时后端自动生成）。
 * 邀请类型由服务端按发起人角色判定（buyer/merchant），前端不可指定。
 * @returns {Promise<{
 *   code:string,
 *   url:string,
 *   inviteType:'buyer'|'merchant',
 *   grantedRoleCode:'buyer'|'merchant',
 *   expiresAt:string|null,
 *   expiresInSeconds:number,
 *   maxUses:number,
 *   usedCount:number,
 *   remainingUses:number,
 *   channel:string
 * }>}
 */
export function getInviteLink() {
  return request.get('/invite/link')
}

/**
 * 重新生成邀请链接（旧 code 立即失效）。
 * 失败提示由 request.js 拦截器统一展示后端 message（并保留 401 跳登录），组件不重复 toast。
 * @param {number} [maxUses] 本次生成的最大使用次数；0 或不传走后端配置默认（0 = 不限次数）。
 *   后端是 @RequestParam，必须放 query，不能放 body。
 * @returns {Promise<{
 *   code:string,
 *   url:string,
 *   inviteType:'buyer'|'merchant',
 *   grantedRoleCode:'buyer'|'merchant',
 *   expiresAt:string|null,
 *   expiresInSeconds:number,
 *   maxUses:number,
 *   usedCount:number,
 *   remainingUses:number,
 *   channel:string
 * }>}
 */
export function rotateInviteLink(maxUses) {
  // 注意：Number(null)/Number('') 都是 0，不能直接用来判断"未传"，必须先排除空值
  const hasValue = maxUses !== undefined && maxUses !== null && maxUses !== ''
  const n = hasValue ? Number(maxUses) : NaN
  // 未传/空值/非数字时不带该参数，交给后端按配置默认值处理
  const config = Number.isFinite(n) ? { params: { maxUses: n } } : undefined
  return request.post('/invite/link/rotate', null, config)
}

/**
 * 邀请统计：invited/bound/pending + inviteType/remainingUses + recent 最近绑定记录。
 * recent[] 每条含 inviteeId/inviteeNickname/inviteType/grantedRoleId/status/boundAt。
 * @returns {Promise<{
 *   invited:number,
 *   bound:number,
 *   pending:number,
 *   inviteType:'buyer'|'merchant',
 *   remainingUses:number,
 *   recent:Array<{
 *     inviteeId:number,
 *     inviteeNickname:string,
 *     inviteType:'buyer'|'merchant',
 *     grantedRoleId:number,
 *     status:string,
 *     boundAt:string
 *   }>
 * }>}
 */
export function getInviteStats() {
  return request.get('/invite/stats')
}

/**
 * 【仅平台运营】生成商家入驻邀约码。
 *
 * <p>与注册邀请不同：该码用于 `POST /merchant/apply` 的 joinCode（入驻方式=邀约），
 * 被邀请人仍需提交资质并经审核，审核通过后才成为商户；注册接口不会因此给出商户身份。
 *
 * <p>非运营调用后端返回 403（`code:403`），拦截器会提示"无权限"。
 *
 * @param {number} [maxUses] 使用次数上限；0 或不传走后端配置默认（0 = 不限次数）
 * @param {boolean} [rotate] true = 作废旧码并重新生成
 * @returns {Promise<{code:string,url:string,inviteType:'merchant_apply',scene:'admin_console',
 *   grantedRoleCode:'merchant',requiresMerchantReview:boolean,expiresAt:string|null,
 *   expiresInSeconds:number,maxUses:number,usedCount:number,remainingUses:number}>}
 */
export function issueMerchantApplyCode(maxUses, rotate = false) {
  const params = {}
  const hasValue = maxUses !== undefined && maxUses !== null && maxUses !== ''
  const n = hasValue ? Number(maxUses) : NaN
  if (Number.isFinite(n)) params.maxUses = n
  if (rotate) params.rotate = true
  return request.post('/invite/admin/merchant-apply', null, { params })
}

export const inviteApi = {
  getLink: getInviteLink,
  rotateLink: rotateInviteLink,
  getStats: getInviteStats,
  issueMerchantApplyCode
}

export default inviteApi
