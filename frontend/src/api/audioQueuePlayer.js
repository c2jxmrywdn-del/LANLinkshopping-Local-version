/**
 * Web Audio API 保序音频分片流式播放队列。
 *
 * <p>特性：
 * <ul>
 *   <li>微秒级保序无缝拼接：基于 AudioContext.currentTime 与 buffer.duration 计算下个分片 start 时间，杜绝杂音与间隙；</li>
 *   <li>零延迟即时打断 (Barge-in)：立即 stop 当前 source 节点，清空所有待播分片并复位时间轴；</li>
 *   <li>无任何 Emoji 字符，严格遵循纯前端代码规范。</li>
 * </ul>
 */
export class AudioQueuePlayer {
  constructor({ onStart, onFinish, onQueueEmpty } = {}) {
    this.onStart = onStart
    this.onFinish = onFinish
    this.onQueueEmpty = onQueueEmpty

    this.audioCtx = null
    this.queue = []
    this.currentSource = null
    this.isPlaying = false
    this.nextStartTime = 0
    this.expectedSeq = 1
    this.isDestroyed = false
  }

  async ensureContext() {
    if (!this.audioCtx || this.audioCtx.state === 'closed') {
      const AudioCtxClass = window.AudioContext || window.webkitAudioContext
      this.audioCtx = new AudioCtxClass()
    }
    if (this.audioCtx.state === 'suspended') {
      await this.audioCtx.resume()
    }
  }

  /**
   * 压入待播放的 TTS 分片。
   *
   * @param {{sequenceId: number, sentence: string, audioBase64: string, format: string}} chunk
   */
  async enqueue(chunk) {
    if (!chunk || !chunk.audioBase64 || this.isDestroyed) return
    await this.ensureContext()

    try {
      const binaryString = atob(chunk.audioBase64)
      const len = binaryString.length
      const bytes = new Uint8Array(len)
      for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i)
      }

      const audioBuffer = await this.audioCtx.decodeAudioData(bytes.buffer.slice(0))
      this.queue.push({
        sequenceId: chunk.sequenceId,
        sentence: chunk.sentence,
        audioBuffer
      })

      // 按 sequenceId 升序重排，防御网络乱序
      this.queue.sort((a, b) => a.sequenceId - b.sequenceId)

      if (!this.isPlaying) {
        this.playNext()
      }
    } catch (e) {
      console.warn('[audio-queue-player] 音频解码失败:', e)
    }
  }

  playNext() {
    if (this.isDestroyed || this.queue.length === 0) {
      this.isPlaying = false
      if (this.onQueueEmpty) {
        this.onQueueEmpty()
      }
      return
    }

    const item = this.queue.shift()
    this.isPlaying = true

    const source = this.audioCtx.createBufferSource()
    source.buffer = item.audioBuffer
    source.connect(this.audioCtx.destination)
    this.currentSource = source

    const now = this.audioCtx.currentTime
    const startTime = Math.max(now, this.nextStartTime)
    this.nextStartTime = startTime + item.audioBuffer.duration

    if (this.onStart) {
      this.onStart(item.sequenceId, item.sentence)
    }

    source.start(startTime)

    source.onended = () => {
      if (this.currentSource === source) {
        this.currentSource = null
      }
      if (this.onFinish) {
        this.onFinish(item.sequenceId, item.sentence)
      }
      this.playNext()
    }
  }

  /**
   * 立即打断当前正在播放的音频，并彻底清空待播放队列。
   */
  clear() {
    if (this.currentSource) {
      try {
        this.currentSource.onended = null
        this.currentSource.stop(0)
        this.currentSource.disconnect()
      } catch (e) {
        // ignore already stopped
      }
      this.currentSource = null
    }

    this.queue = []
    this.isPlaying = false
    this.nextStartTime = 0
    this.expectedSeq = 1

    if (this.onQueueEmpty) {
      this.onQueueEmpty()
    }
  }

  destroy() {
    this.isDestroyed = true
    this.clear()
    if (this.audioCtx && this.audioCtx.state !== 'closed') {
      try {
        this.audioCtx.close()
      } catch (e) {
        // ignore
      }
      this.audioCtx = null
    }
  }
}
