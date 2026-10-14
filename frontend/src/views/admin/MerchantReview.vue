<template>
  <a-card title="商户管理">
    <template #extra>
      <a-space>
        <a-input-search v-model:value="keyword" placeholder="搜索商户ID/企业ID" style="width:200px" allow-clear />
        <a-radio-group v-model:value="filter" button-style="solid" @change="load">
          <a-radio-button :value="null">全部</a-radio-button>
          <a-radio-button :value="0">待审核</a-radio-button>
          <a-radio-button :value="1">已通过</a-radio-button>
          <a-radio-button :value="2">已驳回</a-radio-button>
        </a-radio-group>
      </a-space>
    </template>
    <a-table :data-source="filtered" :columns="cols" row-key="merId" :loading="loading">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'cap'">¥{{ record.regCapital }}</template>
        <template v-else-if="column.key === 'tax'">
          <a-tag :color="record.taxStatus === 1 ? 'green' : 'default'">{{ record.taxStatus === 1 ? '稳定' : '无' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'cert'">
          <a-space :size="4">
            <a-tooltip title="营业执照"><a-tag :color="record.licenseUrl ? 'success' : 'default'">照</a-tag></a-tooltip>
            <a-tooltip title="纳税记录份数"><a-tag :color="taxCount(record) > 0 ? 'success' : 'default'">税 {{ taxCount(record) }}</a-tag></a-tooltip>
            <a-tooltip title="税务登记号"><a-tag :color="record.taxRegNo ? 'success' : 'default'">号</a-tag></a-tooltip>
          </a-space>
        </template>
        <template v-else-if="column.key === 'time'">{{ (record.createTime || '').replace('T', ' ').slice(0, 16) }}</template>
        <template v-else-if="column.key === 'st'">
          <a-tag :color="stColor[record.reviewStatus]">{{ stText[record.reviewStatus] }}</a-tag>
          <a-tooltip v-if="record.rejectReason" :title="record.rejectReason">
            <div class="reason">原因：{{ record.rejectReason }}</div>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space>
            <a-button size="small" @click="openDetail(record)">快速查看</a-button><a-button size="small" type="link" @click="router.push('/admin/merchants/' + record.merId)">档案</a-button>
            <a-button v-if="record.reviewStatus !== 1" size="small" type="primary" @click="approve(record)">通过</a-button>
            <a-button v-if="record.reviewStatus !== 2" size="small" danger @click="openReject(record)">驳回</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 商户详情抽屉：资料维护 + 资质验证 + 流程跟踪 -->
    <a-drawer v-model:open="detailOpen" title="商户档案详情" width="560" :loading="detailLoading">
      <template v-if="detail">
        <!-- 入驻流程跟踪 -->
        <a-steps :current="flowStep" size="small" style="margin-bottom:20px">
          <a-step title="提交申请" :description="fmtTime(detail.merchant.createTime)" />
          <a-step title="资质核验" :description="readinessText" />
          <a-step title="审核完成" :description="reviewDesc" />
        </a-steps>

        <!-- 工商信息 -->
        <a-descriptions title="工商信息" :column="1" size="small" bordered style="margin-bottom:16px">
          <a-descriptions-item label="企业名称">{{ detail.enterprise?.name || '—' }}</a-descriptions-item>
          <a-descriptions-item label="统一社会信用代码">{{ detail.enterprise?.creditCode || '—' }}</a-descriptions-item>
          <a-descriptions-item label="主体类型">{{ detail.merchant.regType }}</a-descriptions-item>
          <a-descriptions-item label="注册资本">¥{{ detail.merchant.regCapital }}</a-descriptions-item>
          <a-descriptions-item label="入驻方式">{{ detail.merchant.joinType || '—' }}</a-descriptions-item>
        </a-descriptions>

        <!-- 资质验证 -->
        <div class="sec-title">资质验证</div>
        <div class="cert-checks">
          <div class="cert-check" :class="{ ok: detail.certReadiness.hasLicense }">
            <check-circle-filled v-if="detail.certReadiness.hasLicense" class="ck ok" />
            <close-circle-filled v-else class="ck miss" />
            营业执照
            <a v-if="detail.merchant.licenseUrl" :href="detail.merchant.licenseUrl" target="_blank" style="margin-left:auto">查看原件</a>
          </div>
          <div class="cert-check" :class="{ ok: detail.certReadiness.taxProofCount > 0 }">
            <check-circle-filled v-if="detail.certReadiness.taxProofCount > 0" class="ck ok" />
            <close-circle-filled v-else class="ck miss" />
            近期纳税记录（{{ detail.certReadiness.taxProofCount }}/3 份）
          </div>
          <div class="cert-check" :class="{ ok: detail.certReadiness.hasTaxRegNo }">
            <check-circle-filled v-if="detail.certReadiness.hasTaxRegNo" class="ck ok" />
            <close-circle-filled v-else class="ck miss" />
            税务登记号{{ detail.merchant.taxRegNo ? '：' + detail.merchant.taxRegNo : '' }}
          </div>
        </div>

        <!-- 纳税记录材料 -->
        <template v-if="taxUrls.length">
          <div class="sec-title">纳税记录材料</div>
          <div class="proof-list">
            <a v-for="(u, i) in taxUrls" :key="i" :href="u" target="_blank" class="proof-item">
              <file-pdf-outlined v-if="u.toLowerCase().includes('.pdf')" />
              <file-image-outlined v-else />
              记录 {{ i + 1 }}
            </a>
          </div>
        </template>

        <!-- 已具备资质 -->
        <template v-if="detail.qualifications?.length">
          <div class="sec-title">已具备资质（{{ detail.qualifications.length }}）</div>
          <a-tag v-for="q in detail.qualifications" :key="q.qId" style="margin-bottom:6px">{{ q.qName }}</a-tag>
        </template>

        <!-- 审核信息 -->
        <div class="sec-title">审核信息</div>
        <a-descriptions :column="1" size="small">
          <a-descriptions-item label="当前状态">
            <a-tag :color="stColor[detail.merchant.reviewStatus]">{{ stText[detail.merchant.reviewStatus] }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item v-if="detail.merchant.rejectReason" label="驳回原因">
            <span style="color:#e4393c">{{ detail.merchant.rejectReason }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="最近更新">{{ fmtTime(detail.merchant.updateTime) }}</a-descriptions-item>
        </a-descriptions>

        <!-- 抽屉内审核操作 -->
        <div style="margin-top:20px" v-if="detail.merchant.reviewStatus !== 1">
          <a-space>
            <a-button type="primary" @click="approve(detail.merchant)">审核通过</a-button>
            <a-button v-if="detail.merchant.reviewStatus !== 2" danger @click="openReject(detail.merchant)">驳回</a-button>
          </a-space>
        </div>
      </template>
    </a-drawer>

    <!-- 驳回原因（必填） -->
    <a-modal v-model:open="rejectOpen" title="驳回入驻申请" @ok="doReject" :confirm-loading="reviewing">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将展示给商户用于整改）" required>
          <a-textarea v-model:value="rejectReason" :rows="3" :maxlength="200" show-count
                      placeholder="例如：注册资本或主体类型不符合平台筛选条件" />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { CheckCircleFilled, CloseCircleFilled, FilePdfOutlined, FileImageOutlined } from '@ant-design/icons-vue'
import { merchantApi } from '../../api'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const filter = ref(null)
const keyword = ref('')
const stText = { 0: '待审核', 1: '已通过', 2: '已驳回' }
const stColor = { 0: 'orange', 1: 'green', 2: 'red' }
const cols = [
  { title: '商户ID', dataIndex: 'merId', width: 80 },
  { title: '企业ID', dataIndex: 'entId', width: 80 },
  { title: '主体类型', dataIndex: 'regType', width: 110 },
  { title: '注册资本', key: 'cap', width: 120 },
  { title: '纳税申报', key: 'tax', width: 90 },
  { title: '证照材料', key: 'cert', width: 150 },
  { title: '申请时间', key: 'time', width: 150 },
  { title: '状态', key: 'st', width: 200 },
  { title: '操作', key: 'op', width: 190 }
]

const filtered = computed(() => {
  const k = keyword.value.trim()
  if (!k) return list.value
  return list.value.filter(r => String(r.merId).includes(k) || String(r.entId || '').includes(k))
})

function taxCount(r) { return r.taxProofUrls ? r.taxProofUrls.split(',').filter(Boolean).length : 0 }

async function load() {
  loading.value = true
  try { list.value = await merchantApi.adminList(filter.value) || [] }
  finally { loading.value = false }
}

// ===== 详情抽屉 =====
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const taxUrls = computed(() => detail.value?.merchant?.taxProofUrls
  ? detail.value.merchant.taxProofUrls.split(',').filter(Boolean) : [])
const flowStep = computed(() => {
  const s = detail.value?.merchant?.reviewStatus
  return s === 1 ? 2 : (s === 2 ? 2 : 1)   // 待审核停在「资质核验」；通过/驳回完成
})
const readinessText = computed(() => {
  const r = detail.value?.certReadiness
  if (!r) return ''
  const n = [r.hasLicense, r.taxProofCount > 0, r.hasTaxRegNo].filter(Boolean).length
  return `材料 ${n}/3`
})
const reviewDesc = computed(() => {
  const s = detail.value?.merchant?.reviewStatus
  if (s === 1) return '已入驻'
  if (s === 2) return '已驳回'
  return '待审核'
})
function fmtTime(t) { return (t || '').replace('T', ' ').slice(0, 16) || '—' }

async function openDetail(record) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try { detail.value = await merchantApi.adminDetail(record.merId) }
  catch (e) { /* 拦截器已提示 */ }
  finally { detailLoading.value = false }
}

// ===== 审核操作 =====
const reviewing = ref(false)
const rejectOpen = ref(false)
const rejectReason = ref('')
const rejectTarget = ref(null)

function approve(record) {
  Modal.confirm({
    title: '确认通过该商户入驻申请？',
    content: `商户 ${record.merId}（${record.regType}，注册资本 ¥${record.regCapital}）通过后即获得商户身份，可发布商品。`,
    okText: '确认通过', cancelText: '取消',
    onOk: async () => {
      reviewing.value = true
      try {
        await merchantApi.adminReview(record.merId, 1, null)
        message.success('已通过')
        if (detailOpen.value && detail.value?.merchant?.merId === record.merId) await openDetail(record)
        await load()
      } finally { reviewing.value = false }
    }
  })
}
function openReject(record) {
  rejectTarget.value = record
  rejectReason.value = ''
  rejectOpen.value = true
}
async function doReject() {
  if (!rejectReason.value.trim()) { message.warning('请填写驳回原因'); return }
  reviewing.value = true
  try {
    await merchantApi.adminReview(rejectTarget.value.merId, 2, rejectReason.value.trim())
    message.success('已驳回')
    rejectOpen.value = false
    if (detailOpen.value && detail.value?.merchant?.merId === rejectTarget.value.merId) await openDetail(rejectTarget.value)
    await load()
  } finally { reviewing.value = false }
}

onMounted(load)
</script>

<style scoped>
.reason { font-size: 12px; color: #999; margin-top: 2px; max-width: 180px;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sec-title { font-weight: 700; font-size: 14px; margin: 16px 0 10px; }
.cert-checks { display: flex; flex-direction: column; gap: 8px; }
.cert-check { display: flex; align-items: center; gap: 8px; padding: 8px 12px;
              border-radius: 8px; background: var(--ll-page, #f7f8fa); font-size: 13px; }
.cert-check.ok { background: rgba(16, 185, 129, .06); }
.ck { font-size: 15px; }
.ck.ok { color: #10b981; }
.ck.miss { color: #cbd5e1; }
.proof-list { display: flex; flex-wrap: wrap; gap: 8px; }
.proof-item { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px;
              border: 1px solid #e5e7eb; border-radius: 8px; font-size: 13px; }
.proof-item:hover { border-color: var(--ll-primary, #1e6eb8); color: var(--ll-primary, #1e6eb8); }
</style>
