import { AudioQueuePlayer } from './audioQueuePlayer'

/**
 * 智能客服实时双向语音全双工流式客户端。
 *
 * <p>核心架构：
 * <ul>
 *   <li>录音采集：基于 Web Audio API 与 AudioWorklet 进行 16kHz 16-bit PCM 采集并流式发送；</li>
 *   <li>长连中枢：通过 WebSocket 双向流式通信，传递二进制 PCM 音频与结构化控制信令；</li>
 *   <li>保序播报：集成 AudioQueuePlayer 调度多句子 Pipeline TTS 无缝拼接播放；</li>
 *   <li>即时打断：支持用户输入事件一键截断后端合成与前端当前音频。</li>
 * </ul>
 */
export class VoiceStreamClient {
  constructor(options = {}) {
    this.visitorToken = options.visitorToken || ''
    this.conversationNo = options.conversationNo || ''
    this.proxySecret = options.proxySecret || ''

    // 回调事件
    this.onReady = options.onReady
    this.onAsrStart = options.onAsrStart
    this.onAsrFinal = options.onAsrFinal
    this.onLlmStart = options.onLlmStart
    this.onTtsChunk = options.onTtsChunk
    this.onSentenceStart = options.onSentenceStart
    this.onSentenceFinish = options.onSentenceFinish
    this.onStreamEnd = options.onStreamEnd
    this.onInterrupted = options.onInterrupted
    this.onError = options.onError
    // 轻量提示（未识别到语音 / 识别超时 / 通道繁忙）：必须与 onError 分开存，
    // 历史上这里漏了赋值，导致 setState 与 handleServerEvent 里的
    // `this.onWarning(...)` 恒为 undefined，警告永远不弹，界面停在「正在思考」。
    this.onWarning = options.onWarning
    this.onStateChange = options.onStateChange

    this.ws = null
    this.state = 'disconnected' // disconnected, connected, listening, thinking, playing
    this.mediaStream = null
    this.recordCtx = null
    this.workletNode = null
    this.sourceNode = null

    this.player = new AudioQueuePlayer({
      onStart: (seq, sentence) => {
        this.setState('playing')
        if (this.onSentenceStart) this.onSentenceStart(seq, sentence)
      },
      onFinish: (seq, sentence) => {
        if (this.onSentenceFinish) this.onSentenceFinish(seq, sentence)
      },
      onQueueEmpty: () => {
        if (this.state === 'playing') {
          this.setState('connected')
        }
      }
    })
  }

  setState(newState) {
    if (this.thinkingTimer) {
      clearTimeout(this.thinkingTimer)
      this.thinkingTimer = null
    }
    if (this.state !== newState) {
      this.state = newState
      if (this.onStateChange) {
        this.onStateChange(newState)
      }
    }
    if (newState === 'thinking') {
      // 识别或合成卡住时收回按钮，避免停在「正在思考」无法再说话
      this.thinkingTimer = setTimeout(() => {
        if (this.state !== 'thinking') return
        this.leaveThinking()
        if (this.onWarning) this.onWarning('语音识别超时，请再试一次')
      }, 90000)
    }
  }

  leaveThinking() {
    const open = this.ws && this.ws.readyState === WebSocket.OPEN
    this.setState(open ? 'connected' : 'disconnected')
  }

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const params = new URLSearchParams()
    if (this.visitorToken) params.set('visitorToken', this.visitorToken)
    if (this.conversationNo) params.set('conversationNo', this.conversationNo)
    if (this.proxySecret) params.set('proxySecret', this.proxySecret)

    const url = `${protocol}//${host}/api/support/stream-voice?${params.toString()}`
    this.ws = new WebSocket(url)
    this.ws.binaryType = 'arraybuffer'

    this.ws.onopen = () => {
      this.setState('connected')
      this.startHeartbeat()
    }

    this.ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        try {
          const msg = JSON.parse(event.data)
          this.handleServerEvent(msg)
        } catch (e) {
          console.warn('[voice-stream-client] 无法解析服务端消息:', e)
        }
      }
    }

    this.ws.onerror = (err) => {
      console.warn('[voice-stream-client] WebSocket 出错:', err)
      if (this.onError) this.onError(err)
    }

    this.ws.onclose = () => {
      this.stopHeartbeat()
      this.setState('disconnected')
    }
  }

  /**
   * 心跳保活：每 25s 发一次 ping。
   *
   * <p>后端 WebSocket 会话与 nginx/Vite 代理都设了读超时（600s）。用户开着对讲
   * 长时间不说话时，中间设备会按「空闲连接」回收，前端却仍显示「对讲就绪」，
   * 直到用户按住说话才报「语音连接已断开」。心跳让连接始终有流量，
   * 同时服务端 `pong` 也顺带作为链路存活探针。
   */
  startHeartbeat() {
    this.stopHeartbeat()
    this.lastPongAt = Date.now()
    this.heartbeatTimer = window.setInterval(() => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
      // 两个心跳周期都没有 pong：判定为半开连接（中间设备已回收但未发 FIN），
      // 主动关闭并让 UI 回到「已断开」，避免用户以为还能说话。
      if (Date.now() - (this.lastPongAt || 0) > 60000) {
        console.warn('[voice-stream-client] 心跳超时，主动断开半开连接')
        try { this.ws.close() } catch (e) { /* ignore */ }
        return
      }
      this.sendJson({ action: 'ping' })
    }, 25000)
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  handleServerEvent(msg) {
    switch (msg.event) {
      case 'ready':
        this.sessionId = msg.sessionId
        if (this.onReady) this.onReady(msg.sessionId)
        break
      case 'asr_start':
        this.setState('thinking')
        if (this.onAsrStart) this.onAsrStart()
        break
      case 'asr_final': {
        const text = String(msg.text || '').trim()
        if (text) {
          if (this.onAsrFinal) this.onAsrFinal(text)
        } else {
          // 空转写不会进入问答；不在这里结束思考，等紧随的 warning / error 收口
          this.emptyAsrPending = true
          setTimeout(() => {
            if (!this.emptyAsrPending || this.state !== 'thinking') return
            this.emptyAsrPending = false
            this.leaveThinking()
            if (this.onWarning) this.onWarning('没有听到清晰的话，请再试一次')
          }, 80)
        }
        break
      }
      case 'llm_start':
        this.emptyAsrPending = false
        this.setState('thinking')
        if (this.onLlmStart) this.onLlmStart(msg.question)
        break
      case 'tts_chunk':
        if (this.onTtsChunk) this.onTtsChunk(msg)
        this.player.enqueue(msg)
        break
      case 'stream_end':
        if (this.onStreamEnd) this.onStreamEnd(msg)
        // 没有音频分片可播时，队列不会回调，思考态必须在这里收回
        if (this.state !== 'playing') this.leaveThinking()
        break
      case 'interrupted':
        this.player.clear()
        this.leaveThinking()
        if (this.onInterrupted) this.onInterrupted(msg)
        break
      case 'pong':
        this.lastPongAt = Date.now()
        break
      case 'warning':
        this.emptyAsrPending = false
        this.leaveThinking()
        if (this.onWarning) this.onWarning(msg.message || '没有听到清晰的话，请再试一次')
        break
      case 'error':
        this.emptyAsrPending = false
        this.leaveThinking()
        if (this.onError) this.onError(new Error(msg.message || '语音处理失败'))
        break
    }
  }

  /**
   * 启动麦克风录音并流式上传 16kHz PCM 数据。
   */
  async startListening() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.connect()
    }

    // 若当前正在播放播报，用户开始说话则触发即时打断
    if (this.player.isPlaying || this.state === 'playing') {
      this.interrupt()
    }

    try {
      if (!window.isSecureContext && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
        throw new Error('浏览器安全限制：当前处于非安全上下文（HTTP局域网访问），麦克风已被浏览器禁用。请改用 http://localhost:5173 访问，或为当前服务开启 HTTPS')
      }

      const mediaDevices = navigator?.mediaDevices
      if (!mediaDevices || typeof mediaDevices.getUserMedia !== 'function') {
        throw new Error('当前浏览器环境不支持麦克风采集或权限受限，请使用 Chrome/Edge 浏览器并检查麦克风权限')
      }

      this.mediaStream = await mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })

      const AudioCtxClass = window.AudioContext || window.webkitAudioContext
      if (!AudioCtxClass) {
        throw new Error('当前浏览器不支持 Web Audio API，无法进行语音录制')
      }
      this.recordCtx = new AudioCtxClass()
      if (this.recordCtx.state === 'suspended') {
        await this.recordCtx.resume()
      }

      if (!this.recordCtx.audioWorklet) {
        throw new Error('当前浏览器不支持 AudioWorklet，无法进行流式录音')
      }

      await this.recordCtx.audioWorklet.addModule('/pcm-recorder-processor.js')
      this.workletNode = new AudioWorkletNode(this.recordCtx, 'pcm-recorder-processor')

      this.sourceNode = this.recordCtx.createMediaStreamSource(this.mediaStream)
      this.sourceNode.connect(this.workletNode)

      this.workletNode.port.onmessage = (event) => {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.ws.send(event.data) // 发送二进制 PCM 采样块
        }
      }

      this.sendJson({ action: 'start' })
      this.setState('listening')
    } catch (e) {
      this.cleanupRecordNodes()
      if (this.state === 'thinking' || this.state === 'listening') this.leaveThinking()
      console.error('[voice-stream-client] 启动麦克风失败:', e)
      let userMsg = e?.message || '无法访问麦克风'
      if (e?.name === 'NotAllowedError' || e?.name === 'PermissionDeniedError') {
        userMsg = '麦克风权限被拒绝，请在浏览器地址栏左侧允许麦克风权限后重试'
      } else if (e?.name === 'NotFoundError' || e?.name === 'DevicesNotFoundError') {
        userMsg = '未检测到麦克风录音设备，请检查系统音频输入硬件'
      } else if (e?.name === 'NotReadableError' || e?.name === 'TrackStartError') {
        userMsg = '麦克风被其他应用程序占用，请释放后重试'
      }
      if (this.onError) this.onError(new Error(userMsg))
    }
  }

  /**
   * 结束说话，触发 ASR 识别与应答。
   */
  stopListening() {
    this.cleanupRecordNodes()
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.leaveThinking()
      if (this.onError) this.onError(new Error('语音连接已断开，请退出后重新开启对讲'))
      return
    }
    this.sendJson({ action: 'asr_end' })
    this.setState('thinking')
  }

  /**
   * 即时打断 (Barge-in)。
   */
  interrupt() {
    this.player.clear()
    this.sendJson({ action: 'interrupt' })
    if (this.state === 'playing' || this.state === 'thinking') {
      this.setState('connected')
    }
  }

  /**
   * 直接发送文本。
   */
  sendText(text) {
    if (!text || !text.trim()) return
    this.interrupt()
    this.sendJson({ action: 'text', text: text.trim() })
    this.setState('thinking')
  }

  sendJson(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj))
    }
  }

  cleanupRecordNodes() {
    if (this.sourceNode) {
      try { this.sourceNode.disconnect() } catch (e) {}
      this.sourceNode = null
    }
    if (this.workletNode) {
      try { this.workletNode.disconnect() } catch (e) {}
      this.workletNode = null
    }
    if (this.recordCtx && this.recordCtx.state !== 'closed') {
      try { this.recordCtx.close() } catch (e) {}
      this.recordCtx = null
    }
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop())
      this.mediaStream = null
    }
  }

  close() {
    this.stopHeartbeat()
    this.cleanupRecordNodes()
    this.player.destroy()
    if (this.ws) {
      try { this.ws.close() } catch (e) {}
      this.ws = null
    }
    this.setState('disconnected')
  }
}
