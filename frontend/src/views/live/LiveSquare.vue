<template>
  <div class="ls-page">
    <header class="ls-head">
      <div class="ls-head-copy">
        <h1>直播广场</h1>
        <p>商户直播实时开讲，边看边买一键直达。</p>
      </div>
      <SegmentedControl v-model="status" :options="STATUS_OPTIONS" aria-label="直播状态筛选" @update:modelValue="reload" />
    </header>

    <a-spin :spinning="loading">
      <div v-if="rooms.length" class="ls-grid">
        <article v-for="r in rooms" :key="r.roomId" class="ls-card" role="button" tabindex="0"
                 :aria-label="'进入直播间：' + r.title"
                 @click="goRoom(r)" @keydown.enter.prevent="goRoom(r)">
          <div class="ls-cover">
            <img v-if="r.coverUrl" :src="r.coverUrl" :alt="r.title" loading="lazy" @error="onCoverError($event)" />
            <div v-else class="ls-cover-fallback"><Video :size="30" :stroke-width="1.5" /></div>
            <span class="ls-flag" :class="'st-' + Number(r.status)">
              <i v-if="Number(r.status) === 1" class="ls-dot" aria-hidden="true"></i>
              {{ statusText(r.status) }}
            </span>
            <span v-if="Number(r.status) === 1" class="ls-viewers">
              <Eye :size="13" :stroke-width="2.1" />{{ formatCount(r.viewerCount) }}
            </span>
          </div>
          <div class="ls-meta">
            <h3>{{ r.title }}</h3>
            <div class="ls-sub">
              <span class="ls-merchant"><Store :size="12" :stroke-width="2" />{{ r.merchantName || '官方直播间' }}</span>
              <span v-if="Number(r.status) === 0 && r.startTime" class="ls-time">
                <Clock :size="12" :stroke-width="2" />{{ formatTime(r.startTime) }}<em>&nbsp;开播</em>
              </span>
              <span v-else-if="Number(r.status) === 2 && Number(r.productCount) > 0" class="ls-time">
                {{ r.productCount }}<em>&nbsp;件商品</em>
              </span>
            </div>
          </div>
        </article>
      </div>
      <a-empty v-else-if="!loading" :description="status === 'all' ? '暂无直播，去开播' : '暂无相关直播'" style="padding: 48px 0" />
    </a-spin>
  </div>
</template>

<script setup>
/**
 * 直播广场（/live，公开）：状态分段筛选 + 直播间卡片网格。
 * 数据源 GET /live/rooms?status=，15s 轻轮询保持在线人数/状态新鲜（onUnmounted 清理）。
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Eye, Store, Clock, Video } from 'lucide-vue-next'
import { liveApi } from '../../api'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const STATUS_OPTIONS = [
  { label: '全部', value: 'all' },
  { label: '直播中', value: 1 },
  { label: '预告', value: 0 },
  { label: '已结束', value: 2 }
]

const router = useRouter()
const status = ref('all')
const rooms = ref([])
const loading = ref(false)
let pollTimer = null

async function load() {
  loading.value = true
  try {
    const params = status.value === 'all' ? undefined : status.value
    rooms.value = (await liveApi.rooms(params)) || []
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

function reload() { load() }

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    // 轮询静默刷新：不打断用户当前筛选
    liveApi.rooms(status.value === 'all' ? undefined : status.value, { silent: true })
      .then(list => { rooms.value = list || [] })
      .catch(() => { /* 静默 */ })
  }, 15000)
}

function goRoom(r) {
  router.push('/live/' + r.roomId)
}

function statusText(s) {
  return { 0: '预告', 1: '直播中', 2: '已结束' }[Number(s)] || '直播'
}

function formatCount(n) {
  const v = Number(n || 0)
  return v >= 10000 ? (v / 10000).toFixed(1) + ' 万' : String(v)
}

function formatTime(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(5, 16)
}

function onCoverError(e) {
  e.target.style.display = 'none'
}

onMounted(() => { load(); startPolling() })
onUnmounted(() => { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } })
</script>

<style scoped>
.ls-page { max-width: 1200px; margin: 0 auto; padding: 18px 20px 60px; }
.ls-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; margin-bottom: 20px; }
.ls-head h1 { margin: 0 0 4px; font-size: 26px; font-weight: 700; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.ls-head p { margin: 0; font-size: 13px; color: var(--ll-muted, #64748B); }

.ls-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.ls-card {
  background: var(--ll-white, #fff);
  border: 1px solid rgba(19, 35, 58, .06);
  border-radius: 18px;
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .25s ease, box-shadow .25s ease;
  outline: none;
}
.ls-card:hover, .ls-card:focus-visible { transform: translateY(-4px); box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 16px 40px rgba(0, 0, 0, .10); }

.ls-cover { position: relative; aspect-ratio: 16 / 10; background: var(--ll-thumb-bg, #eef1f6); overflow: hidden; }
.ls-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ls-cover-fallback { width: 100%; height: 100%; display: grid; place-items: center; color: var(--ll-thumb-fg, #b9c3d4); }

.ls-flag {
  position: absolute; left: 10px; top: 10px;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 10px; border-radius: 999px;
  font-size: 11.5px; font-weight: 700; letter-spacing: .02em; color: #fff;
  background: rgba(19, 35, 58, .55); backdrop-filter: blur(8px);
}
.ls-flag.st-1 { background: rgba(155, 45, 32, .88); }
.ls-flag.st-0 { background: rgba(30, 110, 184, .88); }
.ls-dot { width: 6px; height: 6px; border-radius: 50%; background: #fff; animation: ls-blink 1.2s ease-in-out infinite; }
@keyframes ls-blink { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }

.ls-viewers {
  position: absolute; right: 10px; top: 10px;
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 9px; border-radius: 999px;
  font-size: 11.5px; font-weight: 600; color: #fff;
  background: rgba(19, 35, 58, .55); backdrop-filter: blur(8px);
  font-variant-numeric: tabular-nums;
}

.ls-meta { padding: 12px 14px 14px; }
.ls-meta h3 {
  margin: 0 0 6px; font-size: 14.5px; font-weight: 600; letter-spacing: -.01em;
  color: var(--ll-ink, #0F172A);
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
}
.ls-sub { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.ls-merchant, .ls-time { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--ll-muted, #64748B); min-width: 0; }
.ls-time em { font-style: normal; }
.ls-merchant { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.ls-time { flex-shrink: 0; font-variant-numeric: tabular-nums; }

@media (max-width: 860px) {
  .ls-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  .ls-head h1 { font-size: 22px; }
}
@media (max-width: 560px) {
  .ls-page { padding: 14px 12px 48px; }
  .ls-grid { grid-template-columns: 1fr; }
}
</style>
