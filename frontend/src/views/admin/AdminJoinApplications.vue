<template>
  <div class="admin-join">
    <a-card :bordered="false" class="card">
      <div class="page-head">
        <div>
          <div class="eyebrow">JOIN US · REVIEW</div>
          <h2>加入我们审核</h2>
          <p class="lead">统一处理求职与渠道合作申请；审核结果将通过站内消息通知申请人。</p>
        </div>
        <a-button :loading="loading" @click="load">刷新</a-button>
      </div>

      <div class="filters">
        <a-radio-group v-model:value="type" button-style="solid" size="small" @change="load">
          <a-radio-button value="">全部类型</a-radio-button>
          <a-radio-button value="job">求职</a-radio-button>
          <a-radio-button value="partner">渠道合作</a-radio-button>
        </a-radio-group>
        <a-radio-group v-model:value="status" button-style="solid" size="small" @change="load">
          <a-radio-button value="">全部状态</a-radio-button>
          <a-radio-button value="PENDING">待审核</a-radio-button>
          <a-radio-button value="APPROVED">已通过</a-radio-button>
          <a-radio-button value="REJECTED">未通过</a-radio-button>
        </a-radio-group>
        <span class="count">{{ countText }}</span>
      </div>

      <a-table :data-source="rows" :columns="cols" row-key="id" size="middle"
               :pagination="{ pageSize: 10, showSizeChanger: false }" :loading="loading">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <a-tag :color="record.applyType === 'job' ? 'blue' : 'gold'">{{ typeLabel(record.applyType) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'target'">
            <div class="target">
              <strong>{{ target(record) }}</strong>
              <small v-if="record.applyType === 'partner' && record.city">{{ record.city }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'contact'">
            <div class="contact">
              <span>{{ record.contactName }}</span>
              <small>{{ record.phone }}<template v-if="record.email"> · {{ record.email }}</template></small>
            </div>
          </template>
          <template v-else-if="column.key === 'status'">
            <span class="status" :class="statusClass(record.status)">{{ statusLabel(record.status) }}</span>
          </template>
          <template v-else-if="column.key === 'applyTime'">
            {{ formatTime(record.applyTime) }}
          </template>
          <template v-else-if="column.key === 'op'">
            <a-button v-if="record.status === 'PENDING'" size="small" type="primary" ghost @click="openReview(record)">审核</a-button>
            <a-button v-else size="small" @click="openReview(record, true)">查看</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="reviewOpen" :title="readonly ? '申请详情' : '审核申请'" :width="600"
             :confirm-loading="submitting" :ok-text="readonly ? '关闭' : '确认提交'"
             cancel-text="取消" :ok-button-props="{ danger: !readonly && !approved }"
             @ok="readonly ? (reviewOpen = false) : submitReview()">
      <div v-if="current" class="review">
        <div class="review-grid">
          <div><span>申请类型</span><b>{{ typeLabel(current.applyType) }}</b></div>
          <div><span>{{ current.applyType === 'job' ? '应聘职位' : '公司/机构' }}</span><b>{{ target(current) }}</b></div>
          <div><span>联系人</span><b>{{ current.contactName }}</b></div>
          <div><span>联系电话</span><b>{{ current.phone }}</b></div>
          <div><span>邮箱</span><b>{{ current.email || '—' }}</b></div>
          <div><span>{{ current.applyType === 'job' ? '工作年限' : '意向区域' }}</span><b>{{ (current.applyType === 'job' ? current.experience : current.city) || '—' }}</b></div>
          <div><span>提交时间</span><b>{{ formatTime(current.applyTime) }}</b></div>
          <div><span>当前状态</span><b>{{ statusLabel(current.status) }}</b></div>
        </div>
        <div v-if="current.message" class="review-block">
          <span class="block-label">{{ current.applyType === 'job' ? '自我介绍' : '合作说明' }}</span>
          <p>{{ current.message }}</p>
        </div>
        <div v-if="current.reviewRemark" class="review-block">
          <span class="block-label">历史审核备注</span>
          <p>{{ current.reviewRemark }}</p>
        </div>

        <template v-if="!readonly">
          <a-radio-group v-model:value="approved" class="review-radio">
            <a-radio :value="true">通过</a-radio>
            <a-radio :value="false">驳回</a-radio>
          </a-radio-group>
          <a-textarea v-model:value="remark" :rows="3" :maxlength="255" show-count
                      :placeholder="approved ? '备注（选填）' : '驳回原因（必填，将通知申请人）'" />
        </template>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { joinApi } from '../../api'
import { locale } from '../../i18n'

const countText = computed(() => locale.value === 'en-US' ? `${rows.value.length} applications` : `共 ${rows.value.length} 条`)

const loading = ref(false)
const rows = ref([])
const type = ref('')
const status = ref('')

const reviewOpen = ref(false)
const readonly = ref(false)
const submitting = ref(false)
const current = ref(null)
const approved = ref(true)
const remark = ref('')

const cols = [
  { title: '类型', key: 'type', width: 110 },
  { title: '申请方向', key: 'target', width: 220 },
  { title: '联系人', key: 'contact', width: 220 },
  { title: '状态', key: 'status', width: 100 },
  { title: '提交时间', key: 'applyTime', width: 150 },
  { title: '操作', key: 'op', width: 100 }
]

const TYPE_LABEL = { job: '求职', partner: '渠道合作' }
const STATUS_LABEL = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '未通过' }
function typeLabel(t) { return TYPE_LABEL[t] || t || '—' }
function statusLabel(s) { return STATUS_LABEL[s] || s || '—' }
function statusClass(s) { return s === 'APPROVED' ? 'ok' : s === 'REJECTED' ? 'no' : 'pending' }
function target(r) { return r.applyType === 'job' ? (r.position || '—') : (r.companyName || '—') }
function formatTime(v) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    rows.value = (await joinApi.adminList({
      applyType: type.value || undefined,
      status: status.value || undefined
    })) || []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openReview(record, viewOnly = false) {
  current.value = record
  readonly.value = viewOnly || record.status !== 'PENDING'
  approved.value = true
  remark.value = ''
  reviewOpen.value = true
}

async function submitReview() {
  if (!current.value) return
  if (!approved.value && !remark.value.trim()) {
    message.warning('驳回时必须填写原因')
    return
  }
  submitting.value = true
  try {
    await joinApi.review(current.value.id, { approved: approved.value, remark: remark.value.trim() })
    message.success(approved.value ? '已通过该申请' : '已驳回该申请')
    reviewOpen.value = false
    await load()
  } catch (e) {
    // 拦截器统一提示
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.admin-join { max-width: 1200px; margin: 0 auto; }
.card { border-radius: 18px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.eyebrow { font-size: 10.5px; font-weight: 800; letter-spacing: .18em; color: var(--ll-brand-gold-strong, #92700F); }
.page-head h2 { margin: 6px 0 4px; font-size: 24px; letter-spacing: -.02em; }
.lead { margin: 0; color: var(--ll-muted, #667085); font-size: 13px; }
.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.count { margin-left: auto; font-size: 12.5px; color: var(--ll-muted, #667085); }
.target { display: flex; flex-direction: column; }
.target small { color: var(--ll-muted, #667085); font-size: 11.5px; }
.contact { display: flex; flex-direction: column; }
.contact small { color: var(--ll-muted, #667085); font-size: 11.5px; }
.status { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 999px; }
.status.pending { background: rgba(245, 158, 11, .12); color: #B45309; }
.status.ok { background: rgba(16, 185, 129, .12); color: #047857; }
.status.no { background: rgba(239, 68, 68, .1); color: #B91C1C; }
.review-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.review-grid > div { display: flex; flex-direction: column; gap: 2px; }
.review-grid span { font-size: 11.5px; color: var(--ll-muted, #667085); }
.review-grid b { font-size: 13.5px; color: var(--ll-ink, #0F172A); font-weight: 600; }
.review-block { margin-top: 14px; }
.block-label { font-size: 11.5px; color: var(--ll-muted, #667085); }
.review-block p { margin: 4px 0 0; font-size: 13px; line-height: 1.7; color: #334155; white-space: pre-wrap; }
.review-radio { margin: 16px 0 10px; }
</style>
