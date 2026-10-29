<template>
  <div class="lr-page">
    <a-spin :spinning="loading">
      <template v-if="room">
        <div class="lr-grid">
          <!-- ===== 播放区（手机优先：置顶全宽） ===== -->
          <section class="lr-main">
            <div class="lr-player">
              <video
                v-if="hasStream && !videoFailed"
                :key="room.roomId"
                class="lr-video"
                :src="room.streamUrl"
                :poster="room.coverUrl || undefined"
                autoplay muted loop playsinline
                @error="onVideoError"
              ></video>
              <div v-else class="lr-cover" :class="{ 'is-breathing': Number(room.status) === 1 }">
                <img v-if="room.coverUrl" :src="room.coverUrl" :alt="room.title" @error="onCoverError" />
                <div v-else class="lr-cover-fallback"><Video :size="44" :stroke-width="1.4" /></div>
                <span v-if="Number(room.status) === 1" class="lr-live-badge">
                  <i class="lr-live-dot" aria-hidden="true"></i>LIVE
                </span>
                <span v-if="videoFailed" class="lr-stream-wait">直播信号即将开始，请稍候</span>
              </div>

              <div class="lr-topbar">
                <button type="button" class="lr-back" aria-label="返回" @click="goBack">
                  <ChevronLeft :size="18" :stroke-width="2.2" />
                </button>
                <span class="lr-room-title">{{ room.title }}</span>
                <span class="lr-flag" :class="'st-' + Number(room.status)">
                  <i v-if="Number(room.status) === 1" class="lr-flag-dot" aria-hidden="true"></i>
                  {{ statusText(room.status) }}
                </span>
              </div>
            </div>

            <div class="lr-stats-row">
              <span class="lr-stat"><Eye :size="14" :stroke-width="2" /><NumberTicker :value="viewerCount" /> <em>在线</em></span>
              <span class="lr-stat"><Heart :size="14" :stroke-width="2" /><NumberTicker :value="likeCount" /> <em>点赞</em></span>
              <button v-if="products.length" type="button" class="lr-products-entry" @click="productsOpen = true">
                <ShoppingBag :size="14" :stroke-width="2" /><span>全部商品</span><i>（{{ products.length }}）</i>
              </button>
            </div>

            <div v-if="room.notice" class="lr-notice">
              <Volume2 :size="14" :stroke-width="2.1" />
              <b>公告</b>
              <span>{{ room.notice }}</span>
            </div>

            <div v-if="Number(room.status) === 0" class="lr-state-tip">
              <Clock :size="15" :stroke-width="2" />
              <span>主播尚未开播，敬请期待</span>
              <span v-if="room.startTime" class="lr-state-time">{{ formatTime(room.startTime) }}<em>&nbsp;开播</em></span>
            </div>
            <div v-else-if="Number(room.status) === 2" class="lr-state-tip is-ended">
              <CircleCheck :size="15" :stroke-width="2" />
              <span>本场直播已结束，感谢观看</span>
            </div>

            <!-- 讲解中商品卡：先上报点击再跳商品详情 -->
            <div v-if="pinned" class="lr-pinned" role="button" tabindex="0" @click="buy(pinned)" @keydown.enter.prevent="buy(pinned)">
              <div class="lr-pinned-cover">
                <img v-if="pinned.product && pinned.product.coverUrl" :src="pinned.product.coverUrl" :alt="pinned.product.title" />
                <div v-else class="lr-pinned-fallback"><ShoppingBag :size="20" :stroke-width="1.6" /></div>
              </div>
              <div class="lr-pinned-info">
                <span class="lr-showcase-tag"><Radio :size="11" :stroke-width="2.2" />讲解中</span>
                <h4>{{ pinned.product ? pinned.product.title : '商品 #' + pinned.prodId }}</h4>
                <div class="lr-price-row">
                  <span class="lr-live-price"><em>¥</em>{{ money(pinned.livePrice) }}</span>
                  <del v-if="pinned.product && Number(pinned.product.price) > Number(pinned.livePrice)">¥{{ money(pinned.product.price) }}</del>
                </div>
              </div>
              <a-button type="primary" class="lr-buy-btn">去购买</a-button>
            </div>
          </section>

          <!-- ===== 弹幕区 ===== -->
          <aside class="lr-chat">
            <div class="lr-chat-head"><MessageCircle :size="14" :stroke-width="2.1" />弹幕</div>
            <div ref="chatList" class="lr-chat-list" @scroll.passive="onChatScroll">
              <div v-for="c in comments" :key="c.commentId" class="lr-msg" :class="'t-' + c.msgType">
                <template v-if="c.msgType === 'system'">
                  <span class="lr-msg-sys">{{ c.content }}</span>
                </template>
                <template v-else>
                  <img class="lr-msg-avatar" :src="c.avatar || defaultAvatar" :alt="c.nickname || '用户'" loading="lazy" @error="onAvatarError" />
                  <div class="lr-msg-body">
                    <span class="lr-msg-name">{{ c.nickname || '观众' }}</span>
                    <p :class="{ 'is-buy': c.msgType === 'buy' }">{{ c.content }}</p>
                  </div>
                </template>
              </div>
              <p v-if="!comments.length" class="lr-chat-empty">暂无弹幕，来说两句吧</p>
            </div>
            <div class="lr-chat-input">
              <a-input
                v-model:value="draft"
                placeholder="说点什么…"
                :maxlength="200"
                :disabled="sending"
                @keydown.enter="send"
              />
              <a-button type="primary" :loading="sending" @click="send">发送</a-button>
              <div class="lr-like-wrap">
                <button type="button" class="lr-like-btn" aria-label="点赞" @click="onLike">
                  <Heart :size="18" :stroke-width="2.1" :class="{ 'is-liked': likedPulse }" />
                </button>
                <span v-for="h in hearts" :key="h.id" class="lr-heart-float" :style="{ left: h.x + 'px', '--drift': h.drift + 'px' }">
                  <Heart :size="14" :stroke-width="2.4" />
                </span>
              </div>
            </div>
          </aside>
        </div>

        <!-- ===== 商品列表抽屉 ===== -->
        <a-drawer v-model:open="productsOpen" title="全部商品" placement="right" :width="drawerWidth" class="lr-drawer">
          <div class="lr-drawer-list">
            <div v-for="lp in products" :key="lp.id" class="lr-drawer-item" @click="buy(lp)">
              <div class="lr-drawer-cover">
                <img v-if="lp.product && lp.product.coverUrl" :src="lp.product.coverUrl" :alt="lp.product.title" loading="lazy" />
                <div v-else class="lr-pinned-fallback"><ShoppingBag :size="18" :stroke-width="1.6" /></div>
              </div>
              <div class="lr-drawer-info">
                <h5>{{ lp.product ? lp.product.title : '商品 #' + lp.prodId }}</h5>
                <div class="lr-price-row">
                  <span class="lr-live-price"><em>¥</em>{{ money(lp.livePrice) }}</span>
                  <del v-if="lp.product && Number(lp.product.price) > Number(lp.livePrice)">¥{{ money(lp.product.price) }}</del>
                </div>
              </div>
              <a-button size="small" type="primary" ghost>去购买</a-button>
            </div>
            <a-empty v-if="!products.length" description="本直播间暂无商品" style="padding: 32px 0" />
          </div>
        </a-drawer>
      </template>
      <a-empty v-else-if="!loading" description="直播间不存在或已关闭" style="padding: 64px 0" />
    </a-spin>
  </div>
</template>

<script setup>
/**
 * 直播间（/live/:roomId，公开，手机优先）：
 * - 播放区：streamUrl 有值用 <video autoplay muted loop playsinline>，否则封面 + LIVE 呼吸光效
 * - 弹幕：3s 轮询（升序，自动滚底）；评论登录后发送，游客引导登录
 * - 点赞：500ms 节流 + CSS 上浮爱心
 * - 生命周期：进入 join，离开 leave（sendBeacon 兜底），切后台 leave / 回来 join 一次
 * - 所有轮询与监听器均在 onUnmounted 清理
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  ChevronLeft, CircleCheck, Clock, Eye, Heart, MessageCircle, Radio, ShoppingBag, Video, Volume2
} from 'lucide-vue-next'
import { liveApi } from '../../api'
import NumberTicker from '../../components/ui/NumberTicker.vue'
import { useUserStore } from '../../store/user'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'
const defaultAvatar = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="56" height="56"><rect width="56" height="56" rx="28" fill="#eef1f6"/><circle cx="28" cy="22" r="9" fill="#b9c3d4"/><path d="M12 46c2-10 9-14 16-14s14 4 16 14z" fill="#b9c3d4"/></svg>'
)

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const room = ref(null)
const products = ref([])
const comments = ref([])
const loading = ref(true)
const draft = ref('')
const sending = ref(false)
const productsOpen = ref(false)
const chatList = ref(null)

let commentTimer = null
let roomTimer = null
const heartTimers = new Set()
let chatStick = true // 用户未主动上翻时保持自动滚底

const roomId = computed(() => route.params.roomId)
const hasStream = computed(() => !!(room.value && room.value.streamUrl))
const viewerCount = computed(() => Number(room.value?.viewerCount || 0))
const likeCount = computed(() => Number(room.value?.likeCount || 0))
const pinned = computed(() => {
  const r = room.value
  if (r && r.pinnedProduct) return r.pinnedProduct
  const pid = Number(r?.pinnedProdId || 0)
  return pid ? (products.value.find(p => Number(p.prodId) === pid) || null) : null
})
const drawerWidth = computed(() => (window.innerWidth <= 560 ? window.innerWidth : 400))

// ===== HLS 黑屏兜底 =====
// Safari/iOS 原生支持 HLS；Chrome/Edge/Firefox 需 hls.js(MSE)，<video> 直连 .m3u8 只会黑屏。
const videoFailed = ref(false)
const nativeHls = computed(() => {
  try { return document.createElement('video').canPlayType('application/vnd.apple.mpegurl') !== '' }
  catch (e) { return false }
})
watch(() => room.value && room.value.streamUrl, (url) => {
  videoFailed.value = !!(url && /\.m3u8(\?|#|$)/i.test(url) && !nativeHls.value)
}, { immediate: true })
function onVideoError() { videoFailed.value = true }

const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const statusText = (s) => ({ 0: '预告', 1: '直播中', 2: '已结束' }[Number(s)] || '直播')
const formatTime = (s) => (s ? String(s).replace('T', ' ').slice(5, 16) : '')

// ===== 数据加载 =====
async function loadRoom() {
  try {
    const r = await liveApi.room(roomId.value)
    room.value = r || null
  } catch (e) {
    room.value = null
  }
}

async function loadProducts() {
  try { products.value = (await liveApi.products(roomId.value)) || [] }
  catch (e) { products.value = [] }
}

let lastCommentId = null // 已滚底对应的最新一条弹幕 ID：无新弹幕时不重复强制滚底

async function loadComments() {
  try {
    const list = (await liveApi.comments(roomId.value, 50, { silent: true })) || []
    const next = Array.isArray(list) ? list : []
    const lastItem = next[next.length - 1]
    const newestId = lastItem ? (lastItem.commentId ?? next.length) : null
    comments.value = next
    if (newestId !== lastCommentId) {
      lastCommentId = newestId
      scrollToBottom()
    }
  } catch (e) { /* 静默：下一轮再取 */ }
}

function scrollToBottom() {
  if (!chatStick) return
  nextTick(() => {
    const el = chatList.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onChatScroll() {
  const el = chatList.value
  if (!el) return
  chatStick = el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

// ===== 进出直播间统计（游客静默吸收 401） =====
async function joinRoom() {
  try { const online = await liveApi.join(roomId.value, { silent: true }) ; if (room.value && online != null) room.value.viewerCount = online }
  catch (e) { /* 游客/异常静默 */ }
}

function leaveRoom() {
  // 优先 sendBeacon：页面隐藏/卸载时仍可靠投递，且 /live/** 不在 CSRF 拦截范围（无需令牌头）。
  // visibilitychange 与 onUnmounted 共用本函数，beacon 成功即返回，天然避免 leave 双发；
  // sendBeacon 不可用才降级 axios 静默上报。
  try {
    if (navigator.sendBeacon) {
      navigator.sendBeacon(`${API_BASE}/live/room/${roomId.value}/leave`, new Blob([], { type: 'text/plain' }))
      return
    }
  } catch (e) { /* 降级 axios */ }
  liveApi.leave(roomId.value, { silent: true }).catch(() => {})
}

function onVisibility() {
  if (!room.value) return
  if (document.visibilityState === 'hidden') {
    leaveRoom()
  } else {
    joinRoom()
  }
}

// ===== 轮询 =====
function startPolling() {
  if (!commentTimer) commentTimer = setInterval(loadComments, 3000)
  if (!roomTimer) {
    roomTimer = setInterval(() => {
      liveApi.room(roomId.value, { silent: true })
        .then(r => { if (r) room.value = r })
        .catch(() => { /* 静默 */ })
    }, 6000)
  }
}

function stopPolling() {
  if (commentTimer) { clearInterval(commentTimer); commentTimer = null }
  if (roomTimer) { clearInterval(roomTimer); roomTimer = null }
}

// ===== 互动 =====
async function send() {
  const text = (draft.value || '').trim()
  if (!text || sending.value) return
  if (!user.logged) {
    message.warning('请先登录后参与互动')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  sending.value = true
  try {
    const c = await liveApi.comment(roomId.value, text, 'chat')
    draft.value = ''
    if (c) {
      comments.value = [...comments.value, c]
      chatStick = true
      scrollToBottom()
    }
  } catch (e) { /* 拦截器已提示 */ }
  sending.value = false
}

// 点赞：500ms 节流，返回累计数直接回填
let likeLocked = false
const likedPulse = ref(false)
async function onLike() {
  if (likeLocked) return
  likeLocked = true
  spawnHeart()
  likedPulse.value = true
  setTimeout(() => { likedPulse.value = false }, 320)
  try {
    const total = await liveApi.like(roomId.value, 1, { silent: true })
    if (room.value && total != null) room.value.likeCount = total
  } catch (e) { /* 游客/异常静默 */ }
  setTimeout(() => { likeLocked = false }, 500)
}

const hearts = ref([])
let heartSeq = 0
function spawnHeart() {
  const id = ++heartSeq
  hearts.value = [...hearts.value.slice(-6), {
    id,
    x: 6 + Math.round(Math.random() * 16),
    drift: Math.round((Math.random() - 0.5) * 28)
  }]
  const t = setTimeout(() => {
    hearts.value = hearts.value.filter(h => h.id !== id)
    heartTimers.delete(t)
  }, 900)
  heartTimers.add(t)
}

// ===== 购买：先上报点击（返回含 livePrice），再跳商品详情并携带直播归因参数 =====
async function buy(lp) {
  if (!lp || !lp.prodId) return
  try {
    const fresh = await liveApi.productClick(roomId.value, lp.prodId, { silent: true })
    // 回填最新直播价（讲解卡/抽屉同步刷新）
    if (fresh && fresh.livePrice != null) lp.livePrice = fresh.livePrice
  } catch (e) { /* 点击上报失败不阻断跳转 */ }
  productsOpen.value = false
  router.push(`/product/${lp.prodId}?liveId=${roomId.value}&source=live`)
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/live')
}

function onCoverError(e) { e.target.style.display = 'none' }
function onAvatarError(e) { e.target.src = defaultAvatar }

onMounted(async () => {
  await loadRoom()
  if (room.value) {
    loadProducts()
    await loadComments()
    joinRoom()
    startPolling()
    document.addEventListener('visibilitychange', onVisibility)
  }
  loading.value = false
})

onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', onVisibility)
  heartTimers.forEach(t => clearTimeout(t))
  heartTimers.clear()
  if (room.value) leaveRoom()
})
</script>

<style scoped>
.lr-page { max-width: 1160px; margin: 0 auto; padding: 12px 12px 48px; }
.lr-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; }
@media (min-width: 861px) {
  .lr-grid { grid-template-columns: minmax(0, 1fr) 340px; align-items: start; }
  .lr-page { padding: 20px 20px 60px; }
}

/* ===== 播放区 ===== */
.lr-player {
  position: relative;
  border-radius: 16px;
  overflow: hidden;
  background: #0b1220;
  height: 56vw;
  max-height: 520px;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
}
@media (min-width: 861px) { .lr-player { height: 460px; } }

.lr-video { width: 100%; height: 100%; object-fit: cover; display: block; background: #0b1220; }
.lr-cover { position: relative; width: 100%; height: 100%; }
.lr-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.lr-cover.is-breathing::after {
  content: "";
  position: absolute; inset: 0;
  pointer-events: none;
  animation: lr-breathe 2.6s ease-in-out infinite;
}
@keyframes lr-breathe {
  0%, 100% { box-shadow: inset 0 0 0 rgba(155, 45, 32, 0); }
  50% { box-shadow: inset 0 0 90px rgba(155, 45, 32, .35); }
}
.lr-cover-fallback { width: 100%; height: 100%; display: grid; place-items: center; color: #4a5b74; }

.lr-live-badge {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  display: inline-flex; align-items: center; gap: 7px;
  padding: 8px 18px; border-radius: 999px;
  background: rgba(155, 45, 32, .92); color: #fff;
  font-size: 15px; font-weight: 800; letter-spacing: .12em;
  box-shadow: 0 0 0 6px rgba(155, 45, 32, .18);
  animation: lr-badge-pop 2.2s ease-in-out infinite;
}
@keyframes lr-badge-pop {
  0%, 100% { transform: translate(-50%, -50%) scale(1); }
  50% { transform: translate(-50%, -50%) scale(1.06); }
}
.lr-live-dot { width: 8px; height: 8px; border-radius: 50%; background: #fff; animation: lr-blink 1.1s ease-in-out infinite; }
.lr-stream-wait {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, 44px);
  padding: 7px 16px; border-radius: 999px;
  background: rgba(11, 18, 32, .66); color: #fff;
  font-size: 12.5px; font-weight: 600; letter-spacing: .02em;
  backdrop-filter: blur(8px);
  white-space: nowrap;
}
@keyframes lr-blink { 0%, 100% { opacity: 1; } 50% { opacity: .2; } }

.lr-topbar {
  position: absolute; left: 0; right: 0; top: 0;
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  background: linear-gradient(180deg, rgba(11, 18, 32, .62), rgba(11, 18, 32, 0));
}
.lr-back {
  flex-shrink: 0;
  width: 32px; height: 32px; border-radius: 50%;
  border: 0; cursor: pointer;
  display: grid; place-items: center;
  background: rgba(255, 255, 255, .16); color: #fff;
  backdrop-filter: blur(8px);
  transition: background .2s ease;
}
.lr-back:hover { background: rgba(255, 255, 255, .28); }
.lr-room-title {
  flex: 1; min-width: 0;
  color: #fff; font-size: 13.5px; font-weight: 600; letter-spacing: -.01em;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  text-shadow: 0 1px 4px rgba(0, 0, 0, .4);
}
.lr-flag {
  flex-shrink: 0;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px; border-radius: 999px;
  font-size: 11px; font-weight: 700; color: #fff;
  background: rgba(19, 35, 58, .55); backdrop-filter: blur(8px);
}
.lr-flag.st-1 { background: rgba(155, 45, 32, .88); }
.lr-flag-dot { width: 5px; height: 5px; border-radius: 50%; background: #fff; animation: lr-blink 1.1s ease-in-out infinite; }

/* ===== 数据行 / 公告 / 状态提示 ===== */
.lr-stats-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-top: 10px; }
.lr-stat { display: inline-flex; align-items: baseline; gap: 5px; font-size: 14px; font-weight: 700; color: var(--ll-ink, #0F172A); font-variant-numeric: tabular-nums; }
.lr-stat em { font-style: normal; font-size: 12px; font-weight: 500; color: var(--ll-muted, #64748B); }
.lr-stat svg { align-self: center; color: var(--ll-muted, #64748B); }
.lr-products-entry {
  margin-left: auto;
  display: inline-flex; align-items: center; gap: 5px;
  border: 1px solid rgba(19, 35, 58, .1);
  background: var(--ll-white, #fff);
  color: var(--ll-ink, #0F172A);
  font: 600 12.5px/1 -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', sans-serif;
  padding: 7px 12px; border-radius: 999px; cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04);
  transition: transform .2s ease, box-shadow .2s ease;
}
.lr-products-entry:hover { transform: translateY(-2px); box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 20px rgba(0, 0, 0, .08); }

.lr-notice {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px; padding: 9px 13px;
  border-radius: 12px;
  background: var(--ll-brand-glow-gold, rgba(200, 164, 92, .12));
  border: 1px solid rgba(200, 164, 92, .28);
  font-size: 12.5px; color: var(--ll-brand-gold-strong, #80601F);
}
.lr-notice svg { flex-shrink: 0; }
.lr-notice b { flex-shrink: 0; font-weight: 700; }
.lr-notice span { min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }

.lr-state-tip {
  display: flex; align-items: center; gap: 7px;
  margin-top: 10px; padding: 10px 13px;
  border-radius: 12px;
  background: rgba(30, 110, 184, .08);
  border: 1px solid rgba(30, 110, 184, .16);
  font-size: 12.5px; color: var(--ll-brand-blue, #1E6EB8);
}
.lr-state-tip.is-ended { background: rgba(100, 116, 139, .08); border-color: rgba(100, 116, 139, .16); color: var(--ll-muted, #64748B); }
.lr-state-time { margin-left: auto; font-variant-numeric: tabular-nums; }
.lr-state-time em { font-style: normal; }
.lr-products-entry i { font-style: normal; font-variant-numeric: tabular-nums; }

/* ===== 讲解中商品卡 ===== */
.lr-pinned {
  display: flex; align-items: center; gap: 12px;
  margin-top: 12px; padding: 12px;
  background: var(--ll-white, #fff);
  border: 1px solid rgba(155, 45, 32, .14);
  border-radius: 16px;
  cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .25s ease, box-shadow .25s ease;
}
.lr-pinned:hover, .lr-pinned:focus-visible { transform: translateY(-4px); box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 16px 40px rgba(0, 0, 0, .10); outline: none; }
.lr-pinned-cover { flex-shrink: 0; width: 74px; height: 74px; border-radius: 12px; overflow: hidden; background: var(--ll-thumb-bg, #eef1f6); }
.lr-pinned-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.lr-pinned-fallback { width: 100%; height: 100%; display: grid; place-items: center; color: var(--ll-thumb-fg, #b9c3d4); }
.lr-pinned-info { flex: 1; min-width: 0; }
.lr-pinned-info h4 {
  margin: 4px 0 6px; font-size: 14px; font-weight: 600; letter-spacing: -.01em; color: var(--ll-ink, #0F172A);
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
}
.lr-showcase-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border-radius: 999px;
  font-size: 11px; font-weight: 700;
  color: var(--ll-brand-red, #9B2D20);
  background: rgba(155, 45, 32, .09);
}
.lr-price-row { display: flex; align-items: baseline; gap: 8px; }
.lr-live-price { color: var(--ll-price, #d93025); font-size: 18px; font-weight: 800; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.lr-live-price em { font-style: normal; font-size: 12px; font-weight: 700; margin-right: 1px; }
.lr-price-row del { font-size: 12px; color: var(--ll-muted, #64748B); }
.lr-buy-btn { flex-shrink: 0; border-radius: 999px; }

/* ===== 弹幕区 ===== */
.lr-chat {
  display: flex; flex-direction: column;
  border-radius: 16px;
  background: var(--ll-white, #fff);
  border: 1px solid rgba(19, 35, 58, .06);
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  overflow: hidden;
}
@media (min-width: 861px) { .lr-chat { height: 640px; position: sticky; top: 88px; } }
.lr-chat-head {
  display: flex; align-items: center; gap: 6px;
  padding: 11px 14px; font-size: 13px; font-weight: 700; color: var(--ll-ink, #0F172A);
  border-bottom: 1px solid rgba(19, 35, 58, .06);
}
.lr-chat-list { flex: 1; min-height: 180px; max-height: 42vh; overflow-y: auto; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
@media (min-width: 861px) { .lr-chat-list { max-height: none; } }
.lr-chat-empty { margin: auto; font-size: 12.5px; color: var(--ll-muted, #64748B); }
.lr-msg { display: flex; gap: 8px; }
.lr-msg.t-system { justify-content: center; }
.lr-msg-sys {
  font-size: 11.5px; color: var(--ll-muted, #64748B);
  background: rgba(19, 35, 58, .05);
  padding: 3px 10px; border-radius: 999px;
}
.lr-msg-avatar { flex-shrink: 0; width: 28px; height: 28px; border-radius: 50%; object-fit: cover; background: var(--ll-thumb-bg, #eef1f6); }
.lr-msg-body { min-width: 0; }
.lr-msg-name { font-size: 11.5px; color: var(--ll-muted, #64748B); }
.lr-msg-body p {
  margin: 1px 0 0; font-size: 13px; line-height: 1.5; color: var(--ll-ink, #0F172A);
  word-break: break-word; overflow-wrap: anywhere;
}
.lr-msg-body p.is-buy { color: var(--ll-brand-gold-strong, #80601F); font-weight: 700; }
.lr-msg.t-buy .lr-msg-body p {
  background: rgba(200, 164, 92, .14);
  border-radius: 10px; padding: 4px 9px;
  display: inline-block;
}

.lr-chat-input { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-top: 1px solid rgba(19, 35, 58, .06); }
.lr-chat-input .ant-input-affix-wrapper, .lr-chat-input :deep(.ant-input) { border-radius: 999px; }
.lr-like-wrap { position: relative; flex-shrink: 0; }
.lr-like-btn {
  width: 36px; height: 36px; border-radius: 50%;
  border: 0; cursor: pointer;
  display: grid; place-items: center;
  background: rgba(155, 45, 32, .08); color: var(--ll-brand-red, #9B2D20);
  transition: transform .2s ease, background .2s ease;
}
.lr-like-btn:hover { background: rgba(155, 45, 32, .16); }
.lr-like-btn svg.is-liked { animation: lr-like-pop .32s ease; }
@keyframes lr-like-pop { 0% { transform: scale(1); } 45% { transform: scale(1.45); } 100% { transform: scale(1); } }
.lr-heart-float {
  position: absolute; bottom: 34px;
  color: var(--ll-brand-red, #9B2D20);
  pointer-events: none;
  animation: lr-heart-up .9s ease-out forwards;
}
@keyframes lr-heart-up {
  0% { opacity: 0; transform: translateY(4px) scale(.6) translateX(0); }
  18% { opacity: 1; }
  100% { opacity: 0; transform: translateY(-64px) scale(1.15) translateX(var(--drift, 0px)); }
}

/* ===== 商品抽屉 ===== */
.lr-drawer-list { display: flex; flex-direction: column; gap: 12px; }
.lr-drawer-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px; border-radius: 14px; cursor: pointer;
  border: 1px solid rgba(19, 35, 58, .07);
  transition: transform .2s ease, box-shadow .2s ease;
}
.lr-drawer-item:hover { transform: translateY(-2px); box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 20px rgba(0, 0, 0, .08); }
.lr-drawer-cover { flex-shrink: 0; width: 58px; height: 58px; border-radius: 10px; overflow: hidden; background: var(--ll-thumb-bg, #eef1f6); }
.lr-drawer-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.lr-drawer-info { flex: 1; min-width: 0; }
.lr-drawer-info h5 {
  margin: 0 0 4px; font-size: 13px; font-weight: 600; color: var(--ll-ink, #0F172A);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.lr-drawer-info .lr-live-price { font-size: 15px; }

@media (max-width: 560px) {
  .lr-page { padding: 8px 8px 40px; }
  .lr-player { border-radius: 14px; }
  .lr-products-entry { margin-left: 0; }
  .lr-stats-row { gap: 10px; }
}
</style>
