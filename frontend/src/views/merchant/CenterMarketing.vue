<template>
  <div class="mk-page">
    <div class="mk-head">
      <div>
        <h2>营销活动</h2>
        <p>报名平台大促、自建店铺促销，冲刺销售目标赢取积分激励。</p>
      </div>
      <a-button @click="$router.push('/activity')">查看用户端活动</a-button>
    </div>

    <a-spin :spinning="loading">
      <!-- 商户激励：进度冲刺 -->
      <div class="mk-section" v-if="incentives.length">
        <div class="mk-sec-title"><Store :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:6px;color:#047857" /> 激励奖励 <span>按活动时间窗内本店已支付销售额计算进度</span></div>
        <div class="mk-grid">
          <MagicCard v-for="c in incentives" :key="c.campaignId"
                     class="mk-card mk-incentive" :gradient-size="260"
                     gradient-from="#1E6EB8" gradient-to="#38bdf8"
                     gradient-color="rgba(30, 110, 184, .07)">
            <div class="mi-top">
              <span class="mk-chip green">商户激励</span>
              <span class="mi-window">{{ fmtDate(c.startTime) }} ~ {{ fmtDate(c.endTime) || '长期' }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="mi-target">
              <span>销售目标 ¥{{ money(c.targetAmount) }}</span>
              <span>奖励 <b>{{ c.rewardPoints }}</b> 积分</span>
            </div>
            <div class="mi-progress">
              <div class="mi-bar"><i :style="{ width: (c.progressPercent || 0) + '%' }" :class="{ done: c.targetReached }"></i></div>
              <div class="mi-progress-meta">
                <span>已完成 <b>¥{{ money(c.salesAmount) }}</b></span>
                <span class="mi-percent">{{ c.progressPercent || 0 }}%</span>
              </div>
            </div>
            <div class="mi-foot">
              <template v-if="!c.enrolled">
                <a-button type="primary" :loading="actionId === c.campaignId" :disabled="!!actionId" @click="enroll(c)">报名参加</a-button>
              </template>
              <template v-else-if="c.rewarded">
                <a-tag color="green">奖励已发放</a-tag>
              </template>
              <template v-else-if="c.targetReached">
                <a-button type="primary" danger :loading="actionId === c.campaignId" :disabled="!!actionId" @click="claimReward(c)">领取 {{ c.rewardPoints }} 积分奖励</a-button>
              </template>
              <template v-else>
                <a-tag color="blue">已报名 · 冲刺中</a-tag>
              </template>
              <span class="mi-hint" v-if="c.enrolled && !c.targetReached && !c.rewarded">
                还差 ¥{{ money(c.targetAmount - c.salesAmount) }} 达标
              </span>
            </div>
          </MagicCard>
        </div>
      </div>
      <a-empty v-else-if="!loading" description="暂无可报名的激励活动" style="padding:32px 0" />

      <!-- 平台大促：开放报名的活动 -->
      <div class="mk-section" v-if="joinables.length">
        <div class="mk-sec-title"><Flame :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:6px;color:#e0354b" /> 平台大促 <span>报名后店铺商品将参与活动会场</span></div>
        <div class="mk-grid">
          <MagicCard v-for="c in joinables" :key="c.campaignId"
                     class="mk-card" :gradient-size="260"
                     gradient-from="#1E6EB8" gradient-to="#38bdf8"
                     gradient-color="rgba(30, 110, 184, .07)">
            <div class="mi-top">
              <span class="mk-chip" :class="typeClass(c.type)">{{ typeLabel(c.type) }}</span>
              <span class="mi-window">{{ fmtDate(c.startTime) }} ~ {{ fmtDate(c.endTime) || '长期' }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <p class="mk-desc">{{ c.description }}</p>
            <div class="mi-foot">
              <span class="mk-scope">{{ c.indName ? c.indName + '行业' : '全场' }}{{ c.enrolled ? ' · 已报名' : '' }}</span>
              <a-button v-if="!c.enrolled" type="primary" ghost :loading="actionId === c.campaignId" :disabled="!!actionId" @click="enroll(c)">立即报名</a-button>
              <a-tag v-else color="green">已报名</a-tag>
            </div>
          </MagicCard>
        </div>
      </div>
    </a-spin>

    <!-- 商户自建促销：满减/折扣/秒杀，提交后由平台审核，通过后方可上架 -->
    <div class="mk-section">
      <div class="mk-sec-title"><BadgePercent :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:6px;color:#b45309" /> 自建促销 <span>提交后由平台审核，审核通过前不会对用户展示</span></div>

      <!-- 创建促销 -->
      <a-card :bordered="false" class="mk-panel">
        <template #title>
          <span class="mk-panel-title"><BadgePercent :size="15" :stroke-width="2.2" /> 创建促销</span>
        </template>
        <a-form layout="vertical" class="mk-form" @submit.prevent>
          <div class="mk-form-grid">
            <a-form-item label="活动标题" required>
              <a-input v-model:value="promoForm.title" :maxlength="60" show-count placeholder="如：全场满 200 减 30" />
            </a-form-item>
            <a-form-item label="促销类型" required>
              <a-select v-model:value="promoForm.type" option-label-prop="label">
                <a-select-option v-for="t in PROMO_TYPES" :key="t.value" :value="t.value" :label="t.label">
                  <div class="mk-type-opt">
                    <span class="mk-type-name">{{ t.label }}</span>
                    <span class="mk-type-desc">{{ t.desc }}</span>
                  </div>
                </a-select-option>
              </a-select>
              <div class="mk-form-tip">{{ typeHint }}</div>
            </a-form-item>
          </div>
          <div class="mk-form-grid">
            <a-form-item label="起止时间" required>
              <a-range-picker v-model:value="promoForm.range" show-time value-format="YYYY-MM-DD HH:mm:ss"
                              :placeholder="['开始时间', '结束时间']" style="width:100%" />
            </a-form-item>
            <a-form-item label="门槛金额（元）" required>
              <a-input-number v-model:value="promoForm.threshold" :min="0" :precision="2" style="width:100%"
                              placeholder="满 X 元生效（0 = 无门槛）" />
            </a-form-item>
          </div>
          <div class="mk-form-grid" v-if="promoForm.type === 'full_reduce'">
            <a-form-item label="优惠金额（元）" required>
              <a-input-number v-model:value="promoForm.benefitAmount" :min="0.01" :precision="2" style="width:100%"
                              placeholder="满 X 减 Y 的 Y" />
            </a-form-item>
          </div>
          <div class="mk-form-grid" v-else>
            <a-form-item label="折扣率" required>
              <a-input-number v-model:value="promoForm.discountRate" :min="0.01" :max="0.99" :step="0.01" :precision="2"
                              style="width:100%" placeholder="0.85 = 85 折" />
              <div class="mk-form-tip">0.85 = 85 折，需在 0 ~ 1 之间</div>
            </a-form-item>
          </div>
          <a-form-item label="选择商品" required>
            <a-select v-model:value="promoForm.prodIds" mode="multiple" :options="prodOptions"
                      :loading="prodsLoading" option-filter-prop="label"
                      placeholder="选择参与促销的在售商品（至少 1 件）" />
            <div class="mk-form-tip" v-if="!prodsLoading && !prods.length">还没有在售商品，请先在「商品管理」发布并通过审核</div>
          </a-form-item>
          <a-form-item label="活动说明">
            <a-textarea v-model:value="promoForm.description" :rows="2" :maxlength="200" placeholder="展示给用户的促销说明" />
          </a-form-item>
          <div class="mk-form-actions">
            <a-button type="primary" :loading="submitting" @click="submitPromo">提交审核</a-button>
            <span class="mk-form-hint">提交后进入平台审核队列，审核通过后才可上架</span>
          </div>
        </a-form>
      </a-card>

      <!-- 我的促销 -->
      <a-card :bordered="false" class="mk-panel">
        <template #title>
          <span class="mk-panel-title"><ListChecks :size="15" :stroke-width="2.2" /> 我的促销</span>
        </template>
        <template #extra>
          <a-button type="link" size="small" @click="loadMyPromos">刷新</a-button>
        </template>
        <a-table :data-source="myPromos" :columns="promoColumns" row-key="campaignId"
                 :loading="myLoading" :pagination="false" size="small" :scroll="{ x: 820 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'title'">
              <div class="mk-promo-title">
                <b>{{ record.title }}</b>
                <small>绑定 {{ String(record.prodIds || '').split(',').filter(Boolean).length }} 件商品<template v-if="record.description"> · {{ record.description }}</template></small>
              </div>
            </template>
            <template v-else-if="column.key === 'type'">
              <a-tag :color="promoTypeClass(record.type)">{{ promoTypeLabel(record.type) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'time'">
              <div class="mk-promo-sub">{{ fmtRange(record.startTime) }} ~ {{ fmtRange(record.endTime) || '长期' }}</div>
            </template>
            <template v-else-if="column.key === 'rule'">
              <span class="mk-promo-sub">{{ promoRule(record) }}</span>
            </template>
            <template v-else-if="column.key === 'review'">
              <a-tooltip v-if="record.reviewStatus === 2 && record.reviewNote" :title="'驳回原因：' + record.reviewNote">
                <a-tag color="red" style="cursor: help">已驳回</a-tag>
              </a-tooltip>
              <a-tag v-else :color="reviewColor(record.reviewStatus)">{{ reviewLabel(record.reviewStatus) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'shelf'">
              <a-tooltip v-if="record.reviewStatus !== 1" :title="record.reviewStatus === 2 ? '已驳回的促销不可上架' : '待平台审核通过后可上架'">
                <a-switch :checked="record.status === 1" size="small" disabled />
              </a-tooltip>
              <a-switch v-else :checked="record.status === 1" size="small"
                        :loading="togglingId === record.campaignId" @change="togglePromo(record)" />
            </template>
          </template>
          <template #emptyText>
            <a-empty description="还没有自建促销，先在上方创建并提交审核" style="padding: 24px 0" />
          </template>
        </a-table>
      </a-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { campaignApi, productApi } from '../../api'
import MagicCard from '../../components/ui/MagicCard.vue'
import { Store, Flame, BadgePercent, ListChecks } from 'lucide-vue-next'

const campaigns = ref([])
const loading = ref(false)

const incentives = computed(() => campaigns.value.filter(c => c.type === 'merchant_incentive'))
const joinables = computed(() => campaigns.value.filter(c => c.type !== 'merchant_incentive'))

async function load() {
  loading.value = true
  try { campaigns.value = (await campaignApi.merchantList()) || [] }
  catch (e) { campaigns.value = [] } finally { loading.value = false }
}

// 报名/领奖请求中的活动 ID：按钮 loading + 全局禁用，防重复触发
const actionId = ref(null)

async function enroll(c) {
  if (actionId.value) return
  actionId.value = c.campaignId
  try {
    await campaignApi.merchantJoin(c.campaignId)
    message.success('报名成功')
    load()
  } catch (e) { /* 拦截器已提示 */ } finally { actionId.value = null }
}
async function claimReward(c) {
  if (actionId.value) return
  actionId.value = c.campaignId
  try {
    await campaignApi.merchantClaimReward(c.campaignId)
    message.success('激励奖励已发放至积分账户')
    load()
  } catch (e) { /* 拦截器已提示 */ } finally { actionId.value = null }
}

const typeLabel = (t) => ({ full_reduce: '满减大促', discount: '折扣大促', flash_sale: '限时秒杀' }[t] || '平台活动')
const typeClass = (t) => ({ full_reduce: 'blue', discount: 'blue', flash_sale: 'red' }[t] || 'blue')
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

// ===== 自建促销：创建 + 我的促销 =====
const PROMO_TYPES = [
  { value: 'full_reduce', label: '满减', desc: '满 X 元立减 Y 元' },
  { value: 'discount', label: '折扣', desc: '订单按折扣率结算' },
  { value: 'flash_sale', label: '限时秒杀', desc: '时间窗内秒杀价' }
]
const myPromos = ref([])
const myLoading = ref(false)
const togglingId = ref(null)
const prods = ref([])
const prodsLoading = ref(false)
const submitting = ref(false)
const promoForm = reactive({
  title: '', type: 'full_reduce', range: null, threshold: null,
  benefitAmount: null, discountRate: null, prodIds: [], description: ''
})

const promoColumns = [
  { key: 'title', title: '标题', width: 220 },
  { key: 'type', title: '类型', width: 90 },
  { key: 'time', title: '时间窗', width: 200 },
  { key: 'rule', title: '门槛与优惠', width: 150 },
  { key: 'review', title: '审核状态', width: 100 },
  { key: 'shelf', title: '上下架', width: 90 }
]

const typeHint = computed(() => (PROMO_TYPES.find(t => t.value === promoForm.type) || {}).desc || '')
const prodOptions = computed(() => prods.value.map(p => ({ value: p.prodId, label: `${p.title} · ￥${money(p.price)}` })))

async function loadMyPromos() {
  myLoading.value = true
  try { myPromos.value = (await campaignApi.merchantMy()) || [] }
  catch (e) { myPromos.value = [] } finally { myLoading.value = false }
}

// 数据源：商户自己在售且已过审的商品（与 CenterPromotion 同一接口）
async function loadProds() {
  prodsLoading.value = true
  try {
    const page = await productApi.myList({ current: 1, size: 100, status: 1, reviewStatus: 1 })
    prods.value = page.records || []
  } catch (e) { prods.value = [] } finally { prodsLoading.value = false }
}

function validatePromo() {
  if (!promoForm.title.trim()) return '请填写活动标题'
  if (!promoForm.range || !promoForm.range[0] || !promoForm.range[1]) return '请选择促销起止时间'
  if (String(promoForm.range[1]) <= String(promoForm.range[0])) return '结束时间需晚于开始时间'
  if (promoForm.threshold == null) return '请填写门槛金额（0 表示无门槛）'
  if (promoForm.type === 'full_reduce' && !(promoForm.benefitAmount > 0)) return '满减促销必须填写优惠金额'
  if (['discount', 'flash_sale'].includes(promoForm.type)
      && !(promoForm.discountRate > 0 && promoForm.discountRate < 1)) return '折扣率需在 0 ~ 1 之间（0.85 = 85 折）'
  if (!promoForm.prodIds.length) return '请至少选择 1 件参与促销的商品'
  return null
}

async function submitPromo() {
  const err = validatePromo()
  if (err) return message.warning(err)
  submitting.value = true
  try {
    await campaignApi.merchantCreate({
      title: promoForm.title.trim(),
      type: promoForm.type,
      description: promoForm.description || '',
      accent: 'blue',
      startTime: promoForm.range[0],
      endTime: promoForm.range[1],
      threshold: promoForm.threshold || 0,
      benefitAmount: promoForm.type === 'full_reduce' ? promoForm.benefitAmount : null,
      discountRate: promoForm.type === 'full_reduce' ? null : promoForm.discountRate,
      prodIds: promoForm.prodIds
    })
    message.success('已提交，等待平台审核')
    Object.assign(promoForm, { title: '', range: null, threshold: null, benefitAmount: null, discountRate: null, prodIds: [], description: '' })
    loadMyPromos()
  } catch (e) { /* 拦截器已提示 */ } finally { submitting.value = false }
}

async function togglePromo(r) {
  togglingId.value = r.campaignId
  try {
    const d = await campaignApi.merchantToggle(r.campaignId)
    r.status = d && d.status != null ? Number(d.status) : (r.status === 1 ? 0 : 1)
    message.success(r.status === 1 ? '已上架' : '已下架')
  } catch (e) { /* 拦截器已提示 */ } finally { togglingId.value = null }
}

const promoTypeLabel = (t) => ({ full_reduce: '满减', discount: '折扣', flash_sale: '限时秒杀' }[t] || '促销')
const promoTypeClass = (t) => ({ full_reduce: 'blue', discount: 'geekblue', flash_sale: 'red' }[t] || 'blue')
const reviewLabel = (s) => ({ 0: '待审核', 1: '已通过', 2: '已驳回' }[s] || '待审核')
const reviewColor = (s) => ({ 0: 'orange', 1: 'green', 2: 'red' }[s] || 'orange')
const trimZero = (v) => (Number(v) % 1 === 0 ? String(Number(v)) : String(v))
const promoRule = (r) => {
  const th = Number(r.threshold) > 0 ? `满 ¥${trimZero(r.threshold)}` : '无门槛'
  if (r.type === 'full_reduce') return `${th} 减 ¥${trimZero(r.benefitAmount)}`
  if (r.discountRate != null) return `${trimZero(Number(r.discountRate) * 10)} 折 · ${th}`
  return th
}
const fmtRange = (v) => v ? String(v).replace('T', ' ').slice(0, 16) : ''

onMounted(() => { load(); loadMyPromos(); loadProds() })
</script>

<style scoped>
.mk-page { padding: 6px 2px; }
.mk-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 20px; }
.mk-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-head p { margin: 0; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

.mk-section { margin-bottom: 26px; }
.mk-sec-title { font-size: 15px; font-weight: 700; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); margin-bottom: 12px; }
.mk-sec-title span { font-size: 12px; font-weight: 400; color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-left: 10px; }

.mk-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.mk-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1);
  border-radius: 18px; padding: 18px 20px; display: flex; flex-direction: column;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.mk-card:hover { transform: translateY(-3px); box-shadow: 0 14px 34px rgba(19, 35, 58, .08); }

.mi-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 10px; }
.mk-chip { font-size: 11.5px; color: #fff; padding: 3px 10px; border-radius: 999px; }
.mk-chip.green { background: linear-gradient(135deg, #047857, #34d399); }
.mk-chip.blue { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.mk-chip.red { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.mi-window { font-size: 11.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.mk-card h3 { margin: 0 0 8px; font-size: 15.5px; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-desc { margin: 0 0 12px; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 42px; }

.mi-target { display: flex; justify-content: space-between; align-items: baseline; font-size: 13px;
  color: var(--mc-text2, var(--ll-muted, #3a3a3c)); margin-bottom: 10px; }
.mi-target b { font-size: 17px; color: #b45309; }

.mi-progress { margin-bottom: 14px; }
.mi-bar { height: 8px; border-radius: 999px; background: var(--mc-soft, #f5f5f7); overflow: hidden; }
.mi-bar i { display: block; height: 100%; border-radius: 999px;
  background: linear-gradient(90deg, #1E6EB8, #38bdf8); transition: width .6s var(--ll-ease-out, ease); }
.mi-bar i.done { background: linear-gradient(90deg, #047857, #34d399); }
.mi-progress-meta { display: flex; justify-content: space-between; font-size: 12px;
  color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-top: 6px; }
.mi-progress-meta b { color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mi-percent { font-variant-numeric: tabular-nums; font-weight: 700; }

.mi-foot { margin-top: auto; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.mi-hint { font-size: 12px; color: #d93025; }
.mk-scope { font-size: 12.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

/* ===== 自建促销 ===== */
.mk-panel { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1);
  border-radius: 18px; box-shadow: 0 1px 2px rgba(19, 35, 58, .03); margin-bottom: 14px; }
.mk-panel :deep(.ant-card-head) { border-bottom-color: var(--mc-border, #e8e6e1); min-height: 52px; }
.mk-panel :deep(.ant-card-head-title) { padding: 14px 0; }
.mk-panel-title { display: inline-flex; align-items: center; gap: 6px; font-size: 14.5px; font-weight: 700;
  color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-form { max-width: 880px; }
.mk-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
.mk-form-tip { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-top: 4px; line-height: 1.5; }
.mk-form-actions { display: flex; align-items: center; gap: 14px; }
.mk-form-hint { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.mk-type-opt { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.mk-type-name { font-weight: 600; }
.mk-type-desc { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.mk-promo-title b { display: block; font-size: 13.5px; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.mk-promo-title small { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73));
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
.mk-promo-sub { font-size: 12.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

@media (max-width: 700px) {
  .mk-head { flex-direction: column; align-items: flex-start; }
  .mk-grid { grid-template-columns: 1fr; }
  .mk-form-grid { grid-template-columns: 1fr; }
}
</style>
