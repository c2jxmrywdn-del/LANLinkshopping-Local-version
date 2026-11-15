<template>
  <div class="rv-wrap">
    <!-- 订单评价聚合（t_order_review 新链路）：与旧行级评价并列展示 -->
    <div v-if="orderSummary.count" class="rv-summary rv-order-summary">
      <div class="rv-score">
        <b>{{ orderSummary.average }}</b>
        <a-rate :value="orderSummary.average" allow-half disabled />
        <span class="rv-meta">{{ orderSummaryMeta }}</span>
      </div>
      <div class="rv-bars">
        <div v-for="star in [5, 4, 3, 2, 1]" :key="star" class="rv-bar">
          <span class="rv-star">{{ starLabel(star) }}</span>
          <a-progress
            :percent="orderSummary.count ? Math.round((orderSummary.distribution[star] || 0) * 100 / orderSummary.count) : 0"
            :show-info="false"
            size="small"
            :stroke-color="star >= 4 ? '#f59e0b' : '#cbd5e1'"
          />
          <span class="rv-cnt">{{ orderSummary.distribution[star] || 0 }}</span>
        </div>
      </div>
    </div>

    <div v-if="orderSummary.count" class="rv-order-title">{{ txt.orderReviews }}</div>
    <a-spin :spinning="orderLoading">
      <ul v-if="orderList.length" class="rv-list">
        <li v-for="r in orderList" :key="r.reviewId" class="rv-item">
          <div class="rv-head">
            <span class="rv-name" data-no-translate>{{ r.nickname || txt.anonymous }}</span>
            <a-rate :value="r.rating" disabled />
            <span class="rv-time">{{ shortTime(r.createdAt) }}</span>
          </div>
          <p v-if="r.content" class="rv-content" data-no-translate>{{ r.content }}</p>
          <div v-if="r.attachments?.length" class="rv-att">
            <img v-for="(a, i) in r.attachments" :key="a + i" :src="a" alt="" loading="lazy" decoding="async" class="rv-att-img" />
          </div>
        </li>
      </ul>
    </a-spin>

    <div v-if="summary.count" class="rv-order-title">{{ txt.itemReviews }}</div>
    <div v-if="summary.count" class="rv-summary">
      <div class="rv-score">
        <b>{{ summary.average }}</b>
        <a-rate :value="summary.average" allow-half disabled />
        <span class="rv-meta">{{ summaryMeta }}</span>
      </div>
      <div class="rv-bars">
        <div v-for="star in [5, 4, 3, 2, 1]" :key="star" class="rv-bar">
          <span class="rv-star">{{ starLabel(star) }}</span>
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
      <a-alert v-if="loadError" type="error" show-icon :message="txt.loadFailed">
        <template #action>
          <a-button size="small" @click="load">{{ txt.retry }}</a-button>
        </template>
      </a-alert>
      <a-empty v-else-if="!loading && !summary.count" :description="txt.empty" />
      <ul v-else class="rv-list">
        <li v-for="r in list" :key="r.reviewId" class="rv-item">
          <div class="rv-head">
            <span class="rv-name" data-no-translate>{{ r.nickname || txt.anonymous }}</span>
            <a-rate :value="r.rating" disabled />
            <span class="rv-time">{{ shortTime(r.createTime) }}</span>
          </div>
          <p v-if="r.content" class="rv-content" data-no-translate>{{ r.content }}</p>
          <div v-if="r.merchantReply" class="rv-reply">
            <b>{{ txt.reply }}</b>
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
import { computed, onMounted, ref } from 'vue'
import { reviewApi } from '../../api'
import { locale } from '../../i18n'

const props = defineProps({ prodId: { type: [Number, String], required: true } })

const loading = ref(false)
const loadError = ref(false)
const list = ref([])
const total = ref(0)
const current = ref(1)
const size = 10
const summary = ref({ count: 0, average: 0, goodRate: 0, distribution: {} })

// 订单维度评价（t_order_review）独立加载：失败不打断旧行级评价展示
const orderLoading = ref(false)
const orderList = ref([])
const orderSummary = ref({ count: 0, average: 0, goodRate: 0, distribution: {} })

const isEn = computed(() => locale.value === 'en-US')
const txt = computed(() => isEn.value ? {
  empty: 'No reviews yet — be the first to share your experience',
  reply: 'Merchant reply',
  anonymous: 'Anonymous buyer',
  loadFailed: 'Reviews could not be loaded',
  retry: 'Retry',
  reviews: 'reviews',
  goodRate: 'positive',
  orderReviews: 'Order reviews (from completed orders)',
  itemReviews: 'Historical product reviews'
} : {
  empty: '暂无评价，期待你的第一条评价',
  reply: '商家回复',
  anonymous: '匿名买家',
  loadFailed: '评价加载失败',
  retry: '重试',
  reviews: '条评价',
  goodRate: '好评率',
  orderReviews: '订单评价（来自已完成订单）',
  itemReviews: '历史商品评价'
})

// 计数与好评率本是一句话，插值会把它拆成多个文本节点导致语序错乱，故整体成句
const summaryMeta = computed(() => isEn.value
  ? `${summary.value.count} ${txt.value.reviews} · ${summary.value.goodRate}% ${txt.value.goodRate}`
  : `${summary.value.count} ${txt.value.reviews} · ${txt.value.goodRate} ${summary.value.goodRate}%`)
const orderSummaryMeta = computed(() => isEn.value
  ? `${orderSummary.value.count} ${txt.value.reviews} · ${orderSummary.value.goodRate}% ${txt.value.goodRate}`
  : `${orderSummary.value.count} ${txt.value.reviews} · ${txt.value.goodRate} ${orderSummary.value.goodRate}%`)

function starLabel(star) {
  return isEn.value ? `${star} ${star === 1 ? 'star' : 'stars'}` : `${star} 星`
}

function shortTime(v) {
  return String(v || '').replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [page, sum] = await Promise.all([
      reviewApi.list(props.prodId, { page: current.value, size }),
      reviewApi.summary(props.prodId)
    ])
    list.value = page?.records || []
    total.value = page?.total || 0
    summary.value = sum || { count: 0, average: 0, goodRate: 0, distribution: {} }
  } catch (e) {
    // 详情页的次要区块：失败不打断主流程，但必须与"暂无评价"区分开
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 订单评价聚合：新链路独立请求，与旧行级评价互不阻塞 */
async function loadOrderReviews() {
  orderLoading.value = true
  try {
    const [page, sum] = await Promise.all([
      reviewApi.orderList(props.prodId, { page: 1, size: 10 }),
      reviewApi.orderSummary(props.prodId)
    ])
    orderList.value = page?.records || []
    orderSummary.value = sum || { count: 0, average: 0, goodRate: 0, distribution: {} }
  } catch (e) {
    orderSummary.value = { count: 0, average: 0, goodRate: 0, distribution: {} }
  } finally {
    orderLoading.value = false
  }
}

onMounted(() => { load(); loadOrderReviews() })
</script>

<style scoped>
.rv-wrap { display: flex; flex-direction: column; gap: 18px; }
.rv-summary { display: flex; gap: 40px; flex-wrap: wrap; padding: 16px; background: #fafafa; border-radius: 12px; }
.rv-score { display: flex; flex-direction: column; gap: 6px; min-width: 180px; }
.rv-score b { font-size: 34px; line-height: 1; color: #b45309; }
.rv-meta { color: #6b7280; font-size: 13px; }
.rv-bars { flex: 1; min-width: 240px; display: flex; flex-direction: column; gap: 4px; }
.rv-bar { display: flex; align-items: center; gap: 10px; }
.rv-star { width: 46px; color: #6b7280; font-size: 12px; }
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
/* 订单评价区块标题与晒图 */
.rv-order-title { font-size: 13px; font-weight: 600; color: #475569; }
.rv-att { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 6px; }
.rv-att-img { width: 72px; height: 72px; object-fit: cover; border-radius: 8px; border: 1px solid #f0f0f0; }
</style>
