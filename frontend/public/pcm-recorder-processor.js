/**
 * AudioWorkletProcessor: 实时将麦克风输入降采样至 16kHz 16-bit Mono 小端 PCM。
 * 运行于音频渲染独立线程，零 DOM 依赖，零 Emoji 字符。
 */
class PcmRecorderProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.targetSampleRate = 16000
    // 缓冲区大小：1024 个 16-bit 采样点（2048 字节，约 64ms），适合流式平滑传输
    this.bufferSize = 1024
    this.pcmBuffer = new Int16Array(this.bufferSize)
    this.pcmIndex = 0
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0]
    if (!input || !input[0] || input[0].length === 0) {
      return true
    }

    const channelData = input[0] // 单声道采集
    const sourceSampleRate = sampleRate // AudioWorklet 全局当前采样率（通常为 44100 或 48000）

    if (sourceSampleRate === this.targetSampleRate) {
      for (let i = 0; i < channelData.length; i++) {
        this.pushSample(channelData[i])
      }
    } else {
      // 线性插值重采样至 16000Hz
      const ratio = sourceSampleRate / this.targetSampleRate
      const outputLength = Math.floor(channelData.length / ratio)
      for (let i = 0; i < outputLength; i++) {
        const sourceIndex = i * ratio
        const i1 = Math.floor(sourceIndex)
        const i2 = Math.min(i1 + 1, channelData.length - 1)
        const fraction = sourceIndex - i1
        const sample = channelData[i1] + (channelData[i2] - channelData[i1]) * fraction
        this.pushSample(sample)
      }
    }

    return true
  }

  pushSample(sample) {
    // 限制在 -1.0 到 1.0 之间并转为 16-bit signed PCM
    const clamped = Math.max(-1.0, Math.min(1.0, sample))
    const pcmSample = clamped < 0 ? clamped * 32768 : clamped * 32767
    this.pcmBuffer[this.pcmIndex++] = Math.round(pcmSample)

    if (this.pcmIndex >= this.bufferSize) {
      // 拷贝一份发送至主线程，重置指针
      const payload = new Int16Array(this.pcmBuffer)
      this.port.postMessage(payload.buffer, [payload.buffer])
      this.pcmIndex = 0
    }
  }
}

registerProcessor('pcm-recorder-processor', PcmRecorderProcessor)
