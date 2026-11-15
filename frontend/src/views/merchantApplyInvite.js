/**
 * 商家入驻邀约预填：把运营后台生成的 merchant_apply 码带进入驻表单。
 *
 * <p>纯函数，不读写路由或表单状态，便于单测。query 值可能是字符串或数组
 * （同名参数重复出现时 Vue Router 返回数组），数组取首个非空值。
 */
export function readInvitePrefill(query) {
  const raw = query && query.invite
  const candidates = Array.isArray(raw) ? raw : [raw]
  for (const value of candidates) {
    if (typeof value !== 'string') continue
    const code = value.trim().toUpperCase()
    if (code) return { joinType: '邀约', joinCode: code }
  }
  return null
}
