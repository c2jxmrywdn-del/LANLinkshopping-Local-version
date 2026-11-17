import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { AudioQueuePlayer } from './audioQueuePlayer'

/**
 * 保序播放回归测试。
 *
 * <p>背景缺陷：后端用 16 线程并发合成 TTS，`tts_chunk` 的到达顺序与 sequenceId
 * 无关（实测 [3,4,1,5,2]）。原 `enqueue` 是「push → sort → 只要空闲就取队首」，
 * 第一个到达的 seq=3 会立刻开播，用户听到前后颠倒的答复。
 *
 * <p>这里用假的 AudioContext / AudioBuffer 驱动真实 AudioQueuePlayer，
 * 断言「实际播放顺序」而不是「队列内容」。
 */

function makeFakeAudioBuffer(duration = 1) {
  return { duration }
}

/** 构造一个最小可用的 AudioContext 替身。 */
function installFakeAudioContext() {
  class FakeBufferSource {
    constructor() {
      this.onended = null
      this.buffer = null
    }
    connect() {}
    disconnect() {}
    // 不自动触发 onended：由测试显式驱动，避免异步时序干扰断言
    start() {}
    stop() {}
  }

  class FakeAudioContext {
    constructor() {
      this.state = 'running'
      this.currentTime = 0
      this.destination = {}
    }
    createBufferSource() {
      return new FakeBufferSource()
    }
    async resume() {}
    async close() {
      this.state = 'closed'
    }
    async decodeAudioData(buf) {
      return makeFakeAudioBuffer(1)
    }
  }

  globalThis.window = globalThis.window || {}
  const original = globalThis.window.AudioContext
  globalThis.window.AudioContext = FakeAudioContext
  return () => {
    globalThis.window.AudioContext = original
  }
}

function chunk(sequenceId, payload = 'A') {
  return {
    sequenceId,
    sentence: `sentence-${sequenceId}`,
    audioBase64: Buffer.from(payload.repeat(sequenceId)).toString('base64'),
    format: 'mp3'
  }
}

describe('AudioQueuePlayer 保序播放', () => {
  let restore
  let playOrder

  beforeEach(() => {
    playOrder = []
    restore = installFakeAudioContext()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    restore()
  })

  it('乱序到达时按 sequenceId 顺序播放，而不是按到达顺序', async () => {
    // 用 onStart 记录实际开播顺序（它是唯一权威的「用户听到的顺序」）
    const player = new AudioQueuePlayer({ onStart: (seq) => playOrder.push(seq) })

    // 模拟后端实测的乱序到达：[3,4,1,5,2]
    for (const seq of [3, 4, 1, 5, 2]) {
      await player.enqueue(chunk(seq))
    }

    // 只有 seq=1 到位后才允许开播
    expect(playOrder).toEqual([1])

    // 驱动播放链：每片结束后播下一片
    for (let i = 0; i < 4; i += 1) {
      const src = player.currentSource
      expect(src).toBeTruthy()
      src.onended()
    }

    expect(playOrder).toEqual([1, 2, 3, 4, 5])
    player.destroy()
  })

  it('首个到达的分片序号大于 1 时不抢播，等待缺口补齐', async () => {
    const player = new AudioQueuePlayer({ onStart: (seq) => playOrder.push(seq) })

    await player.enqueue(chunk(2))
    await player.enqueue(chunk(3))

    expect(playOrder).toEqual([])

    await player.enqueue(chunk(1))
    expect(playOrder).toEqual([1])

    player.destroy()
  })

  it('缺口永久丢失时，宽限期到期后跳过缺口继续播放', async () => {
    const player = new AudioQueuePlayer({ onStart: (seq) => playOrder.push(seq) })

    await player.enqueue(chunk(1))
    expect(playOrder).toEqual([1])

    // 故意跳过 seq=2
    await player.enqueue(chunk(3))
    player.currentSource.onended()
    expect(playOrder).toEqual([1])

    // 宽限期（默认 3000ms）到期后应跳过 seq=2 播 seq=3
    vi.advanceTimersByTime(3100)
    expect(playOrder).toEqual([1, 3])

    player.destroy()
  })
})
