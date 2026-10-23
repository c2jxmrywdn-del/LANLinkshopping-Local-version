import { ref } from 'vue'
import { locale } from '../i18n'

/**
 * 浏览器语音合成（Web Speech API）封装。
 * 不依赖外部 API Key，支持中英文；后续接入服务端神经 TTS 时可在此替换实现。
 */
export const ttsSpeaking = ref(false)
let currentUtterance = null

export function ttsSupported() {
  return typeof window !== 'undefined'
    && typeof window.speechSynthesis !== 'undefined'
    && typeof window.SpeechSynthesisUtterance !== 'undefined'
}

function pickVoice(lang) {
  if (!ttsSupported()) return null
  const voices = window.speechSynthesis.getVoices() || []
  if (!voices.length) return null
  const norm = String(lang || '').replace('_', '-').toLowerCase()
  const base = norm.split('-')[0]
  return voices.find(v => String(v.lang || '').replace('_', '-').toLowerCase() === norm)
    || voices.find(v => String(v.lang || '').toLowerCase().startsWith(base))
    || null
}

/**
 * 朗读文本。
 * @param {string} text 要朗读的文本
 * @param {{lang?: string, rate?: number, pitch?: number, onEnd?: () => void}} options
 * @returns {boolean} 是否成功发起朗读
 */
export function speakText(text, options = {}) {
  if (!ttsSupported()) return false
  const plain = String(text || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 2000)
  if (!plain) return false

  stopSpeaking()
  const utterance = new window.SpeechSynthesisUtterance(plain)
  utterance.lang = options.lang || (locale.value === 'en-US' ? 'en-US' : 'zh-CN')
  utterance.rate = options.rate ?? 0.95
  utterance.pitch = options.pitch ?? 1
  const voice = pickVoice(utterance.lang)
  if (voice) utterance.voice = voice

  const done = () => {
    ttsSpeaking.value = false
    currentUtterance = null
    if (typeof options.onEnd === 'function') options.onEnd()
  }
  utterance.onend = done
  utterance.onerror = done

  currentUtterance = utterance
  ttsSpeaking.value = true
  window.speechSynthesis.speak(utterance)
  return true
}

export function stopSpeaking() {
  if (!ttsSupported()) return
  try { window.speechSynthesis.cancel() } catch (e) { /* ignore */ }
  ttsSpeaking.value = false
  currentUtterance = null
}

/** 语音列表在部分浏览器异步加载，触发一次读取以尽早初始化。 */
if (ttsSupported()) {
  window.speechSynthesis.getVoices()
  window.speechSynthesis.addEventListener?.('voiceschanged', () => {
    window.speechSynthesis.getVoices()
  })
}
