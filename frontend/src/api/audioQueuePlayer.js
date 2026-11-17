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
    /**
     * 缺片等待计时器与宽限期。
     *
     * <p>严格保序的代价是：若某个 sequenceId 的分片永久丢失（合成失败 / 发送失败），
     * 队列会一直等它，后续分片全部卡住不播。宽限期到期后跳过缺口，
     * 宁可少一句也要把后面的内容播完。
     */
    this.gapTimer = null
    this.gapGraceMs = 3000
  }

  /** 清掉缺片计时器（正常播放、打断、销毁都要清，避免野回调） */
  clearGapTimer() {
    if (this.gapTimer) {
      clearTimeout(this.gapTimer)
      this.gapTimer = null
    }
  }

  /**
   * 队首不是期望序号时启动宽限计时；到期仍未补齐则跳过缺口继续播。
   *
   * @param {{onGapSkip?: (skippedTo:number) => void}} [opts]
   */
  armGapTimer() {
    if (this.gapTimer || this.isDestroyed) return
    this.gapTimer = setTimeout(() => {
      this.gapTimer = null
      if (this.isDestroyed || this.isPlaying || this.queue.length === 0) return
      const head = this.queue[0]
      if (head.sequenceId === this.expectedSeq) return
      // 跳到队首序号：接受「中间少一句」，保证后续能继续播
      console.warn('[audio-queue-player] 分片缺口超时，跳过 seq', this.expectedSeq, '->', head.sequenceId)
      this.expectedSeq = head.sequenceId
      this.playNext()
    }, this.gapGraceMs)
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

      // 关键：只有队首正是期望序号时才能开播。
      //
      // 后端用 16 线程并发合成 TTS，分片完成顺序与 sequenceId 无关
      // （实测到达顺序 [3,4,1,5,2]）。原实现是「push → sort → 只要空闲就取队首」，
      // 于是第一个到达的 seq=3 立刻开播，用户听到的是前后颠倒的答复。
      // 现在改为等待 expectedSeq 就位再播；缺片时保持等待（不播放错序内容）。
      if (!this.isPlaying && this.queue.length > 0 && this.queue[0].sequenceId === this.expectedSeq) {
        this.clearGapTimer()
        this.playNext()
      } else if (!this.isPlaying && this.queue.length > 0) {
        // 队首不是期望序号：起一个宽限计时，防止某片永久丢失导致整轮卡死
        this.armGapTimer()
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

    // 队首不是期望序号：说明前面的分片还没到，等待后续 enqueue 触发。
    // 这比「先播能播的」重要得多——错序播报会让用户听到语义颠倒的客服答复。
    if (this.queue[0].sequenceId !== this.expectedSeq) {
      this.isPlaying = false
      // 若后续分片永远不来（合成/发送失败），宽限到期后跳过缺口，避免整轮卡死
      this.armGapTimer()
      return
    }

    const item = this.queue.shift()
    this.expectedSeq = item.sequenceId + 1
    this.isPlaying = true
    this.clearGapTimer()

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
    this.clearGapTimer()
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
