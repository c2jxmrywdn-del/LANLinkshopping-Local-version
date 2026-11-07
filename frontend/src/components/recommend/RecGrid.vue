<template>
  <section class="rec-grid" v-if="items.length">
    <div class="rec-head">
      <div class="rec-title">
        <h3>{{ personalized ? '猜你喜欢' : '热门商品' }}</h3>
        <span class="rec-note">{{ personalized ? '根据你的浏览偏好推荐' : '平台热销排行' }}</span>
      </div>
      <button v-if="personalized" type="button" class="rec-switch" @click="$emit('disable')">
        关闭个性化推荐
      </button>
    </div>
    <div class="rec-items">
      <div
        v-for="p in items" :key="p.prodId"
        class="rec-card" @click="goDetail(p)"
      >
        <img :src="resolveStorageUrl(p.coverUrl) || fallbackImg" :alt="p.title" loading="lazy" />
        <div class="rec-info">
          <p class="rec-name">{{ p.title }}</p>
          <span class="rec-price">¥{{ p.price }}</span>
          <span class="rec-sales">销量 {{ p.sales || 0 }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { recommendApi, trafficApi } from '../../api'
import { resolveStorageUrl } from '../../utils/storageUrl'

const router = useRouter()

defineProps({
  personalized: { type: Boolean, default: false }
})
const emit = defineEmits(['personalized', 'disable'])

const items = ref([])
const fallbackImg = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="90"><rect width="100%" height="100%" fill="#eef1f5"/></svg>')

onMounted(async () => {
  try {
    const r = await recommendApi.home(8)
    items.value = r?.items || []
    if (r?.personalized !== undefined) {
      // 个性化状态回传父组件（未授权时父组件显示"热门商品"语义）
      emitPersonalized(r.personalized)
    }
    // 推荐位曝光埋点：sourceType='recommend' 与有机流量隔离（平台看板按来源算 CTR，
    // 共现/热度物化排除该来源）。analytics 门控在 trackPage 内，未授权时静默跳过。
    const seen = new Set()
    items.value.forEach(p => {
      if (!p?.prodId || seen.has(p.prodId)) return
      seen.add(p.prodId)
      trackRec('VIEW_PRODUCT', p)
    })
  } catch (e) { /* 推荐位静默失败，不影响主列表 */ }
})

/** 推荐位点击：跳详情 + 点击埋点（推荐位 CTR 评估的分子，与曝光同来源口径） */
function goDetail(p) {
  trackRec('CLICK_PRODUCT', p)
  router.push('/product/' + p.prodId)
}

function trackRec(eventType, p) {
  trafficApi.trackPage({
    merchantId: p.merId,
    productId: p.prodId,
    eventType,
    sourceType: 'recommend'
  }).catch(() => {})
}

function emitPersonalized(v) {
  emit('personalized', !!v)
}
</script>

<style scoped>
.rec-grid { margin-top: 20px; padding: 18px; border: 1px solid #e6e9ef; border-radius: 16px; background: rgba(255,255,255,.9); }
.rec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.rec-title { display: flex; align-items: baseline; gap: 10px; }
.rec-title h3 { margin: 0; color: #13233A; font-size: 16px; }
.rec-note { color: #98A2B3; font-size: 11.5px; }
.rec-switch { border: 0; background: transparent; color: #667085; font: inherit; font-size: 11.5px; cursor: pointer; }
.rec-switch:hover { color: #13233A; text-decoration: underline; }
.rec-items { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.rec-card { border: 1px solid #edf0f3; border-radius: 12px; overflow: hidden; cursor: pointer; transition: .18s ease; background: #fff; }
.rec-card:hover { transform: translateY(-2px); box-shadow: 0 10px 24px rgba(15,23,42,.08); }
.rec-card img { width: 100%; height: 110px; object-fit: cover; display: block; background: #eef1f5; }
.rec-info { padding: 9px 10px 11px; }
.rec-name { margin: 0 0 5px; color: #13233A; font-size: 12px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 33px; }
.rec-price { color: #C2410C; font-weight: 800; font-size: 13px; margin-right: 8px; }
.rec-sales { color: #98A2B3; font-size: 11px; }
@media (max-width: 767px) {
  .rec-items { grid-template-columns: repeat(2, 1fr); }
  .rec-card img { height: 96px; }
}
</style>
