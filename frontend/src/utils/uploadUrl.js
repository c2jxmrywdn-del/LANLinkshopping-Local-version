/**
 * 上传文件 URL 规范化工具。
 *
 * 后端 WebConfig 把 {user.dir}/uploads/** 映射到 /api/uploads/**（context-path=/api），
 * 上传接口返回的规范形态是 "/api/uploads/..."。历史种子/演示数据里出现过缺 /api 前缀的
 * "/uploads/..."，浏览器按前端源解析会 404（vite/nginx 只代理 /api），导致后台"查看原件"
 * 破图。渲染前统一在数据入口做防御性归一，杜绝旧格式再次致坏。
 */
export function normalizeUploadUrl(u) {
  // 兼容后端未来直接返回数组的形态，同时保持纯函数语义（返回新数组）
  if (Array.isArray(u)) return u.map(normalizeUploadUrl)
  if (typeof u !== 'string' || !u) return u
  // 兼容纯 URL、JSON 字符串以及 JSON 中被转义的 \/uploads\/ 形态；
  // 已带 /api 前缀、外域 CDN 绝对地址、缺少前导斜杠的相对路径均保持原样。
  return u
    .replace(/(^|["',\s])\/uploads\//g, '$1/api/uploads/')
    .replace(/(^|["',\s])\\\/uploads\\\//g, '$1\\/api\\/uploads\\/')
}

/** 归一商户证照 URL 字段（营业执照/特殊经营许可证/完税凭证），返回新对象，不修改入参。
 *  兼容两种数据形态：商户对象本身（列表行），或 { merchant: {...} } 包装（adminDetail 响应）。 */
export function fixMerchantUploadUrls(m) {
  // 数组不是商户对象；原样返回，避免展开后变成 { 0: ..., 1: ... } 破坏结构
  if (!m || typeof m !== 'object' || Array.isArray(m)) return m
  const fixed = fixFields(m)
  if (m.merchant && typeof m.merchant === 'object' && !Array.isArray(m.merchant)) {
    return { ...fixed, merchant: fixFields(m.merchant) }
  }
  return fixed
}

function fixFields(m) {
  const next = { ...m }
  // 只归一实际存在的字段，避免给列表行凭空补出 undefined 属性
  if ('licenseUrl' in m) next.licenseUrl = normalizeUploadUrl(m.licenseUrl)
  if ('specialPermitUrl' in m) next.specialPermitUrl = normalizeUploadUrl(m.specialPermitUrl)
  if ('taxProofUrls' in m) next.taxProofUrls = normalizeUploadUrl(m.taxProofUrls)
  return next
}
