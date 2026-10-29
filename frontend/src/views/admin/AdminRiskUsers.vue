<template>
  <div class="risk-page">
    <a-page-header title="用户风险名单" sub-title="风控打分卡（30 天窗口）— 只分级提示，处置走人工流程" />

    <div class="risk-toolbar">
      <a-select v-model:value="level" style="width:140px" @change="load">
        <a-select-option value="">全部级别</a-select-option>
        <a-select-option value="REVIEW">REVIEW（建议人工处理）</a-select-option>
        <a-select-option value="WATCH">WATCH（关注）</a-select-option>
        <a-select-option value="NORMAL">NORMAL</a-select-option>
      </a-select>
      <a-input-number v-model:value="minScore" :min="0" :max="100" placeholder="最低分" style="width:120px" @change="load" />
      <a-button type="primary" @click="load">刷新</a-button>
    </div>

    <a-table
      :data-source="rows" :loading="loading" row-key="userId"
      :pagination="{ current: page, pageSize: size, total, onChange: p => { page = p; load() } }"
    >
      <a-table-column title="用户ID" data-index="userId" :width="110" />
      <a-table-column title="风险分" data-index="totalScore" :width="100">
        <template #default="{ record }">
          <span class="risk-score" :class="record.level?.toLowerCase()">{{ record.totalScore }}</span>
        </template>
      </a-table-column>
      <a-table-column title="级别" data-index="level" :width="130">
        <template #default="{ record }">
          <a-tag :color="record.level === 'REVIEW' ? 'red' : record.level === 'WATCH' ? 'orange' : 'default'">
            {{ record.level }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="命中次数" data-index="hitCount" :width="100" />
      <a-table-column title="最近命中" data-index="lastEventTime" :width="170" />
      <a-table-column title="操作" :width="120">
        <template #default="{ record }">
          <a-button size="small" @click="openDetail(record)">明细</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-drawer v-model:open="drawer" title="命中明细" width="560">
      <a-table :data-source="events" :loading="eventsLoading" row-key="id" :pagination="false" size="small">
        <a-table-column title="规则" data-index="ruleCode" :width="70" />
        <a-table-column title="分值" data-index="score" :width="60" />
        <a-table-column title="详情" data-index="detail" />
        <a-table-column title="时间" data-index="createTime" :width="150" />
        <a-table-column title="复核" :width="150">
          <template #default="{ record }">
            <a-tag v-if="record.reviewedStatus === 0" color="default">已标误报</a-tag>
            <a-tag v-else-if="record.reviewedStatus === 1" color="green">已确认</a-tag>
            <template v-else>
              <a-button size="small" type="link" @click="review(record, 0)">误报</a-button>
              <a-button size="small" type="link" danger @click="review(record, 1)">确认</a-button>
            </template>
          </template>
        </a-table-column>
      </a-table>
    </a-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import request from '../../api/request'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const level = ref('')
const minScore = ref(null)
const loading = ref(false)
const drawer = ref(false)
const events = ref([])
const eventsLoading = ref(false)
const currentUserId = ref(null)

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size }
    if (level.value) params.level = level.value
    if (minScore.value != null) params.minScore = minScore.value
    const r = await request.get('/admin/risk/users', { params, silent: true })
    // request.js 拦截器已剥壳返回 data（此处为 Page 本体），不再读 r.data
    rows.value = r?.records || []
    total.value = Number(r?.total || 0)
  } catch (e) { /* 静默：列表加载失败保留空态 */ } finally { loading.value = false }
}

async function openDetail(record) {
  currentUserId.value = record.userId
  drawer.value = true
  eventsLoading.value = true
  try {
    const r = await request.get(`/admin/risk/users/${record.userId}/events`, { silent: true })
    events.value = Array.isArray(r) ? r : []
  } finally { eventsLoading.value = false }
}

async function review(event, status) {
  try {
    const r = await request.post(`/admin/risk/users/${currentUserId.value}/review`,
      { eventId: event.id, reviewedStatus: status }, { silent: true })
    message.success(`已复核，该用户风险分 ${r?.totalScore ?? '?'}（${r?.level ?? '?'}）`)
    openDetail({ userId: currentUserId.value })
    load()
  } catch (e) { message.error('复核失败') }
}

onMounted(load)
</script>

<style scoped>
.risk-page { max-width: 1080px; margin: 0 auto; }
.risk-toolbar { display: flex; gap: 12px; margin: 8px 0 16px; }
.risk-score { font-weight: 800; font-size: 15px; color: #13233A; }
.risk-score.watch { color: #D97706; }
.risk-score.review { color: #DC2626; }
</style>
