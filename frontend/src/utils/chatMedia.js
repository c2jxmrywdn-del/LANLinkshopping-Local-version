/**
 * 聊天媒体（图片/文件/语音）的前端校验与展示工具。
 *
 * 白名单与体积口径对齐后端 ChatMediaService（前端预检只为给出即时中文提示，
 * 最终以后端魔数嗅探为准）：
 *  - image：JPG/JPEG/PNG/WEBP/GIF，≤8MB；
 *  - file ：PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/ZIP，≤20MB（TXT/CSV 无魔数可校验，收紧至 ≤2MB）；
 *  - voice：WEBM/WAV/MP3/M4A/OGG，≤10MB，时长 1~300 秒（时长由录音器负责，此处只校验体积）。
 */

/** 各 kind 的体积上限（字节）与中文文案标签 */
export const KIND_LIMITS = {
  image: { max: 8 * 1024 * 1024, label: '图片' },
  file: { max: 20 * 1024 * 1024, label: '文件' },
  voice: { max: 10 * 1024 * 1024, label: '语音' }
}

/** 纯文本（txt/csv）无魔数可校验，单独收紧到 2MB（与后端 MAX_TEXT 一致） */
const TEXT_LIMIT = 2 * 1024 * 1024

/**
 * 各 kind 的扩展名与 MIME 双重判断规则：
 * mimes 中以 "/" 结尾的按前缀匹配（如 image/、audio/），其余精确匹配。
 */
const KIND_RULES = {
  image: {
    exts: ['jpg', 'jpeg', 'png', 'webp', 'gif'],
    mimes: ['image/'],
    hint: '图片仅支持 JPG / PNG / WEBP / GIF 格式'
  },
  file: {
    exts: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'csv', 'zip'],
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
      'application/octet-stream'
    ],
    hint: '文件仅支持 PDF / DOC / DOCX / XLS / XLSX / PPT / PPTX / TXT / CSV / ZIP 格式'
  },
  voice: {
    exts: ['webm', 'wav', 'mp3', 'm4a', 'ogg'],
    mimes: ['audio/'],
    hint: '语音仅支持 WEBM / WAV / MP3 / M4A / OGG 格式'
  }
}

/** 按文件 MIME 前缀归类聊天媒体 kind：image/*→image，audio/*→voice，其余→file */
export function pickKind(file) {
  const type = file && file.type ? String(file.type).toLowerCase() : ''
  if (type.startsWith('image/')) return 'image'
  if (type.startsWith('audio/')) return 'voice'
  return 'file'
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
  return KIND_RULES[kind].mimes.some(m => (m.endsWith('/') ? mime.startsWith(m) : mime === m))
}

/**
 * 发送前校验聊天文件：扩展名 + MIME 双重判断、体积上限（txt/csv 收紧到 2MB）。
 * 返回中文错误文案；合法返回 null。
 */
export function validateChatFile(file, kind) {
  if (!file || typeof file.size !== 'number' || file.size <= 0) return '文件内容为空，请重新选择'
  const rules = KIND_RULES[kind]
  if (!rules) return '不支持的上传类型'
  const ext = extOf(file.name)
  if (!ext || !rules.exts.includes(ext)) return rules.hint
  if (!mimeAllowed(kind, file.type)) return rules.hint
  const limit = KIND_LIMITS[kind]
  const cap = kind === 'file' && (ext === 'txt' || ext === 'csv') ? TEXT_LIMIT : limit.max
  if (file.size > cap) {
    // 人类可读上限：formatBytes 保留一位小数，提示语里去掉末尾 ".0"（8.0MB → 8MB）
    return `${limit.label}「${file.name}」大小不能超过 ${formatBytes(cap).replace(/\.0(B|KB|MB)$/, '$1')}`
  }
  return null
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

/**
 * 媒体消息的会话摘要占位（与后端 placeholder 口径一致）：
 * image→[图片]、file→[文件] 名、voice→[语音] 12″；文本/缺省回退 content。
 */
export function mediaPlaceholder(msg) {
  if (!msg) return ''
  switch (msg.msgType) {
    case 'image':
      return '[图片]'
    case 'file':
      return `[文件] ${msg.mediaName || '未命名文件'}`
    case 'voice': {
      const secs = Number(msg.voiceSeconds)
      return Number.isFinite(secs) && secs > 0 ? `[语音] ${Math.round(secs)}″` : '[语音]'
    }
    default:
      return msg.content || ''
  }
}
