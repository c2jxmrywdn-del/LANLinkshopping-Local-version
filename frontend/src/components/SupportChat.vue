<template>
  <div class="support-root">
    <button class="support-launcher" type="button" :class="{ 'is-open': visible }"
      aria-expanded="visible"
      aria-controls="lanlink-support-panel" @click="toggle">
      <SupportAgentAvatar :size="30" status="online" class="support-launcher-avatar" />
      <span class="support-launcher-text">专属客服</span>
    </button>

    <!-- 聊天面板：Border Beam 流光边框（移植自 border-beam@1.4.1，白底 light 主题）
         BorderBeam 为多根组件不透传 class，定位由外层 .support-beam-host 容器接管 -->
    <div v-if="visible" class="support-beam-host">
      <BorderBeam :border-radius="18" theme="light" color-variant="colorful">
        <section id="lanlink-support-panel" class="support-panel"
          role="dialog" aria-label="LANLinkshopping 专属客服">
      <header class="support-header">
        <SupportAgentAvatar :size="34" status="online" />
        <div class="support-header-copy">
          <strong>LANLinkshopping 客服</strong>
          <p>只回答专属知识库已收录的电商问题</p>
        </div>
        <button class="support-close" type="button" aria-label="关闭客服" @click="visible = false">×</button>
      </header>

      <div ref="messageList" class="support-messages" aria-live="polite">
        <div v-for="(item, index) in messages" :key="index" class="support-message"
          :class="item.role === 'user' ? 'from-user' : 'from-support'">
          <div class="support-message-label">
            <SupportAgentAvatar v-if="item.role !== 'user'" :size="16" />
            <span>{{ item.role === 'user' ? '你' : '专属客服' }}</span>
            <button v-if="item.role !== 'user'" type="button" class="support-tts"
              :class="{ 'is-on': ttsKey === 'msg-' + index }"
              :aria-label="ttsKey === 'msg-' + index ? '停止语音播报' : '语音播报'"
              :title="ttsKey === 'msg-' + index ? '停止语音播报' : '语音播报'"
              @click.stop="toggleSpeak('msg-' + index, item.text)">
              <Square v-if="ttsKey === 'msg-' + index" :size="12" class="inline-block align-middle" />
              <Volume2 v-else :size="13" class="inline-block align-middle" />
            </button>
          </div>
          <p>{{ item.text }}</p>
        </div>
        <div v-if="messages.length === 1 && !loading" class="support-suggestions" aria-label="常见问题">
          <p>可以先试试</p>
          <button type="button" @click="askSuggested('如何注册账号？')">账号注册</button>
          <button type="button" @click="askSuggested('购物车怎么结算？')">购物车结算</button>
          <button type="button" @click="askSuggested('如何申请商户入驻？')">商户入驻</button>
        </div>
        <div v-if="loading" class="support-message from-support">
          <div class="support-message-label">
            <SupportAgentAvatar :size="16" />
            <span>专属客服</span>
          </div>
          <p>正在检索知识库…</p>
        </div>
      </div>

      <form class="support-compose" @submit.prevent="send">
        <label class="support-sr-only" for="support-question">输入电商相关问题</label>
        <textarea id="support-question" v-model="draft" :disabled="loading" maxlength="300" rows="2"
          placeholder="请输入电商相关问题（最多 300 字）"
          @keydown.enter.exact.prevent="send" />
        <div class="support-compose-footer">
          <span>{{ draft.length }}/300</span>
          <button type="submit" :disabled="loading || !draft.trim()">发送</button>
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
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Volume2, Square } from 'lucide-vue-next'
import { supportApi } from '../api'
import SupportAgentAvatar from './ui/SupportAgentAvatar.vue'
import BorderBeam from './ui/BorderBeam.vue'
import { translateDataText } from '../i18n'
import { speakText, stopSpeaking } from '../utils/tts'

const FALLBACK = '您好！感谢您的咨询。当前客服仅能解答 LANLinkshopping 专属知识库已收录的电商相关问题，可咨询账号注册与登录、商品浏览、购物车与订单、商户入驻、钱包与账期、会员与活动、站内消息及 Cookie 设置；其他问题暂不在可答范围内，敬请谅解。'
const WELCOME = '您好，我是 LANLinkshopping 专属客服。请咨询平台账号注册与登录、商品浏览、购物车与订单、商户入驻、钱包与账期、会员与活动、站内消息或 Cookie 设置等已收录问题。'
const TOKEN_KEY = 'll_support_visitor_token'

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
const persistConversation = ref(false)
const lastHistoryMessageId = ref(0)
const messages = ref([{ role: 'assistant', text: WELCOME }])
const ttsKey = ref('')
let pollTimer = null

/** 播放/停止单条客服回复的语音播报 */
function toggleSpeak(key, text) {
  if (ttsKey.value === key) {
    stopSpeaking()
    ttsKey.value = ''
    return
  }
  const ok = speakText(translateDataText(text), {
    onEnd: () => { if (ttsKey.value === key) ttsKey.value = '' }
  })
  ttsKey.value = ok ? key : ''
}

function toggle() { visible.value = !visible.value }
function askSuggested(question) {
  if (loading.value) return
  draft.value = question
  void send()
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
  stopSpeaking()
}
async function syncHistory() {
  if (!visible.value || loading.value) return
  const result = await supportApi.history(visitorToken.value, lastHistoryMessageId.value)
  if (result.visitorToken) visitorToken.value = result.visitorToken
  let maxId = lastHistoryMessageId.value
  for (const item of result.messages || []) {
    const id = Number(item.id || 0)
    if (!id || id <= lastHistoryMessageId.value) continue
    const senderType = String(item.senderType || '')
    const role = senderType === 'customer' ? 'user' : 'assistant'
    messages.value.push({ role, text: String(item.content || ''), messageId: id, senderName: item.senderName || '' })
    maxId = Math.max(maxId, id)
  }
  lastHistoryMessageId.value = Math.max(maxId, Number(result.lastMessageId || 0))
}
async function send() {
  const question = draft.value.trim()
  if (!question || loading.value) return
  if (question.length > 300) {
    messages.value.push({ role: 'assistant', text: FALLBACK })
    return
  }
  messages.value.push({ role: 'user', text: question })
  draft.value = ''
  loading.value = true
  try {
    const result = await supportApi.ask(question, visitorToken.value, persistConversation.value)
    if (result.visitorToken) visitorToken.value = result.visitorToken
    const answer = typeof result?.answer === 'string' && result.answer ? result.answer : FALLBACK
    messages.value.push({ role: 'assistant', text: answer, unavailable: result.unavailable === true })
    lastHistoryMessageId.value = Math.max(lastHistoryMessageId.value, Number(result.lastMessageId || 0))
  } catch {
    messages.value.push({ role: 'assistant', text: FALLBACK })
  } finally {
    loading.value = false
    void syncHistory()
  }
}
</script>

<style scoped>
.support-root { position: fixed; right: 24px; bottom: 24px; z-index: 1200; font-family: inherit; }
.support-launcher { display: inline-flex; align-items: center; gap: 9px; border: 0; border-radius: 999px; padding: 12px 18px 12px 12px; color: #fff; background: #13233A; box-shadow: 0 10px 30px rgba(19,35,58,.24); font-size: 14px; font-weight: 650; cursor: pointer; }
.support-launcher:hover { background: #203958; }
.support-launcher-avatar { margin-right: 1px; }
/* 流光宿主：接管原面板的浮动定位，BorderBeam 在其 1px 边环上跑光 */
.support-beam-host { position: absolute; right: 0; bottom: 62px; width: min(370px, calc(100vw - 28px)); height: min(560px, calc(100vh - 110px)); }
/* BorderBeam 根元素铺满宿主（多根组件不透传 class，需 :deep 撑满） */
.support-beam-host :deep(.bb-wrap) { width: 100%; height: 100%; }
.support-panel { display: flex; flex-direction: column; position: relative; width: 100%; height: 100%; background: #fff; border: 1px solid #e4e7ec; border-radius: 18px; box-shadow: 0 20px 55px rgba(16,24,40,.2); overflow: hidden; }
.support-header { display: flex; align-items: center; gap: 11px; padding: 17px 18px; background: #13233A; color: #fff; }
.support-header-copy { flex: 1 1 auto; min-width: 0; }
.support-header strong { display: block; font-size: 14px; font-weight: 700; }
.support-header p { margin: 4px 0 0; color: rgba(255,255,255,.78); font-size: 11px; }
.support-close { border: 0; background: transparent; color: #fff; font-size: 26px; line-height: 1; cursor: pointer; }
.support-messages { display: flex; flex: 1; flex-direction: column; align-items: stretch; gap: 12px; overflow-y: auto; padding: 16px 13px; background: #f8f9fb; }
.support-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; padding: 2px 0 5px; }
.support-suggestions p { width: 100%; margin: 0; color: #667085; font-size: 11px; }
.support-suggestions button { border: 1px solid #d8dee8; border-radius: 999px; padding: 6px 9px; color: #344054; background: #fff; font: inherit; font-size: 11px; cursor: pointer; }
.support-suggestions button:hover { border-color: #587ca5; color: #13233A; background: #f3f7fc; }
.support-message { max-width: 92%; padding: 10px 12px; border-radius: 13px; border: 1px solid #e7eaf0; background: #fff; overflow-wrap: anywhere; }
.support-message.from-user { align-self: flex-end; border-color: #d6e4f5; background: #eef5ff; }
.support-message.from-support { align-self: flex-start; }
.support-message-label { display: flex; align-items: center; gap: 5px; margin-bottom: 5px; color: #667085; font-size: 10px; font-weight: 700; }
.support-tts { margin-left: auto; display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; padding: 0; border: 1px solid #e4e7ec; border-radius: 7px; background: #fff; color: #667085; font-size: 11px; line-height: 1; cursor: pointer; transition: background .16s ease, border-color .16s ease, color .16s ease; }
.support-tts:hover { border-color: #b8c6d8; color: #344054; background: #f5f8fc; }
.support-tts.is-on { border-color: #9fc0e8; color: #1e6eb8; background: #eef5ff; }
.support-message p { margin: 0; color: #182230; font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
.support-compose { padding: 11px 12px 9px; border-top: 1px solid #e4e7ec; background: #fff; }
.support-compose textarea { display: block; box-sizing: border-box; width: 100%; resize: none; border: 1px solid #d0d5dd; border-radius: 10px; padding: 10px; outline: none; color: #182230; background: #fff; font: inherit; font-size: 12px; line-height: 1.6; }
.support-compose textarea:focus { border-color: #587ca5; box-shadow: 0 0 0 3px rgba(88,124,165,.12); }
.support-compose-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; color: #98a2b3; font-size: 10px; }
.support-compose-footer button { padding: 7px 14px; border: 0; border-radius: 8px; color: #fff; background: #13233A; font-size: 12px; font-weight: 650; cursor: pointer; }
.support-compose-footer button:disabled { opacity: .45; cursor: not-allowed; }
.support-consent { display:flex; align-items:flex-start; gap:7px; color:#344054; font-size:10px; line-height:1.5; cursor:pointer; }
.support-consent input { margin-top:2px; accent-color:#13233a; }
.support-scope-note p { margin:5px 0 0; }
.support-launcher { position:relative; overflow:hidden; background:linear-gradient(135deg,#13233a 0%,#203958 62%,#34577a 100%); transition:transform .18s ease,box-shadow .18s ease,opacity .18s ease; }
/* 面板打开时隐藏悬浮球，避免同屏出现两个"客服"入口 */
.support-launcher.is-open { opacity: 0; pointer-events: none; transform: scale(.9); }
.support-launcher::after { content:""; position:absolute; top:-80%; left:-40%; width:40%; height:260%; transform:rotate(28deg); background:linear-gradient(90deg,transparent,rgba(255,255,255,.14),transparent); animation:support-sheen 6s ease-in-out infinite; pointer-events:none; }
.support-launcher:hover { transform:translateY(-1px); box-shadow:0 14px 34px rgba(19,35,58,.3); }
.support-message { transition:transform .16s ease,border-color .16s ease; }
.support-message:hover { border-color:#d0d5dd; }
@keyframes support-sheen { 0%,70%,100% { left:-45%; } 30% { left:130%; } }
@media (prefers-reduced-motion:reduce) { .support-launcher,.support-message { transition:none; } .support-launcher::after { animation:none; display:none; } }
.support-scope-note { padding: 0 12px 12px; color: #667085; background: #fff; font-size: 10px; line-height: 1.5; }
.support-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
@media (max-width: 520px) {
  /* 悬浮球：收为圆形图标 FAB，减少占屏 */
  .support-root { right: 14px; bottom: 14px; }
  .support-launcher { padding: 0; width: 52px; height: 52px; justify-content: center; border-radius: 50%; }
  .support-launcher-text { display: none; }
  .support-launcher-avatar { margin: 0; }
  /* 面板：底部抽屉式（全宽 + 76vh 上限），贴合手机聊天习惯而非浮窗遮内容 */
  .support-beam-host { position: fixed; left: 0; right: 0; bottom: 0; width: 100vw;
    height: min(76vh, 76dvh, 620px); border-radius: 18px 18px 0 0; }
  .support-panel { border-radius: 18px 18px 0 0; border-left: 0; border-right: 0; border-bottom: 0;
    padding-bottom: env(safe-area-inset-bottom, 0); }
  .support-header { padding: 13px 14px; }
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
