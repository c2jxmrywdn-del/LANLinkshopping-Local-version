// 智能客服多模态附件：预签名 → 浏览器直传对象存储 → confirm → 状态轮询。
// 契约见后端 POST /support/media/presign，响应体为 { code: 0, data: {...} }，
// 与全局 axios 拦截器约定的 code === 200 不一致，故这里直接用 fetch 解析，避免被拦截器判为业务失败。
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')

const MB = 1024 * 1024

/** 一条消息最多附件数（前端软限制，后端仍会校验） */
export const MAX_MEDIA_COUNT = 5

/** 状态轮询间隔与上限（3s × 20 次） */
export const POLL_INTERVAL_MS = 3000
export const MAX_POLL_TIMES = 20

const EXT_MIME = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif',
  mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4', ogg: 'audio/ogg', webm: 'audio/webm',
  mp4: 'video/mp4', mov: 'video/quicktime',
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  txt: 'text/plain', csv: 'text/csv', md: 'text/markdown', zip: 'application/zip'
}

// 按扩展名优先匹配：部分桌面浏览器（尤其 Windows）上传文件时 File.type 为空或不准。
const KIND_RULES = [
  { kind: 'IMAGE', maxBytes: 10 * MB, maxLabel: '10MB', exts: ['jpg', 'jpeg', 'png', 'webp', 'gif'], mimePrefix: 'image/' },
  { kind: 'AUDIO', maxBytes: 10 * MB, maxLabel: '10MB', exts: ['mp3', 'wav', 'm4a', 'ogg', 'webm'], mimePrefix: 'audio/' },
  { kind: 'VIDEO', maxBytes: 20 * MB, maxLabel: '20MB', exts: ['mp4', 'mov'], mimePrefix: 'video/' },
  {
    kind: 'DOCUMENT',
    maxBytes: 20 * MB,
    maxLabel: '20MB',
    exts: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'csv', 'md', 'zip'],
    mimePrefix: ''
  }
]

export function extensionOf(fileName = '') {
  const name = String(fileName)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** 推断附件 kind；无法识别时返回空串 */
export function resolveKind(file) {
  if (!file) return ''
  const ext = extensionOf(file.name)
  const byExt = KIND_RULES.find(rule => rule.exts.includes(ext))
  if (byExt) return byExt.kind
  const mime = String(file.type || '').toLowerCase()
  if (!mime) return ''
  const byMime = KIND_RULES.find(rule => rule.mimePrefix && mime.startsWith(rule.mimePrefix))
  return byMime ? byMime.kind : ''
}

/** 上传时的 Content-Type 兜底：File.type 缺失时按扩展名补 */
export function contentTypeOf(file) {
  if (!file) return 'application/octet-stream'
  if (file.type) return file.type
  return EXT_MIME[extensionOf(file.name)] || 'application/octet-stream'
}

/** 前端软校验：类型 + 体积；不通过时给出可直接展示的中文原因 */
export function validateFile(file) {
  const kind = resolveKind(file)
  if (!kind) {
    return {
      ok: false,
      reason: `不支持的附件类型：「${file?.name || '未知文件'}」。仅支持图片、语音、视频与常见文档（pdf/doc/docx/xls/xlsx/ppt/pptx/txt/csv/md/zip）`
    }
  }
  const rule = KIND_RULES.find(item => item.kind === kind)
  if (Number(file.size || 0) > rule.maxBytes) {
    return { ok: false, reason: `「${file.name}」超过 ${rule.maxLabel} 上限，请压缩后重试` }
  }
  if (Number(file.size || 0) <= 0) {
    return { ok: false, reason: `「${file.name}」是空文件，无法上传` }
  }
  return { ok: true, kind }
}

async function requestJson(path, { method = 'GET', body } = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    payload = null
  }
  const code = payload && payload.code !== undefined ? Number(payload.code) : null
  // 兼容 code:0（本模块契约）与 code:200（平台通用约定）
  if (!res.ok || (code !== null && code !== 0 && code !== 200)) {
    const serverMsg = payload && (payload.message || payload.msg)
    if (!res.ok && res.status === 413) throw new Error('文件过大，请压缩后重试')
    throw new Error(serverMsg || `请求失败（HTTP ${res.status}）`)
  }
  if (!payload) return null
  return payload.data !== undefined ? payload.data : payload
}

/**
 * 预签名 action 规范化：
 * 强制转换为当前页面同源的 /support-media 目标地址（生产 Nginx 与本地 Vite 均按此路径反代到 MinIO 私有桶）。
 * 无论后端回显的是 localhost、127.0.0.1 还是其它 IP，均转为本页同源，彻底杜绝跨 Host 引起的 CORS 与 PNA 拦截。
 */
export function resolveActionUrl(action) {
  if (!action) return `${window.location.origin}/support-media`
  try {
    const url = new URL(String(action), window.location.origin)
    const pathname = url.pathname && url.pathname !== '/' ? url.pathname : '/support-media'
    return `${window.location.origin}${pathname}`
  } catch {
    return `${window.location.origin}/support-media`
  }
}

/**
 * 浏览器直传对象存储：必须用 XMLHttpRequest 才能拿到 upload.onprogress。
 * 顺序固定：先 append 预签名 fields（含 key、policy、x-amz 系列头与 Content-Type），最后 append("file", file)；
 * 不设置任何请求头（交给浏览器生成 multipart boundary），也不携带 Authorization。
 */
export function uploadToStorage({ action, fields, file, onProgress, timeout = 180000 }) {
  return new Promise((resolve, reject) => {
    const target = resolveActionUrl(action)
    if (!target) {
      reject(new Error('缺少上传地址'))
      return
    }
    const xhr = new XMLHttpRequest()
    xhr.open('POST', target, true)
    xhr.timeout = timeout
    xhr.upload.onprogress = (event) => {
      if (!onProgress || !event.lengthComputable || !event.total) return
      onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      // MinIO/S3 失败时返回 XML，尽量把 <Message> 提取成中文可读原因
      const text = String(xhr.responseText || '')
      const matched = text.match(/<Message>([^<]*)<\/Message>/i)
      reject(new Error(matched ? `存储服务拒绝：${matched[1]}` : `上传失败（HTTP ${xhr.status}）`))
    }
    xhr.onerror = (e) => {
      console.error('[supportMedia] 直传对象存储失败 target=', target, e)
      reject(new Error('网络连接中断或存储服务不可达，上传失败'))
    }
    xhr.ontimeout = () => reject(new Error('上传超时，请重试'))
    xhr.onabort = () => reject(new Error('上传已取消'))

    try {
      const form = new FormData()
      Object.entries(fields || {}).forEach(([key, value]) => {
        if (value === undefined || value === null) return
        form.append(key, String(value))
      })
      form.append('file', file)
      xhr.send(form)
    } catch (err) {
      reject(err)
    }
  })
}

export const supportMediaApi = {
  /** 申请直传凭证 */
  presign: (payload) => requestJson('/support/media/presign', { method: 'POST', body: payload }),
  /** 上传完成回执，后端据此进入解析流程 */
  confirm: (mediaNo, visitorToken) => requestJson(
    `/support/media/${encodeURIComponent(mediaNo)}/confirm`,
    { method: 'POST', body: { visitorToken } }
  ),
  /** 媒体识别状态：INIT|UPLOADED|PARSING|READY|FAILED|EXPIRED */
  status: (mediaNo, visitorToken) => requestJson(
    `/support/media/${encodeURIComponent(mediaNo)}?visitorToken=${encodeURIComponent(visitorToken || '')}`
  ),
  /** 移除或放弃媒体，释放配额并清理服务端记录 */
  discard: (mediaNo, visitorToken) => requestJson(
    `/support/media/${encodeURIComponent(mediaNo)}?visitorToken=${encodeURIComponent(visitorToken || '')}`,
    { method: 'DELETE' }
  ),
  uploadToStorage,
  validateFile,
  resolveKind,
  contentTypeOf
}
