<template>
  <div class="live-card" :class="{ offline: !isLive }">
    <!-- ===== 播放器区（21st.dev HLS Video Player：headless 自绘控制层） ===== -->
    <div class="player-box" ref="playerBox">
      <video v-if="room" ref="videoRef" class="player-video" playsinline
             :poster="room.coverUrl || ''" @play="onPlay" @pause="onPaused" />
      <div class="player-shade" aria-hidden="true"></div>

      <!-- 左上：LIVE 脉冲徽标（21st.dev Status Dot 模式，品牌蓝脉冲）+ 观看人数 -->
      <div class="live-top">
        <span v-if="isLive" class="live-badge"><i class="live-dot"></i>LIVE</span>
        <span v-else class="live-badge offline-badge"><i class="live-dot gray"></i>{{ badgeText }}</span>
        <span v-if="isLive" class="viewer-chip">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          <b class="viewer-num"><span v-for="(d, i) in viewerDigits" :key="i" class="digit-col">
            <i class="digit-track" :style="{ transform: `translateY(-${Number(d)}em)` }">
              <em v-for="n in 10" :key="n - 1">{{ n - 1 }}</em>
            </i>
          </span></b>
          <span class="viewer-suffix">人观看</span>
        </span>
      </div>

      <!-- 中央播放/暂停按钮（首次点击起播，满足浏览器自动播放策略） -->
      <button v-if="!started" type="button" class="play-btn" aria-label="播放生产线直播" @click="startPlay">
        <i class="play-tri"></i>
      </button>

      <!-- 底部：标题 + 毛玻璃控制条（静音/全屏） -->
      <div class="live-bottom">
        <div class="live-title-row">
          <b class="live-title">{{ room?.title || '生产线直播' }}</b>
          <span class="merchant-chip">{{ merchantName }}</span>
        </div>
        <div class="ctrl-bar" v-if="isLive">
          <button type="button" class="ctrl-btn" :aria-label="muted ? '取消静音' : '静音'" @click="toggleMute">
            <svg v-if="muted" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
            <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>
          </button>
          <span class="ctrl-live-time" v-if="liveMinutes >= 1">已播 {{ liveMinutes }} 分钟</span>
          <button type="button" class="ctrl-btn" aria-label="全屏" @click="goFullscreen">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
          </button>
        </div>
        <div class="ctrl-bar offline-tip" v-else>本场直播已结束，欢迎浏览商品图文介绍</div>
      </div>

      <!-- 弹幕层：近端评论上飘（pointer-events:none 不挡操作） -->
      <div v-if="isLive && danmus.length" class="danmu-layer" aria-hidden="true">
        <span v-for="d in danmus" :key="d.commentId || d.id" class="danmu-item" :style="{ top: d.top + '%', animationDuration: d.dur + 's' }">
          <i class="danmu-avatar">{{ (d.nickname || '客').slice(0, 1) }}</i>{{ d.content }}
        </span>
      </div>
    </div>

    <!-- ===== 直播间互动条：点赞 + 弹幕输入（登录可发言，游客可看） ===== -->
    <div class="live-interact" v-if="isLive">
      <div class="interact-left">
        <button type="button" class="like-btn" :class="{ popped: likePop }" @click="sendLike" aria-label="点赞">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7 10v12"/><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"/></svg>
          <b>{{ likeCount }}</b>
        </button>
        <span class="interact-hint">直播间互动</span>
      </div>
      <form class="danmu-form" @submit.prevent="sendComment">
        <input v-model="draft" maxlength="60" placeholder="说点什么…" :disabled="sending" />
        <button type="submit" :disabled="sending || !draft.trim()">发送</button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import Hls from 'hls.js'
import { liveApi } from '../../api'
import { useUserStore } from '../../store/user'

const props = defineProps({
  /** 直播间对象（后端 LiveRoom：roomId/title/streamUrl/coverUrl/status/viewerCount/notice…） */
  room: { type: Object, required: true },
  /** 商户名（展示用） */
  merchantName: { type: String, default: '' }
})

const user = useUserStore()
const videoRef = ref(null)
const playerBox = ref(null)
const isLive = computed(() => Number(props.room?.status) === 1)
const badgeText = computed(() => {
  const s = Number(props.room?.status)
  if (s === 1) return 'LIVE'
  return s === 0 ? '直播预告' : '直播结束'
})
const liveMinutes = computed(() => {
  const s = props.room?.startTime
  if (!s) return 0
  return Math.max(0, Math.round((Date.now() - new Date(String(s).replace(' ', 'T'))) / 60000))
})

// ===== 播放（hls.js：Safari 原生 HLS，其余走 MSE） =====
const started = ref(false)
const muted = ref(true)
const playing = ref(false)
let hls = null

function startPlay() {
  const v = videoRef.value
  if (!v) return
  muted.value = true // 自动播放策略：静音起播
  v.play().catch(() => {})
}

function onPlay() { playing.value = true; started.value = true }
function onPaused() { playing.value = false }

function attachStream() {
  const v = videoRef.value
  if (!v || !props.room?.streamUrl) return
  const url = props.room.streamUrl
  if (hls) { hls.destroy(); hls = null }
  if (v.canPlayType('application/vnd.apple.mpegurl')) {
    v.src = url // Safari/iOS 原生 HLS
  } else if (Hls.isSupported()) {
    hls = new Hls({ lowLatencyMode: true, liveSyncDurationCount: 3 })
    hls.loadSource(url)
    hls.attachMedia(v)
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal) message.warning('直播流连接中断，正在尝试恢复…')
    })
  } else {
    v.src = url // 最终回落：直链 mp4
  }
  // 直播流默认静音起播（自动播放策略），用户可在控制条取消静音
  v.muted = true
}

function toggleMute() {
  const v = videoRef.value
  if (!v) return
  v.muted = !v.muted
  muted.value = v.muted
  if (!playing.value) v.play().catch(() => {})
}

function goFullscreen() {
  const box = playerBox.value
  if (!box) return
  if (document.fullscreenElement) document.exitFullscreen?.()
  else box.requestFullscreen?.()
}

// ===== 在线人数：Sliding Number 竖滚模式（每位数字一列 0-9 轨道） =====
const viewerCount = ref(Number(props.room?.viewerCount || 0))
const viewerDigits = computed(() => String(viewerCount.value).split(''))
let heartbeatTimer = null
let joined = false

async function heartbeat() {
  if (!isLive.value || !props.room?.roomId) return
  // 进入直播间 join 一次（后端原子 +1），之后周期性拉取房间数刷新；离开页面 leave -1
  if (!joined) {
    joined = true
    try { await liveApi.join(props.room.roomId, { silent: true }) } catch (e) { /* 静默 */ }
  }
  try {
    const room = await liveApi.room(props.room.roomId, { silent: true })
    if (room && typeof room.viewerCount === 'number') viewerCount.value = room.viewerCount
  } catch (e) { /* 静默 */ }
}

// ===== 弹幕与点赞 =====
const danmus = ref([])
const draft = ref('')
const sending = ref(false)
const likeCount = ref(Number(props.room?.likeCount || 0))
const likePop = ref(false)
let commentTimer = null
let danmuSeq = 0

async function pollComments() {
  if (!isLive.value || !props.room?.roomId) return
  try {
    const list = await liveApi.comments(props.room.roomId, 6, { silent: true })
    const fresh = (list || []).slice(-6)
    danmus.value = fresh.map(c => ({
      commentId: c.commentId,
      id: c.id || ++danmuSeq,
      nickname: c.nickname || c.senderName || '访客',
      content: c.content,
      top: 12 + ((danmuSeq++ * 37) % 46),
      dur: 7 + (danmuSeq % 4)
    }))
  } catch (e) { /* 静默 */ }
}

async function sendComment() {
  const text = draft.value.trim()
  if (!text) return
  if (!user.logged) { message.warning('登录后参与弹幕互动'); return }
  sending.value = true
  try {
    await liveApi.comment(props.room.roomId, text)
    draft.value = ''
    pollComments()
  } catch (e) { /* 拦截器已提示 */ }
  sending.value = false
}

let likeLocked = false // 500ms 节流：防连点刷接口
async function sendLike() {
  if (likeLocked) return
  likeLocked = true
  likePop.value = false
  requestAnimationFrame(() => { likePop.value = true })
  try {
    const n = await liveApi.like(props.room.roomId, 1, { silent: true })
    if (typeof n === 'number') likeCount.value = n
    else likeCount.value++
  } catch (e) { likeCount.value++ }
  setTimeout(() => { likeLocked = false }, 500)
}

// ===== 生命周期 =====
watch(() => props.room?.roomId, () => {
  attachStream()
  viewerCount.value = Number(props.room?.viewerCount || 0)
  likeCount.value = Number(props.room?.likeCount || 0)
  joined = false
  if (isLive.value) {
    heartbeat()
    heartbeatTimer = window.setInterval(heartbeat, 20000)
    pollComments()
    commentTimer = window.setInterval(pollComments, 10000)
  }
}, { immediate: true })

onMounted(attachStream)
onBeforeUnmount(() => {
  if (hls) { hls.destroy(); hls = null }
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  if (commentTimer) clearInterval(commentTimer)
  // 离开页面即视为离开直播间（后端 leaveRoom 原子递减）
  if (props.room?.roomId && joined && isLive.value) {
    liveApi.leave(props.room.roomId, { silent: true }).catch(() => {})
  }
})
</script>

<style scoped>
.live-card { position: relative; border-radius: 16px; overflow: hidden; background: #0d1523;
  box-shadow: 0 14px 34px rgba(10, 18, 32, .22); }
.player-box { position: relative; aspect-ratio: 16 / 9; background: #0d1523; }
.player-video { width: 100%; height: 100%; object-fit: cover; display: block; }
.player-shade { position: absolute; inset: 0; pointer-events: none;
  background: linear-gradient(180deg, rgba(6, 12, 24, .45) 0%, transparent 26%, transparent 62%, rgba(6, 12, 24, .66) 100%); }

/* LIVE 徽标：品牌蓝脉冲（Status Dot 模式） */
.live-top { position: absolute; top: 12px; left: 12px; right: 12px; display: flex; align-items: center; gap: 8px; z-index: 3; }
.live-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 11px; border-radius: 999px;
  background: rgba(8, 15, 30, .55); backdrop-filter: blur(8px);
  color: #fff; font-size: 11px; font-weight: 800; letter-spacing: .12em; }
.live-dot { position: relative; width: 8px; height: 8px; border-radius: 50%; background: #38bdf8; }
.live-dot::after { content: ""; position: absolute; inset: 0; border-radius: 50%; background: inherit; animation: liveRipple 1.6s ease-out infinite; }
.live-dot.gray { background: #9ca3af; }
.live-dot.gray::after { animation: none; }
.offline-badge { letter-spacing: .04em; color: #cbd5e1; }
@keyframes liveRipple { to { transform: scale(2.6); opacity: 0; } }

/* 观看人数：eye + 竖滚数字（Sliding Number 模式） */
.viewer-chip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 11px; border-radius: 999px;
  background: rgba(8, 15, 30, .55); backdrop-filter: blur(8px); color: #e2e8f0; font-size: 11.5px; }
.viewer-num { display: inline-flex; overflow: hidden; height: 1.25em; line-height: 1.25em;
  font-variant-numeric: tabular-nums; font-weight: 700; color: #fff; }
.digit-col { display: inline-block; width: .72em; text-align: center; }
.digit-track { display: inline-block; transition: transform .45s cubic-bezier(.16, 1, .3, 1); }
.digit-track em { display: block; height: 1.25em; font-style: normal; }
.viewer-suffix { color: #94a3b8; }

/* 中央播放按钮（点击起播，满足自动播放策略） */
.play-btn { position: absolute; inset: 0; margin: auto; width: 72px; height: 72px; border-radius: 50%;
  border: none; cursor: pointer; z-index: 4; display: grid; place-items: center;
  background: rgba(56, 189, 248, .92); box-shadow: 0 10px 32px rgba(56, 189, 248, .45);
  transition: transform .2s ease; }
.play-btn:hover { transform: scale(1.07); }
.play-tri { display: block; width: 0; height: 0; margin-left: 6px;
  border-left: 20px solid #06263c; border-top: 13px solid transparent; border-bottom: 13px solid transparent; }

/* 底部信息 + 毛玻璃控制条 */
.live-bottom { position: absolute; left: 0; right: 0; bottom: 0; z-index: 3; padding: 12px 14px; }
.live-title-row { display: flex; align-items: center; gap: 9px; margin-bottom: 9px; min-width: 0; }
.live-title { color: #fff; font-size: 14px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.merchant-chip { flex-shrink: 0; font-size: 10.5px; color: #bae6fd; background: rgba(56, 189, 248, .16);
  border: 1px solid rgba(56, 189, 248, .35); border-radius: 999px; padding: 2px 9px; }
.ctrl-bar { display: flex; align-items: center; gap: 9px; padding: 7px 10px; border-radius: 12px;
  background: rgba(10, 18, 32, .55); backdrop-filter: blur(10px); width: fit-content; }
.ctrl-bar.offline-tip { width: auto; color: #cbd5e1; font-size: 11.5px; }
.ctrl-btn { border: 0; background: transparent; color: #e2e8f0; cursor: pointer; width: 26px; height: 26px;
  display: grid; place-items: center; border-radius: 8px; transition: background .15s ease; }
.ctrl-btn:hover { background: rgba(255, 255, 255, .12); }
.ctrl-live-time { color: #94a3b8; font-size: 11px; }

/* 弹幕层 */
.danmu-layer { position: absolute; inset: 0; overflow: hidden; pointer-events: none; z-index: 2; }
.danmu-item { position: absolute; left: 100%; white-space: nowrap; display: inline-flex; align-items: center; gap: 6px;
  color: #fff; font-size: 12px; padding: 4px 11px; border-radius: 999px;
  background: rgba(10, 18, 32, .45); backdrop-filter: blur(6px);
  animation: danmuMove linear forwards; }
.danmu-avatar { font-style: normal; width: 17px; height: 17px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; font-size: 10px;
  background: linear-gradient(135deg, #38bdf8, #0284c7); color: #fff; }
@keyframes danmuMove { from { transform: translateX(0); } to { transform: translateX(calc(-100vw - 100%)); } }

/* 互动条 */
.live-interact { display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  background: linear-gradient(160deg, #f0f9ff, #fff 60%); border-top: 1px solid rgba(14, 165, 233, .16); }
.interact-left { display: flex; align-items: center; gap: 9px; flex-shrink: 0; }
.like-btn { display: inline-flex; align-items: center; gap: 5px; border: 1px solid rgba(14, 165, 233, .35);
  background: #fff; color: #0284c7; border-radius: 999px; padding: 6px 13px; cursor: pointer; font: inherit;
  font-size: 12px; font-weight: 700; transition: transform .15s ease; }
.like-btn.popped { animation: likePop .4s ease; }
@keyframes likePop { 35% { transform: scale(1.22); } }
.interact-hint { color: #64748b; font-size: 11.5px; }
.danmu-form { display: flex; gap: 7px; flex: 1; min-width: 0; }
.danmu-form input { flex: 1; min-width: 0; border: 1px solid #dbe7f3; border-radius: 999px;
  padding: 7px 14px; font: inherit; font-size: 12px; outline: none; background: #fff; color: #1e293b; }
.danmu-form input:focus { border-color: #38bdf8; box-shadow: 0 0 0 3px rgba(56, 189, 248, .15); }
.danmu-form button { border: 0; border-radius: 999px; background: #0ea5e9; color: #fff; cursor: pointer;
  font: inherit; font-size: 12px; font-weight: 700; padding: 6px 15px; }
.danmu-form button:disabled { opacity: .5; cursor: not-allowed; }

@media (prefers-reduced-motion: reduce) {
  .live-dot::after, .danmu-item { animation: none; }
  .digit-track { transition: none; }
}
@media (max-width: 600px) {
  .player-box { aspect-ratio: 4 / 3; }
  .play-btn { width: 58px; height: 58px; }
  .play-tri { border-left-width: 16px; border-top-width: 10px; border-bottom-width: 10px; }
  .live-title { font-size: 12.5px; }
  .danmu-form input { padding: 6px 11px; }
  .interact-hint { display: none; }
}
</style>
