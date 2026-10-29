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
        class="rec-card" @click="$router.push('/product/' + p.prodId)"
      >
        <img :src="p.coverUrl || fallbackImg" :alt="p.title" loading="lazy" />
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
import { recommendApi } from '../../api'

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
  } catch (e) { /* 推荐位静默失败，不影响主列表 */ }
})

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
