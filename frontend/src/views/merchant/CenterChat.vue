<template>
  <div class="cc-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>客户消息</h1>
        <p>买家从商品页发起的咨询在此收发，未读消息会显示在侧栏角标。</p>
      </div>
    </section>

    <div class="cc-shell" :class="{ 'detail-open': activeId }">
      <!-- 会话列表 -->
      <div class="cc-list" :class="{ 'hide-on-mobile': activeId && isNarrow }">
        <div class="cc-list-head">
          <span>全部会话</span>
          <button type="button" class="cc-refresh" @click="load">刷新</button>
        </div>
        <div class="cc-rows" v-if="sessions.length">
          <button v-for="s in sessions" :key="s.sessionId" type="button" class="cc-row"
                  :class="{ on: activeId === s.sessionId }" @click="openSession(s)">
            <span class="cc-avatar" aria-hidden="true"><User :size="17" :stroke-width="2" /></span>
            <span class="cc-meta">
              <b class="cc-peer">{{ s.peerName }}<i v-if="s.prodTitle" class="cc-prod">{{ s.prodTitle }}</i></b>
              <small class="cc-last">{{ s.lastMsg || '（无消息）' }}</small>
            </span>
            <span class="cc-side">
              <b v-if="s.unreadCount" class="cc-unread">{{ s.unreadCount }}</b>
              <small class="cc-time">{{ shortTime(s.updateTime) }}</small>
            </span>
          </button>
        </div>
        <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无客户咨询" style="padding:50px 0" />
      </div>

      <!-- 聊天窗 -->
      <div class="cc-chat" v-if="activeId">
        <header class="cc-chat-head">
          <button v-if="isNarrow" type="button" class="cc-back" aria-label="返回列表" @click="closeSession">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
          </button>
          <b>{{ activeSession?.peerName || '客户' }}</b>
          <a v-if="activeSession?.prodId" class="cc-chat-prod" @click="$router.push('/product/' + activeSession.prodId)">查看商品 →</a>
        </header>
        <div ref="bodyRef" class="cc-msgs">
          <div v-for="(m, i) in messages" :key="m.id" class="msg" :class="m.senderRole === 'merchant' ? 'mine' : 'theirs'">
            <span v-if="m.senderRole !== 'merchant'" class="msg-avatar" aria-hidden="true">
              <User :size="14" :stroke-width="2" />
            </span>
            <div class="msg-main">
              <div v-if="isMedia(m)" class="msg-bubble mm-bubble">
                <MessageMedia :msg="m" />
              </div>
              <p v-else class="msg-bubble">{{ m.content }}</p>
              <small v-if="showTime(i)" class="msg-time">{{ fmt(m.createTime) }}</small>
            </div>
          </div>
          <div v-if="sending" class="msg mine">
            <div class="msg-main typing" aria-label="消息发送中">
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
          <div v-if="!messages.length && !ccLoading" class="cc-empty">
            <span class="empty-ico" aria-hidden="true"><MessageCircle :size="28" :stroke-width="1.6" /></span>
            <p>还没有消息，回复客户开启对话。</p>
          </div>
        </div>
        <form class="cc-compose" @submit.prevent="send">
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
            <textarea ref="taRef" v-model="draft" rows="1" maxlength="500" placeholder="回复客户（最多 500 字）"
                      :disabled="busy" @keydown.enter.exact="onEnter" />
            <button type="submit" class="send-btn" :disabled="busy || !draft.trim()" aria-label="发送">
              <Send :size="16" :stroke-width="2.2" />
            </button>
          </template>
        </form>
      </div>
      <div class="cc-chat cc-placeholder" v-else-if="!isNarrow">
        <span class="empty-ico" aria-hidden="true"><MessageCircle :size="34" :stroke-width="1.5" /></span>
        <p>从左侧选择一个会话开始回复客户</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Empty, message } from 'ant-design-vue'
import { MessageCircle, Mic, Paperclip, Send, User, Smile, Trash2 } from 'lucide-vue-next'
import { chatApi } from '../../api'
import { mediaPlaceholder, pickKind, validateChatFile } from '../../utils/chatMedia'
import { useVoiceRecorder } from '../../utils/useVoiceRecorder'
import EmojiPicker from '../../components/chat/EmojiPicker.vue'
import MessageMedia from '../../components/chat/MessageMedia.vue'

const aEmpty = Empty
const sessions = ref([])
const activeId = ref(null)
const activeSession = ref(null)
const messages = ref([])
const draft = ref('')
const sending = ref(false)
const ccLoading = ref(false)
const bodyRef = ref(null)
const taRef = ref(null)
const showEmoji = ref(false)

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

// 与样式 991px 双态断点一致；matchMedia 响应式（computed 无依赖只求值一次，跨断点不更新）
const isNarrow = ref(false)
let narrowMq = null

let pollTimer = null
let lastId = 0
let stopped = false

function fmt(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '' }
function shortTime(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '' }

/** 时间戳降噪：与上一条不同发送方或时间不同才显示 */
function showTime(i) {
  if (i === 0) return true
  const prev = messages.value[i - 1]
  const cur = messages.value[i]
  return prev.senderRole !== cur.senderRole || fmt(prev.createTime) !== fmt(cur.createTime)
}

function scrollBottom() {
  nextTick(() => { if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight })
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

async function load() {
  try {
    const data = await chatApi.merchant.sessions()
    if (!stopped) sessions.value = data.records || []
  } catch (e) { /* 拦截器已提示 */ }
}

async function openSession(s) {
  activeId.value = s.sessionId
  activeSession.value = s
  lastId = 0
  messages.value = []
  showEmoji.value = false
  recorder.cancel() // 切会话丢弃未发送的录音
  await loadMessages(true)
  // 进入会话时后端已清该会话未读，静默刷新列表同步角标
  load()
}

function closeSession() {
  activeId.value = null
  activeSession.value = null
  messages.value = []
  lastId = 0
  recorder.cancel()
}

/** 拉取增量消息；响应回来时会话可能已被切走，过期则丢弃 */
async function loadMessages(initial = false) {
  const sid = activeId.value
  if (!sid) return
  if (initial) ccLoading.value = true
  try {
    const data = await chatApi.merchant.messages(sid, initial ? 0 : lastId)
    if (sid !== activeId.value) return
    appendMessages(data.messages || [])
  } catch (e) { /* 拦截器已提示 */ }
  if (initial && sid === activeId.value) ccLoading.value = false
}

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value || !activeId.value) return
  const sid = activeId.value
  sending.value = true
  try {
    const r = await chatApi.merchant.send(sid, text)
    if (stopped || sid !== activeId.value) return
    if (r?.message) {
      appendMessages([r.message])
      draft.value = ''
      // 同步列表行：媒体消息用占位摘要（[图片]/[文件] 名/[语音] 12″），文本回退 content
      if (activeSession.value) {
        activeSession.value.lastMsg = mediaPlaceholder(r.message)
        activeSession.value.updateTime = r.message.createTime || activeSession.value.updateTime
      }
      load()
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
  const sid = activeId.value
  if (busy.value || !sid) return
  uploading.value = { name: file.name, percent: 0 }
  try {
    const media = await chatApi.merchant.upload(file, kind, (p) => { if (uploading.value) uploading.value.percent = p })
    const payload = { msgType: kind, mediaUrl: media.url, mediaName: media.name, mediaSize: media.size }
    if (kind === 'voice') payload.voiceSeconds = Math.max(1, Math.round(voiceSeconds || 0))
    const r = await chatApi.merchant.send(sid, payload)
    if (stopped || sid !== activeId.value) return
    if (r?.message) {
      appendMessages([r.message])
      // 同步列表行（与文本 send 同口径，媒体用占位摘要）
      if (activeSession.value) {
        activeSession.value.lastMsg = mediaPlaceholder(r.message)
        activeSession.value.updateTime = r.message.createTime || activeSession.value.updateTime
      }
      load()
    }
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
  } else if (!pollTimer && !stopped) {
    startPolling()
    if (activeId.value) loadMessages(false)
  }
}

function startPolling() {
  if (pollTimer || stopped) return
  pollTimer = window.setInterval(() => { if (activeId.value) loadMessages(false) }, 4000)
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

onMounted(() => {
  // 先注册轮询/监听再拉数据：load 慢时不丢轮询窗口
  if (typeof window.matchMedia === 'function') {
    narrowMq = window.matchMedia('(max-width: 991px)')
    isNarrow.value = narrowMq.matches
    narrowMq.addEventListener?.('change', e => { isNarrow.value = e.matches })
  }
  startPolling()
  document.addEventListener('visibilitychange', onVisibility)
  load()
})

onBeforeUnmount(() => {
  stopped = true
  stopPolling()
  narrowMq?.removeEventListener?.('change', () => {})
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.cc-page { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--mc-chart-solid, #38bdf8); margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }

.cc-shell { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 0;
  background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1);
  border-radius: 16px; overflow: hidden; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); min-height: 560px; }

.cc-list { border-right: 1px solid var(--mc-border-soft, #f0eee9); display: flex; flex-direction: column; }
.cc-list.hide-on-mobile { display: none; }
.cc-list-head { display: flex; justify-content: space-between; align-items: center; padding: 13px 15px;
  border-bottom: 1px solid var(--mc-border-soft, #f0eee9); font-size: 13px; font-weight: 700; color: var(--mc-ink, #1d1d1f); }
.cc-refresh { border: 0; background: transparent; color: var(--mc-accent, #9b2d20); font: inherit; font-size: 12px; cursor: pointer; }
.cc-rows { overflow-y: auto; max-height: 620px; }
.cc-row { display: flex; gap: 10px; align-items: center; width: 100%; border: 0; background: transparent;
  font: inherit; text-align: left; padding: 13px 14px; cursor: pointer; border-bottom: 1px solid var(--mc-border-soft, #f0eee9);
  transition: background .15s ease; }
.cc-row:hover { background: var(--mc-soft, #f5f5f7); }
.cc-row.on { background: rgba(14, 165, 233, .08); }
.cc-avatar { width: 40px; height: 40px; border-radius: 50%; background: linear-gradient(135deg, #e0f2fe, #bae6fd);
  color: #0369a1; display: grid; place-items: center; flex-shrink: 0; }
.cc-meta { flex: 1; min-width: 0; }
.cc-peer { display: flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 650; color: var(--mc-ink, #1d1d1f); }
.cc-prod { font-style: normal; font-weight: 400; font-size: 10.5px; color: var(--mc-faint, #a1a1a6);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cc-last { display: block; margin-top: 3px; font-size: 11.5px; color: var(--mc-muted2, #86868b);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cc-side { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.cc-unread { font-size: 10.5px; line-height: 1; color: #fff; background: var(--mc-accent, #9b2d20);
  border-radius: 999px; padding: 3px 6px; font-weight: 700; }
.cc-time { font-size: 10px; color: var(--mc-faint, #a1a1a6); }

.cc-chat { display: flex; flex-direction: column; min-height: 560px; }
.cc-placeholder { display: grid; place-content: center; text-align: center; color: var(--mc-faint, #a1a1a6); gap: 12px; }
.cc-placeholder p { margin: 0; font-size: 13px; }
.cc-chat-head { display: flex; align-items: center; gap: 10px; padding: 13px 16px;
  border-bottom: 1px solid var(--mc-border-soft, #f0eee9); }
.cc-chat-head b { font-size: 14px; color: var(--mc-ink, #1d1d1f); }
.cc-back { border: 0; background: var(--mc-soft, #f5f5f7); width: 30px; height: 30px; border-radius: 9px;
  display: grid; place-items: center; cursor: pointer; color: var(--mc-ink, #1d1d1f); }
.cc-chat-prod { margin-left: auto; font-size: 12px; color: var(--mc-accent, #9b2d20); cursor: pointer; }
.cc-msgs { flex: 1; display: flex; flex-direction: column; gap: 10px; overflow-y: auto;
  padding: 16px; background: var(--mc-tint, #faf9f6); min-height: 300px; }
.cc-empty { text-align: center; color: var(--mc-faint, #a1a1a6); font-size: 12.5px; padding: 30px 0; }
.empty-ico { display: inline-grid; place-items: center; width: 58px; height: 58px; border-radius: 18px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd); color: #0369a1; margin-bottom: 10px; }
.cc-empty p { margin: 0; font-size: 12.5px; }

/* 21st.dev ChatBubble 模式：买家消息带头像、同侧收拢 */
.msg { display: flex; gap: 8px; align-items: flex-end; max-width: 76%; }
.msg.mine { align-self: flex-end; flex-direction: row-reverse; }
.msg.theirs { align-self: flex-start; }
.msg + .msg.mine, .msg + .msg.theirs { margin-top: -4px; }
.msg-avatar { width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0; display: grid; place-items: center;
  color: #0369a1; background: linear-gradient(135deg, #e0f2fe, #bae6fd); }
.msg-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.msg.mine .msg-main { align-items: flex-end; }
.msg-bubble { margin: 0; padding: 9px 13px; border-radius: 14px; font-size: 13.5px; line-height: 1.65;
  overflow-wrap: anywhere; white-space: pre-wrap; color: #182230; background: #fff;
  border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 1px 4px rgba(19, 35, 58, .05);
  animation: msgIn .28s cubic-bezier(.22, 1, .36, 1);
  /* 表情统一用 LL Emoji（Noto 子集）字形，未覆盖字符回落系统 emoji 字体 */
  font-family: 'LL Emoji', -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif; }
.msg.mine .msg-bubble { background: linear-gradient(135deg, #9b2d20, #b64a39); border-color: transparent;
  color: #fff; border-bottom-right-radius: 5px; }
.msg.theirs .msg-bubble { border-bottom-left-radius: 5px; }
@keyframes msgIn { from { opacity: 0; transform: translateY(6px) scale(.98); } to { opacity: 1; transform: none; } }
.msg-time { color: var(--mc-faint, #a1a1a6); font-size: 10px; opacity: .75; }

/* 打字指示（21st.dev Thinking Indicator 模式）：发送中脉冲 */
.typing { display: inline-flex; gap: 4px; padding: 12px 14px; background: #fff;
  border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px; border-bottom-right-radius: 5px; }
.typing span { width: 6px; height: 6px; border-radius: 50%; background: #94a3b8;
  animation: typingBlink 1.2s infinite; }
.typing span:nth-child(2) { animation-delay: .18s; }
.typing span:nth-child(3) { animation-delay: .36s; }
@keyframes typingBlink { 0%, 60%, 100% { opacity: .3; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }

.cc-compose { position: relative; display: flex; align-items: flex-end; gap: 9px; padding: 11px 14px;
  border-top: 1px solid var(--mc-border-soft, #f0eee9); }
.emoji-btn { border: 1px solid var(--mc-border, #e8e6e1); background: var(--mc-soft, #f5f5f7);
  color: var(--mc-muted2, #86868b); width: 38px; height: 38px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; cursor: pointer;
  transition: color .15s ease, border-color .15s ease, background .15s ease; }
.emoji-btn:hover, .emoji-btn.on { color: var(--mc-accent, #9b2d20); border-color: rgba(155, 45, 32, .35);
  background: rgba(155, 45, 32, .06); }
/* EmojiPicker 主题变量对齐商户中心 --mc-*（含深色 data-theme） */
.cc-compose :deep(.emoji-picker) {
  --ep-bg: var(--mc-card, #fff);
  --ep-border: var(--mc-border, #e8e6e1);
  --ep-ink: var(--mc-ink, #1d1d1f);
  --ep-muted: var(--mc-muted, #6e6e73);
  --ep-hover: rgba(155, 45, 32, .08);
}
.cc-compose textarea { flex: 1; min-width: 0; resize: none; border: 1px solid var(--mc-border, #e8e6e1);
  border-radius: 12px; padding: 10px 13px; font: inherit; font-size: 13px; line-height: 1.5;
  font-family: 'LL Emoji', -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  max-height: 96px; outline: none; background: var(--mc-card, #fff); color: var(--mc-ink, #1d1d1f); }
.cc-compose textarea:focus { border-color: #38bdf8; box-shadow: 0 0 0 3px rgba(56, 189, 248, .15); }
.cc-compose .send-btn { border: 0; width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; cursor: pointer; color: #fff;
  background: linear-gradient(135deg, #9b2d20, #b64a39);
  box-shadow: 0 2px 8px rgba(155, 45, 32, .25); transition: transform .15s ease, box-shadow .15s ease; }
.cc-compose .send-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(155, 45, 32, .32); }
.cc-compose .send-btn:disabled { opacity: .45; cursor: not-allowed; }
/* 附件/录音圆钮：38px 与 emoji-btn 视觉一致，悬停 --mc-accent 红（同 emoji-btn 主题） */
.cc-compose .attach-btn, .cc-compose .mic-btn { width: 38px; height: 38px; padding: 0; border-radius: 50%;
  border: 1px solid var(--mc-border, #e8e6e1); background: var(--mc-soft, #f5f5f7);
  color: var(--mc-muted2, #86868b); display: grid; place-items: center; flex-shrink: 0; font-size: 0;
  transition: color .15s ease, border-color .15s ease, background .15s ease; }
.cc-compose .attach-btn:hover:not(:disabled), .cc-compose .mic-btn:hover:not(:disabled) {
  color: var(--mc-accent, #9b2d20); border-color: rgba(155, 45, 32, .35); background: rgba(155, 45, 32, .06); }
.cc-compose .attach-btn:disabled, .cc-compose .mic-btn:disabled { opacity: .45; cursor: not-allowed; }

/* 录音态：红色脉冲圆点 + 秒数 + 取消/发送（compose 行整体切换，textarea 隐藏即等效禁用） */
.rec-dot { width: 12px; height: 12px; border-radius: 50%; background: #ef4444; align-self: center; flex-shrink: 0;
  animation: recPulse 1.2s ease-in-out infinite; }
@keyframes recPulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, .38); } 50% { box-shadow: 0 0 0 7px rgba(239, 68, 68, 0); } }
.rec-time { align-self: center; font-size: 14px; font-weight: 700; color: #ef4444;
  font-variant-numeric: tabular-nums; flex-shrink: 0; }
.rec-fill { flex: 1; align-self: center; }
.cc-compose .rec-cancel, .cc-compose .rec-send { width: 38px; height: 38px; padding: 0; border-radius: 50%;
  flex-shrink: 0; display: grid; place-items: center; cursor: pointer; font-size: 0; }
.cc-compose .rec-cancel { border: 1px solid rgba(239, 68, 68, .45); background: transparent; color: #ef4444; }
.cc-compose .rec-cancel:hover { background: rgba(239, 68, 68, .08); }
.cc-compose .rec-send { border: 0; color: #fff; background: linear-gradient(135deg, #ef4444, #dc2626);
  box-shadow: 0 2px 8px rgba(239, 68, 68, .3); }

/* 上传条：临时节点（不进 messages），文件名 + 进度 */
.upload-card { display: flex; flex-direction: column; gap: 2px; min-width: 220px; padding: 10px 13px;
  background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px;
  box-shadow: 0 1px 4px rgba(19, 35, 58, .05); animation: msgIn .28s cubic-bezier(.22, 1, .36, 1); }
.upload-name { font-size: 12px; color: var(--mc-ink, #1d1d1f);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 媒体气泡：替代文本 p，保持两侧气泡底色；内边距收紧让图片/卡片贴近边缘 */
.msg-bubble.mm-bubble { padding: 4px; line-height: 1.4; }

@media (max-width: 991px) {
  .cc-shell { grid-template-columns: minmax(0, 1fr); min-height: 480px;
    /* overflow:hidden（桌面裁圆角）会让子孙 sticky 失效，移动端放开（同 Chat.vue 做法） */
    overflow: visible; }
  .cc-list { border-right: 0; }
  /* 手机：列表 → 详情 两态切换（与 isNarrow 断点一致） */
  .cc-chat { min-height: 520px; }
  /* 长会话下输入区贴底，免随消息流滚出屏幕 */
  .cc-compose { position: sticky; bottom: 0; z-index: 5;
    background: var(--mc-card, #fff); border-radius: 0 0 15px 15px; }
}
@media (max-width: 700px) {
  .hero h1 { font-size: 22px; }
  .msg { max-width: 86%; }
  .cc-compose { gap: 6px; padding-bottom: calc(11px + env(safe-area-inset-bottom)); }
  /* 390px 宽下 4 钮 + textarea 可用：圆钮缩至 34px、发送钮收窄 */
  .cc-compose .attach-btn, .cc-compose .mic-btn,
  .cc-compose .rec-cancel, .cc-compose .rec-send { width: 34px; height: 34px; }
  .cc-compose .send-btn { width: 36px; height: 36px; }
  .cc-compose .emoji-btn { width: 34px; height: 34px; }
  .upload-card { min-width: 180px; }
}
</style>
