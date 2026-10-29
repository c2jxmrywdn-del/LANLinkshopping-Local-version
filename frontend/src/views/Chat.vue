<template>
  <div class="chat-page">
    <div class="chat-shell" :class="{ fullscreen: isMobile }">
      <!-- 头部：商家 + 咨询商品 -->
      <header class="chat-head">
        <button type="button" class="chat-back" aria-label="返回" @click="router.back()">
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
        <div class="chat-peer">
          <b>{{ session.merchantName || '商家' }}</b>
          <small v-if="session.prodTitle">咨询商品：{{ session.prodTitle }}</small>
          <small v-else>在线对话 · 平台登录用户</small>
        </div>
        <span v-if="session.prodId" class="chat-prod-link" @click="$router.push('/product/' + session.prodId)">查看商品 →</span>
      </header>

      <!-- 消息流 -->
      <div ref="listRef" class="chat-body">
        <div v-if="loading" class="chat-state">加载中…</div>
        <template v-else>
          <div v-for="(m, i) in messages" :key="m.id" class="msg" :class="m.senderRole === 'buyer' ? 'mine' : 'theirs'">
            <span v-if="m.senderRole !== 'buyer'" class="msg-avatar" aria-hidden="true">
              <Store :size="15" :stroke-width="2" />
            </span>
            <div class="msg-main">
              <div v-if="isMedia(m)" class="msg-bubble mm-bubble">
                <MessageMedia :msg="m" />
              </div>
              <p v-else class="msg-bubble">{{ m.content }}</p>
              <small v-if="showTime(i)" class="msg-time">{{ fmt(m.createTime) }}</small>
            </div>
          </div>
          <div v-if="sending" class="msg theirs">
            <span class="msg-avatar" aria-hidden="true"><Store :size="15" :stroke-width="2" /></span>
            <div class="msg-main typing" aria-label="对方正在输入">
              <span></span><span></span><span></span>
            </div>
          </div>
          <!-- 媒体上传条：独立临时节点（不进 messages 数组、无乐观 id），与 typing 三点分离 -->
          <div v-if="uploading" class="msg mine">
            <div class="msg-main">
              <div class="upload-card">
                <span class="upload-name">{{ uploading.name }}</span>
                <a-progress :percent="uploading.percent" size="small" :show-info="true" />
              </div>
            </div>
          </div>
          <div v-if="!messages.length" class="chat-empty">
            <span class="empty-ico"><MessageCircle :size="30" :stroke-width="1.6" /></span>
            <p>开始与商家对话：可咨询商品规格、库存、批发价与交付方式。</p>
          </div>
        </template>
      </div>

      <!-- 输入区：表情/附件/录音 + 胶囊输入 + 圆形发送钮（21st.dev Prompt Input 模式）；
           录音态整行切换为 红色脉冲圆点 + 秒数 + 取消/发送 -->
      <form class="chat-compose" @submit.prevent="send">
        <template v-if="recording">
          <span class="rec-dot" aria-hidden="true"></span>
          <b class="rec-time">{{ recSeconds }}″</b>
          <span class="rec-fill" aria-hidden="true"></span>
          <button type="button" class="rec-cancel" aria-label="取消录音" @click="cancelRecord">
            <Trash2 :size="17" :stroke-width="2" />
          </button>
          <button type="button" class="rec-send" aria-label="发送语音" @click="sendRecord">
            <Send :size="16" :stroke-width="2.2" />
          </button>
        </template>
        <template v-else>
          <button type="button" class="emoji-btn" :class="{ on: showEmoji }" aria-label="插入表情"
                  @click="showEmoji = !showEmoji">
            <Smile :size="19" :stroke-width="2" />
          </button>
          <button type="button" class="attach-btn" :disabled="busy" aria-label="发送图片或文件" @click="pickFile">
            <Paperclip :size="18" :stroke-width="2" />
          </button>
          <button type="button" class="mic-btn" :disabled="busy" aria-label="录制语音" @click="startRecord">
            <Mic :size="18" :stroke-width="2" />
          </button>
          <input ref="fileInputRef" type="file" hidden :accept="acceptTypes" @change="onPickFile" />
          <EmojiPicker v-if="showEmoji" @select="insertEmoji" @close="showEmoji = false" />
          <textarea ref="taRef" v-model="draft" rows="1" maxlength="500" placeholder="输入消息（最多 500 字）"
                    :disabled="busy" @keydown.enter.exact="onEnter" />
          <button type="submit" class="send-btn" :disabled="busy || !draft.trim()" aria-label="发送">
            <Send :size="16" :stroke-width="2.2" />
          </button>
        </template>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { MessageCircle, Mic, Paperclip, Send, Store, Smile, Trash2 } from 'lucide-vue-next'
import { chatApi } from '../api'
import { isMobileViewport } from '../utils/mobileAdapter'
import { pickKind, validateChatFile } from '../utils/chatMedia'
import { useVoiceRecorder } from '../utils/useVoiceRecorder'
import EmojiPicker from '../components/chat/EmojiPicker.vue'
import MessageMedia from '../components/chat/MessageMedia.vue'

const route = useRoute()
const router = useRouter()
const session = ref({})
const messages = ref([])
const draft = ref('')
const loading = ref(true)
const sending = ref(false)
const listRef = ref(null)
const taRef = ref(null)
const showEmoji = ref(false)
const isMobile = ref(isMobileViewport())
let mobileMq = null

// ===== 媒体发送（附件/录音）=====
const uploading = ref(null) // { name, percent }：上传条独立于消息数组渲染
const fileInputRef = ref(null)
// 图片 + 文档 MIME（语音走录音入口，不进附件选择器）
const acceptTypes = 'image/jpeg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif,'
  + '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.zip'
const busy = computed(() => sending.value || !!uploading.value)

// 语音录音（权限/不支持时经 onError 中文提示）
const recorder = useVoiceRecorder({ onError: (m) => message.error(m) })
const recording = recorder.recording
const recSeconds = recorder.seconds

/** 媒体消息（非文本）走 MessageMedia 渲染 */
function isMedia(m) {
  return !!m && !!m.msgType && m.msgType !== 'text'
}

let pollTimer = null
let lastId = 0
let stopped = false

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(5, 16) : ''
}

/** 时间戳降噪：与上一条不同发送方或时间不同才显示 */
function showTime(i) {
  if (i === 0) return true
  const prev = messages.value[i - 1]
  const cur = messages.value[i]
  return prev.senderRole !== cur.senderRole || fmt(prev.createTime) !== fmt(cur.createTime)
}

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

/** 去重后按 id 顺序追加（发送与轮询撞车时避免重复气泡） */
function appendMessages(list) {
  if (!Array.isArray(list) || !list.length) return false
  const known = new Set(messages.value.map(m => m.id))
  const fresh = list.filter(m => !known.has(m.id)).sort((a, b) => a.id - b.id)
  if (!fresh.length) return false
  messages.value.push(...fresh)
  lastId = Math.max(lastId, ...fresh.map(m => m.id))
  scrollBottom()
  return true
}

/** 拉取会话与增量消息（sessionId 显式传入，避免路由 replace 异步期间 params 陈旧） */
async function load(sessionId, initial = false) {
  if (stopped || !sessionId) return
  try {
    const data = await chatApi.messages(sessionId, initial ? 0 : lastId)
    if (data.session) session.value = data.session
    appendMessages(data.messages || [])
    if (initial) scrollBottom()
  } catch (e) { /* 拦截器已提示 */ }
}

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value) return
  const sessionId = route.params.sessionId
  sending.value = true
  try {
    const r = await chatApi.send(sessionId, text)
    if (r?.message) {
      appendMessages([r.message])
      draft.value = ''
    }
  } catch (e) { /* 拦截器已提示 */ }
  sending.value = false
}

// ===== 媒体发送：上传成功后立即以服务端返回的 message 为准追加（无乐观插入） =====

function pickFile() {
  if (busy.value || recording.value) return
  fileInputRef.value?.click()
}

/** 附件选择 → 前端预检（扩展名+MIME+体积）→ 上传 → 媒体 payload 发送 */
async function onPickFile(e) {
  const input = e.target
  const file = input.files && input.files[0]
  input.value = '' // 复位以支持连续选择同一文件
  if (!file || busy.value) return
  const kind = pickKind(file)
  const err = validateChatFile(file, kind)
  if (err) { message.error(err); return }
  await sendMedia(file, kind)
}

/** 上传媒体并以媒体 payload 发送；上传进度条独立于 messages 渲染 */
async function sendMedia(file, kind, voiceSeconds) {
  if (busy.value || !route.params.sessionId) return
  uploading.value = { name: file.name, percent: 0 }
  try {
    const media = await chatApi.upload(file, kind, (p) => { if (uploading.value) uploading.value.percent = p })
    const payload = { msgType: kind, mediaUrl: media.url, mediaName: media.name, mediaSize: media.size }
    if (kind === 'voice') payload.voiceSeconds = Math.max(1, Math.round(voiceSeconds || 0))
    const r = await chatApi.send(route.params.sessionId, payload)
    if (r?.message) appendMessages([r.message])
  } catch (e) { /* 拦截器已提示 */ }
  uploading.value = null
}

async function startRecord() {
  if (busy.value) return
  try {
    await recorder.start()
  } catch { /* onError 已提示 */ }
}

/** 发送语音：stop 取回录音 → webm/m4a 命名 → voice kind 上传发送 */
async function sendRecord() {
  if (!recording.value) return
  const result = await recorder.stop()
  if (!result || !result.blob || !result.blob.size) return
  const seconds = Math.max(1, Math.round(result.seconds || 0))
  const isMp4 = /mp4/i.test(result.blob.type || '')
  const file = new File([result.blob], `语音_${seconds}s.${isMp4 ? 'm4a' : 'webm'}`,
    { type: result.blob.type || 'audio/webm' })
  await sendMedia(file, 'voice', seconds)
}

function cancelRecord() {
  recorder.cancel()
}

/** IME 组词期间的 Enter（isComposing/keyCode 229）不发送，防未上屏拼音误发 */
function onEnter(e) {
  if (e.isComposing || e.keyCode === 229) return
  send()
}

/** 表情插入到光标处（emoji 占 2 个 UTF-16 码元，按码元切片/恢复光标即正确） */
function insertEmoji(ch) {
  const ta = taRef.value
  const cur = draft.value
  const start = ta && ta.selectionStart != null ? ta.selectionStart : cur.length
  const end = ta && ta.selectionEnd != null ? ta.selectionEnd : start
  draft.value = cur.slice(0, start) + ch + cur.slice(end)
  nextTick(() => {
    if (!ta) return
    ta.focus()
    const pos = start + ch.length
    ta.setSelectionRange(pos, pos)
  })
}

/** 页面不可见时暂停轮询，回来自动恢复（避免与全局轮询叠加打爆接口） */
function onVisibility() {
  if (document.hidden) {
    stopPolling()
  } else if (route.params.sessionId) {
    startPolling(route.params.sessionId)
    load(route.params.sessionId, false)
  }
}

function startPolling(sessionId) {
  if (pollTimer || stopped || !sessionId) return
  pollTimer = window.setInterval(() => load(sessionId, false), 4000)
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

onMounted(async () => {
  // 响应式断点：matchMedia 变化实时更新（原 computed 无依赖只求值一次，跨断点不更新）
  if (typeof window.matchMedia === 'function') {
    mobileMq = window.matchMedia('(max-width: 767px)')
    isMobile.value = mobileMq.matches
    mobileMq.addEventListener?.('change', e => { isMobile.value = e.matches })
  }
  // 带 merId+prodId 进入：先 find-or-create，await replace 保证 params 就绪再拉消息
  if (route.query.merId) {
    try {
      const s = await chatApi.start(Number(route.query.merId), route.query.prodId ? Number(route.query.prodId) : null)
      await router.replace('/chat/' + s.sessionId)
      await load(s.sessionId, true)
      startPolling(s.sessionId)
    } catch (e) { /* 拦截器已提示 */ }
    if (!stopped) loading.value = false
  } else {
    await load(route.params.sessionId, true)
    if (!stopped) {
      loading.value = false
      startPolling(route.params.sessionId)
    }
  }
  document.addEventListener('visibilitychange', onVisibility)
  scrollBottom()
})

onBeforeUnmount(() => {
  stopped = true
  stopPolling()
  mobileMq?.removeEventListener?.('change', () => {})
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.chat-page { max-width: 920px; margin: 0 auto; padding: 18px 12px 24px; min-height: calc(100vh - 80px);
  display: flex; flex-direction: column; }
.chat-shell { flex: 1; display: flex; flex-direction: column; min-height: 560px;
  background: var(--ll-card, #fff); border: 1px solid var(--ll-border, #e5e2da);
  border-radius: 18px; overflow: hidden; box-shadow: 0 10px 34px rgba(19, 35, 58, .07); }

.chat-head { display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  background: var(--ll-navy, #13233a); color: #fff; }
.chat-back { border: 0; background: rgba(255,255,255,.1); color: #fff; width: 30px; height: 30px;
  border-radius: 9px; display: grid; place-items: center; cursor: pointer; flex-shrink: 0; }
.chat-peer { flex: 1; min-width: 0; }
.chat-peer b { display: block; font-size: 14.5px; }
.peer-status { display: flex; align-items: center; gap: 6px; margin-top: 2px; color: rgba(255,255,255,.62); font-size: 11px; }
.online-dot { width: 7px; height: 7px; border-radius: 50%; background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, .18); }
.chat-peer small:not(.peer-status) { display: block; margin-top: 2px; color: rgba(255,255,255,.62);
  font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-prod-link { flex-shrink: 0; color: #bae6fd; font-size: 12px; cursor: pointer; }
.chat-prod-link:hover { text-decoration: underline; }

.chat-body { flex: 1; display: flex; flex-direction: column; gap: 10px; overflow-y: auto;
  padding: 16px 14px; background: var(--ll-page, #f5f6f8); min-height: 300px; }
.chat-state { text-align: center; color: var(--ll-muted, #64748b); font-size: 12px; padding: 24px 0; }
/* 21st.dev ChatBubble 模式：对方带头像、同侧消息收拢分组 */
.msg { display: flex; gap: 8px; align-items: flex-end; max-width: 76%; }
.msg.mine { align-self: flex-end; flex-direction: row-reverse; }
.msg.theirs { align-self: flex-start; }
.msg + .msg.mine { margin-top: -4px; }
.msg + .msg.theirs { margin-top: -4px; }
.msg-avatar { width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0; display: grid; place-items: center;
  color: #fff; background: linear-gradient(135deg, #13233a, #34577a); }
.msg-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.msg.mine .msg-main { align-items: flex-end; }
.msg-bubble { margin: 0; padding: 9px 13px; border-radius: 14px; font-size: 13.5px; line-height: 1.65;
  overflow-wrap: anywhere; white-space: pre-wrap; color: #182230;
  background: #fff; border: 1px solid var(--ll-border, #e5e2da);
  box-shadow: 0 1px 4px rgba(19, 35, 58, .05);
  animation: msgIn .28s cubic-bezier(.22, 1, .36, 1);
  /* 表情统一用 LL Emoji（Noto 子集）字形，未覆盖字符回落系统 emoji 字体 */
  font-family: 'LL Emoji', -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif; }
.msg.mine .msg-bubble { background: linear-gradient(135deg, #0ea5e9, #0284c7); border-color: transparent;
  color: #fff; border-bottom-right-radius: 5px; }
.msg.theirs .msg-bubble { border-bottom-left-radius: 5px; }
@keyframes msgIn { from { opacity: 0; transform: translateY(6px) scale(.98); } to { opacity: 1; transform: none; } }
.msg-time { color: #a1a1a6; font-size: 10px; opacity: .75; }

/* 打字指示（21st.dev Thinking Indicator 模式） */
.typing { display: inline-flex; gap: 4px; padding: 12px 14px; background: #fff;
  border: 1px solid var(--ll-border, #e5e2da); border-radius: 14px; border-bottom-left-radius: 5px; }
.typing span { width: 6px; height: 6px; border-radius: 50%; background: #94a3b8;
  animation: typingBlink 1.2s infinite; }
.typing span:nth-child(2) { animation-delay: .18s; }
.typing span:nth-child(3) { animation-delay: .36s; }
@keyframes typingBlink { 0%, 60%, 100% { opacity: .3; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }

.chat-empty { text-align: center; padding: 40px 16px; color: var(--ll-muted, #64748b); }
.empty-ico { display: inline-grid; place-items: center; width: 64px; height: 64px; border-radius: 20px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd); color: #0369a1; margin-bottom: 12px; }
.chat-empty p { margin: 0; font-size: 12.5px; line-height: 1.7; }

.chat-compose { position: relative; display: flex; align-items: flex-end; gap: 9px; padding: 11px 13px;
  border-top: 1px solid var(--ll-border, #e5e2da); background: var(--ll-card, #fff); }
.emoji-btn { border: 1px solid var(--ll-border, #e5e2da); background: var(--ll-page, #f5f6f8);
  color: var(--ll-muted, #64748b); width: 38px; height: 38px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; cursor: pointer;
  transition: color .15s ease, border-color .15s ease, background .15s ease; }
.emoji-btn:hover, .emoji-btn.on { color: #0284c7; border-color: #38bdf8; background: rgba(56, 189, 248, .08); }
.chat-compose textarea { flex: 1; min-width: 0; resize: none; border: 1px solid var(--ll-border, #e5e2da);
  border-radius: 12px; padding: 10px 13px; font: inherit; font-size: 13px; line-height: 1.5;
  font-family: 'LL Emoji', -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  max-height: 96px; outline: none; background: var(--ll-page, #f5f6f8); color: var(--ll-ink, #0f172a); }
.chat-compose textarea:focus { border-color: #38bdf8; box-shadow: 0 0 0 3px rgba(56, 189, 248, .15); }
.chat-compose button { border: 0; border-radius: 12px; background: linear-gradient(135deg, #0ea5e9, #0284c7);
  color: #fff; font: inherit; font-size: 13px; font-weight: 700; padding: 10px 20px; cursor: pointer; }
.chat-compose button:disabled { opacity: .45; cursor: not-allowed; }
/* 附件/录音圆钮：38px 与 emoji-btn 视觉一致（置于 .chat-compose button 之后，用类选择器覆盖其胶囊样式） */
.chat-compose .attach-btn, .chat-compose .mic-btn { width: 38px; height: 38px; padding: 0; border-radius: 50%;
  border: 1px solid var(--ll-border, #e5e2da); background: var(--ll-page, #f5f6f8);
  color: var(--ll-muted, #64748b); display: grid; place-items: center; flex-shrink: 0; font-size: 0;
  transition: color .15s ease, border-color .15s ease, background .15s ease; }
.chat-compose .attach-btn:hover:not(:disabled), .chat-compose .mic-btn:hover:not(:disabled) {
  color: #0284c7; border-color: #38bdf8; background: rgba(56, 189, 248, .08); }
.chat-compose .attach-btn:disabled, .chat-compose .mic-btn:disabled { opacity: .45; cursor: not-allowed; }

/* 录音态：红色脉冲圆点 + 秒数 + 取消/发送（compose 行整体切换，textarea 隐藏即等效禁用） */
.rec-dot { width: 12px; height: 12px; border-radius: 50%; background: #ef4444; align-self: center; flex-shrink: 0;
  animation: recPulse 1.2s ease-in-out infinite; }
@keyframes recPulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, .38); } 50% { box-shadow: 0 0 0 7px rgba(239, 68, 68, 0); } }
.rec-time { align-self: center; font-size: 14px; font-weight: 700; color: #ef4444;
  font-variant-numeric: tabular-nums; flex-shrink: 0; }
.rec-fill { flex: 1; align-self: center; }
.chat-compose .rec-cancel, .chat-compose .rec-send { width: 38px; height: 38px; padding: 0; border-radius: 50%;
  flex-shrink: 0; display: grid; place-items: center; cursor: pointer; font-size: 0; }
.chat-compose .rec-cancel { border: 1px solid rgba(239, 68, 68, .45); background: transparent; color: #ef4444; }
.chat-compose .rec-cancel:hover { background: rgba(239, 68, 68, .08); }
.chat-compose .rec-send { border: 0; color: #fff; background: linear-gradient(135deg, #ef4444, #dc2626);
  box-shadow: 0 2px 8px rgba(239, 68, 68, .3); }

/* 上传条：临时节点（不进 messages），文件名 + 进度 */
.upload-card { display: flex; flex-direction: column; gap: 2px; min-width: 220px; padding: 10px 13px;
  background: #fff; border: 1px solid var(--ll-border, #e5e2da); border-radius: 14px;
  box-shadow: 0 1px 4px rgba(19, 35, 58, .05); animation: msgIn .28s cubic-bezier(.22, 1, .36, 1); }
.upload-name { font-size: 12px; color: var(--ll-ink, #0f172a);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 媒体气泡：替代文本 p，保持两侧气泡底色；内边距收紧让图片/卡片贴近边缘 */
.msg-bubble.mm-bubble { padding: 4px; line-height: 1.4; }

/* 手机：全屏聊天（贴合移动 IM 习惯）。导航头部高度随内容变化（两行折叠），
   不用固定 offset：输入区 sticky 贴视口底，消息随页面滚动，任意头部高度都成立 */
@media (max-width: 600px) {
  .chat-page { padding: 0; min-height: calc(100vh - 96px); }
  /* overflow:hidden（桌面裁圆角用）会让子孙 sticky 失效，移动端圆角为 0 必须放开 */
  .chat-shell { border: 0; border-radius: 0; box-shadow: none; min-height: calc(100dvh - 96px); overflow: visible; }
  .chat-head { padding: 12px 13px; }
  .chat-body { padding: 13px 10px; }
  .msg { max-width: 86%; }
  .chat-compose { position: sticky; bottom: 0; z-index: 5; gap: 6px;
    padding: 9px 10px calc(9px + env(safe-area-inset-bottom)); }
  /* 390px 宽下 4 钮 + textarea 可用：圆钮缩至 34px、发送钮收窄 */
  .chat-compose .attach-btn, .chat-compose .mic-btn,
  .chat-compose .rec-cancel, .chat-compose .rec-send { width: 34px; height: 34px; }
  .chat-compose .send-btn { padding: 9px 14px; }
  .upload-card { min-width: 180px; }
}
</style>
