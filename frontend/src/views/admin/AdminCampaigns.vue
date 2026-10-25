<template>
  <div class="ac-page">
    <div class="ac-head">
      <div>
        <h2>活动管理</h2>
        <p>上架领券、促销、秒杀与激励活动；配置规则、名额与商户报名。</p>
      </div>
      <a-button type="primary" size="large" @click="openCreate">＋ 创建活动</a-button>
    </div>

    <div class="ac-toolbar">
      <a-select v-model:value="q.type" style="width:150px" allow-clear placeholder="全部类型" @change="reload">
        <a-select-option v-for="(label, key) in TYPE_LABELS" :key="key" :value="key">{{ label }}</a-select-option>
      </a-select>
      <a-select v-model:value="q.status" style="width:120px" allow-clear placeholder="全部状态" @change="reload">
        <a-select-option :value="1">已上架</a-select-option>
        <a-select-option :value="0">已下架</a-select-option>
      </a-select>
      <a-input-search v-model:value="q.keyword" style="width:240px" placeholder="搜索活动名称" allow-clear @search="reload" />
      <span class="ac-total" v-if="total">共 {{ total }} 个活动</span>
    </div>

    <a-table :data-source="rows" :columns="columns" :loading="loading" row-key="campaignId"
             :pagination="{ current, pageSize, total, showSizeChanger: false }" @change="onPage"
             :scroll="{ x: 920 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'title'">
          <div class="ac-title">
            <i class="ac-dot" :class="'ac-' + (record.accent || 'blue')"></i>
            <div class="ac-title-main">
              <b>{{ record.title }}</b>
              <span class="ac-desc">{{ record.description }}</span>
            </div>
          </div>
        </template>
        <template v-else-if="column.key === 'type'">
          <a-tag :color="TYPE_COLOR[record.type]">{{ TYPE_LABELS[record.type] || record.type }}</a-tag>
        </template>
        <template v-else-if="column.key === 'rule'">
          <span class="ac-rule">{{ ruleText(record) }}</span>
        </template>
        <template v-else-if="column.key === 'time'">
          <div class="ac-time">{{ fmt(record.startTime) || '即时' }}<br />至 {{ fmt(record.endTime) || '长期' }}</div>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 1 ? 'green' : 'default'">{{ record.status === 1 ? '已上架' : '已下架' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space :size="4">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button v-if="record.status !== 1" type="link" size="small" @click="toggle(record, 1)">上架</a-button>
            <a-button v-else type="link" size="small" danger @click="toggle(record, 0)">下架</a-button>
            <a-button type="link" size="small" danger @click="remove(record)">删除</a-button>
          </a-space>
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <div class="ac-stats" v-if="stats[record.campaignId]">
          <span>领取 <b>{{ stats[record.campaignId].claimedCount }}</b><template v-if="record.totalQuota > 0"> / {{ record.totalQuota }}</template> 张</span>
          <span>核销 <b>{{ stats[record.campaignId].usedCount }}</b> 张</span>
          <span>用户参与 <b>{{ stats[record.campaignId].participantCount }}</b> 人次</span>
          <span>商户报名 <b>{{ stats[record.campaignId].merchantCount }}</b> 家</span>
        </div>
        <a-skeleton v-else :paragraph="{ rows: 1 }" active />
      </template>
    </a-table>

    <!-- 创建 / 编辑弹窗 -->
    <a-modal v-model:open="modalOpen" :title="editing ? '编辑活动' : '创建活动'" :width="720"
             :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="save" @cancel="modalOpen = false">
      <a-form layout="vertical" class="ac-form">
        <div class="ac-form-grid">
          <a-form-item label="活动名称" required>
            <a-input v-model:value="form.title" placeholder="如：新客专享满 200 减 30 券" :maxlength="100" show-count />
          </a-form-item>
          <a-form-item label="活动类型" required>
            <a-select v-model:value="form.type" :disabled="!!editing" @change="onTypeChange">
              <a-select-option v-for="(label, key) in TYPE_LABELS" :key="key" :value="key">{{ label }}</a-select-option>
            </a-select>
          </a-form-item>
        </div>

        <a-form-item label="活动说明">
          <a-textarea v-model:value="form.description" :rows="2" :maxlength="500" placeholder="展示在活动卡片与详情页" />
        </a-form-item>

        <div class="ac-form-grid">
          <a-form-item label="活动时间">
            <a-range-picker v-model:value="form.range" show-time value-format="YYYY-MM-DDTHH:mm:ss"
                            :placeholder="['开始（可留空）', '结束（可留空）']" style="width:100%" />
          </a-form-item>
          <a-form-item label="卡片主题色">
            <a-select v-model:value="form.accent">
              <a-select-option value="blue">品牌蓝</a-select-option>
              <a-select-option value="red">热力红</a-select-option>
              <a-select-option value="gold">鎏金</a-select-option>
              <a-select-option value="green">翡翠绿</a-select-option>
              <a-select-option value="purple">星紫</a-select-option>
            </a-select>
          </a-form-item>
        </div>

        <!-- 促销/领券通用规则 -->
        <template v-if="['coupon', 'full_reduce', 'discount', 'flash_sale'].includes(form.type)">
          <div class="ac-form-grid">
            <a-form-item label="门槛金额（满 X 可用，0 = 无门槛）">
              <a-input-number v-model:value="form.threshold" :min="0" :precision="2" style="width:100%" />
            </a-form-item>
            <a-form-item v-if="form.type === 'coupon'" label="券种类">
              <a-radio-group v-model:value="form.couponKind" @change="onKindChange">
                <a-radio value="voucher">满减券</a-radio>
                <a-radio value="rate">折扣券</a-radio>
              </a-radio-group>
            </a-form-item>
          </div>
          <div class="ac-form-grid">
            <a-form-item v-if="form.type === 'full_reduce' || (form.type === 'coupon' && form.couponKind === 'voucher')"
                         label="优惠金额（元）" required>
              <a-input-number v-model:value="form.benefitAmount" :min="0.01" :precision="2" style="width:100%" />
            </a-form-item>
            <a-form-item v-if="['discount', 'flash_sale'].includes(form.type) || (form.type === 'coupon' && form.couponKind === 'rate')"
                         label="折扣率（0.95 = 95 折）" required>
              <a-input-number v-model:value="form.discountRate" :min="0.01" :max="0.99" :precision="2" :step="0.05" style="width:100%" />
            </a-form-item>
          </div>
          <a-form-item v-if="['discount', 'flash_sale'].includes(form.type) || (form.type === 'coupon' && form.couponKind === 'rate')"
                       :label="form.type === 'coupon' ? '优惠封顶金额（元，必填）' : '优惠封顶金额（元，选填）'">
            <a-input-number v-model:value="form.capAmount" :min="0" :precision="2" style="width:100%" />
          </a-form-item>
          <div class="ac-form-grid" v-if="form.type === 'coupon'">
            <a-form-item label="每人限领（张）">
              <a-input-number v-model:value="form.perUserLimit" :min="1" :precision="0" style="width:100%" />
            </a-form-item>
            <a-form-item label="发放总量（0 = 不限量）">
              <a-input-number v-model:value="form.totalQuota" :min="0" :precision="0" style="width:100%" />
            </a-form-item>
          </div>
          <a-form-item label="适用范围">
            <a-radio-group v-model:value="form.scope">
              <a-radio value="all">全场通用</a-radio>
              <a-radio value="ind">限定行业</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="form.scope === 'ind'" label="限定行业" required>
            <a-select v-model:value="form.indId" placeholder="选择行业">
              <a-select-option v-for="i in industries" :key="i.indId" :value="i.indId">{{ i.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </template>

        <!-- 用户激励 -->
        <template v-if="form.type === 'register' || form.type === 'purchase'">
          <a-form-item label="参与奖励积分" required>
            <a-input-number v-model:value="form.rewardPoints" :min="1" :precision="0" style="width:100%" />
          </a-form-item>
        </template>

        <!-- 商户激励 -->
        <template v-if="form.type === 'merchant_incentive'">
          <div class="ac-form-grid">
            <a-form-item label="目标销售额（元）" required>
              <a-input-number v-model:value="form.merTargetAmount" :min="0.01" :precision="2" style="width:100%" />
            </a-form-item>
            <a-form-item label="奖励积分" required>
              <a-input-number v-model:value="form.merRewardPoints" :min="1" :precision="0" style="width:100%" />
            </a-form-item>
          </div>
        </template>

        <a-form-item v-if="form.type !== 'merchant_incentive'" label="开放商户报名">
          <a-switch v-model:checked="form.joinable" checked-children="可报名" un-checked-children="关闭" />
          <span class="ac-form-tip">开启后商户可在商户中心报名参加该活动</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { campaignApi, homeApi } from '../../api'

const TYPE_LABELS = {
  coupon: '领券活动', full_reduce: '满减促销', discount: '折扣促销', flash_sale: '限时秒杀',
  register: '注册有礼', purchase: '消费有礼', merchant_incentive: '商户激励'
}
const TYPE_COLOR = {
  coupon: 'red', full_reduce: 'blue', discount: 'geekblue', flash_sale: 'purple',
  register: 'gold', purchase: 'gold', merchant_incentive: 'green'
}

const columns = [
  { key: 'title', title: '活动', width: 260 },
  { key: 'type', title: '类型', width: 110 },
  { key: 'rule', title: '规则', width: 200 },
  { key: 'time', title: '时间窗', width: 170 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'actions', title: '操作', width: 170 }
]

const rows = ref([])
const loading = ref(false)
const total = ref(0)
const current = ref(1)
const pageSize = 10
const q = reactive({ type: undefined, status: undefined, keyword: '' })
const stats = reactive({})
const industries = ref([])

const modalOpen = ref(false)
const saving = ref(false)
const editing = ref(null)
const blank = () => ({
  title: '', type: 'coupon', description: '', accent: 'blue', range: null,
  threshold: 0, couponKind: 'voucher', benefitAmount: null, discountRate: null, capAmount: null,
  perUserLimit: 1, totalQuota: 0, scope: 'all', indId: undefined,
  rewardPoints: 100, merTargetAmount: null, merRewardPoints: 1000, joinable: false
})
const form = reactive(blank())

function onTypeChange() {
  form.scope = form.type === 'merchant_incentive' || form.type === 'register' || form.type === 'purchase' ? 'all' : form.scope
  if (form.type === 'flash_sale' && !form.range) {
    const now = new Date()
    const iso = (d) => d.toISOString().slice(0, 19)
    const end = new Date(now.getTime() + 6 * 3600 * 1000)
    form.range = [iso(now), iso(end)]
  }
}
function onKindChange() { form.benefitAmount = null; form.discountRate = null; form.capAmount = null }

async function reload() {
  loading.value = true
  try {
    const page = await campaignApi.adminPage({
      page: current.value, size: pageSize,
      type: q.type || undefined, status: q.status ?? undefined,
      keyword: q.keyword || undefined
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    rows.value.forEach(r => loadStats(r.campaignId))
  } finally { loading.value = false }
}
async function loadStats(id) {
  try {
    const d = await campaignApi.adminDetail(id)
    stats[id] = d
  } catch (e) { /* 忽略单项统计失败 */ }
}
function onPage(p) { current.value = p.current; reload() }

function openCreate() {
  editing.value = null
  Object.assign(form, blank())
  modalOpen.value = true
}
function openEdit(record) {
  editing.value = record
  Object.assign(form, blank(), {
    title: record.title, type: record.type, description: record.description || '',
    accent: record.accent || 'blue',
    range: [record.startTime || null, record.endTime || null],
    threshold: Number(record.threshold) || 0,
    couponKind: record.discountRate ? 'rate' : 'voucher',
    benefitAmount: record.benefitAmount != null ? Number(record.benefitAmount) : null,
    discountRate: record.discountRate != null ? Number(record.discountRate) : null,
    capAmount: record.benefitAmount != null ? Number(record.benefitAmount) : null,
    perUserLimit: record.perUserLimit || 1, totalQuota: record.totalQuota || 0,
    scope: record.scope || 'all', indId: record.indId || undefined,
    rewardPoints: record.rewardPoints || 100,
    merTargetAmount: record.merTargetAmount != null ? Number(record.merTargetAmount) : null,
    merRewardPoints: record.merRewardPoints || 1000,
    joinable: record.joinable === 1
  })
  modalOpen.value = true
}

function validate() {
  if (!form.title.trim()) return '请填写活动名称'
  const isRate = ['discount', 'flash_sale'].includes(form.type) || (form.type === 'coupon' && form.couponKind === 'rate')
  if (form.type === 'full_reduce' && !(form.benefitAmount > 0)) return '满减活动必须填写优惠金额'
  if (isRate && !(form.discountRate > 0 && form.discountRate < 1)) return '折扣率需在 0 ~ 1 之间（0.95 = 95 折）'
  if (form.type === 'coupon') {
    if (form.couponKind === 'voucher' && !(form.benefitAmount > 0)) return '满减券必须填写面值金额'
    if (form.couponKind === 'rate' && !(form.capAmount > 0)) return '折扣券必须填写优惠封顶金额'
  }
  if (['register', 'purchase'].includes(form.type) && !(form.rewardPoints > 0)) return '激励活动必须配置奖励积分'
  if (form.type === 'merchant_incentive') {
    if (!(form.merTargetAmount > 0)) return '商户激励必须填写目标销售额'
    if (!(form.merRewardPoints > 0)) return '商户激励必须填写奖励积分'
  }
  if (form.type === 'flash_sale' && (!form.range || !form.range[0] || !form.range[1])) return '限时秒杀必须配置起止时间'
  if (form.scope === 'ind' && !form.indId) return '行业限定活动必须选择行业'
  return null
}

function buildPayload() {
  const p = {
    title: form.title.trim(), type: form.type, description: form.description || '',
    accent: form.accent, startTime: form.range?.[0] || null, endTime: form.range?.[1] || null,
    scope: ['coupon', 'full_reduce', 'discount', 'flash_sale'].includes(form.type) ? form.scope : 'all',
    indId: form.scope === 'ind' ? form.indId : null,
    joinable: form.type === 'merchant_incentive' ? 1 : (form.joinable ? 1 : 0)
  }
  if (['coupon', 'full_reduce', 'discount', 'flash_sale'].includes(form.type)) {
    p.threshold = form.threshold || 0
    if (form.type === 'full_reduce') { p.benefitAmount = form.benefitAmount; p.discountRate = null }
    else if (['discount', 'flash_sale'].includes(form.type)) { p.discountRate = form.discountRate; p.benefitAmount = form.capAmount || null }
    else { // coupon
      if (form.couponKind === 'rate') { p.discountRate = form.discountRate; p.benefitAmount = form.capAmount }
      else { p.benefitAmount = form.benefitAmount; p.discountRate = null }
      p.perUserLimit = form.perUserLimit || 1
      p.totalQuota = form.totalQuota || 0
    }
  }
  if (['register', 'purchase'].includes(form.type)) p.rewardPoints = form.rewardPoints
  if (form.type === 'merchant_incentive') { p.merTargetAmount = form.merTargetAmount; p.merRewardPoints = form.merRewardPoints }
  return p
}

async function save() {
  const err = validate()
  if (err) return message.warning(err)
  saving.value = true
  try {
    const payload = buildPayload()
    if (editing.value) {
      await campaignApi.adminUpdate(editing.value.campaignId, payload)
      message.success('活动已更新')
    } else {
      await campaignApi.adminCreate(payload)
      message.success('活动已创建（默认下架，点击"上架"对外发布）')
    }
    modalOpen.value = false
    reload()
  } catch (e) { /* 拦截器已提示 */ } finally { saving.value = false }
}

async function toggle(record, status) {
  try {
    await campaignApi.adminToggle(record.campaignId, status)
    message.success(status === 1 ? '已上架' : '已下架')
    reload()
  } catch (e) { /* 拦截器已提示 */ }
}
function remove(record) {
  Modal.confirm({
    title: '删除活动',
    content: `确定删除「${record.title}」？已有用户领券/参与的活动无法删除（请改为下架）。`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        await campaignApi.adminDelete(record.campaignId)
        message.success('已删除')
        reload()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

const fmt = (v) => v ? String(v).replace('T', ' ').slice(0, 16) : ''
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
function ruleText(r) {
  switch (r.type) {
    case 'coupon':
      return `${r.discountRate ? (trimZero(Number(r.discountRate) * 10) + ' 折（封顶 ¥' + trimZero(r.benefitAmount) + '）') : ('减 ¥' + trimZero(r.benefitAmount))} · 满 ¥${trimZero(r.threshold)} · 限领 ${r.perUserLimit || 1} 张`
    case 'full_reduce': return `满 ¥${trimZero(r.threshold)} 减 ¥${trimZero(r.benefitAmount)}`
    case 'discount': case 'flash_sale': {
      const cap = r.benefitAmount ? `（封顶 ¥${trimZero(r.benefitAmount)}）` : ''
      return `${trimZero(Number(r.discountRate) * 10)} 折${cap} · 满 ¥${trimZero(r.threshold)}`
    }
    case 'register': case 'purchase': return `奖励 ${r.rewardPoints} 积分`
    case 'merchant_incentive': return `销售满 ¥${trimZero(r.merTargetAmount)} 奖 ${r.merRewardPoints} 积分`
    default: return ''
  }
}

onMounted(async () => {
  reload()
  try { industries.value = await homeApi.industries() } catch (e) { industries.value = [] }
})
</script>

<style scoped>
.ac-page { padding: 4px 2px; }
.ac-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; }
.ac-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; }
.ac-head p { margin: 0; color: var(--ll-muted, #64748b); font-size: 13px; }
.ac-toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.ac-total { font-size: 12.5px; color: var(--ll-muted, #64748b); }
.ac-title { display: flex; gap: 10px; align-items: center; }
.ac-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.ac-dot.ac-blue { background: #1E6EB8; } .ac-dot.ac-red { background: #e0354b; }
.ac-dot.ac-gold { background: #b45309; } .ac-dot.ac-green { background: #047857; }
.ac-dot.ac-purple { background: #6d28d9; }
.ac-title-main b { display: block; font-size: 13.5px; }
.ac-desc { font-size: 12px; color: var(--ll-muted, #64748b);
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; max-width: 200px; }
.ac-rule { font-size: 12.5px; color: var(--ll-muted, #64748b); }
.ac-time { font-size: 12.5px; color: var(--ll-muted, #64748b); line-height: 1.7; }
.ac-stats { display: flex; gap: 22px; flex-wrap: wrap; padding: 6px 4px; font-size: 13px; color: var(--ll-muted, #64748b); }
.ac-stats b { color: var(--ll-ink, #0f172a); font-size: 15px; margin: 0 2px; }
.ac-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.ac-form-tip { margin-left: 10px; font-size: 12px; color: var(--ll-muted, #64748b); }
@media (max-width: 700px) {
  .ac-head { flex-direction: column; align-items: flex-start; }
  .ac-form-grid { grid-template-columns: 1fr; }
}
</style>
