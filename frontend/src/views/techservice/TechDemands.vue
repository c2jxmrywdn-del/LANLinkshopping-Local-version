<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">DEMAND HALL · ENTERPRISE NEEDS</span>
        <h1>{{ isEn ? 'Describe the problem, not the product.' : '把问题说清楚，比先选技术更重要。' }}</h1>
        <p>
          {{ isEn
            ? 'Post a technical need in business language. A broker turns it into a technical brief, screens achievements and experts, and tracks the engagement as a project.'
            : '用业务语言发布技术需求；技术经纪人将其翻译成技术任务书，筛选成果与专家，并把对接过程沉淀为可跟踪的项目。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="openPublish">
            <Plus :size="16" :stroke-width="2.2" /> {{ isEn ? 'Post a demand' : '发布技术需求' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/experts')">
            {{ isEn ? 'Talk to a broker' : '找技术经纪人' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="OPEN DEMANDS"
        :title="isEn ? 'Demands currently open for matching' : '正在开放对接的企业需求'"
        :desc="isEn
          ? 'Public view hides contact details. Use the platform to express interest; a broker will verify identity and coordinate the introduction.'
          : '公开视图不展示联系方式；通过平台表达对接意向后，由技术经纪人核验身份并组织引荐。'"
      />
      <div class="tech-toolbar">
        <SegmentedControl v-model="status" :options="statusOptions" :aria-label="isEn ? 'Status filter' : '状态筛选'" />
        <a-input
          v-model:value="keyword"
          class="grow"
          allow-clear
          :placeholder="isEn ? 'Search title, field or description' : '搜索标题、技术领域或描述'"
          @pressEnter="reload(1)"
        />
        <a-select
          v-model:value="industry"
          style="width: 160px"
          allow-clear
          :placeholder="isEn ? 'Industry' : '行业'"
          :options="industryOptions"
        />
        <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="reload(1)">
          {{ isEn ? 'Search' : '搜索' }}
        </button>
      </div>

      <a-spin :spinning="loading">
        <div v-if="demands.length" class="tech-grid">
          <TechDemandCard
            v-for="demand in demands"
            :key="demand.demandId"
            :demand="demand"
            @view="openDetail(demand)"
            @contact="contact(demand)"
          />
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <Search :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No matching demands' : '暂无匹配需求' }}</b>
          <span>{{ isEn ? 'Try a broader filter, or post the first demand.' : '试试更宽泛的筛选，或发布第一条需求。' }}</span>
        </div>
      </a-spin>

      <div v-if="total > size" class="tech-pagination">
        <a-pagination v-model:current="page" :total="total" :page-size="size" :show-size-changer="false" @change="reload" />
      </div>
    </section>

    <a-drawer v-model:open="detailOpen" :width="'min(560px, 94vw)'" :title="detail?.title || (isEn ? 'Demand detail' : '需求详情')">
      <div v-if="detail" class="tech-drawer-body">
        <div class="tech-row">
          <span class="tech-chip cyan">{{ detail.industry }}</span>
          <span class="tech-chip">{{ detail.techField }}</span>
          <span class="tech-chip" :class="detail.status === 'MATCHING' ? 'amber' : 'green'">
            {{ detail.status === 'MATCHING' ? (isEn ? 'Matching' : '匹配中') : (isEn ? 'In progress' : '对接中') }}
          </span>
        </div>
        <h3 class="tech-mt">{{ detail.title }}</h3>
        <p class="sub">{{ detail.description }}</p>
        <dl class="tech-kv">
          <dt>{{ isEn ? 'Pain point' : '核心痛点' }}</dt>
          <dd>{{ detail.painPoints || '—' }}</dd>
          <dt>{{ isEn ? 'Expected outcome' : '期望结果' }}</dt>
          <dd>{{ detail.expectedOutcome || '—' }}</dd>
          <dt>{{ isEn ? 'Budget' : '预算范围' }}</dt>
          <dd>{{ budgetText(detail) }}</dd>
          <dt>{{ isEn ? 'Deadline' : '期望完成' }}</dt>
          <dd>{{ detail.deadline || '—' }}</dd>
          <dt>{{ isEn ? 'Published' : '发布方' }}</dt>
          <dd>{{ detail.anonymous ? (isEn ? 'Anonymous enterprise' : '匿名企业') : (isEn ? 'Enterprise (verified on platform)' : '企业（平台核验）') }}</dd>
          <dt>{{ isEn ? 'Views' : '浏览量' }}</dt>
          <dd>{{ detail.viewCount }}</dd>
        </dl>
        <div class="tech-note tech-mt">
          <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
          <div>
            <strong>{{ isEn ? 'Contact through the platform' : '请通过平台对接' }}</strong>
            <span v-if="isEn">
              Contact details are intentionally hidden. A broker will verify both sides and coordinate the introduction;
              never transfer funds or sign contracts outside the platform.
            </span>
            <span v-else>
              为保护双方隐私，平台不公开联系方式。技术经纪人将核验双方身份并组织引荐；请勿在平台外私下转账或签约。
            </span>
          </div>
        </div>
        <div class="tech-row tech-mt">
          <button type="button" class="tech-btn tech-btn-primary" @click="contact(detail)">
            {{ isEn ? 'Express interest' : '我要对接' }}
          </button>
          <button type="button" class="tech-btn tech-btn-outline" @click="go('/tech/experts')">
            {{ isEn ? 'Find a broker' : '找技术经纪人' }}
          </button>
        </div>
      </div>
    </a-drawer>

    <a-modal
      v-model:open="publishOpen"
      :title="isEn ? 'Post a technical demand' : '发布技术需求'"
      :ok-text="isEn ? 'Publish' : '发布需求'"
      :cancel-text="isEn ? 'Cancel' : '取消'"
      :confirm-loading="submitting"
      width="min(680px, 94vw)"
      @ok="submitDemand"
    >
      <a-form layout="vertical">
        <div class="tech-form-grid">
          <a-form-item class="full" :label="isEn ? 'Title' : '需求标题'" required>
            <a-input v-model:value="form.title" :placeholder="isEn ? 'e.g. High-precision robot vision calibration' : '例如：高精度机器人视觉标定技术'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Industry' : '行业'" required>
            <a-select v-model:value="form.industry" :options="industryOptions" :placeholder="isEn ? 'Select' : '请选择'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Technical field' : '技术领域'" required>
            <a-input v-model:value="form.techField" :placeholder="isEn ? 'e.g. Machine vision' : '例如：机器视觉'" />
          </a-form-item>
          <a-form-item class="full" :label="isEn ? 'Description' : '需求描述'" required>
            <a-textarea v-model:value="form.description" :rows="4" :placeholder="isEn ? 'Current process, constraints and what you have tried.' : '现有工艺、约束条件、已经尝试过的方案。'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Pain point' : '核心痛点'">
            <a-input v-model:value="form.painPoints" :placeholder="isEn ? 'What is blocked?' : '目前卡在哪里？'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Expected outcome' : '期望结果'">
            <a-input v-model:value="form.expectedOutcome" :placeholder="isEn ? 'Measurable acceptance criteria' : '可验收的指标或结果'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Budget min (¥)' : '预算下限（元）'">
            <a-input-number v-model:value="form.budgetMin" :min="0" :step="10000" style="width: 100%" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Budget max (¥)' : '预算上限（元）'">
            <a-input-number v-model:value="form.budgetMax" :min="0" :step="10000" style="width: 100%" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Expected completion' : '期望完成时间'">
            <a-date-picker v-model:value="form.deadline" value-format="YYYY-MM-DD" style="width: 100%" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Show anonymously' : '匿名展示'">
            <a-switch v-model:checked="form.anonymous" />
          </a-form-item>
        </div>
      </a-form>
      <div class="tech-note">
        <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>{{ isEn ? 'What happens next' : '发布后会发生什么' }}</strong>
          <span v-if="isEn">
            A demand record and a tracked project are created. A broker reviews the need, checks confidentiality and
            contacts you within one business day. Do not include trade secrets in the public description.
          </span>
          <span v-else>
            发布后会生成需求记录与陪跑项目；技术经纪人将在 1 个工作日内完成需求诊断并联系您。公开描述请勿包含商业秘密。
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
import { Plus, Search, ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import TechDemandCard from '../../components/techservice/TechDemandCard.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const INDUSTRIES = ['智能制造', '新材料', '电子信息', '新能源', '绿色低碳', '生物医药']
const industryOptions = computed(() => INDUSTRIES.map((v) => ({ label: v, value: v })))
const statusOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'Matching' : '匹配中', value: 'MATCHING' },
  { label: isEn.value ? 'In progress' : '对接中', value: 'IN_PROGRESS' }
])

const loading = ref(true)
const demands = ref([])
const total = ref(0)
const page = ref(1)
const size = 9
const status = ref('ALL')
const industry = ref(undefined)
const keyword = ref('')

const detailOpen = ref(false)
const detail = ref(null)

const publishOpen = ref(false)
const submitting = ref(false)
const form = reactive({
  title: '',
  industry: undefined,
  techField: '',
  description: '',
  painPoints: '',
  expectedOutcome: '',
  budgetMin: undefined,
  budgetMax: undefined,
  deadline: undefined,
  anonymous: false
})

function money(value) {
  const n = Number(value)
  if (!n) return ''
  return n >= 10000 ? `¥${(n / 10000).toFixed(1).replace('.0', '')}万` : `¥${n}`
}
function budgetText(demand) {
  const max = money(demand.budgetMax)
  const min = money(demand.budgetMin)
  if (!max && !min) return isEn.value ? 'Negotiable' : '面议'
  if (min && max && min !== max) return `${min}–${max}`
  return max || min
}

async function reload(p = 1) {
  page.value = p
  loading.value = true
  try {
    const res = await techApi.demands({
      page: p,
      size,
      industry: industry.value || undefined,
      keyword: keyword.value || undefined,
      status: status.value === 'ALL' ? undefined : status.value
    })
    demands.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    demands.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(demand) {
  try {
    detail.value = await techApi.demand(demand.demandId)
    detailOpen.value = true
    if (String(route.query.assess || '') === '1') {
      router.replace({ path: route.path, query: {} })
    }
  } catch (e) {
    detail.value = null
  }
}

function contact(demand) {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  router.push({ path: '/tech/experts', query: { demand: demand.demandId } })
}

function openPublish() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  publishOpen.value = true
}

async function submitDemand() {
  if (!form.title.trim() || !form.industry || !form.techField.trim() || !form.description.trim()) {
    message.warning(isEn.value ? 'Please complete the required fields' : '请填写标题、行业、技术领域与需求描述')
    return
  }
  submitting.value = true
  try {
    await techApi.createDemand({
      title: form.title,
      industry: form.industry,
      techField: form.techField,
      description: form.description,
      painPoints: form.painPoints,
      expectedOutcome: form.expectedOutcome,
      budgetMin: form.budgetMin,
      budgetMax: form.budgetMax,
      deadline: form.deadline || undefined,
      anonymous: form.anonymous
    })
    message.success(isEn.value ? 'Demand published; project created' : '需求已发布，陪跑项目已创建')
    publishOpen.value = false
    Object.assign(form, {
      title: '', industry: undefined, techField: '', description: '', painPoints: '',
      expectedOutcome: '', budgetMin: undefined, budgetMax: undefined, deadline: undefined, anonymous: false
    })
    await reload(1)
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

watch([status, industry], () => reload(1))

onMounted(async () => {
  await reload(1)
  if (route.query.id) {
    await openDetail({ demandId: route.query.id })
  }
  if (String(route.query.publish || '') === '1') {
    openPublish()
    router.replace({ path: route.path, query: {} })
  }
})
</script>

<style scoped>
.tech-pagination { display: flex; justify-content: center; margin-top: 22px; }
</style>
