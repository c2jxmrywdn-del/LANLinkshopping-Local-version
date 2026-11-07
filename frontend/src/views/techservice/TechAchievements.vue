<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">ACHIEVEMENT LIBRARY · DORMANT PATENTS</span>
        <h1>{{ isEn ? 'Not every patent is an asset. Screen before you invest.' : '不是每一件专利都是资产，先快筛再投入。' }}</h1>
        <p>
          {{ isEn
            ? 'Browse patents and technology achievements with legal status, maturity, licensing mode and a quick screening score. Formal appraisal is issued by qualified institutions.'
            : '查看专利与技术成果的法律状态、成熟度、许可方式与快筛评分；正式评估由具备相应能力的第三方机构出具。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="go('/tech/hosting')">
            <ArchiveRestore :size="16" :stroke-width="2.2" /> {{ isEn ? 'Host a dormant patent' : '申请沉睡专利托管' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/workspace')">
            {{ isEn ? 'My projects' : '我的陪跑项目' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="SCREEN & MATCH"
        :title="isEn ? 'Achievements open for licensing or assignment' : '开放许可、转让或合作的成果'"
        :desc="isEn
          ? 'Quick screening is a reference, not an appraisal. It helps decide whether to spend on formal evaluation and validation.'
          : '快筛结果仅供参考，不替代正式评估；它的作用是帮助判断是否值得投入评估与验证资源。'"
      />
      <div class="tech-toolbar">
        <SegmentedControl v-model="type" :options="typeOptions" :aria-label="isEn ? 'Type filter' : '类型筛选'" />
        <SegmentedControl v-model="dormant" :options="dormantOptions" :aria-label="isEn ? 'Dormant filter' : '沉睡专利筛选'" />
        <a-input
          v-model:value="keyword"
          class="grow"
          allow-clear
          :placeholder="isEn ? 'Search title, field or summary' : '搜索标题、技术领域或摘要'"
          @pressEnter="reload(1)"
        />
        <a-select
          v-model:value="industry"
          style="width: 150px"
          allow-clear
          :placeholder="isEn ? 'Industry' : '行业'"
          :options="industryOptions"
        />
        <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="reload(1)">
          {{ isEn ? 'Search' : '搜索' }}
        </button>
      </div>

      <a-spin :spinning="loading">
        <div v-if="achievements.length" class="tech-grid">
          <TechAchievementCard
            v-for="achievement in achievements"
            :key="achievement.achievementId"
            :achievement="achievement"
            @view="openDetail(achievement)"
            @assess="openAssessment(achievement)"
          />
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <BadgeCheck :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No matching achievements' : '暂无匹配成果' }}</b>
          <span>{{ isEn ? 'Try a different industry or include dormant patents.' : '换个行业，或把筛选范围放宽到沉睡专利。' }}</span>
        </div>
      </a-spin>

      <div v-if="total > size" class="tech-pagination">
        <a-pagination v-model:current="page" :total="total" :page-size="size" :show-size-changer="false" @change="reload" />
      </div>
    </section>

    <a-drawer v-model:open="detailOpen" :width="'min(600px, 94vw)'" :title="detail?.achievement?.title || (isEn ? 'Achievement detail' : '成果详情')">
      <div v-if="detail" class="tech-drawer-body">
        <div class="tech-row">
          <span class="tech-chip cyan">{{ detail.achievement.achievementType === 'PATENT' ? (isEn ? 'Patent' : '专利') : (isEn ? 'Technology' : '技术成果') }}</span>
          <span v-if="detail.achievement.dormant" class="tech-chip amber">{{ isEn ? 'Dormant' : '沉睡专利' }}</span>
          <span v-if="detail.achievement.verified" class="tech-chip green">{{ isEn ? 'Ownership verified' : '权属已核验' }}</span>
          <span class="tech-chip">{{ detail.achievement.status }}</span>
        </div>
        <h3 class="tech-mt">{{ detail.achievement.title }}</h3>
        <p class="sub">{{ detail.achievement.summary }}</p>
        <dl class="tech-kv">
          <dt>{{ isEn ? 'Patent no.' : '专利号' }}</dt>
          <dd>{{ detail.achievement.patentNo || '—' }}</dd>
          <dt>{{ isEn ? 'Legal status' : '法律状态' }}</dt>
          <dd>{{ detail.achievement.legalStatus || '—' }}</dd>
          <dt>{{ isEn ? 'Owners' : '权属人' }}</dt>
          <dd>{{ detail.achievement.owners || '—' }}</dd>
          <dt>{{ isEn ? 'Maturity' : '成熟度' }}</dt>
          <dd>TRL {{ detail.achievement.maturityLevel }}</dd>
          <dt>{{ isEn ? 'Licensing' : '许可方式' }}</dt>
          <dd>{{ licensingLabel(detail.achievement.licensingMode) }}</dd>
          <dt>{{ isEn ? 'Expected price' : '参考价格' }}</dt>
          <dd>{{ priceLabel(detail.achievement.expectedPrice) }}</dd>
        </dl>
        <h4>{{ isEn ? 'Highlights' : '技术亮点' }}</h4>
        <p class="sub">{{ detail.achievement.highlights }}</p>
        <h4>{{ isEn ? 'Application scenarios' : '应用场景' }}</h4>
        <p class="sub">{{ detail.achievement.applicationScenarios }}</p>

        <h4>{{ isEn ? 'Quick screening (reference only)' : 'AI 快筛（仅供参考）' }}</h4>
        <div class="tech-score-grid">
          <div v-for="(score, key) in detail.assessment.scores" :key="key" class="tech-score">
            <b>{{ score }}</b>
            <span>{{ scoreLabel(key) }}</span>
          </div>
        </div>
        <div class="tech-row tech-mt">
          <span class="tech-chip cyan">{{ detail.assessment.level }} · {{ detail.assessment.levelLabel }}</span>
          <span class="tech-chip">{{ isEn ? 'Composite' : '综合' }} {{ detail.assessment.composite }} / 100</span>
        </div>
        <ul class="tech-reasons">
          <li v-for="reason in detail.assessment.reasons" :key="reason">{{ reason }}</li>
        </ul>
        <div class="tech-note">
          <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
          <div>
            <strong>{{ isEn ? 'Screening disclaimer' : '快筛免责声明' }}</strong>
            <span>{{ detail.assessment.disclaimer }}</span>
          </div>
        </div>
        <div class="tech-row tech-mt">
          <button type="button" class="tech-btn tech-btn-primary" @click="openHosting(detail.achievement)">
            {{ isEn ? 'Apply for hosting' : '申请托管' }}
          </button>
          <button type="button" class="tech-btn tech-btn-outline" @click="openDeal(detail.achievement)">
            {{ isEn ? 'Start a deal' : '发起交易' }}
          </button>
          <button type="button" class="tech-btn tech-btn-light" @click="go('/tech/experts')">
            {{ isEn ? 'Talk to an expert' : '找专家判断' }}
          </button>
        </div>
      </div>
    </a-drawer>

    <a-modal
      v-model:open="assessOpen"
      :title="isEn ? 'Quick screening' : 'AI 专利/成果快筛'"
      :footer="null"
      width="min(580px, 94vw)"
    >
      <div v-if="assessment" class="tech-drawer-body">
        <div class="tech-row">
          <span class="tech-chip cyan">{{ assessment.level }} · {{ assessment.levelLabel }}</span>
          <span class="tech-chip">{{ isEn ? 'Composite' : '综合' }} {{ assessment.composite }} / 100</span>
        </div>
        <h3 class="tech-mt">{{ assessment.title }}</h3>
        <div class="tech-score-grid">
          <div v-for="(score, key) in assessment.scores" :key="key" class="tech-score">
            <b>{{ score }}</b>
            <span>{{ scoreLabel(key) }}</span>
          </div>
        </div>
        <ul class="tech-reasons">
          <li v-for="reason in assessment.reasons" :key="reason">{{ reason }}</li>
        </ul>
        <div class="tech-note">
          <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
          <div>
            <strong>{{ isEn ? 'Reference only' : '仅供参考' }}</strong>
            <span>{{ assessment.disclaimer }}</span>
          </div>
        </div>
      </div>
    </a-modal>

    <a-modal
      v-model:open="hostingOpen"
      :title="isEn ? 'Apply for patent hosting' : '申请专利托管'"
      :ok-text="isEn ? 'Submit' : '提交申请'"
      :cancel-text="isEn ? 'Cancel' : '取消'"
      :confirm-loading="submitting"
      width="min(600px, 94vw)"
      @ok="submitHosting"
    >
      <a-form layout="vertical">
        <a-form-item :label="isEn ? 'Target' : '托管标的'">
          <a-input :value="target?.title" disabled />
        </a-form-item>
        <a-form-item :label="isEn ? 'Scope' : '托管范围'" required>
          <a-textarea v-model:value="hostingForm.scope" :rows="3" :placeholder="isEn ? 'Ownership checks, annuity management, profiling, buyer matching...' : '权属核查、年费管理、技术画像、买家匹配……'" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Activation plan' : '盘活计划'">
          <a-textarea v-model:value="hostingForm.activationPlan" :rows="3" :placeholder="isEn ? 'Target scenarios, buyer profile, licensing strategy...' : '目标场景、买家画像、许可策略……'" />
        </a-form-item>
        <div class="tech-form-grid">
          <a-form-item :label="isEn ? 'Fee mode' : '费用模式'">
            <a-select v-model:value="hostingForm.feeMode" :options="feeModeOptions" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Annual fee (¥)' : '年费（元）'">
            <a-input-number v-model:value="hostingForm.feeAmount" :min="0" :step="500" style="width: 100%" />
          </a-form-item>
        </div>
      </a-form>
      <div class="tech-note">
        <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>{{ isEn ? 'Zero-threshold hosting' : '零门槛托管' }}</strong>
          <span v-if="isEn">
            No upfront listing fee. A success fee of 15–25% applies only when a deal closes. Ownership and annuity checks
            are completed before the hosting agreement is activated.
          </span>
          <span v-else>
            不收取挂牌费，成交后按 15–25% 收取服务分成；托管协议生效前完成权属与年费核查，不成交不收费。
          </span>
        </div>
      </div>
    </a-modal>

    <a-modal
      v-model:open="dealOpen"
      :title="isEn ? 'Start a deal intent' : '发起交易意向'"
      :ok-text="isEn ? 'Submit intent' : '提交意向'"
      :cancel-text="isEn ? 'Cancel' : '取消'"
      :confirm-loading="submitting"
      width="min(560px, 94vw)"
      @ok="submitDeal"
    >
      <a-form layout="vertical">
        <a-form-item :label="isEn ? 'Achievement' : '交易标的'">
          <a-input :value="target?.title" disabled />
        </a-form-item>
        <a-form-item :label="isEn ? 'Deal type' : '交易方式'">
          <a-select v-model:value="dealForm.dealType" :options="dealTypeOptions" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Amount (¥)' : '交易金额（元）'" required>
          <a-input-number v-model:value="dealForm.amount" :min="1" :step="10000" style="width: 100%" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Payment mode' : '付款方式'">
          <a-select v-model:value="dealForm.paymentMode" :options="paymentModeOptions" />
        </a-form-item>
      </a-form>
      <div class="tech-note">
        <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>{{ isEn ? 'Escrow by licensed institution' : '持牌机构资金监管' }}</strong>
          <span v-if="isEn">
            The platform does not collect or hold deal funds. Escrow, settlement and contract witnessing are handled by
            licensed partners; the demo environment does not move real money.
          </span>
          <span v-else>
            平台不代收代付交易资金；资金监管、结算与合同鉴证由持牌合作机构办理；演示环境不划转真实资金。
          </span>
        </div>
      </div>
    </a-modal>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArchiveRestore, BadgeCheck, ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import TechAchievementCard from '../../components/techservice/TechAchievementCard.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const INDUSTRIES = ['智能制造', '新材料', '电子信息', '新能源', '绿色低碳', '生物医药']
const industryOptions = computed(() => INDUSTRIES.map((v) => ({ label: v, value: v })))
const typeOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'Patents' : '专利', value: 'PATENT' },
  { label: isEn.value ? 'Technologies' : '技术成果', value: 'TECHNOLOGY' }
])
const dormantOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'Dormant' : '沉睡专利', value: 'DORMANT' }
])
const feeModeOptions = computed(() => [
  { label: isEn.value ? 'Commission' : '成交分成', value: 'COMMISSION' },
  { label: isEn.value ? 'Annual fee' : '年费', value: 'ANNUAL' }
])
const dealTypeOptions = computed(() => [
  { label: isEn.value ? 'License' : '许可', value: 'LICENSE' },
  { label: isEn.value ? 'Assignment' : '转让', value: 'ASSIGN' },
  { label: isEn.value ? 'Open license' : '开放许可', value: 'OPEN_LICENSE' }
])
const paymentModeOptions = computed(() => [
  { label: isEn.value ? 'Escrow' : '资金监管', value: 'ESCROW' },
  { label: isEn.value ? 'Installments' : '分期支付', value: 'INSTALLMENT' },
  { label: isEn.value ? 'Pay after use' : '先使用后付费', value: 'PAY_AFTER_USE' }
])

const loading = ref(true)
const achievements = ref([])
const total = ref(0)
const page = ref(1)
const size = 9
const type = ref('ALL')
const dormant = ref('ALL')
const industry = ref(undefined)
const keyword = ref('')

const detailOpen = ref(false)
const detail = ref(null)
const assessOpen = ref(false)
const assessment = ref(null)
const hostingOpen = ref(false)
const dealOpen = ref(false)
const target = ref(null)
const submitting = ref(false)
const hostingForm = reactive({ scope: '', activationPlan: '', feeMode: 'COMMISSION', feeAmount: 0 })
const dealForm = reactive({ dealType: 'LICENSE', amount: 0, paymentMode: 'ESCROW' })

function scoreLabel(key) {
  const map = {
    legal: isEn.value ? 'Legal' : '法律',
    technology: isEn.value ? 'Technology' : '技术',
    economic: isEn.value ? 'Economic' : '经济',
    maturity: isEn.value ? 'Maturity' : '成熟度'
  }
  return map[key] || key
}
function licensingLabel(mode) {
  const map = {
    ASSIGN: isEn.value ? 'Assignment' : '转让',
    LICENSE: isEn.value ? 'License' : '许可',
    OPEN_LICENSE: isEn.value ? 'Open license' : '开放许可'
  }
  return map[mode] || mode || '—'
}
function priceLabel(value) {
  const n = Number(value)
  if (!n) return isEn.value ? 'Negotiable' : '面议'
  return n >= 10000 ? `¥${(n / 10000).toFixed(1).replace('.0', '')}万` : `¥${n}`
}

async function reload(p = 1) {
  page.value = p
  loading.value = true
  try {
    const res = await techApi.achievements({
      page: p,
      size,
      industry: industry.value || undefined,
      type: type.value === 'ALL' ? undefined : type.value,
      dormant: dormant.value === 'DORMANT' ? 1 : undefined,
      keyword: keyword.value || undefined
    })
    achievements.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    achievements.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(achievement) {
  try {
    detail.value = await techApi.achievement(achievement.achievementId)
    detailOpen.value = true
  } catch (e) {
    detail.value = null
  }
}

async function openAssessment(achievement) {
  try {
    assessment.value = await techApi.assessment(achievement.achievementId)
    assessOpen.value = true
  } catch (e) {
    assessment.value = null
  }
}

function requireLogin() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return false
  }
  return true
}

function openHosting(achievement) {
  if (!requireLogin()) return
  target.value = achievement
  hostingForm.scope = isEn.value
    ? 'Ownership and annuity checks, technology profiling, licensing/assignment design, buyer matching'
    : '权属与年费核查、技术画像、许可/转让方案设计、买家匹配'
  hostingForm.activationPlan = isEn.value
    ? 'Prioritize the strongest application scenario, prepare a one-page technology brief, and approach 3–5 target buyers.'
    : '优先锁定最强应用场景，制作一页式技术简报，定向触达 3–5 家潜在买家。'
  hostingForm.feeMode = 'COMMISSION'
  hostingForm.feeAmount = 0
  hostingOpen.value = true
}

async function submitHosting() {
  if (!hostingForm.scope.trim()) {
    message.warning(isEn.value ? 'Please describe the scope' : '请填写托管范围')
    return
  }
  submitting.value = true
  try {
    await techApi.createHosting({
      hostingType: 'PATENT',
      targetId: target.value.achievementId,
      targetName: target.value.title,
      scope: hostingForm.scope,
      activationPlan: hostingForm.activationPlan,
      feeMode: hostingForm.feeMode,
      feeAmount: hostingForm.feeAmount
    })
    message.success(isEn.value ? 'Hosting application submitted' : '托管申请已提交，陪跑项目已创建')
    hostingOpen.value = false
    detailOpen.value = false
    router.push('/tech/workspace')
  } catch (e) {
    // request wrapper shows the error
  } finally {
    submitting.value = false
  }
}

function openDeal(achievement) {
  if (!requireLogin()) return
  target.value = achievement
  dealForm.dealType = achievement.licensingMode === 'ASSIGN' ? 'ASSIGN'
    : achievement.licensingMode === 'OPEN_LICENSE' ? 'OPEN_LICENSE' : 'LICENSE'
  dealForm.amount = Number(achievement.expectedPrice || 0) || 0
  dealForm.paymentMode = 'ESCROW'
  dealOpen.value = true
}

async function submitDeal() {
  if (!dealForm.amount || Number(dealForm.amount) <= 0) {
    message.warning(isEn.value ? 'Please enter the deal amount' : '请填写交易金额')
    return
  }
  submitting.value = true
  try {
    await techApi.createDeal({
      achievementId: target.value.achievementId,
      dealType: dealForm.dealType,
      amount: dealForm.amount,
      paymentMode: dealForm.paymentMode
    })
    message.success(isEn.value ? 'Deal intent submitted' : '交易意向已提交，陪跑项目已创建')
    dealOpen.value = false
    detailOpen.value = false
    router.push('/tech/workspace')
  } catch (e) {
    // request wrapper shows the error
  } finally {
    submitting.value = false
  }
}

function go(path, query) {
  router.push({ path, query })
}

watch([type, dormant, industry], () => reload(1))

onMounted(async () => {
  await reload(1)
  if (route.query.id) {
    await openDetail({ achievementId: route.query.id })
    if (String(route.query.assess || '') === '1') {
      await openAssessment({ achievementId: route.query.id })
      router.replace({ path: route.path, query: {} })
    }
  }
})
</script>

<style scoped>
.tech-pagination { display: flex; justify-content: center; margin-top: 22px; }
.tech-drawer-body h4 { margin: 22px 0 6px; font-size: 14px; }
.tech-reasons { margin: 12px 0 0; padding-left: 18px; color: var(--ll-muted); font-size: 13px; line-height: 1.9; }
</style>
