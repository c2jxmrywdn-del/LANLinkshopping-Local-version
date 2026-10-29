import { getCurrentInstance, onBeforeUnmount, ref } from 'vue'

/** 后端语音时长上限（秒）：达到后自动停止录制，避免超出 voiceSeconds 1~300 校验 */
const MAX_SECONDS = 300

/**
 * 语音录音组合式函数：MediaRecorder 封装。
 *
 * - start()：申请麦克风权限并开始录音；环境不支持（如 jsdom/老浏览器）或权限被拒时，
 *   通过 onError 回调中文文案并抛出 Error，调用方 catch 后无需重复提示；
 * - stop()：返回 Promise<{ blob, seconds }>，未在录音/已停止时返回 null；
 * - cancel()：丢弃录音并释放麦克风；挂起的 stop() 会 resolve(null)；
 * - 组件卸载时自动 cancel 释放麦克风（在 setup 上下文中调用时注册）。
 *
 * MIME 探测优先 'audio/webm;codecs=opus'，回落 'audio/mp4'（Safari/iOS），
 * 都不支持时不指定 mimeType 交由浏览器自选。
 */
export function useVoiceRecorder({ onError } = {}) {
  const recording = ref(false)
  const seconds = ref(0)

  let recorder = null
  let stream = null
  let timer = null
  let chunks = []
  let cancelled = false
  let pendingStop = null

  function supported() {
    return typeof window !== 'undefined'
      && typeof window.MediaRecorder !== 'undefined'
      && typeof navigator !== 'undefined'
      && !!navigator.mediaDevices?.getUserMedia
  }

  function pickMime() {
    const MR = window.MediaRecorder
    if (MR && typeof MR.isTypeSupported === 'function') {
      if (MR.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
      if (MR.isTypeSupported('audio/mp4')) return 'audio/mp4'
    }
    return ''
  }

  /** 停表并释放麦克风轨道（结束/取消都会走这里，幂等） */
  function cleanup() {
    if (timer) { clearInterval(timer); timer = null }
    if (stream) {
      stream.getTracks().forEach(t => { try { t.stop() } catch { /* 轨道已结束 */ } })
      stream = null
    }
    recorder = null
    chunks = []
    recording.value = false
  }

  /** 统一失败出口：清理资源 → 中文错误回调 → 抛错让调用方 catch */
  function fail(msg) {
    cleanup()
    if (onError) onError(msg)
    return Promise.reject(new Error(msg))
  }

  async function start() {
    if (recording.value) return true
    if (!supported()) return fail('当前浏览器不支持录音，请改用 Chrome / Edge 等桌面浏览器')
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      return fail('无法访问麦克风：请在浏览器设置中允许麦克风权限后重试')
    }
    const mime = pickMime()
    try {
      recorder = new window.MediaRecorder(stream, mime ? { mimeType: mime } : undefined)
    } catch {
      return fail('录音初始化失败，请重试')
    }
    cancelled = false
    chunks = []
    seconds.value = 0
    recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data) }
    recorder.onstop = () => {
      const blob = new Blob(chunks, { type: (recorder && recorder.mimeType) || mime || 'audio/webm' })
      const secs = seconds.value
      cleanup()
      const resolve = pendingStop
      pendingStop = null
      if (resolve) resolve(cancelled ? null : { blob, seconds: secs })
    }
    try {
      recorder.start()
    } catch {
      return fail('录音启动失败，请重试')
    }
    recording.value = true
    timer = window.setInterval(() => {
      seconds.value += 1
      if (seconds.value >= MAX_SECONDS) stop()
    }, 1000)
    return true
  }

  /** 结束录音并取回音频数据；未在录音时返回 null */
  function stop() {
    if (!recording.value || !recorder) return Promise.resolve(null)
    if (recorder.state === 'inactive') return Promise.resolve(null)
    return new Promise((resolve) => {
      pendingStop = resolve
      try {
        recorder.stop()
      } catch {
        cleanup()
        resolve(null)
      }
    })
  }

  /** 丢弃录音并释放麦克风；挂起的 stop() 将 resolve(null) */
  function cancel() {
    cancelled = true
    if (recorder && recorder.state !== 'inactive') {
      try { recorder.stop() } catch { cleanup() }
      // onstop 异步回调里会做最终 cleanup；这里先复位让 UI 立即退出录音态
      recording.value = false
    } else {
      const resolve = pendingStop
      cleanup()
      pendingStop = null
      if (resolve) resolve(null)
    }
  }

  // 组件卸载自动停止并释放麦克风（非 setup 上下文调用时跳过）
  if (getCurrentInstance()) {
    onBeforeUnmount(() => { cancel() })
  }

  return { recording, seconds, start, stop, cancel, supported }
}
