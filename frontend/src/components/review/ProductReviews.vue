<template>
  <div class="rv-wrap">
    <div v-if="summary.count" class="rv-summary">
      <div class="rv-score">
        <b>{{ summary.average }}</b>
        <a-rate :value="summary.average" allow-half disabled />
        <span class="rv-meta">{{ summary.count }} 条评价 · 好评率 {{ summary.goodRate }}%</span>
      </div>
      <div class="rv-bars">
        <div v-for="star in [5, 4, 3, 2, 1]" :key="star" class="rv-bar">
          <span class="rv-star">{{ star }} 星</span>
          <a-progress
            :percent="summary.count ? Math.round((summary.distribution[star] || 0) * 100 / summary.count) : 0"
            :show-info="false"
            size="small"
            :stroke-color="star >= 4 ? '#f59e0b' : '#cbd5e1'"
          />
          <span class="rv-cnt">{{ summary.distribution[star] || 0 }}</span>
        </div>
      </div>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !summary.count" description="暂无评价，期待你的第一条评价" />
      <ul v-else class="rv-list">
        <li v-for="r in list" :key="r.reviewId" class="rv-item">
          <div class="rv-head">
            <span class="rv-name" data-no-translate>{{ r.nickname || '匿名买家' }}</span>
            <a-rate :value="r.rating" disabled />
            <span class="rv-time">{{ shortTime(r.createTime) }}</span>
          </div>
          <p v-if="r.content" class="rv-content" data-no-translate>{{ r.content }}</p>
          <div v-if="r.merchantReply" class="rv-reply">
            <b>商家回复</b>
            <span data-no-translate>{{ r.merchantReply }}</span>
          </div>
        </li>
      </ul>
    </a-spin>

    <div v-if="total > size" class="rv-pager">
      <a-pagination v-model:current="current" :total="total" :page-size="size" size="small" @change="load" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { reviewApi } from '../../api'

const props = defineProps({ prodId: { type: [Number, String], required: true } })

const loading = ref(false)
const list = ref([])
const total = ref(0)
const current = ref(1)
const size = 10
const summary = ref({ count: 0, average: 0, goodRate: 0, distribution: {} })

function shortTime(v) {
  return String(v || '').replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    const [page, sum] = await Promise.all([
      reviewApi.list(props.prodId, { page: current.value, size }),
      reviewApi.summary(props.prodId)
    ])
    list.value = page?.records || []
    total.value = page?.total || 0
    summary.value = sum || { count: 0, average: 0, goodRate: 0, distribution: {} }
  } catch (e) {
    // 详情页的次要区块：失败不打断主流程，拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rv-wrap { display: flex; flex-direction: column; gap: 18px; }
.rv-summary { display: flex; gap: 40px; flex-wrap: wrap; padding: 16px; background: #fafafa; border-radius: 12px; }
.rv-score { display: flex; flex-direction: column; gap: 6px; min-width: 180px; }
.rv-score b { font-size: 34px; line-height: 1; color: #b45309; }
.rv-meta { color: #6b7280; font-size: 13px; }
.rv-bars { flex: 1; min-width: 240px; display: flex; flex-direction: column; gap: 4px; }
.rv-bar { display: flex; align-items: center; gap: 10px; }
.rv-star { width: 34px; color: #6b7280; font-size: 12px; }
.rv-bar :deep(.ant-progress) { flex: 1; margin: 0; }
.rv-cnt { width: 32px; text-align: right; color: #6b7280; font-size: 12px; }
.rv-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 16px; }
.rv-item { border-bottom: 1px solid #f0f0f0; padding-bottom: 12px; }
.rv-head { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.rv-name { font-weight: 600; }
.rv-time { margin-left: auto; color: #9ca3af; font-size: 12px; }
.rv-content { margin: 0 0 8px; white-space: pre-wrap; word-break: break-word; }
.rv-reply { background: #f8fafc; border-radius: 8px; padding: 8px 12px; font-size: 13px; color: #475569; display: flex; gap: 8px; }
.rv-pager { display: flex; justify-content: flex-end; }
</style>
