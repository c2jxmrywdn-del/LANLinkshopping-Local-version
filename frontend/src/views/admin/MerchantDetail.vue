<template>
  <div class="page">
    <a-page-header title="商户档案" sub-title="企业资料、资质核验与审核轨迹" @back="$router.push('/admin/merchants')" />
    <a-spin :spinning="loading">
      <template v-if="detail?.merchant">
        <a-card :bordered="false" class="hero">
          <div>
            <div class="eyebrow">MERCHANT / {{ detail.merchant.merId }}</div>
            <h1>{{ detail.enterprise?.name || ('商户 #' + detail.merchant.merId) }}</h1>
            <p>{{ detail.merchant.regType }} · 注册资本 ¥{{ detail.merchant.regCapital }}</p>
          </div>
          <a-tag :color="statusColor">{{ statusText }}</a-tag>
        </a-card>

        <a-card :bordered="false" class="card flow-card" title="入驻流程">
          <a-steps :current="flowStep" size="small" responsive>
            <a-step title="提交申请" :description="fmt(detail.merchant.createTime)" />
            <a-step title="资质核验" :description="readinessText" />
            <a-step title="审核完成" :description="reviewText" />
          </a-steps>
        </a-card>

        <div class="grid">
          <a-card :bordered="false" class="card" title="企业资料">
            <a-descriptions :column="1" size="small" bordered>
              <a-descriptions-item label="企业名称">{{ detail.enterprise?.name || '—' }}</a-descriptions-item>
              <a-descriptions-item label="统一社会信用代码">{{ detail.enterprise?.creditCode || '—' }}</a-descriptions-item>
              <a-descriptions-item label="主体类型">{{ detail.merchant.regType || '—' }}</a-descriptions-item>
              <a-descriptions-item label="注册资本">¥{{ detail.merchant.regCapital || '0.00' }}</a-descriptions-item>
              <a-descriptions-item label="入驻方式">{{ detail.merchant.joinType || '—' }}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <a-card :bordered="false" class="card" title="资质核验">
            <div v-for="item in checks" :key="item.label" class="check" :class="{ ok: item.ok }">
              <span class="dot">{{ item.ok ? '✓' : '!' }}</span>
              <span>{{ item.label }}</span>
              <a-tag :color="item.ok ? 'green' : 'default'">{{ item.ok ? '已具备' : '待补充' }}</a-tag>
            </div>
            <a-divider v-if="taxUrls.length" />
            <div v-if="taxUrls.length" class="proofs">
              <div class="section-title">纳税材料</div>
              <a v-for="(u,i) in taxUrls" :key="i" :href="u" target="_blank" rel="noopener">材料 {{ i + 1 }} ↗</a>
            </div>
          </a-card>

          <a-card :bordered="false" class="card" title="审核记录">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="当前状态">
                <a-tag :color="statusColor">{{ statusText }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="最近更新">{{ fmt(detail.merchant.updateTime) }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.merchant.rejectReason" label="驳回原因">
                <span class="reject">{{ detail.merchant.rejectReason }}</span>
              </a-descriptions-item>
            </a-descriptions>
            <a-divider />
            <a-space>
              <a-button v-if="detail.merchant.reviewStatus !== 1" type="primary" @click="approve">审核通过</a-button>
              <a-button v-if="detail.merchant.reviewStatus !== 2" danger @click="rejectOpen = true">驳回</a-button>
            </a-space>
          </a-card>

          <a-card :bordered="false" class="card" title="已具备资质">
            <template v-if="detail.qualifications?.length">
              <a-tag v-for="q in detail.qualifications" :key="q.qId" class="qtag">{{ q.qName }}</a-tag>
            </template>
            <a-empty v-else :image="null" description="暂无额外资质" />
          </a-card>
        </div>
      </template>
      <a-result v-else-if="!loading" status="404" title="商户档案不存在">
        <template #extra><a-button type="primary" @click="$router.push('/admin/merchants')">返回商户管理</a-button></template>
      </a-result>
    </a-spin>

    <a-modal v-model:open="rejectOpen" title="驳回入驻申请" ok-text="确认驳回" cancel-text="取消" :confirm-loading="reviewing" @ok="reject">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填）" required>
          <a-textarea v-model:value="reason" :rows="4" :maxlength="200" show-count placeholder="说明需要商户整改的材料或原因" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { merchantApi } from '../../api'

const route = useRoute(), router = useRouter()
const detail = ref(null), loading = ref(false), rejectOpen = ref(false), reviewing = ref(false), reason = ref('')

const statusText = computed(() => ({0:'待审核',1:'已通过',2:'已驳回'}[detail.value?.merchant?.reviewStatus] || '未知'))
const statusColor = computed(() => ({0:'orange',1:'green',2:'red'}[detail.value?.merchant?.reviewStatus] || 'default'))
const flowStep = computed(() => detail.value?.merchant?.reviewStatus === 0 ? 1 : 2)
const reviewText = computed(() => detail.value?.merchant?.reviewStatus === 1 ? '已入驻' : detail.value?.merchant?.reviewStatus === 2 ? '已驳回' : '待审核')
const readinessText = computed(() => {
  const r = detail.value?.certReadiness
  if (!r) return ''
  return `材料 ${[r.hasLicense, r.taxProofCount > 0, r.hasTaxRegNo].filter(Boolean).length}/3`
})
const taxUrls = computed(() => detail.value?.merchant?.taxProofUrls ? detail.value.merchant.taxProofUrls.split(',').filter(Boolean) : [])
const checks = computed(() => {
  const r = detail.value?.certReadiness || {}
  return [
    { label:'营业执照', ok:Boolean(r.hasLicense) },
    { label:`近期纳税记录（${r.taxProofCount || 0}/3）`, ok:(r.taxProofCount || 0) > 0 },
    { label:'税务登记号', ok:Boolean(r.hasTaxRegNo) }
  ]
})
const fmt = v => v ? String(v).replace('T',' ').slice(0,16) : '—'

async function load() {
  loading.value = true
  try { detail.value = await merchantApi.adminDetail(route.params.merId) }
  catch (e) { detail.value = null }
  finally { loading.value = false }
}
async function approve() {
  reviewing.value = true
  try { await merchantApi.adminReview(route.params.merId, 1, null); message.success('审核通过'); await load() }
  finally { reviewing.value = false }
}
async function reject() {
  if (!reason.value.trim()) { message.warning('请填写驳回原因'); return }
  reviewing.value = true
  try { await merchantApi.adminReview(route.params.merId, 2, reason.value.trim()); message.success('已驳回'); rejectOpen.value = false; await load() }
  finally { reviewing.value = false }
}
onMounted(load)
</script>

<style scoped>
.page{max-width:1120px;margin:0 auto}.hero{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px;display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.hero h1{color:#fff;margin:7px 0 3px;font-size:28px}.hero p{margin:0;color:rgba(255,255,255,.7)}.eyebrow{font-size:11px;letter-spacing:.16em;opacity:.7}.flow-card,.card{border-radius:18px}.flow-card{margin-bottom:16px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.check{display:flex;align-items:center;gap:10px;padding:11px 12px;margin-bottom:8px;border-radius:10px;background:#f7f8fa}.check.ok{background:rgba(16,185,129,.06)}.check span:nth-child(2){flex:1}.dot{width:22px;height:22px;border-radius:50%;display:grid;place-items:center;background:#e5e7eb;font-size:12px}.check.ok .dot{background:#dcfce7;color:#15803d}.section-title{font-weight:700;font-size:13px;margin-bottom:8px}.proofs{display:flex;gap:8px;flex-wrap:wrap}.proofs a,.qtag{margin-bottom:6px}.reject{color:#b42318}.actions{margin-top:20px}@media(max-width:700px){.hero{flex-direction:column}.grid{grid-template-columns:1fr}}
</style>