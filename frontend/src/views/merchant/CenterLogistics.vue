<template>
  <div class="lg-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>物流发货</h1>
        <p>全部运单一屏管理：跟踪配送轨迹，买家签收后确认完成。</p>
      </div>
      <div class="hero-actions">
        <a-button :type="showDashboard ? 'primary' : 'default'" class="tower-btn" @click="showDashboard = !showDashboard">
          <Activity :size="13" class="inline mr-1 align-middle" />
          {{ showDashboard ? '收起控制塔' : '全景控制塔大屏' }}
        </a-button>
        <a-input-search v-model:value="orderNo" placeholder="按订单号筛选" allow-clear
                        class="search" @search="onSearch" @pressEnter="onSearch" />
        <a-select v-model:value="status" class="filter" @change="onSearch">
          <a-select-option :value="null">全部状态</a-select-option>
          <a-select-option v-for="(v, k) in WAYBILL_STATUS" :key="k" :value="k">{{ v.text }}</a-select-option>
        </a-select>
      </div>
    </section>

    <!-- 在途停滞 SLA 跑马灯预警条 (21st.dev 动效) -->
    <SlaAlertBanner
      :alerts="slaAlerts"
      @openDashboard="showDashboard = true"
      @refresh="handleSlaRefresh"
    />

    <!-- SLA 控制塔大屏 (可展开/折叠) -->
    <transition name="fade-slide">
      <div v-if="showDashboard" class="dashboard-wrapper">
        <LogisticsSlaDashboard ref="slaDashboardRef" @expedited="handleSlaRefresh" />
      </div>
    </transition>

    <!-- 状态速览 -->
    <section class="mini-grid">
      <div v-for="s in summaryBars" :key="s.key" class="mini-card" @click="quickFilter(s.key)">
        <b>{{ s.count }}</b><span>{{ s.label }}</span>
      </div>
    </section>

    <a-card :bordered="false" class="panel">
      <a-table :data-source="rows" :columns="cols" row-key="waybillId" :loading="loading" class="mc-table"
               :scroll="{ x: 1000 }"
               :pagination="{ current, pageSize, total, showSizeChanger: false,
                              onChange: (p) => { current = p; load() } }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'waybillNo'">
            <div class="no-cell">
              <b class="mono">{{ record.waybillNo }}</b>
              <small v-if="record.logisticsNo">{{ record.logisticsNo }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'orderNo'">
            <a class="mono" @click="$router.push(`/merchant/center/orders/${record.orderNo}`)">{{ record.orderNo }}</a>
          </template>
          <template v-else-if="column.key === 'receiver'">
            <div class="buyer-cell">
              <span>{{ record.receiverName || '—' }}</span>
              <small>{{ maskPhone(record.receiverPhone) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="WAYBILL_STATUS[record.status]?.color">{{ WAYBILL_STATUS[record.status]?.text || record.status }}</a-tag>
          </template>
          <template v-else-if="column.key === 'podStatus'">
            <div class="pod-cell">
              <span v-if="record.podAuditStatus === 'approved'" class="epod-chip approved">
                <ShieldCheck :size="12" class="inline mr-1" />公章验签合格
              </span>
              <span v-else-if="record.podAuditStatus === 'pending'" class="epod-chip pending">
                <Clock :size="12" class="inline mr-1" />待审公章回执
              </span>
              <span v-else-if="record.podAuditStatus === 'rejected'" class="epod-chip rejected">
                <AlertTriangle :size="12" class="inline mr-1" />已驳回重传
              </span>
              <span v-else-if="record.coaDocsJson" class="epod-chip coa">
                <FileCheck :size="12" class="inline mr-1" />随货 CoA 单证
              </span>
              <span v-else class="epod-chip default">普通运单</span>
            </div>
          </template>
          <template v-else-if="column.key === 'shipTime'">{{ shortTime(record.shipTime) }}</template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="2">
              <a-button
                v-if="record.podAuditStatus === 'pending'"
                type="primary"
                size="small"
                class="audit-btn-pulse"
                @click="openAuditModal(record)"
              >
                <Stamp :size="12" class="inline mr-1" />验签
              </a-button>
              <a-button
                v-else-if="record.podAuditStatus"
                type="link"
                size="small"
                @click="openAuditModal(record)"
              >
                <FileSearch :size="12" class="inline mr-1" />查验
              </a-button>
              <a-button type="link" size="small" @click="openTrace(record)">轨迹</a-button>
              <a-button v-if="signable(record)" type="link" size="small" @click="signConfirm(record)">确认签收</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- e-POD 现场数字公章回执验签工作台弹窗 -->
    <a-modal
      v-model:open="auditModalOpen"
      title="数字 e-POD 现场签收回执验签"
      :confirm-loading="auditSubmitting"
      :width="650"
      @ok="handleAuditSubmit"
    >
      <div v-if="auditWaybill" class="audit-workplace">
        <a-descriptions bordered size="small" :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="运单号">{{ auditWaybill.waybillNo }}</a-descriptions-item>
          <a-descriptions-item label="对应订单">{{ auditWaybill.orderNo }}</a-descriptions-item>
          <a-descriptions-item label="现场签收人">{{ auditWaybill.podSignedBy || '—' }}</a-descriptions-item>
          <a-descriptions-item label="签收人电话">{{ auditWaybill.podSignedPhone || '—' }}</a-descriptions-item>
          <a-descriptions-item label="签收打卡时间" :span="2">{{ shortTime(auditWaybill.podSignedTime) || '—' }}</a-descriptions-item>
        </a-descriptions>

        <div class="audit-photo-section">
          <div class="photo-block">
            <span class="block-title">
              <Stamp :size="14" class="text-rose-600 inline mr-1" />加盖公章原件照片 (纸质单拍照)
            </span>
            <div v-if="auditWaybill.podStampPhoto" class="stamp-view-box">
              <a :href="auditWaybill.podStampPhoto" target="_blank" rel="noopener noreferrer">
                <img :src="auditWaybill.podStampPhoto" alt="公章原件" class="audit-stamp-img" />
              </a>
              <span class="zoom-tip">点击查看高清原图验印</span>
            </div>
            <div v-else class="empty-photo">未上传加盖公章原件</div>
          </div>

          <div class="photo-block">
            <span class="block-title">
              <Camera :size="14" class="text-sky-600 inline mr-1" />现场货物外观与开箱验收照片
            </span>
            <div v-if="cargoPhotosList.length" class="cargo-view-grid">
              <a v-for="(img, idx) in cargoPhotosList" :key="idx" :href="img" target="_blank" rel="noopener noreferrer">
                <img :src="img" alt="货物验收照片" class="audit-cargo-img" />
              </a>
            </div>
            <div v-else class="empty-photo">未上传外观照片</div>
          </div>
        </div>

        <div v-if="auditWaybill.podAuditStatus !== 'approved'" class="audit-decision-form">
          <a-form layout="vertical">
            <a-form-item label="验签核验结论" required>
              <a-radio-group v-model:value="auditDecision">
                <a-radio value="approved">
                  <span class="font-medium text-emerald-600">公章印迹与实名核验一致 · 审核通过</span>
                </a-radio>
                <a-radio value="rejected">
                  <span class="font-medium text-rose-600">印迹模糊 / 货物外观受损 / 驳回重传</span>
                </a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="核验批注 / 驳回原因说明" :required="auditDecision === 'rejected'">
              <a-textarea
                v-model:value="auditNote"
                :rows="2"
                maxlength="200"
                show-count
                placeholder="通过可留空或备注印鉴一致；驳回请说明具体原因（如章印不全、缺签字等）"
              />
            </a-form-item>
          </a-form>
        </div>
        <div v-else class="approved-already-tip">
          <ShieldCheck :size="16" class="text-emerald-600 inline mr-1" />
          该运单公章已由 {{ auditWaybill.podAuditedBy || '商户/平台' }} 于 {{ shortTime(auditWaybill.podAuditedAt) }} 验签通过，已完成终态归档。
        </div>
      </div>
    </a-modal>

    <!-- L4：轨迹抽屉 -->
    <TraceDrawer v-model:open="traceOpen" :waybill="traceWaybill" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { logisticsApi } from '../../api'
import { WAYBILL_STATUS } from '../../utils/merchantStatus'
import TraceDrawer from '../../components/merchant/TraceDrawer.vue'
import SlaAlertBanner from '../../components/logistics/SlaAlertBanner.vue'
import LogisticsSlaDashboard from '../../components/logistics/LogisticsSlaDashboard.vue'
import {
  Stamp, ShieldCheck, Clock, AlertTriangle, FileCheck, FileSearch, Camera, Activity
} from 'lucide-vue-next'

const rows = ref([])
const loading = ref(false)
const orderNo = ref('')
const status = ref(null)
const current = ref(1)
const pageSize = 10
const total = ref(0)
const traceOpen = ref(false)
const traceWaybill = ref(null)

// SLA 控制塔大屏与跑马灯预警
const showDashboard = ref(false)
const slaAlerts = ref([])
const slaDashboardRef = ref(null)

async function loadSlaAlerts() {
  try {
    const res = await logisticsApi.slaOverview()
    const d = res?.data || res
    slaAlerts.value = d?.recentAlerts || []
  } catch (e) {}
}

function handleSlaRefresh() {
  loadSlaAlerts()
  load()
  loadSummary()
  if (slaDashboardRef.value?.fetchData) {
    slaDashboardRef.value.fetchData()
  }
}

// e-POD 验签核验工作台状态
const auditModalOpen = ref(false)
const auditSubmitting = ref(false)
const auditWaybill = ref(null)
const auditDecision = ref('approved')
const auditNote = ref('')

const cargoPhotosList = computed(() => {
  if (!auditWaybill.value?.podCargoPhotos) return []
  try {
    return JSON.parse(auditWaybill.value.podCargoPhotos)
  } catch (e) {
    return []
  }
})

function openAuditModal(wb) {
  auditWaybill.value = wb
  auditDecision.value = wb.podAuditStatus === 'rejected' ? 'rejected' : 'approved'
  auditNote.value = wb.podAuditNote || ''
  auditModalOpen.value = true
}

async function handleAuditSubmit() {
  if (auditWaybill.value.podAuditStatus === 'approved') {
    auditModalOpen.value = false
    return
  }
  if (auditDecision.value === 'rejected' && !auditNote.value?.trim()) {
    message.warning('驳回时必须填写原因说明')
    return
  }
  auditSubmitting.value = true
  try {
    // 后端契约 PodAuditRequest(action, auditNote)：action 取值 approve / reject
    const payload = {
      action: auditDecision.value === 'rejected' ? 'reject' : 'approve',
      auditNote: auditNote.value?.trim() || ''
    }
    const wbId = auditWaybill.value.waybillId || auditWaybill.value.id
    await logisticsApi.auditPod(wbId, payload)
    message.success(auditDecision.value === 'approved' ? 'e-POD 验签通过，运单已归档签收' : '已驳回，已通知买方重新上传')
    auditModalOpen.value = false
    load()
    loadSummary()
  } catch (e) {
    // 拦截器已处理
  } finally {
    auditSubmitting.value = false
  }
}

const cols = [
  { title: '运单号', key: 'waybillNo', width: 150 },
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '收货人', key: 'receiver', width: 120 },
  { title: '运单状态', key: 'status', width: 90 },
  { title: '单证 / e-POD', key: 'podStatus', width: 130 },
  { title: '发货时间', key: 'shipTime', width: 130 },
  { title: '操作', key: 'op', width: 150, fixed: 'right' }
]

// 速览卡计数：独立拉取全量运单（无过滤、演示规模量级）统计，避免只数当前分页页
const summaryRows = ref([])
const summaryBars = computed(() => {
  const map = { pending_ship: '待揽收', picked_up: '已揽收', in_transit: '运输中', out_for_delivery: '派送中', delivered: '已签收', exception: '异常' }
  return Object.entries(map).map(([key, label]) => ({
    key, label, count: summaryRows.value.filter(r => r.status === key).length
  }))
})
async function loadSummary() {
  try {
    const page = await logisticsApi.waybillList({ page: 1, size: 500 })
    summaryRows.value = page.records || []
  } catch (e) { /* 速览失败可忽略 */ }
}

function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}
function signable(wb) {
  // 状态机仅允许「派送中 → 已签收」；运输中需等待服务商轨迹推进到派送中
  return wb.status === 'out_for_delivery'
}

function onSearch() { current.value = 1; load() }
function quickFilter(key) {
  status.value = status.value === key ? null : key
  onSearch()
}

async function load() {
  loading.value = true
  try {
    const page = await logisticsApi.waybillList({
      page: current.value, size: pageSize,
      status: status.value || undefined,
      orderNo: orderNo.value || undefined
    })
    rows.value = page.records || []
    total.value = Number(page.total || 0)
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

function openTrace(wb) {
  traceWaybill.value = wb
  traceOpen.value = true
}

function signConfirm(wb) {
  Modal.confirm({
    title: '确认该运单已签收？',
    content: `运单 ${wb.waybillNo} 将被标记为「已签收」。请与买家确认后操作。`,
    okText: '确认签收', cancelText: '取消',
    onOk: async () => {
      try {
        await logisticsApi.transit(wb.waybillId, 'delivered', '商户确认签收')
        message.success('运单已签收')
        load()
        loadSummary()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

onMounted(() => {
  load()
  loadSummary()
  loadSlaAlerts()
})
</script>

<style scoped>
.lg-page { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #9b2d20; margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; align-items: center; }
.tower-btn {
  display: inline-flex;
  align-items: center;
  font-weight: 600;
  border-radius: 8px;
}
.dashboard-wrapper {
  margin-bottom: 4px;
}
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
.search { width: 230px; }
.filter { width: 130px; }

.mini-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.mini-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px; padding: 12px 14px; cursor: pointer; transition: .18s ease; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mini-card:hover { transform: translateY(-2px); box-shadow: 0 8px 22px rgba(20, 24, 34, .08); }
.mini-card b { display: block; font-size: 20px; font-weight: 750; color: var(--mc-ink, #1d1d1f); letter-spacing: -.02em; }
.mini-card span { font-size: 11.5px; color: var(--mc-muted2, #86868b); }

.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.no-cell b { display: block; color: var(--mc-ink, #1d1d1f); font-weight: 600; }
.no-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.buyer-cell span { display: block; font-size: 12.5px; color: var(--mc-ink, #1d1d1f); }
.buyer-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }

@media (max-width: 900px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .search { flex: 1; width: auto; }
  .mini-grid { grid-template-columns: repeat(3, 1fr); }
}

/* ===== Phase 2: e-POD 标签与验签按钮动效 ===== */
.pod-cell {
  display: flex;
  align-items: center;
}
.epod-chip {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  font-weight: 500;
}
.epod-chip.approved {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}
.epod-chip.pending {
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  animation: pulse-border 2s infinite ease-in-out;
}
.epod-chip.rejected {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}
.epod-chip.coa {
  background: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}
.epod-chip.default {
  background: #f8fafc;
  color: #94a3b8;
  border: 1px solid #e2e8f0;
}

@keyframes pulse-border {
  0%, 100% { box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.4); }
  50% { box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.1); }
}

.audit-btn-pulse {
  background: #2563eb !important;
  border-color: #2563eb !important;
  animation: subtle-glow 1.8s infinite ease-in-out;
}
@keyframes subtle-glow {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.03); }
}

/* ===== 验签工作台 ===== */
.audit-workplace {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.audit-photo-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.photo-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.block-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
  display: inline-flex;
  align-items: center;
}
.stamp-view-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.audit-stamp-img {
  width: 100%;
  height: 160px;
  object-fit: contain;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: zoom-in;
  transition: transform 0.2s;
}
.audit-stamp-img:hover {
  border-color: #e11d48;
}
.zoom-tip {
  font-size: 11px;
  color: #94a3b8;
}
.cargo-view-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.audit-cargo-img {
  width: 100%;
  height: 76px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  cursor: zoom-in;
}
.empty-photo {
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #94a3b8;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px dashed #cbd5e1;
}
.audit-decision-form {
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.approved-already-tip {
  padding: 12px;
  background: #ecfdf5;
  color: #065f46;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  display: flex;
  align-items: center;
}

@media (max-width: 640px) {
  .audit-photo-section {
    grid-template-columns: 1fr;
  }
}
</style>
