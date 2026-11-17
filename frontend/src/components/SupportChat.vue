<template>
  <div class="support-root">
    <button class="support-launcher" type="button" :class="{ 'is-open': visible }"
      aria-expanded="visible"
      aria-controls="lanlink-support-panel" @click="toggle">
      <SupportAgentAvatar :size="22" status="online" class="support-launcher-avatar" />
      <span class="support-launcher-text">智能客服</span>
    </button>

    <!-- 聊天面板：Border Beam 改为品牌琥珀、慢速、静止色相，避免彩虹边喧宾夺主。
         BorderBeam 为多根组件不透传 class，定位由外层 .support-beam-host 容器接管 -->
    <div v-if="visible" class="support-beam-host">
      <BorderBeam :border-radius="20" theme="light" color-variant="gold" :static-colors="true" :strength="0.45" :duration="10">
        <section id="lanlink-support-panel" class="support-panel"
          role="dialog" aria-label="LANLinkshopping 专属客服">
      <header class="support-header">
        <SupportAgentAvatar :size="36" status="online" />
        <div class="support-header-copy">
          <!-- 深色顶栏：LAN / shopping 保持白，Link 保持琥珀，与首页词标一致 -->
          <BrandWordmark size="15px" />
          <p>
            <span class="support-live-dot" aria-hidden="true"></span>
            <span>客服在线</span>
          </p>
        </div>
        <div class="support-header-tools">
          <button class="support-voice-toggle" type="button" :class="{ 'is-active': voiceCallActive }"
            :aria-pressed="voiceCallActive"
            :title="voiceCallActive ? '退出实时对讲' : '开启双向实时语音对讲'"
            :aria-label="voiceCallActive ? '退出实时对讲' : '开启双向实时语音对讲'"
            @click="toggleVoiceCall">
            <PhoneCall v-if="!voiceCallActive" :size="15" />
            <PhoneOff v-else :size="15" />
          </button>
          <button class="support-tts-toggle" type="button" :class="{ 'is-on': ttsEnabled }"
            :aria-pressed="ttsEnabled"
            :title="ttsEnabled ? '关闭语音播报' : '开启语音播报'"
            :aria-label="ttsEnabled ? '关闭语音播报' : '开启语音播报'"
            @click="toggleTtsEnabled">
            <Volume2 v-if="ttsEnabled" :size="15" />
            <VolumeX v-else :size="15" />
          </button>
          <button class="support-close" type="button" aria-label="关闭客服" @click="visible = false">
            <X :size="16" />
          </button>
        </div>
      </header>

      <div ref="messageList" class="support-messages" aria-live="polite">
        <div v-for="(item, index) in messages" :key="index" class="support-turn"
          :class="[item.role === 'user' ? 'is-user' : 'is-agent', { 'is-thinking': item.isThinking }]">
          <SupportAgentAvatar v-if="item.role !== 'user'" :size="28" variant="plain" class="support-turn-avatar" />
          <div class="support-message" :class="[item.role === 'user' ? 'from-user' : 'from-support', { 'is-streaming': item.isStreaming && item.text }]">
          <div v-if="item.role !== 'user'" class="support-message-label">
            <span>{{ item.isAgent ? '人工客服' : '智能客服' }}</span>
            <span v-if="item.source === 'AI' || item.source === 'RAG'" class="support-ai-tag"
              title="由本地大模型基于平台知识库生成">AI 生成</span>
            <AiCacheBadge v-if="item.role !== 'user' && item.cacheStatus"
              :cache-status="item.cacheStatus"
              :similarity="item.cacheSimilarity"
              :latency-ms="item.cacheLatencyMs" />
            <button v-if="item.role !== 'user' && item.text" type="button" class="support-tts"
              :class="{ 'is-on': activeMessage === item }"
              :aria-label="activeMessage === item ? '停止语音播报' : '语音播报'"
              :title="activeMessage === item ? '停止语音播报' : '语音播报'"
              @click.stop="toggleSpeak(item)">
              <Square v-if="activeMessage === item" :size="12" class="inline-block align-middle" />
              <Volume2 v-else :size="13" class="inline-block align-middle" />
            </button>
          </div>

          <!-- DeepSeek-R1 / CoT 思维链折叠卡片 -->
          <AiReasoningCollapse v-if="item.role !== 'user' && (item.reasoning || item.isThinking)"
            :reasoning="item.reasoning"
            :is-thinking="item.isThinking"
            :duration-sec="item.thinkingDuration" />

          <!-- 语音附件转写：转写完成后在消息内呈现（Apple 风 + 小清新蓝，lucide 图标） -->
          <div v-if="item.role === 'user' && item.transcripts && item.transcripts.length" class="support-transcripts">
            <div v-for="(tr, ti) in item.transcripts" :key="ti" class="support-transcript-card">
              <span class="support-transcript-head">
                <AudioLines :size="12" aria-hidden="true" />
                <span>{{ t('supportVoice.transcript') }}</span>
              </span>
              <p class="support-transcript-text">{{ tr.text }}</p>
            </div>
          </div>

          <!-- P2 图像/文件理解摘要：图片理解 / 文档解析结果与附件气泡就近呈现（lucide 图标，禁 emoji） -->
          <div v-if="item.role === 'user' && item.insights && item.insights.length" class="support-transcripts">
            <div v-for="(ins, ii) in item.insights" :key="'ins-' + ii" class="support-transcript-card">
              <span class="support-transcript-head">
                <Eye v-if="ins.kind === 'IMAGE'" :size="12" aria-hidden="true" />
                <FileText v-else :size="12" aria-hidden="true" />
                <span>{{ ins.kind === 'IMAGE' ? t('supportMedia.insightTitle') : t('supportMedia.docPreviewTitle') }} · {{ ins.name }}</span>
              </span>
              <p class="support-transcript-text">{{ ins.text }}</p>
              <p v-if="ins.partial" class="support-transcript-text is-partial">
                <FileWarning :size="11" aria-hidden="true" />
                {{ t('supportMedia.partialTitle') }}{{ ins.partialReason ? '：' + ins.partialReason : '' }}
              </p>
            </div>
          </div>

          <!-- 正文回答：流式输出时在句末留一条琥珀光标 -->
          <p v-if="item.text" class="support-body">{{ item.text }}</p>
          <!-- 浏览器拦截自动播放时的兜底入口：用户点击后在同一手势内播放 -->
          <button v-if="item.role !== 'user' && item.ttsBlocked && item.text" type="button"
            class="support-tts-fallback" @click.stop="playMessage(item)">▶ 播放语音</button>

          <!-- B2B 现货阶梯报价卡片 -->
          <AiQuoteCard v-if="item.role !== 'user' && item.quote"
            :quote="item.quote" />

          <!-- Hybrid RAG 双轨接地引用来源 -->
          <AiSourceCitations v-if="item.role !== 'user' && item.sources && item.sources.length"
            :sources="item.sources" />
        </div>
        </div>
        <div v-if="messages.length === 1 && !loading" class="support-suggestions" aria-label="常见问题">
          <p>可以先试试</p>
          <button type="button" @click="askSuggested('如何注册账号？')">账号注册</button>
          <button type="button" @click="askSuggested('购物车怎么结算？')">购物车结算</button>
          <button type="button" @click="askSuggested('如何申请商户入驻？')">商户入驻</button>
        </div>
      </div>

      <!-- 实时双向语音对讲条 -->
      <div v-if="voiceCallActive" class="support-voice-bar" :class="'state-' + voiceState">
        <div class="voice-bar-status">
          <span class="voice-status-dot" :class="{ 'is-listening': voiceState === 'listening', 'is-playing': voiceState === 'playing' }"></span>
          <span class="voice-status-label">{{ voiceStatusText }}</span>
          <span v-if="currentSentence" class="voice-status-sentence">"{{ currentSentence }}"</span>
        </div>
        <div class="voice-bar-controls">
          <button v-if="voiceState !== 'listening'" type="button" class="voice-ctrl-btn btn-mic" @click="startVoiceInput">
            <Mic :size="13" />
            <span>按此说话</span>
          </button>
          <button v-else type="button" class="voice-ctrl-btn btn-stop" @click="stopVoiceInput">
            <Square :size="13" />
            <span>说完了</span>
          </button>
          <button v-if="voiceState === 'playing'" type="button" class="voice-ctrl-btn btn-interrupt" @click="interruptVoice">
            打断
          </button>
          <button type="button" class="voice-ctrl-btn btn-close" @click="toggleVoiceCall" title="退出实时对讲">
            退出
          </button>
        </div>
      </div>

      <!-- 人工会话态横幅（P1-20）：值班排班待拍板⑦-11，占位文案如实说明留言队列 -->
      <div v-if="humanSession" class="support-human-banner" role="status">
        <div class="human-banner-head">
          <Headphones :size="14" aria-hidden="true" />
          <strong>{{ t('supportHuman.transferred') }}</strong>
        </div>
        <p class="human-banner-note">{{ t('supportHuman.hoursNote') }}</p>
        <p class="human-banner-queue">{{ t('supportHuman.queueNote') }}</p>
      </div>

      <form class="support-compose" :class="{ 'is-dragover': dragActive }" @submit.prevent="send"
        @dragenter.prevent="onDragEnter" @dragover.prevent="onDragOver" @dragleave.prevent="onDragLeave"
        @drop.prevent="onDrop">
        <SupportMediaUploader ref="mediaUploader" :visitor-token="visitorToken" :disabled="loading" />
        <label class="support-sr-only" for="support-question">输入电商相关问题</label>
        <textarea id="support-question" v-model="draft" :disabled="loading" maxlength="300" rows="2"
          placeholder="问问订单、商品、购物车或商户入驻"
          @keydown.enter.exact.prevent="send" />
        <div v-if="dragActive" class="support-drop-hint" aria-hidden="true">松开即可添加附件</div>
        <div class="support-compose-footer">
          <!-- 转人工（P1-20）：后端无访客侧转人工端点（sender_type 仅 customer/bot/agent/note），
               走 supportApi.ask 落一条普通客户消息 + 前端本地人工会话态 -->
          <button v-if="!humanSession" type="button" class="support-human-btn" :disabled="loading || transferRequesting"
            :title="t('supportHuman.transfer')" :aria-label="t('supportHuman.transfer')"
            @click="requestHuman">
            <Headphones :size="13" aria-hidden="true" />
            <span>{{ t('supportHuman.transfer') }}</span>
          </button>
          <button v-else type="button" class="support-human-btn is-back" :disabled="transferRequesting"
            :title="t('supportHuman.backToAi')" :aria-label="t('supportHuman.backToAi')"
            @click="exitHumanSession">
            <RotateCcw :size="13" aria-hidden="true" />
            <span>{{ t('supportHuman.backToAi') }}</span>
          </button>
          <span class="support-count">{{ draft.length }}/300</span>
          <button class="support-send" type="submit" :disabled="loading || !draft.trim()">发送</button>
        </div>
      </form>
      <footer class="support-scope-note">
        <label class="support-consent">
          <input id="support-persist-conversation" name="persistConversation"
                 v-model="persistConversation" type="checkbox" />
          <span class="consent-full">允许保存脱敏对话，供客服跟进与改进知识库（可选）</span>
          <span class="consent-short">允许保存脱敏对话（可选）</span>
        </label>
        <p class="consent-note">不勾选时仍可使用知识库客服，但本次对话不会保存到客服工作台。请勿发送密码、验证码或银行卡信息。</p>
      </footer>
      </section>
      </BorderBeam>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { Volume2, VolumeX, Square, Mic, PhoneCall, PhoneOff, AudioLines, Eye, FileText, FileWarning, Headphones, RotateCcw, X } from 'lucide-vue-next'
import { VoiceStreamClient } from '../api/voiceStreamClient'
import { supportApi } from '../api'
import { synthesize } from '../api/supportTts'
import SupportAgentAvatar from './ui/SupportAgentAvatar.vue'
import BrandWordmark from './BrandWordmark.vue'
import BorderBeam from './ui/BorderBeam.vue'
import SupportMediaUploader from './support/SupportMediaUploader.vue'
import AiCacheBadge from './ai/AiCacheBadge.vue'
import AiReasoningCollapse from './ai/AiReasoningCollapse.vue'
import AiSourceCitations from './ai/AiSourceCitations.vue'
import AiQuoteCard from './ai/AiQuoteCard.vue'
import { translateDataText, t } from '../i18n'

const FALLBACK = '您好！感谢您的咨询。当前客服仅能解答 LANLinkshopping 专属知识库已收录的电商相关问题，可咨询账号注册与登录、商品浏览、购物车与订单、商户入驻、钱包与账期、会员与活动、站内消息及 Cookie 设置；其他问题暂不在可答范围内，敬请谅解。'
const WELCOME = '您好，我是 LANLinkshopping 专属客服。请咨询平台账号注册与登录、商品浏览、购物车与订单、商户入驻、钱包与账期、会员与活动、站内消息或 Cookie 设置等已收录问题。'
const TOKEN_KEY = 'll_support_visitor_token'
const TTS_ENABLED_KEY = 'll_support_tts_enabled'
// 人工会话态本地标记（P1-20）：后端 waiting_agent 由管理端消息中心维护，
// 买家侧无法查询，故会话期持久化在 sessionStorage
const HUMAN_MODE_KEY = 'll_support_human_mode'
// 与后端 /support/tts 的上限保持一致：超长文本只朗读前 500 字，避免整次请求被拒
const TTS_MAX_CHARS = 500
// 太短的回复（「好的」）朗读价值低，还会频繁触发合成请求
const TTS_MIN_CHARS = 8

function readTtsEnabled() {
  try { return localStorage.getItem(TTS_ENABLED_KEY) !== '0' } catch { return true }
}

function readHumanMode() {
  try { return sessionStorage.getItem(HUMAN_MODE_KEY) === '1' } catch { return false }
}

function writeHumanMode(on) {
  try { sessionStorage.setItem(HUMAN_MODE_KEY, on ? '1' : '0') } catch { /* ignore */ }
}

function createToken() {
  try {
    const existing = sessionStorage.getItem(TOKEN_KEY)
    if (existing && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(existing)) return existing
    const token = window.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
    })
    sessionStorage.setItem(TOKEN_KEY, token)
    return token
  } catch {
    return window.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
    })
  }
}

const visible = ref(false)
const draft = ref('')
const loading = ref(false)
const messageList = ref(null)
const visitorToken = ref(createToken())
// 默认开启脱敏对话留存：为意图/客服模型的后训练沉淀语料；用户可随时取消勾选。
const persistConversation = ref(true)
const lastHistoryMessageId = ref(0)
const messages = ref([{ role: 'assistant', text: WELCOME }])
const ttsEnabled = ref(readTtsEnabled())
const activeMessage = ref(null)
const mediaUploader = ref(null)
// 拖拽计数：dragenter/dragleave 在子元素间冒泡会抖动，用计数判定真正的"离开输入区"
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)
let pollTimer = null
let currentStreamAbort = null
let currentAudio = null
// 播放令牌：每次停止/切换自增，异步合成回来后据此丢弃过期结果
let playbackToken = 0

// 实时语音双向流式对讲
// 人工会话态（P1-20）：进入后 AI 自动回答不再追加，等待人工在消息中心回复后经 syncHistory 同步
const humanSession = ref(readHumanMode())
const transferRequesting = ref(false)
const voiceCallActive = ref(false)
const voiceState = ref('disconnected')
const currentSentence = ref('')
let voiceClient = null
let currentVoiceAiMsg = null

const voiceStatusText = computed(() => {
  switch (voiceState.value) {
    case 'listening': return '正在倾听...'
    case 'thinking': return '正在思考...'
    case 'playing': return '客服正在回答...'
    case 'connected': return '对讲就绪（可点击说话）'
    default: return '正在连接...'
  }
})

function toggleVoiceCall() {
  if (voiceCallActive.value) {
    if (voiceClient) {
      voiceClient.close()
      voiceClient = null
    }
    voiceCallActive.value = false
    voiceState.value = 'disconnected'
    currentSentence.value = ''
  } else {
    if (!window.isSecureContext && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
      message.error('浏览器安全限制：非本地安全上下文（HTTP局域网访问）无法启用麦克风，请使用 http://localhost:5173 访问')
      return
    }
    if (!navigator?.mediaDevices?.getUserMedia) {
      message.error('当前浏览器环境不支持麦克风采集或已被禁用，请使用 Chrome/Edge 浏览器并检查麦克风权限')
      return
    }
    if (voiceClient) {
      voiceClient.close()
      voiceClient = null
      voiceCallActive.value = false
    }
    stopPlayback()
    voiceCallActive.value = true
    voiceClient = new VoiceStreamClient({
      visitorToken: visitorToken.value,
      onStateChange: (st) => { voiceState.value = st },
      onAsrFinal: (text) => {
        if (text && text.trim()) {
          messages.value.push({ role: 'user', text: text.trim() })
        }
      },
      onLlmStart: () => {
        currentVoiceAiMsg = {
          role: 'assistant',
          text: '',
          source: 'AI',
          isStreaming: true
        }
        messages.value.push(currentVoiceAiMsg)
      },
      onSentenceStart: (seq, sentence) => {
        currentSentence.value = sentence
        if (currentVoiceAiMsg) {
          if (!currentVoiceAiMsg.text) {
            currentVoiceAiMsg.text = sentence
          } else {
            currentVoiceAiMsg.text += sentence
          }
        }
      },
      onStreamEnd: (msg) => {
        currentSentence.value = ''
        if (currentVoiceAiMsg) {
          if (msg.fullText) currentVoiceAiMsg.text = msg.fullText
          currentVoiceAiMsg.isStreaming = false
          currentVoiceAiMsg = null
        }
      },
      onInterrupted: () => {
        currentSentence.value = ''
        if (currentVoiceAiMsg) {
          currentVoiceAiMsg.text += ' (已打断)'
          currentVoiceAiMsg.isStreaming = false
          currentVoiceAiMsg = null
        }
      },
      onWarning: (text) => {
        message.warning(text || '没有听到清晰的话，请再试一次')
      },
      onError: (err) => {
        message.warning(err?.message || '语音连接异常')
      }
    })
    voiceClient.connect()
  }
}

function startVoiceInput() {
  if (voiceClient) voiceClient.startListening()
}

function stopVoiceInput() {
  if (voiceClient) voiceClient.stopListening()
}

function interruptVoice() {
  if (voiceClient) voiceClient.interrupt()
}

function hasDraggedFiles(event) {
  const types = event?.dataTransfer?.types
  if (!types) return true
  return Array.from(types).includes('Files')
}
function onDragEnter(event) {
  if (hasDraggedFiles(event)) dragDepth.value += 1
}
function onDragOver(event) {
  if (hasDraggedFiles(event) && dragDepth.value === 0) dragDepth.value = 1
}
function onDragLeave() {
  // dragleave 的 dataTransfer.types 在部分浏览器为空，这里一律回退计数，避免遮罩卡住
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}
function onDrop(event) {
  dragDepth.value = 0
  const files = event?.dataTransfer?.files
  if (files && files.length) void mediaUploader.value?.addFiles(files)
}

/** 停止当前播报（同一时间只播一条；切换消息、关闭面板、卸载组件都走这里） */
function stopPlayback() {
  playbackToken += 1
  if (currentAudio) {
    try { currentAudio.pause() } catch { /* ignore */ }
    currentAudio = null
  }
  activeMessage.value = null
}

function toggleTtsEnabled() {
  ttsEnabled.value = !ttsEnabled.value
  try { localStorage.setItem(TTS_ENABLED_KEY, ttsEnabled.value ? '1' : '0') } catch { /* ignore */ }
  if (!ttsEnabled.value) stopPlayback()
}

/** 合成并播放一条消息；返回是否真正开始播放 */
async function speak(text, target) {
  const plain = translateDataText(String(text || '')).trim()
  if (!plain) return false
  if (voiceClient) {
    voiceClient.close()
    voiceClient = null
    voiceCallActive.value = false
  }
  stopPlayback()
  const token = playbackToken
  let result
  try {
    result = await synthesize(plain.slice(0, TTS_MAX_CHARS))
  } catch (err) {
    // TTS 是增强能力，失败不能让文字回答受影响；但**必须给出可见反馈**。
    // 历史实现是空 catch，用户点「朗读」完全没反应，只能判断为「朗读功能坏了」。
    // 只在用户主动触发（target 非空 / 手动点击）时提示，避免自动播报失败刷屏。
    if (target) {
      const detail = String(err?.message || '').trim()
      message.warning(detail && !/暂不可用/.test(detail)
        ? `语音播报失败：${detail}`
        : '语音播报暂不可用，请稍后重试或查看文字回答')
      target.ttsBlocked = true
    }
    return false
  }
  if (token !== playbackToken || !result?.audioBase64) return false

  const audio = new Audio('data:audio/mp3;base64,' + result.audioBase64)
  currentAudio = audio
  activeMessage.value = target || null
  const cleanup = () => {
    if (currentAudio !== audio) return
    currentAudio = null
    if (activeMessage.value === target) activeMessage.value = null
  }
  audio.addEventListener('ended', cleanup)
  audio.addEventListener('error', cleanup)

  try {
    await audio.play()
    return true
  } catch {
    // 浏览器自动播放策略拦截：交给气泡下的「▶ 播放语音」按钮在用户手势中重试
    cleanup()
    if (target) target.ttsBlocked = true
    return false
  }
}

/** 手动播放/重播一条消息 */
function playMessage(item) {
  if (!item?.text) return
  item.ttsBlocked = false
  void speak(item.text, item)
}

/** 播放/停止单条客服回复的语音播报 */
function toggleSpeak(item) {
  if (activeMessage.value === item) {
    if (voiceClient) {
    voiceClient.close()
    voiceClient = null
    voiceCallActive.value = false
  }
  stopPlayback()
    return
  }
  playMessage(item)
}

/** 最终回答渲染完成后按需自动播报（每条只尝试一次） */
function maybeAutoSpeak(item) {
  if (!ttsEnabled.value || !item || item.ttsAutoSpoken) return
  const text = String(item.text || '').trim()
  if (text.length < TTS_MIN_CHARS) return
  item.ttsAutoSpoken = true
  void speak(text, item)
}

function toggle() { visible.value = !visible.value }
function askSuggested(question) {
  if (loading.value) return
  draft.value = question
  void send()
}

/**
 * 转人工（P1-20）：经 supportApi.ask 落一条普通客户消息——
 * 后端无访客侧转人工端点（SupportController /ask 仅 action=ask|history，
 * 消息 sender_type 仅 customer/bot，会话 status 由管理端维护），
 * 转人工语义由前端本地人工会话态表达；持久化关闭时仅本地进入会话态。
 */
async function requestHuman() {
  if (transferRequesting.value || humanSession.value) return
  transferRequesting.value = true
  messages.value.push({ role: 'user', text: t('supportHuman.markerMessage'), isHumanMarker: true })
  try {
    // persistConversation 固定开启：让消息中心管理端看到这条转人工请求（customer 消息 + open 会话）
    const result = await supportApi.ask(t('supportHuman.markerMessage'), visitorToken.value, true)
    if (result.visitorToken) visitorToken.value = result.visitorToken
    lastHistoryMessageId.value = Math.max(lastHistoryMessageId.value, Number(result.lastMessageId || 0))
  } catch { /* 持久化失败不阻塞本地转人工：会话态由前端维护 */ }
  humanSession.value = true
  writeHumanMode(true)
  stopPlayback()
  transferRequesting.value = false
}

function exitHumanSession() {
  humanSession.value = false
  writeHumanMode(false)
  messages.value.push({ role: 'assistant', text: WELCOME, source: 'AI' })
}

watch(messages, async () => {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}, { deep: true })

watch(visible, isVisible => {
  stopPolling()
  if (isVisible) {
    void syncHistory()
    pollTimer = window.setInterval(() => { void syncHistory() }, 12000)
  }
})
onBeforeUnmount(stopPolling)
function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = null
  if (currentStreamAbort) {
    try { currentStreamAbort.abort() } catch {}
    currentStreamAbort = null
  }
  if (voiceClient) {
    voiceClient.close()
    voiceClient = null
    voiceCallActive.value = false
  }
  stopPlayback()
}
async function syncHistory() {
  if (!visible.value || loading.value) return
  // 流式中不触发历史轮询，避免消息追加并发乱序
  const isStreamingAny = messages.value.some(m => m.isStreaming)
  if (isStreamingAny) return

  // 本地刚上屏的消息还没有服务端 id。AI 回答和人工会话都按正文对上，
  // 避免历史回放把同一条问题和回答再画一遍。
  const localEcho = messages.value.filter(m => m.role === 'user' && !m.messageId)
  const localAnswers = messages.value.filter(m => m.role !== 'user' && !m.messageId && m.text)

  const result = await supportApi.history(visitorToken.value, lastHistoryMessageId.value)
  if (result.visitorToken) visitorToken.value = result.visitorToken
  let maxId = lastHistoryMessageId.value
  for (const item of result.messages || []) {
    const id = Number(item.id || 0)
    if (!id || id <= lastHistoryMessageId.value) continue
    const senderType = String(item.senderType || '')
    const content = String(item.content || '')
    if (senderType === 'customer') {
      const idx = localEcho.findIndex(m => m.text === content)
      if (idx >= 0) {
        localEcho[idx].messageId = id
        localEcho.splice(idx, 1)
        maxId = Math.max(maxId, id)
        continue
      }
    } else {
      const idx = localAnswers.findIndex(m => m.text === content)
      if (idx >= 0) {
        localAnswers[idx].messageId = id
        if (senderType === 'agent') localAnswers[idx].isAgent = true
        localAnswers.splice(idx, 1)
        maxId = Math.max(maxId, id)
        continue
      }
    }
    const role = senderType === 'customer' ? 'user' : 'assistant'
    messages.value.push({
      role, text: String(item.content || ''), messageId: id,
      senderName: item.senderName || '',
      // 后端 sender_type=agent 即人工客服回复（CustomerCommunicationService.insertMessage）
      isAgent: senderType === 'agent'
    })
    maxId = Math.max(maxId, id)
  }
  lastHistoryMessageId.value = Math.max(maxId, Number(result.lastMessageId || 0))
}
async function send() {
  const question = draft.value.trim()
  if (!question || loading.value) return
  // 人工会话态：仍走 ask 持久化（消息中心可见），但 AI 自动回答不上屏；
  // 游标由 ask 响应的 lastMessageId 推进，人工回复经 syncHistory 轮询拉取
  if (humanSession.value) {
    if (question.length > 300) return
    messages.value.push({ role: 'user', text: question })
    draft.value = ''
    try {
      const result = await supportApi.ask(question, visitorToken.value, true)
      if (result.visitorToken) visitorToken.value = result.visitorToken
      lastHistoryMessageId.value = Math.max(lastHistoryMessageId.value, Number(result.lastMessageId || 0))
    } catch { /* 持久化失败不阻塞本地对话 */ }
    void syncHistory()
    return
  }
  if (question.length > 300) {
    messages.value.push({ role: 'assistant', text: FALLBACK })
    return
  }
  const uploader = mediaUploader.value
  if (uploader?.hasUploading?.()) {
    message.warning('附件仍在上传中，请稍候再发送')
    return
  }
  // 仅提交已确认上传（READY / UPLOADED）的媒体；解析中的不阻塞提问
  const mediaIds = uploader?.readyMediaIds?.() || []
  // 语音附件的转写文本：转写完成才展示；解析中先带占位，回答出来后本轮即可见
  const transcripts = uploader?.readyTranscripts?.() || []
  // P2 图像/文件理解摘要：视觉降级链/文档解析产出后与附件气泡就近展示
  const insights = uploader?.readyMediaInsights?.() || []
  if (uploader?.hasParsing?.()) {
    message.info('附件正在解析，回答可能稍后补充')
  }
  messages.value.push({ role: 'user', text: question, transcripts, insights })
  draft.value = ''
  loading.value = true
  // 附件已随本条消息提交：清空待发送列表并释放缩略图 objectURL
  uploader?.clearSent?.()
  if (voiceClient) {
    voiceClient.close()
    voiceClient = null
    voiceCallActive.value = false
  }
  stopPlayback()

  // 立即推送占位助手消息对象，支持打字机流式累加与思维链
  const aiMsg = {
    role: 'assistant',
    text: '',
    reasoning: '',
    isThinking: true,
    isStreaming: true,
    thinkingStartTime: Date.now(),
    thinkingDuration: 0,
    sources: [],
    quote: null,
    cacheStatus: '',
    cacheSimilarity: 0,
    cacheLatencyMs: 0,
    source: 'AI',
    ttsBlocked: false,
    ttsAutoSpoken: false
  }
  messages.value.push(aiMsg)
  // 数组里存的是同一目标对象的响应式代理；播报状态必须写代理，气泡上的按钮才会更新
  const aiMsgView = messages.value[messages.value.length - 1]

  if (currentStreamAbort) {
    try { currentStreamAbort.abort() } catch {}
  }
  currentStreamAbort = new AbortController()

  try {
    await supportApi.askStream({
      question,
      visitorToken: visitorToken.value,
      persistConversation: persistConversation.value,
      thinking: true,
      mediaIds,
      signal: currentStreamAbort.signal,
      onMeta: (meta) => {
        if (!meta) return
        if (meta.cacheStatus) aiMsg.cacheStatus = meta.cacheStatus
        if (meta.similarity !== undefined) aiMsg.cacheSimilarity = Number(meta.similarity) || 0
        if (meta.cache_type) aiMsg.cacheStatus = 'HIT-' + String(meta.cache_type).toUpperCase()
        if (meta.cache_similarity !== undefined) aiMsg.cacheSimilarity = Number(meta.cache_similarity) || 0
        if (meta.cache_latency_ms !== undefined) aiMsg.cacheLatencyMs = Number(meta.cache_latency_ms) || 0
        if (meta.kb_hits || meta.sources) aiMsg.sources = meta.kb_hits || meta.sources
        if (meta.quote) aiMsg.quote = meta.quote
        if (meta.source) aiMsg.source = meta.source
        if (meta.visitorToken) visitorToken.value = meta.visitorToken
        if (meta.lastMessageId) lastHistoryMessageId.value = Math.max(lastHistoryMessageId.value, Number(meta.lastMessageId))
      },
      onReasoning: (chunk) => {
        aiMsg.isThinking = true
        aiMsg.reasoning += chunk
      },
      onContent: (chunk) => {
        if (aiMsg.isThinking) {
          aiMsg.isThinking = false
          aiMsg.thinkingDuration = Math.max(0.1, Number(((Date.now() - aiMsg.thinkingStartTime) / 1000).toFixed(1)))
        }
        aiMsg.text += chunk
      },
      onDone: () => {
        aiMsg.isThinking = false
        aiMsg.isStreaming = false
        if (!aiMsg.thinkingDuration && aiMsg.reasoning) {
          aiMsg.thinkingDuration = Math.max(0.1, Number(((Date.now() - aiMsg.thinkingStartTime) / 1000).toFixed(1)))
        }
        loading.value = false
        // SSE 正常结束：最终回答已定型，按开关自动播报
        maybeAutoSpeak(aiMsgView)
      },
      onError: async (err) => {
        console.warn('[SupportChat] askStream fallback to ask():', err)
        if (!aiMsg.text) {
          try {
            const result = await supportApi.ask(question, visitorToken.value, persistConversation.value, mediaIds)
            if (result.visitorToken) visitorToken.value = result.visitorToken
            aiMsg.text = typeof result?.answer === 'string' && result.answer ? result.answer : FALLBACK
            aiMsg.source = result.source || 'KB'
            aiMsg.unavailable = result.unavailable === true
            lastHistoryMessageId.value = Math.max(lastHistoryMessageId.value, Number(result.lastMessageId || 0))
            // 非流式兜底拿到的也是最终回答，同样按开关播报
            maybeAutoSpeak(aiMsgView)
          } catch {
            aiMsg.text = FALLBACK
          }
        }
        aiMsg.isThinking = false
        aiMsg.isStreaming = false
        loading.value = false
      }
    })
  } catch (err) {
    console.warn('[SupportChat] send error:', err)
    if (!aiMsg.text) aiMsg.text = FALLBACK
    aiMsg.isThinking = false
    aiMsg.isStreaming = false
  } finally {
    loading.value = false
    currentStreamAbort = null
    void syncHistory()
  }
}
</script>

<style scoped>
/* 挂在视口右缘中部，避开商品页底部的「联系商家」 */
.support-root { position: fixed; right: 0; top: 42%; z-index: 1200; font-family: inherit; }
.support-launcher { display: flex; flex-direction: column; align-items: center; gap: 8px; border: 1px solid rgba(245,158,11,.72); border-right: 0; border-radius: 14px 0 0 14px; padding: 12px 8px 14px; color: #fff; background: #13233A; box-shadow: -8px 10px 24px rgba(19,35,58,.22); font-size: 13px; font-weight: 650; cursor: pointer; }
.support-launcher-text { writing-mode: vertical-rl; letter-spacing: .18em; line-height: 1; }
.support-launcher:hover { background: #1F3864; }
.support-launcher-avatar { margin: 0; }
/* 聊天窗仍贴右下角，不跟着挂壁按钮一起挪到屏幕中部 */
.support-beam-host { position: fixed; right: 18px; bottom: 18px; width: min(370px, calc(100vw - 28px)); height: min(560px, calc(100vh - 110px)); }
/* BorderBeam 根元素铺满宿主（多根组件不透传 class，需 :deep 撑满） */
.support-beam-host :deep(.bb-wrap) { width: 100%; height: 100%; }
.support-panel { display: flex; flex-direction: column; position: relative; width: 100%; height: 100%; background: #fff; border: 1px solid #e7e5e4; border-radius: 20px; box-shadow: 0 18px 48px rgba(19,35,58,.16); overflow: hidden; }
.support-header { display: flex; align-items: center; gap: 10px; padding: 14px 14px 13px; background: #13233A; color: #fff; }
.support-header-copy { flex: 1 1 auto; min-width: 0; }
.support-header p { display: flex; align-items: center; gap: 6px; margin: 6px 0 0; color: rgba(255,255,255,.72); font-size: 11px; line-height: 1.35; min-width: 0; white-space: nowrap; }
.support-header p > span { flex: 0 0 auto; white-space: nowrap; }
.support-live-dot { width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: #34d399; box-shadow: 0 0 0 3px rgba(52,211,153,.18); }
.support-header-tools { display: flex; align-items: center; gap: 2px; flex: 0 0 auto; }
.support-close { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 0; background: transparent; color: rgba(255,255,255,.82); border-radius: 999px; cursor: pointer; }
.support-close:hover { background: rgba(255,255,255,.08); color: #fff; }
/* 顶栏工具只留图标，说明放在 aria-label / title */
.support-tts-toggle { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; padding: 0; border: 1px solid transparent; border-radius: 999px; background: transparent; color: rgba(255,255,255,.82); cursor: pointer; }
.support-tts-toggle:hover { background: rgba(255,255,255,.08); color: #fff; }
.support-tts-toggle.is-on { color: #F59E0B; background: rgba(245,158,11,.12); }
.support-messages { display: flex; flex: 1; flex-direction: column; align-items: stretch; gap: 14px; overflow-y: auto; padding: 16px 14px 12px; background: #f6f5f3; }
.support-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; padding: 0 0 2px 36px; }
.support-suggestions p { width: 100%; margin: 0; color: #78716c; font-size: 11px; }
.support-suggestions button { border: 1px solid #e7e5e4; border-radius: 999px; padding: 6px 10px; color: #1c1917; background: #fff; font: inherit; font-size: 12px; cursor: pointer; }
.support-suggestions button:hover { border-color: #F59E0B; color: #13233A; background: #fffbeb; }
.support-turn { display: flex; align-items: flex-end; gap: 8px; max-width: 100%; animation: support-arrive .34s cubic-bezier(.2,.7,.2,1); }
.support-turn.is-user { justify-content: flex-end; animation-name: support-send; }
.support-turn-avatar { align-self: flex-end; }
.support-turn.is-thinking .support-turn-avatar { animation: support-avatar-pulse 1.6s ease-in-out infinite; }
.support-message { max-width: min(86%, 280px); padding: 9px 12px; border-radius: 16px 16px 16px 5px; border: 0; background: #fff; box-shadow: 0 1px 2px rgba(19,35,58,.06); overflow-wrap: anywhere; }
.support-message.from-user { border-radius: 16px 16px 5px 16px; background: #13233A; box-shadow: none; }
.support-message.from-support { background: #fff; }
.support-message-label { display: flex; align-items: center; gap: 5px; margin-bottom: 5px; color: #667085; font-size: 10px; font-weight: 700; }
.support-ai-tag { display: inline-flex; align-items: center; padding: 1px 6px; border-radius: 999px; font-size: 10.5px; font-weight: 700; letter-spacing: .02em; color: #1E6EB8; background: rgba(30, 110, 184, .1); border: 1px solid rgba(30, 110, 184, .22); }
.support-tts { margin-left: auto; display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; padding: 0; border: 1px solid #e4e7ec; border-radius: 7px; background: #fff; color: #667085; font-size: 11px; line-height: 1; cursor: pointer; transition: background .16s ease, border-color .16s ease, color .16s ease; }
.support-tts:hover { border-color: #b8c6d8; color: #344054; background: #f5f8fc; }
.support-voice-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border-radius: 999px;
  border: 1px solid transparent;
  background: transparent;
  color: rgba(255,255,255,.82);
  cursor: pointer;
}
.support-voice-toggle:hover,
.support-voice-toggle.is-active {
  background: rgba(245,158,11,.14);
  color: #F59E0B;
  border-color: transparent;
}
.support-voice-bar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px;
  background: #f0f7ff;
  border-top: 1px solid #d8e7f8;
  border-bottom: 1px solid #d8e7f8;
  transition: background .2s ease;
}
.support-voice-bar.state-listening {
  background: #e6f4ff;
  border-color: #91caff;
}
.support-voice-bar.state-playing {
  background: #f6ffed;
  border-color: #b7eb8f;
}
.voice-bar-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #334155;
}
.voice-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
  transition: background .2s ease;
}
.voice-status-dot.is-listening {
  background: #1e6eb8;
  box-shadow: 0 0 0 4px rgba(30, 110, 184, .25);
  animation: pulse-ring 1.5s infinite;
}
.voice-status-dot.is-playing {
  background: #52c41a;
  box-shadow: 0 0 0 4px rgba(82, 196, 26, .25);
  animation: pulse-ring 1.5s infinite;
}
@keyframes pulse-ring {
  0% { transform: scale(.95); }
  50% { transform: scale(1.15); }
  100% { transform: scale(.95); }
}
.voice-status-sentence {
  color: #52c41a;
  font-style: italic;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}
.voice-bar-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.voice-ctrl-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all .16s ease;
}
.voice-ctrl-btn.btn-mic {
  background: #1e6eb8;
  color: #fff;
}
.voice-ctrl-btn.btn-mic:hover {
  background: #155799;
}
.voice-ctrl-btn.btn-stop {
  background: #ff4d4f;
  color: #fff;
}
.voice-ctrl-btn.btn-interrupt {
  background: #faad14;
  color: #fff;
}
.voice-ctrl-btn.btn-close {
  background: #f1f5f9;
  border-color: #cbd5e1;
  color: #475569;
  margin-left: auto;
}
:global([data-theme='dark']) .support-voice-toggle {
  background: transparent;
  border-color: transparent;
  color: rgba(255,255,255,.82);
}
:global([data-theme='dark']) .support-voice-toggle.is-active {
  background: rgba(245,158,11,.14);
  color: #F59E0B;
}
:global([data-theme='dark']) .support-voice-bar {
  background: #16253b;
  border-color: #2b3e5b;
}
:global([data-theme='dark']) .voice-bar-status {
  color: #e2e8f0;
}
:global([data-theme='dark']) .voice-ctrl-btn.btn-close {
  background: #1f2c40;
  border-color: #3b4e6b;
  color: #cbd5e1;
}

.support-tts.is-on { border-color: #9fc0e8; color: #1e6eb8; background: #eef5ff; }
.support-message p { margin: 0; color: #1c1917; font-size: 13px; line-height: 1.65; white-space: pre-wrap; }
.support-message.from-user p { color: #fff; }
.support-message.is-streaming .support-body::after { content: ""; display: inline-block; width: 1.5px; height: .95em; margin-left: 2px; background: #F59E0B; vertical-align: text-bottom; animation: support-caret 1s steps(1) infinite; }

/* 语音消息转写卡片：用户气泡内轻蓝衬底（Apple 风 + 小清新蓝，lucide AudioLines，禁 emoji） */
.support-transcripts { display: flex; flex-direction: column; gap: 6px; margin: 2px 0 6px; }
.support-transcript-card { padding: 6px 9px; border: 1px solid rgba(30, 110, 184, .16); border-radius: 10px; background: rgba(255, 255, 255, .72); }
.support-transcript-head { display: inline-flex; align-items: center; gap: 4px; margin-bottom: 3px; color: #1e6eb8; font-size: 10px; font-weight: 700; letter-spacing: .02em; }
.support-transcript-text { margin: 0 !important; color: #33415c !important; font-size: 11.5px !important; line-height: 1.6 !important; }
/* P2 partial 截断警示行：琥珀色（Apple 风警示），lucide FileWarning，禁 emoji */
.support-transcript-text.is-partial { display: flex; align-items: flex-start; gap: 4px; margin-top: 3px !important; color: #b45309 !important; font-size: 10.5px !important; }
[data-theme='dark'] .support-transcript-card { background: rgba(127, 182, 232, .08); border-color: rgba(127, 182, 232, .22); }
[data-theme='dark'] .support-transcript-text { color: #c8d6e8 !important; }
/* 自动播放被浏览器拦截后的手动入口，紧跟在正文下方 */
.support-tts-fallback { margin-top: 6px; padding: 3px 9px; border: 1px solid #d6e4f5; border-radius: 999px; background: #f3f7fc; color: #1e6eb8; font: inherit; font-size: 11px; cursor: pointer; }
.support-tts-fallback:hover { background: #eaf2fb; border-color: #9fc0e8; }
.support-thinking-hint { display: inline-flex; align-items: center; gap: 6px; margin: 0; color: #64748b; font-size: 11px; font-style: italic; }
.thinking-spinner { display: inline-block; width: 10px; height: 10px; border: 1.5px solid rgba(100, 116, 139, 0.3); border-top-color: #64748b; border-radius: 50%; animation: support-spin 0.8s linear infinite; }
@keyframes support-spin { to { transform: rotate(360deg); } }
.support-message :deep(.ai-quote-card) { margin-top: 8px; }
.support-message :deep(.ai-source-citations) { margin-top: 8px; }
.support-compose { position: relative; padding: 11px 12px 9px; border-top: 1px solid #e4e7ec; background: #fff; }
.support-compose.is-dragover { border-top-color: #9fc0e8; box-shadow: inset 0 0 0 2px rgba(30, 110, 184, .18); }
.support-drop-hint { position: absolute; inset: 6px; display: flex; align-items: center; justify-content: center; border: 1px dashed #9fc0e8; border-radius: 10px; background: rgba(234, 245, 254, .9); color: #1e6eb8; font-size: 12px; font-weight: 600; pointer-events: none; }
.support-compose textarea { display: block; box-sizing: border-box; width: 100%; resize: none; border: 1px solid #d0d5dd; border-radius: 10px; padding: 10px; outline: none; color: #182230; background: #fff; font: inherit; font-size: 12px; line-height: 1.6; }
.support-compose textarea:focus { border-color: #F59E0B; box-shadow: 0 0 0 3px rgba(245,158,11,.16); }
.support-compose-footer { display: flex; align-items: center; gap: 8px; margin-top: 8px; color: #78716c; font-size: 11px; }
.support-count { margin-left: auto; }
.support-send { padding: 7px 16px; border: 0; border-radius: 999px; color: #1c1917; background: #F59E0B; font-size: 12px; font-weight: 700; cursor: pointer; }
.support-send:disabled { opacity: .4; cursor: not-allowed; }
.support-consent { display:flex; align-items:flex-start; gap:7px; color:#344054; font-size:10px; line-height:1.5; cursor:pointer; }
.support-consent input { margin-top:2px; accent-color:#13233a; }
.support-scope-note p { margin:5px 0 0; }
.support-launcher { position:relative; overflow:hidden; background:#13233A; color:#fff; transition:transform .18s ease,box-shadow .18s ease,opacity .18s ease,background .18s ease; }
/* 面板打开时隐藏悬浮球，避免同屏出现两个"客服"入口 */
.support-launcher.is-open { opacity: 0; pointer-events: none; transform: scale(.9); }
.support-launcher::after { content:""; position:absolute; top:-80%; left:-40%; width:40%; height:260%; transform:rotate(28deg); background:linear-gradient(90deg,transparent,rgba(255,255,255,.14),transparent); animation:support-sheen 6s ease-in-out infinite; pointer-events:none; }
.support-launcher:hover { transform:translateX(-3px); box-shadow:-10px 12px 28px rgba(19,35,58,.22); }
.support-message { transition: transform .16s ease; }
@keyframes support-sheen { 0%,70%,100% { left:-45%; } 30% { left:130%; } }
@keyframes support-arrive { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
@keyframes support-send { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
@keyframes support-caret { 0%, 45% { opacity: 1; } 50%, 100% { opacity: 0; } }
@keyframes support-avatar-pulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.06); } }
@media (prefers-reduced-motion:reduce) {
  .support-launcher,.support-message,.support-turn { transition:none; animation:none; }
  .support-launcher::after, .support-message.is-streaming .support-body::after, .support-turn.is-thinking .support-turn-avatar { animation:none; }
}
.support-scope-note { padding: 0 12px 12px; color: #667085; background: #fff; font-size: 10px; line-height: 1.5; }
.support-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }

/* ===== 转人工（P1-20）：按钮 + 人工会话态横幅（token 化，与 theme.css 对齐） ===== */
.support-human-btn { display: inline-flex; align-items: center; gap: 4px; padding: 6px 11px; border: 1px solid var(--ll-border-strong, #d5dae2); border-radius: 999px; color: var(--ll-primary, #1E6EB8); background: var(--ll-card, #fff); font: inherit; font-size: 11px; font-weight: 600; cursor: pointer; transition: background var(--ll-transition-fast, .2s) ease, border-color var(--ll-transition-fast, .2s) ease, color var(--ll-transition-fast, .2s) ease; }
.support-human-btn:hover { background: #f3f7fc; border-color: var(--ll-primary, #1E6EB8); }
.support-human-btn:disabled { opacity: .5; cursor: not-allowed; }
.support-human-btn.is-back { color: var(--ll-muted, #64748B); }
.support-human-btn.is-back:hover { color: var(--ll-ink, #0F172A); }
.support-human-banner { margin: 0 12px; padding: 10px 13px; border: 1px solid var(--ll-border, #e4e7ec); border-left: 3px solid var(--ll-success, #059669); border-radius: 10px; background: var(--ll-card-soft, #f8f9fb); }
.human-banner-head { display: flex; align-items: center; gap: 6px; color: var(--ll-success, #059669); font-size: 12px; font-weight: 700; }
.human-banner-head strong { color: var(--ll-ink, #0F172A); }
.human-banner-note { margin: 5px 0 0; color: var(--ll-ink, #0F172A); font-size: 11.5px; line-height: 1.6; }
.human-banner-queue { margin: 3px 0 0; color: var(--ll-muted, #64748B); font-size: 10.5px; line-height: 1.6; }
[data-theme='dark'] .support-human-btn { background: var(--ll-card, #16202e); border-color: var(--ll-border-strong, #31405a); color: #9fc0e8; }
[data-theme='dark'] .support-human-btn:hover { background: #1f2c40; border-color: #4a5f80; color: #fff; }
[data-theme='dark'] .support-human-btn.is-back { color: #8fa0b5; }
[data-theme='dark'] .support-human-btn.is-back:hover { color: #e8edf5; }
[data-theme='dark'] .support-human-banner { background: #16253b; border-color: #2b3e5b; border-left-color: var(--ll-success, #10b981); }
[data-theme='dark'] .human-banner-head strong { color: #e8edf5; }
[data-theme='dark'] .human-banner-head { color: var(--ll-success, #10b981); }
[data-theme='dark'] .human-banner-note { color: #c8d6e8; }
[data-theme='dark'] .human-banner-queue { color: #8fa0b5; }
@media (max-width: 520px) {
  .support-human-btn span { display: none; }
  .support-human-btn { padding: 6px 8px; }
  .support-human-banner { margin: 0 10px 8px; }
}

/* 深色主题：令牌与 styles/theme.css 的 [data-theme='dark'] 保持一致；浅色值不受影响 */
:global([data-theme='dark']) .support-panel { background: #121820; border-color: #263349; }
:global([data-theme='dark']) .support-header { background: #0B1220; color: #fff; }
:global([data-theme='dark']) .support-header p { color: rgba(255,255,255,.68); }
:global([data-theme='dark']) .support-close { color: rgba(255,255,255,.82); }
:global([data-theme='dark']) .support-close:hover { background: rgba(255, 255, 255, .08); }
:global([data-theme='dark']) .support-messages { background: #0d1420; }
:global([data-theme='dark']) .support-message { background: #1a2330; border-color: transparent; box-shadow: none; }
:global([data-theme='dark']) .support-message.from-user { background: #1F3864; }
:global([data-theme='dark']) .support-message p { color: #e8edf5; }
:global([data-theme='dark']) .support-message.from-user p { color: #fff; }
:global([data-theme='dark']) .support-message-label { color: #8fa0b5; }
:global([data-theme='dark']) .support-ai-tag { color: #9fc0e8; background: rgba(159, 192, 232, .14); border-color: rgba(159, 192, 232, .3); }
:global([data-theme='dark']) .support-suggestions p { color: #8fa0b5; }
:global([data-theme='dark']) .support-suggestions button { background: #16202e; border-color: #31405a; color: #d7e1ef; }
:global([data-theme='dark']) .support-suggestions button:hover { background: #1f2c40; border-color: #4a5f80; color: #fff; }
:global([data-theme='dark']) .support-tts { background: #1a2536; border-color: #31405a; color: #9fb0c6; }
:global([data-theme='dark']) .support-tts-toggle { background: transparent; border-color: transparent; color: rgba(255,255,255,.82); }
:global([data-theme='dark']) .support-tts-toggle:hover { background: rgba(255,255,255,.08); color: #fff; }
:global([data-theme='dark']) .support-tts-toggle.is-on { background: rgba(245,158,11,.14); color: #F59E0B; }
:global([data-theme='dark']) .support-tts-fallback { background: #1a2536; border-color: #31405a; color: #9fc0e8; }
:global([data-theme='dark']) .support-tts-fallback:hover { background: #1f2c40; border-color: #4a5f80; }
:global([data-theme='dark']) .support-compose { background: #16202e; border-top-color: #263349; }
:global([data-theme='dark']) .support-compose textarea { background: #0f1725; border-color: #31405a; color: #e8edf5; }
:global([data-theme='dark']) .support-compose textarea::placeholder { color: #6f819a; }
:global([data-theme='dark']) .support-compose-footer { color: #8fa0b5; }
:global([data-theme='dark']) .support-drop-hint { background: rgba(23, 38, 58, .92); border-color: #4a5f80; color: #9fc0e8; }
:global([data-theme='dark']) .support-scope-note { background: #16202e; color: #8fa0b5; }
:global([data-theme='dark']) .support-consent { color: #b6c4d6; }
:global([data-theme='dark']) .thinking-spinner { border-color: rgba(143, 160, 181, .3); border-top-color: #8fa0b5; }
@media (max-width: 520px) {
  /* 窄屏仍挂在右缘，只略收一档，避免再变成右下角圆钮挡住「联系商家」 */
  .support-root { top: 38%; }
  .support-launcher { padding: 10px 6px 12px; }
  .support-launcher-text { letter-spacing: .12em; font-size: 12px; }
  /* 面板：底部抽屉式（全宽 + 76vh 上限），贴合手机聊天习惯而非浮窗遮内容 */
  .support-beam-host { position: fixed; left: 0; right: 0; bottom: 0; width: 100vw;
    height: min(76vh, 76dvh, 620px); border-radius: 18px 18px 0 0; }
  .support-panel { border-radius: 18px 18px 0 0; border-left: 0; border-right: 0; border-bottom: 0;
    padding-bottom: env(safe-area-inset-bottom, 0); }
  .support-header { padding: 13px 14px; }
  /* 窄屏只留喇叭图标，避免标题与开关互相挤压 */
  .support-tts-toggle span { display: none; }
  .support-tts-toggle { padding: 4px 6px; }
  .support-messages { padding: 12px 11px; }
  .support-compose { padding: 9px 10px 7px; }
  .support-compose textarea { min-height: 0; }
  /* 底部说明在手机端收为一行短标签，长免责声明隐藏 */
  .support-scope-note { padding: 0 10px 9px; }
  .consent-full { display: none; }
  .consent-short { display: inline; }
  .consent-note { display: none; }
}
@media (min-width: 521px) {
  .consent-short { display: none; }
}
</style>
