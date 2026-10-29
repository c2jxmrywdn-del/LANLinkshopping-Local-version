<template>
  <div class="join-panel">
    <div class="jp-head">
      <div>
        <div class="jp-title">我的加入申请</div>
        <div class="jp-sub">求职与渠道合作申请的处理进度，结果会同步到站内消息。</div>
      </div>
      <div class="jp-actions">
        <a-button size="small" :loading="loading" @click="load">刷新</a-button>
        <a-button size="small" type="primary" ghost @click="goJoin">继续申请</a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <div v-if="!items.length && !loading" class="jp-empty">
        <div class="jp-empty-icon"><AppleIcon name="sparkles" :size="28" /></div>
        <p>还没有提交过申请</p>
        <a-button type="primary" @click="goJoin">去「关于我们 · 加入我们」看看</a-button>
      </div>

      <div v-else class="jp-list">
        <article v-for="it in items" :key="it.id" class="jp-card">
          <div class="jp-card-top">
            <span class="jp-type" :class="it.applyType">{{ typeLabel(it.applyType) }}</span>
            <span class="jp-status" :class="statusClass(it.status)">{{ statusLabel(it.status) }}</span>
          </div>
          <h4>{{ target(it) }}</h4>
          <div class="jp-meta">
            <span>{{ formatTime(it.applyTime) }}</span>
            <span v-if="it.city">{{ it.city }}</span>
            <span v-if="it.experience">{{ it.experience }}</span>
          </div>
          <p v-if="it.message" class="jp-message">{{ it.message }}</p>
          <div v-if="it.reviewRemark" class="jp-remark">
            <span class="jp-remark-label">审核备注</span>
            <span>{{ it.reviewRemark }}</span>
          </div>
          <div v-if="it.reviewedTime" class="jp-reviewed">审核于 {{ formatTime(it.reviewedTime) }}</div>
        </article>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { joinApi } from '../../api'
import AppleIcon from '../ui/AppleIcon.vue'

const router = useRouter()
const loading = ref(false)
const items = ref([])

const TYPE_LABEL = { job: '求职申请', partner: '渠道合作' }
const STATUS_LABEL = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '未通过' }

function typeLabel(t) { return TYPE_LABEL[t] || t || '申请' }
function statusLabel(s) { return STATUS_LABEL[s] || s || '—' }
function statusClass(s) {
  return s === 'APPROVED' ? 'ok' : s === 'REJECTED' ? 'no' : 'pending'
}
function target(it) {
  return it.applyType === 'job' ? (it.position || '求职申请') : (it.companyName || '渠道合作')
}
function formatTime(v) {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
function goJoin() { router.push('/about#join') }

async function load() {
  loading.value = true
  try {
    items.value = (await joinApi.my()) || []
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.join-panel { padding: 4px 0; }
.jp-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.jp-title { font-size: 16px; font-weight: 700; color: var(--ll-ink, #0F172A); letter-spacing: -.01em; }
.jp-sub { margin-top: 4px; font-size: 12.5px; color: var(--ll-muted, #667085); }
.jp-actions { display: flex; gap: 8px; }
.jp-empty { padding: 44px 0; text-align: center; color: var(--ll-muted, #667085); }
.jp-empty-icon { font-size: 28px; margin-bottom: 8px; color: var(--ll-brand-gold, #C8A45C); }
.jp-empty p { margin: 0 0 14px; font-size: 13.5px; }
.jp-list { display: flex; flex-direction: column; gap: 12px; }
.jp-card {
  padding: 18px 20px; border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, .07);
  background: linear-gradient(160deg, rgba(255,255,255,.9), rgba(255,255,255,.6));
  box-shadow: 0 10px 26px rgba(15, 23, 42, .05);
}
.jp-card-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.jp-type { font-size: 11px; font-weight: 700; letter-spacing: .04em; padding: 3px 10px; border-radius: 999px; background: rgba(37, 99, 235, .08); color: #2563EB; }
.jp-type.partner { background: rgba(200, 164, 92, .14); color: #92700F; }
.jp-status { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 999px; }
.jp-status.pending { background: rgba(245, 158, 11, .12); color: #B45309; }
.jp-status.ok { background: rgba(16, 185, 129, .12); color: #047857; }
.jp-status.no { background: rgba(239, 68, 68, .1); color: #B91C1C; }
.jp-card h4 { margin: 2px 0 6px; font-size: 15px; font-weight: 650; color: var(--ll-ink, #0F172A); }
.jp-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--ll-muted, #667085); }
.jp-message { margin: 10px 0 0; font-size: 13px; line-height: 1.7; color: #475569; }
.jp-remark { margin-top: 10px; padding: 10px 12px; border-radius: 12px; background: rgba(15, 23, 42, .04); font-size: 12.5px; color: #475569; display: flex; gap: 8px; }
.jp-remark-label { flex: 0 0 auto; font-weight: 650; color: #334155; }
.jp-reviewed { margin-top: 8px; font-size: 11.5px; color: var(--ll-muted, #94A3B8); }
[data-theme='dark'] .jp-card { background: linear-gradient(160deg, rgba(30,41,59,.7), rgba(15,23,42,.5)); border-color: rgba(255,255,255,.08); }
[data-theme='dark'] .jp-card h4 { color: #F1F5F9; }
[data-theme='dark'] .jp-message, [data-theme='dark'] .jp-remark { color: #CBD5E1; }
</style>
