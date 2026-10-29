<template>
  <div class="admin-tickets">
    <a-card :bordered="false" class="card">
      <div class="page-head">
        <div>
          <div class="eyebrow">SUPPORT · TICKETS</div>
          <h2>{{ t('admin.supportTickets.title') }}</h2>
          <p class="lead">{{ t('admin.supportTickets.lead') }}</p>
        </div>
        <a-button :loading="loading" @click="load(1)">{{ t('admin.supportTickets.refresh') }}</a-button>
      </div>

      <div class="filters">
        <a-radio-group v-model:value="status" button-style="solid" size="small" @change="load(1)">
          <a-radio-button value="">{{ t('admin.supportTickets.allStatus') }}</a-radio-button>
          <a-radio-button value="OPEN">{{ t('acct.settings.csStatusOpen') }}</a-radio-button>
          <a-radio-button value="PROCESSING">{{ t('acct.settings.csStatusProcessing') }}</a-radio-button>
          <a-radio-button value="REPLIED">{{ t('acct.settings.csStatusReplied') }}</a-radio-button>
          <a-radio-button value="CLOSED">{{ t('acct.settings.csStatusClosed') }}</a-radio-button>
        </a-radio-group>
        <span class="counts">
          {{ t('acct.settings.csStatusOpen') }} {{ counts.OPEN || 0 }} ·
          {{ t('acct.settings.csStatusProcessing') }} {{ counts.PROCESSING || 0 }} ·
          {{ t('acct.settings.csStatusReplied') }} {{ counts.REPLIED || 0 }} ·
          {{ t('acct.settings.csStatusClosed') }} {{ counts.CLOSED || 0 }}
        </span>
      </div>

      <a-table :data-source="rows" :columns="cols" row-key="id" size="middle" :loading="loading"
               :pagination="pagination" @change="onTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'category'">
            <a-tag :color="catColor(record.category)">{{ catLabel(record.category) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'title'">
            <div class="t-title">{{ record.title }}</div>
            <div class="t-sub">{{ record.contact || '—' }}</div>
          </template>
          <template v-else-if="column.key === 'status'">
            <span class="status" :class="statusClass(record.status)">{{ statusLabel(record.status) }}</span>
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ fmt(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'op'">
            <a-button size="small" type="primary" ghost @click="openDetail(record)">
              {{ record.status === 'REPLIED' || record.status === 'CLOSED'
                 ? t('admin.supportTickets.view') : t('admin.supportTickets.handle') }}
            </a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="isView ? t('admin.supportTickets.detailTitle') : t('admin.supportTickets.handleTitle')"
             :width="640" :footer="null" destroy-on-close>
      <div v-if="current" class="detail">
        <div class="grid">
          <div><span>{{ t('admin.supportTickets.submitUser') }}</span><b>#{{ current.userId }}</b></div>
          <div><span>{{ t('acct.settings.csCategory') }}</span><b>{{ catLabel(current.category) }}</b></div>
          <div class="span2"><span>{{ t('acct.settings.csTitle') }}</span><b>{{ current.title }}</b></div>
          <div><span>{{ t('acct.settings.csContact') }}</span><b>{{ current.contact || '—' }}</b></div>
          <div><span>{{ t('admin.supportTickets.curStatus') }}</span>
            <b><span class="status" :class="statusClass(current.status)">{{ statusLabel(current.status) }}</span></b>
          </div>
          <div><span>{{ t('acct.settings.csSubmittedAt') }}</span><b>{{ fmt(current.createTime) }}</b></div>
          <div v-if="current.replyTime"><span>{{ t('admin.supportTickets.replyTime') }}</span><b>{{ fmt(current.replyTime) }}</b></div>
        </div>
        <div class="block">
          <span class="block-label">{{ t('acct.settings.csContent') }}</span>
          <p>{{ current.content }}</p>
        </div>
        <div v-if="current.reply" class="reply-view">
          <div class="reply-label">{{ t('acct.settings.csReply') }}</div>
          <p>{{ current.reply }}</p>
        </div>

        <a-divider style="margin: 16px 0" />
        <a-textarea v-model:value="reply" :rows="4" :maxlength="2000" show-count
                    :placeholder="t('admin.supportTickets.replyPh')" />
        <div v-if="replyErr" class="reply-err">{{ replyErr }}</div>
        <div class="actions">
          <a-button @click="open = false">{{ t('admin.supportTickets.cancel') }}</a-button>
          <a-button :loading="busy" @click="markProcessing">{{ t('admin.supportTickets.markProcessing') }}</a-button>
          <a-button danger :loading="busy" @click="closeTicket">{{ t('admin.supportTickets.close') }}</a-button>
          <a-button type="primary" :loading="busy" @click="submitReply">{{ t('admin.supportTickets.sendReply') }}</a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { supportTicketApi } from '../../api'
import { t, locale } from '../../i18n'

const CS_STATUS = { OPEN: 'Open', PROCESSING: 'Processing', REPLIED: 'Replied', CLOSED: 'Closed' }
const CAT_COLOR = { account: 'blue', order: 'geekblue', payment: 'gold', merchant: 'purple', other: 'default' }
const CS_STATUS_CLASS = { OPEN: 'pending', PROCESSING: 'proc', REPLIED: 'ok', CLOSED: 'closed' }

const loading = ref(false)
const busy = ref(false)
const rows = ref([])
const counts = ref({})
const status = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)

const open = ref(false)
const current = ref(null)
const reply = ref('')
const replyErr = ref('')
const isView = computed(() => current.value && (current.value.status === 'REPLIED' || current.value.status === 'CLOSED'))

const cols = computed(() => [
  { title: 'ID', key: 'id', width: 70, customRender: ({ record }) => '#' + record.id },
  { title: t('acct.settings.csCategory'), key: 'category', width: 110 },
  { title: t('acct.settings.csTitle'), key: 'title' },
  { title: t('admin.supportTickets.colStatus'), key: 'status', width: 100 },
  { title: t('acct.settings.csSubmittedAt'), key: 'createTime', width: 150 },
  { title: t('admin.supportTickets.colOp'), key: 'op', width: 96 }
])

const pagination = computed(() => ({
  current: page.value, pageSize: size.value, total: total.value,
  showSizeChanger: false, size: 'small'
}))

function catLabel(c) { return t('acct.settings.csCat' + ((c || '').charAt(0).toUpperCase() + (c || 'other').slice(1))) }
function catColor(c) { return CAT_COLOR[c] || 'default' }
function statusLabel(s) { return t('acct.settings.csStatus' + (CS_STATUS[s] || 'Open')) }
function statusClass(s) { return CS_STATUS_CLASS[s] || 'pending' }
function fmt(v) {
  if (!v) return '—'
  const d = new Date(String(v).replace('T', ' ').replace(/-/g, '/'))
  if (Number.isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16)
  return new Intl.DateTimeFormat(locale.value === 'en-US' ? 'en-US' : 'zh-CN',
    { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(d)
}

async function load(toPage) {
  if (toPage) page.value = toPage
  loading.value = true
  try {
    const data = await supportTicketApi.adminList({ status: status.value || undefined, page: page.value, size: size.value })
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total || 0)
    counts.value = data?.counts || {}
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onTableChange(pag) {
  page.value = pag.current || 1
  load()
}

function openDetail(record) {
  current.value = record
  reply.value = ''
  replyErr.value = ''
  open.value = true
}

async function submitReply() {
  const r = reply.value.trim()
  if (r.length < 2) { replyErr.value = t('admin.supportTickets.replyRequired'); return }
  replyErr.value = ''
  busy.value = true
  try {
    const updated = await supportTicketApi.adminReply(current.value.id, r)
    current.value = updated
    message.success(t('admin.supportTickets.replySuccess'), 3)
    open.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    busy.value = false
  }
}

async function markProcessing() {
  await changeStatus('PROCESSING')
}
async function closeTicket() {
  await changeStatus('CLOSED')
}
async function changeStatus(s) {
  busy.value = true
  try {
    const updated = await supportTicketApi.adminSetStatus(current.value.id, s)
    current.value = updated
    message.success(t('admin.supportTickets.statusSuccess'), 3)
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    busy.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.admin-tickets { max-width: 1200px; margin: 0 auto; }
.card { border-radius: 18px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.eyebrow { font-size: 10.5px; font-weight: 800; letter-spacing: .18em; color: var(--ll-brand-gold-strong, #92700F); }
.page-head h2 { margin: 6px 0 4px; font-size: 24px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.lead { margin: 0; color: var(--ll-muted, #667085); font-size: 13px; }
.filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.counts { margin-left: auto; font-size: 12.5px; color: var(--ll-muted, #667085); }
.t-title { color: var(--ll-ink, #0F172A); font-weight: 600; }
.t-sub { color: var(--ll-muted, #667085); font-size: 11.5px; margin-top: 2px; }
.status { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 999px; white-space: nowrap; }
.status.pending { background: rgba(245, 158, 11, .12); color: #B45309; }
.status.proc { background: rgba(59, 130, 246, .12); color: #1D4ED8; }
.status.ok { background: rgba(16, 185, 129, .12); color: #047857; }
.status.closed { background: rgba(100, 116, 139, .14); color: #475569; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.grid > div { display: flex; flex-direction: column; gap: 2px; }
.grid .span2 { grid-column: 1 / -1; }
.grid span { font-size: 11.5px; color: var(--ll-muted, #667085); }
.grid b { font-size: 13.5px; color: var(--ll-ink, #0F172A); font-weight: 600; }
.block { margin-top: 14px; }
.block-label { font-size: 11.5px; color: var(--ll-muted, #667085); }
.block p { margin: 4px 0 0; font-size: 13px; line-height: 1.7; color: #334155; white-space: pre-wrap; }
.reply-view { margin-top: 14px; padding: 10px 12px; border-radius: 10px; background: var(--ll-brand-gold-soft, #F6EFCF); border-left: 3px solid var(--ll-brand-gold, #C9A24B); }
.reply-label { font-size: 12px; color: var(--ll-brand-gold-strong, #92700F); font-weight: 700; margin-bottom: 4px; }
.reply-view p { margin: 0; font-size: 13px; line-height: 1.7; color: #334155; white-space: pre-wrap; }
.actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }
</style>
