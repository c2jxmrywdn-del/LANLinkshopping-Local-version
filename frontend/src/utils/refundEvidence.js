/**
 * 退款凭证证据（image/video/file）的前端校验、kind 判定与序列化工具。
 *
 * 白名单与体积口径对齐后端 ChatMediaService（前端预检只为给出即时中文提示，
 * 最终以后端魔数嗅探与 RefundEvidenceValidator 权威校验为准；规则常量读自
 * validationRules.js，与后端 ValidationRules 契约同源）：
 *  - image：JPG/JPEG/PNG/WEBP/GIF，≤8MB；
 *  - video：MP4/MOV，≤20MB（受 multipart 全局 25MB 约束）；
 *  - file ：PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/ZIP，≤20MB（TXT/CSV 收紧至 ≤2MB；
 *           Word/压缩包等 zip 容器后端落盘为 .bin，审核端可下载查看）。
 */
import {
  REFUND_EVIDENCE_MAX_COUNT,
  REFUND_EVIDENCE_NAME_MAX,
  REFUND_EVIDENCE_URL_PREFIX,
  ALLOWED_REFUND_EVIDENCE_TYPES,
} from './validationRules'

/** 各 kind 的体积上限（字节）与中文文案标签（后端 ChatMediaService 同值） */
export const EVIDENCE_KIND_LIMITS = {
  image: { max: 8 * 1024 * 1024, label: '图片' },
  video: { max: 20 * 1024 * 1024, label: '视频' },
  file: { max: 20 * 1024 * 1024, label: '文件' },
}

/** 纯文本（txt/csv）无魔数可校验，单独收紧到 2MB（与后端 MAX_TEXT 一致） */
const TEXT_LIMIT = 2 * 1024 * 1024

/**
 * 各 kind 的扩展名与 MIME 双重判断规则（供上传前预检）：
 * mimes 中以 "/" 结尾的按前缀匹配，其余精确匹配。
 */
const EVIDENCE_KIND_RULES = {
  image: {
    exts: ['jpg', 'jpeg', 'png', 'webp', 'gif'],
    mimes: ['image/'],
    hint: '图片仅支持 JPG / PNG / WEBP / GIF 格式',
  },
  video: {
    exts: ['mp4', 'mov'],
    mimes: ['video/mp4', 'video/quicktime'],
    hint: '视频仅支持 MP4 / MOV 格式',
  },
  file: {
    exts: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'zip', 'txt', 'csv'],
    mimes: [
      'application/pdf',
      'application/msword',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'application/vnd.ms-excel',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'application/vnd.ms-powerpoint',
      'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      'application/zip',
      'application/x-zip-compressed',
      'text/plain',
      'text/csv',
      'application/octet-stream',
    ],
    hint: '文件仅支持 PDF / DOC / DOCX / XLS / XLSX / PPT / PPTX / ZIP / TXT / CSV 格式',
  },
}

/** 小写扩展名（不含点）；无扩展名返回空串 */
function extOf(name) {
  const s = String(name || '').toLowerCase()
  const i = s.lastIndexOf('.')
  return i >= 0 ? s.slice(i + 1) : ''
}

/** MIME 是否在 kind 白名单内；浏览器给不出 MIME（空串）时仅按扩展名判断 */
function mimeAllowed(kind, rawType) {
  const mime = String(rawType || '').split(';')[0].trim().toLowerCase()
  if (!mime) return true
  return EVIDENCE_KIND_RULES[kind].mimes.some(m => (m.endsWith('/') ? mime.startsWith(m) : mime === m))
}

/**
 * 按扩展名/MIME 判定证据 kind：image → video → file 依次匹配。
 * 不在白名单返回 null（调用方提示并拒绝入列）。
 */
export function pickEvidenceKind(file) {
  if (!file) return null
  const ext = extOf(file.name)
  const type = String(file.type || '').split(';')[0].trim().toLowerCase()
  for (const kind of ALLOWED_REFUND_EVIDENCE_TYPES) {
    const rules = EVIDENCE_KIND_RULES[kind]
    const extHit = ext && rules.exts.includes(ext)
    const mimeHit = !type || rules.mimes.some(m => (m.endsWith('/') ? type.startsWith(m) : type === m))
    if (extHit && mimeHit) return kind
  }
  return null
}

/**
 * 上传前校验证据文件：kind 白名单 + 扩展名/MIME + 体积上限（txt/csv 收紧到 2MB）。
 * 返回中文错误文案；合法返回 null。
 */
export function validateEvidenceFile(file) {
  if (!file || typeof file.size !== 'number' || file.size <= 0) return '文件内容为空，请重新选择'
  if (file.name && file.name.length > REFUND_EVIDENCE_NAME_MAX) {
    return `文件名「${file.name.slice(0, 32)}…」超出 ${REFUND_EVIDENCE_NAME_MAX} 字上限`
  }
  const kind = pickEvidenceKind(file)
  if (!kind) return '不支持的证据格式（仅图片 / MP4·MOV 视频 / PDF·文档·压缩包文件）'
  const rules = EVIDENCE_KIND_RULES[kind]
  const ext = extOf(file.name)
  if (!ext || !rules.exts.includes(ext)) return rules.hint
  if (!mimeAllowed(kind, file.type)) return rules.hint
  const cap = kind === 'file' && (ext === 'txt' || ext === 'csv') ? TEXT_LIMIT : EVIDENCE_KIND_LIMITS[kind].max
  if (file.size > cap) {
    return `${EVIDENCE_KIND_LIMITS[kind].label}「${file.name}」大小不能超过 ${formatBytes(cap).replace(/\.0(B|KB|MB)$/, '$1')}`
  }
  return null
}

/** 上传成功项 → 证据元素 {type,url,name}（多余字段不进契约） */
export function toEvidenceItem(kind, url, name) {
  return { type: kind, url: String(url || ''), name: String(name || '').slice(0, REFUND_EVIDENCE_NAME_MAX) }
}

/** 已就绪证据列表 → JSON 字符串（与后端传输/落库同构） */
export function serializeEvidences(list) {
  const items = (Array.isArray(list) ? list : [])
    .filter(it => it && it.url && ALLOWED_REFUND_EVIDENCE_TYPES.includes(it.type))
    .map(it => ({ type: it.type, url: it.url, name: it.name }))
  return JSON.stringify(items)
}

/**
 * 容错解析证据 JSON 字符串（审核端展示用）：坏数据/非法 type/缺 URL 一律降级剔除，
 * 保证渲染端拿到纯数组而不抛错。数量超过上限时截断到 REFUND_EVIDENCE_MAX_COUNT。
 */
export function parseEvidences(raw) {
  let arr
  try {
    arr = typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch (e) {
    return []
  }
  if (!Array.isArray(arr)) return []
  return arr
    .filter(it => it && typeof it === 'object'
      && ALLOWED_REFUND_EVIDENCE_TYPES.includes(it.type)
      && typeof it.url === 'string' && it.url.startsWith(REFUND_EVIDENCE_URL_PREFIX))
    .slice(0, REFUND_EVIDENCE_MAX_COUNT)
    .map(it => ({ type: it.type, url: it.url, name: String(it.name || '') }))
}

/** URL 文件名首段（落盘名 {userId}_{ts}.{ext} 的 userId 段）→ 数字或 null */
export function ownerUserIdOf(url) {
  const name = String(url || '').split('?')[0].split('/').pop() || ''
  const seg = name.split('_')[0]
  const n = Number(seg)
  return Number.isInteger(n) && n > 0 ? n : null
}

/** 字节数 → B/KB/MB（一位小数）；null/undefined/非法入参返回空串 */
export function formatBytes(n) {
  if (n === null || n === undefined) return ''
  const size = Number(n)
  if (!Number.isFinite(size) || size < 0) return ''
  if (size < 1024) return `${Math.floor(size)}B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`
  return `${(size / (1024 * 1024)).toFixed(1)}MB`
}
