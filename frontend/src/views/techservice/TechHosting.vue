<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">HOSTING · PATENT / AGENCY / SAAS</span>
        <h1>{{ isEn ? 'Hosting is the entry point. A deal is the outcome.' : '托管是入口，成交才是结果。' }}</h1>
        <p>
          {{ isEn
            ? 'Choose patent activation, agency hosting or SaaS tooling. Every engagement is tracked with milestones, service records and next actions.'
            : '选择沉睡专利盘活、知识产权机构托管或 SaaS 工具托管；每一次托管都有里程碑、服务记录与下一步动作。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="scrollToForm">
            {{ isEn ? 'Apply for hosting' : '申请托管' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/achievements')">
            {{ isEn ? 'Browse patents' : '浏览专利成果' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="CHOOSE A LANE"
        :title="isEn ? 'Three hosting lanes' : '三类托管，按需选择'"
        :desc="isEn
          ? 'Patent activation focuses on licensing/assignment; agency hosting on service delivery; SaaS on monitoring and alerts.'
          : '专利托管聚焦许可/转让与盘活；机构托管聚焦服务交付与验收；SaaS 托管聚焦监控、预警与数据服务。'"
      />
      <div class="tech-bento">
        <button
          v-for="lane in lanes"
          :key="lane.type"
          type="button"
          class="tech-bento-card"
          :class="[lane.span, { dark: lane.type === 'PATENT', tint: lane.type === 'SAAS' }]"
          @click="selectLane(lane.type)"
        >
          <span class="tech-icon-chip"><component :is="lane.icon" :size="20" :stroke-width="1.9" /></span>
          <h3>{{ lane.title }}</h3>
          <p>{{ lane.desc }}</p>
          <span class="tech-link tech-mt">{{ lane.cta }} →</span>
        </button>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="SERVICE PRODUCTS"
        :title="isEn ? 'Productized hosting services' : '产品化托管服务'"
        :desc="isEn
          ? 'Transparent scope, deliverables and fee mode. Custom engagements are quoted after diagnosis.'
          : '范围、交付物与收费模式透明；复杂项目在诊断后单独报价。'"
      >
        <template #action>
          <SegmentedControl v-model="activeTab" :options="tabOptions" :aria-label="isEn ? 'Hosting type' : '托管类型'" />
        </template>
      </TechSectionHead>
      <div v-if="filteredServices.length" class="tech-grid">
        <article v-for="service in filteredServices" :key="service.serviceId" class="tech-card">
          <div class="tech-card-top">
            <span class="tech-chip cyan">{{ service.highlight || categoryLabel(service.category) }}</span>
            <span class="tech-chip">{{ priceModeLabel(service.priceMode) }}</span>
          </div>
          <h3>{{ service.title }}</h3>
          <p>{{ service.subtitle }}</p>
          <div class="tech-card-meta">
            <span>{{ service.durationDays }} {{ isEn ? 'days' : '天' }}</span>
            <span v-if="service.commissionRate">15–25% {{ isEn ? 'success fee' : '成交分成' }}</span>
          </div>
          <div class="tech-card-foot">
            <span class="tech-price">
              {{ service.price ? `¥${service.price}` : (isEn ? 'Quote' : '按需报价') }}
              <small v-if="service.priceMode === 'SUBSCRIPTION'">/{{ isEn ? 'mo' : '月' }}</small>
            </span>
            <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="applyService(service)">
              {{ isEn ? 'Apply' : '申请该服务' }}
            </button>
          </div>
        </article>
      </div>
      <div v-else class="tech-empty">
        <Layers :size="42" :stroke-width="1.4" aria-hidden="true" />
        <b>{{ isEn ? 'No products in this lane yet' : '该类型暂无公开服务' }}</b>
        <span>{{ isEn ? 'Switch the tab or contact a broker for a custom scope.' : '切换类型，或联系技术经纪人定制服务范围。' }}</span>
      </div>
    </section>

    <section id="tech-hosting-apply" class="tech-section">
      <TechSectionHead
        eyebrow="APPLICATION"
        :title="isEn ? 'Apply for hosting' : '提交托管申请'"
        :desc="isEn
          ? 'The application creates a tracked project. Ownership and annuity checks are completed before activation.'
          : '提交后生成可跟踪的陪跑项目；平台先完成权属与年费核查，再激活托管协议。'"
      />
      <div class="tech-form-card">
        <a-form layout="vertical">
          <div class="tech-form-grid">
            <a-form-item :label="isEn ? 'Hosting type' : '托管类型'" required>
              <a-select v-model:value="form.hostingType" :options="tabOptions" @change="onHostingTypeChange" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Target name' : '托管标的'" required>
              <a-input v-model:value="form.targetName" :placeholder="isEn ? 'Patent, service or tool name' : '专利、服务或工具名称'" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Service provider' : '服务机构'">
              <a-select v-model:value="form.providerOrgId" allow-clear :options="providerOptions" :placeholder="isEn ? 'Optional; platform can match' : '可不选，由平台匹配'" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Fee mode' : '费用模式'">
              <a-select v-model:value="form.feeMode" :options="feeModeOptions" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Fee amount (¥)' : '费用（元）'">
              <a-input-number v-model:value="form.feeAmount" :min="0" :step="500" style="width: 100%" />
            </a-form-item>
            <a-form-item class="full" :label="isEn ? 'Scope' : '托管范围'" required>
              <a-textarea v-model:value="form.scope" :rows="3" :placeholder="isEn ? 'What should the provider own and deliver?' : '服务机构需要负责并交付什么？'" />
            </a-form-item>
            <a-form-item class="full" :label="isEn ? 'Activation / operation plan' : '盘活/运营计划'">
              <a-textarea v-model:value="form.activationPlan" :rows="3" :placeholder="isEn ? 'Target scenarios, buyer profile, monitoring scope...' : '目标场景、买家画像、监控范围……'" />
            </a-form-item>
          </div>
          <div class="tech-note">
            <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
            <div>
              <strong>{{ isEn ? 'No upfront listing fee' : '零门槛托管' }}</strong>
              <span v-if="isEn">
                Patent activation is charged as a 15–25% success fee after a deal closes. SaaS subscriptions and agency
                retainers follow the selected fee mode. No real payment is collected in this demo.
              </span>
              <span v-else>
                专利盘活按成交后 15–25% 分成；SaaS 订阅与机构年费按所选费用模式执行。演示环境不收取真实费用。
              </span>
            </div>
          </div>
          <div class="tech-form-actions">
            <button type="button" class="tech-btn tech-btn-primary" :disabled="submitting" @click="submit">
              {{ submitting ? (isEn ? 'Submitting…' : '提交中…') : (isEn ? 'Submit application' : '提交申请') }}
            </button>
          </div>
        </a-form>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="MY HOSTING"
        :title="isEn ? 'My hosting records' : '我的托管记录'"
        :desc="isEn ? 'Track activation, service records and next review.' : '跟踪托管激活、服务记录与下一次复盘。'"
      >
        <template #action>
          <button v-if="user.logged" type="button" class="tech-link" @click="loadMine">
            {{ isEn ? 'Refresh →' : '刷新 →' }}
          </button>
        </template>
      </TechSectionHead>
      <div v-if="!user.logged" class="tech-empty">
        <Layers :size="42" :stroke-width="1.4" aria-hidden="true" />
        <b>{{ isEn ? 'Sign in to track hosting' : '登录后查看托管记录' }}</b>
        <span>{{ isEn ? 'Hosting applications and service records live in the workspace.' : '托管申请与服务记录会进入交易工作台。' }}</span>
        <button type="button" class="tech-btn tech-btn-primary tech-mt" @click="go('/login')">{{ isEn ? 'Sign in' : '去登录' }}</button>
      </div>
      <a-spin v-else :spinning="mineLoading">
        <div v-if="mine.length" class="tech-grid">
          <article v-for="item in mine" :key="item.hostingId" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip cyan">{{ hostingTypeLabel(item.hostingType) }}</span>
              <span class="tech-chip" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span>
            </div>
            <h3>{{ item.targetName }}</h3>
            <p>{{ item.scope }}</p>
            <div class="tech-card-meta">
              <span>{{ item.startDate }} → {{ item.endDate }}</span>
              <span>{{ feeModeLabel(item.feeMode) }}</span>
              <span v-if="item.commissionRate">{{ item.commissionRate }}%</span>
            </div>
            <div v-if="item.activationPlan" class="tech-note tech-mt">
              <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
              <div>
                <strong>{{ isEn ? 'Activation plan' : '盘活/运营计划' }}</strong>
                <span>{{ item.activationPlan }}</span>
              </div>
            </div>
            <div class="tech-card-foot">
              <span class="tech-muted">{{ item.hostingNo }}</span>
              <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="go('/tech/workspace')">
                {{ isEn ? 'Open project' : '查看项目' }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!mineLoading" class="tech-empty">
          <Layers :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No hosting records yet' : '还没有托管记录' }}</b>
          <span>{{ isEn ? 'Submit the first application above.' : '在上方提交第一条托管申请。' }}</span>
        </div>
      </a-spin>
    </section>
  </TechPageShell>
</template>

<script setup>
import { computed, markRaw, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArchiveRestore, Building2, Cloud, Layers, ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { ensureTechUser } from '../../utils/techAuth'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const activeTab = ref('PATENT')
const services = ref([])
const organizations = ref([])
const mine = ref([])
const mineLoading = ref(false)
const submitting = ref(false)
const form = reactive({
  hostingType: 'PATENT',
  targetName: '',
  providerOrgId: undefined,
  feeMode: 'COMMISSION',
  feeAmount: 0,
  scope: '',
  activationPlan: ''
})

const tabOptions = computed(() => [
  { label: isEn.value ? 'Patent' : '专利托管', value: 'PATENT' },
  { label: isEn.value ? 'Agency' : '机构托管', value: 'AGENCY' },
  { label: isEn.value ? 'SaaS' : 'SaaS 托管', value: 'SAAS' }
])
const feeModeOptions = computed(() => [
  { label: isEn.value ? 'Success fee' : '成交分成', value: 'COMMISSION' },
  { label: isEn.value ? 'Annual fee' : '年费', value: 'ANNUAL' },
  { label: isEn.value ? 'Subscription' : '订阅', value: 'SUBSCRIPTION' }
])
const lanes = computed(() => [
  {
    type: 'PATENT',
    span: 'span-4',
    icon: markRaw(ArchiveRestore),
    title: isEn.value ? 'Dormant patent activation' : '沉睡专利盘活',
    desc: isEn.value ? 'Ownership checks, profiling, licensing/assignment design and buyer matching.' : '权属核查、技术画像、许可/转让方案与买家匹配。',
    cta: isEn.value ? 'Activate a patent' : '盘活一件专利'
  },
  {
    type: 'AGENCY',
    span: 'span-4',
    icon: markRaw(Building2),
    title: isEn.value ? 'IP agency hosting' : '知识产权机构托管',
    desc: isEn.value ? 'Curated agencies, milestone acceptance and traceable service records.' : '严选服务机构，按里程碑验收，服务记录可追踪。',
    cta: isEn.value ? 'Host a service' : '托管一项服务'
  },
  {
    type: 'SAAS',
    span: 'span-4',
    icon: markRaw(Cloud),
    title: isEn.value ? 'SaaS tool hosting' : 'SaaS 工具托管',
    desc: isEn.value ? 'Patent monitoring, annuity alerts, competitor tracking and FTO screening.' : '专利监控、年费预警、竞品动态与 FTO 初筛。',
    cta: isEn.value ? 'Start monitoring' : '开通监控'
  }
])
const categoryByType = { PATENT: 'PATENT_HOSTING', AGENCY: 'AGENCY_HOSTING', SAAS: 'SAAS' }
const filteredServices = computed(() => services.value.filter((s) => s.category === categoryByType[activeTab.value]))
const providerOptions = computed(() => {
  const wanted = form.hostingType === 'SAAS' ? 'SAAS' : 'AGENCY'
  return organizations.value
    .filter((o) => o.orgType === wanted)
    .map((o) => ({ label: o.name, value: o.orgId }))
})

function onHostingTypeChange(type) {
  activeTab.value = type
  form.providerOrgId = undefined
  form.feeMode = type === 'PATENT' ? 'COMMISSION' : type === 'AGENCY' ? 'ANNUAL' : 'SUBSCRIPTION'
}

function categoryLabel(category) {
  const map = {
    PATENT_HOSTING: isEn.value ? 'Patent' : '专利托管',
    AGENCY_HOSTING: isEn.value ? 'Agency' : '机构托管',
    SAAS: isEn.value ? 'SaaS' : 'SaaS 托管'
  }
  return map[category] || category
}
function priceModeLabel(mode) {
  const map = {
    COMMISSION: isEn.value ? 'Success fee' : '成交分成',
    SUBSCRIPTION: isEn.value ? 'Subscription' : '订阅',
    FIXED: isEn.value ? 'Fixed' : '固定费用',
    QUOTE: isEn.value ? 'Quote' : '按需报价',
    ANNUAL: isEn.value ? 'Annual' : '年费'
  }
  return map[mode] || mode
}
function hostingTypeLabel(type) {
  const map = { PATENT: isEn.value ? 'Patent' : '专利托管', AGENCY: isEn.value ? 'Agency' : '机构托管', SAAS: isEn.value ? 'SaaS' : 'SaaS 托管' }
  return map[type] || type
}
function feeModeLabel(mode) {
  const map = { COMMISSION: isEn.value ? 'Success fee' : '成交分成', ANNUAL: isEn.value ? 'Annual' : '年费', SUBSCRIPTION: isEn.value ? 'Subscription' : '订阅' }
  return map[mode] || mode
}
function statusLabel(status) {
  // 取值域以 TechHostingService.HOSTING_STATUSES 为准：写库端会置 SUSPENDED/COMPLETED/CANCELLED，
  // 之前这里的 PAUSED/TERMINATED 是注释残留，真实状态在页面上会裸显英文
  const map = {
    APPLIED: isEn.value ? 'Applied' : '已申请',
    ACTIVE: isEn.value ? 'Active' : '托管中',
    SUSPENDED: isEn.value ? 'Suspended' : '已暂停',
    COMPLETED: isEn.value ? 'Completed' : '已完成',
    CANCELLED: isEn.value ? 'Cancelled' : '已取消'
  }
  return map[status] || status
}
function statusTone(status) {
  if (status === 'ACTIVE') return 'green'
  if (status === 'APPLIED') return 'amber'
  if (status === 'CANCELLED') return 'red'
  if (status === 'COMPLETED') return 'blue'
  return ''
}

function selectLane(type) {
  activeTab.value = type
  form.hostingType = type
  form.feeMode = type === 'PATENT' ? 'COMMISSION' : type === 'AGENCY' ? 'ANNUAL' : 'SUBSCRIPTION'
  scrollToForm()
}
function applyService(service) {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/hosting' } })
    return
  }
  const type = service.category === 'PATENT_HOSTING' ? 'PATENT' : service.category === 'SAAS' ? 'SAAS' : 'AGENCY'
  activeTab.value = type
  form.hostingType = type
  form.targetName = service.title
  form.scope = service.description || service.subtitle || ''
  form.feeMode = service.priceMode === 'SUBSCRIPTION' ? 'SUBSCRIPTION' : type === 'PATENT' ? 'COMMISSION' : 'ANNUAL'
  form.feeAmount = Number(service.price || 0)
  scrollToForm()
}
function scrollToForm() {
  document.getElementById('tech-hosting-apply')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function go(path) {
  router.push(path)
}

async function load() {
  try {
    const [serviceRows, orgRows] = await Promise.all([techApi.services(), techApi.organizations()])
    services.value = serviceRows || []
    organizations.value = orgRows || []
  } catch (e) {
    services.value = []
    organizations.value = []
  }
}
async function loadMine() {
  if (!user.logged) return
  mineLoading.value = true
  try {
    mine.value = await techApi.myHostings()
  } catch (e) {
    mine.value = []
  } finally {
    mineLoading.value = false
  }
}
async function submit() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/hosting' } })
    return
  }
  if (!form.targetName.trim() || !form.scope.trim()) {
    message.warning(isEn.value ? 'Please complete the target and scope' : '请填写托管标的与托管范围')
    return
  }
  submitting.value = true
  try {
    await techApi.createHosting({
      hostingType: form.hostingType,
      targetName: form.targetName,
      providerOrgId: form.providerOrgId,
      feeMode: form.feeMode,
      feeAmount: form.feeAmount,
      scope: form.scope,
      activationPlan: form.activationPlan
    })
    message.success(isEn.value ? 'Hosting application submitted' : '托管申请已提交，陪跑项目已创建')
    form.targetName = ''
    form.scope = ''
    form.activationPlan = ''
    await loadMine()
    router.push('/tech/workspace')
  } catch (e) {
    // request wrapper shows the error
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), ensureTechUser().then(() => loadMine())])
})
</script>
