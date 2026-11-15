// 客服回答语音播报：把文本交给后端 /support/tts 合成 MP3，返回 base64 供浏览器直接播放。
// 响应为平台统一的 { code, message, data } 结构，code 兼容 0 与 200 两种约定。
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')

const TIMEOUT_MS = 30000

/**
 * 合成语音。
 * @param {string} text 待朗读文本（后端上限 500 字）
 * @param {{voice?: string, format?: string}} options format 仅支持 mp3
 * @returns {Promise<{audioBase64: string, format: string}>}
 */
export async function synthesize(text, { voice, format = 'mp3' } = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(`${API_BASE}/support/tts`, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, ...(voice ? { voice } : {}), format }),
      signal: controller.signal
    })
    const payload = await res.json().catch(() => null)
    const code = payload && payload.code !== undefined ? Number(payload.code) : null
    if (!res.ok || (code !== null && code !== 0 && code !== 200)) {
      throw new Error((payload && (payload.message || payload.msg)) || '语音合成暂不可用')
    }
    const data = payload && payload.data
    if (!data || !data.audioBase64) throw new Error('语音合成暂不可用')
    return {
      audioBase64: String(data.audioBase64),
      format: data.format ? String(data.format) : format
    }
  } finally {
    clearTimeout(timeoutId)
  }
}

export const supportTtsApi = { synthesize }
