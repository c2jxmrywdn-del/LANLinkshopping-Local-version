<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">WORKSPACE · 全流程陪跑</span>
        <h1>{{ isEn ? 'One project, one team, one next action.' : '一个项目，一支团队，一个明确的下一步。' }}</h1>
        <p>
          {{ isEn
            ? 'Every application becomes a tracked project with milestones, an assigned broker and a next action. Progress is visible to both sides.'
            : '每一次申请都会生成可跟踪的陪跑项目：里程碑、责任经纪人、下一步动作，双方进度透明。' }}
        </p>
      </div>
      <div v-if="user.logged" class="tech-hero-stats">
        <div v-for="s in heroStats" :key="s.label" class="tech-hero-stat">
          <b class="tech-num">{{ s.value }}</b>
          <span>{{ s.label }}</span>
        </div>
      </div>
    </section>

    <section v-if="!user.logged" class="tech-section">
      <div class="tech-empty">
        <ArrowLeftRight :size="42" :stroke-width="1.4" aria-hidden="true" />
        <b>{{ isEn ? 'Sign in to open your workspace' : '登录后进入交易工作台' }}</b>
        <span>{{ isEn ? 'Track projects, hosting records and deal progress in one place.' : '集中查看陪跑项目、托管记录与交易进展。' }}</span>
        <button type="button" class="tech-btn tech-btn-primary tech-mt" @click="go('/login')">{{ isEn ? 'Sign in' : '去登录' }}</button>
      </div>
    </section>

    <template v-else>
      <section class="tech-section">
        <TechSectionHead
          eyebrow="RUNNING PROJECTS"
          :title="isEn ? 'Delivery timeline' : '交付时间轴'"
          :desc="isEn
            ? 'Advance a milestone only when its deliverable is actually accepted. The timeline is the contract between both sides.'
            : '只有交付物实际验收后才推进里程碑——时间轴是双方共同的契约。'"
        >
          <template #action>
            <SegmentedControl v-model="projectFilter" :options="filterOptions" :aria-label="isEn ? 'Project status' : '项目状态'" />
          </template>
        </TechSectionHead>

        <a-spin :spinning="loading">
          <div v-if="filteredProjects.length" class="project-list">
            <article v-for="p in filteredProjects" :key="p.projectId" class="tech-card project-card">
              <div class="tech-card-top">
                <div>
                  <span class="tech-chip cyan">{{ projectTypeLabel(p.projectType) }}</span>
                  <span class="tech-chip" :class="projectTone(p.status)">{{ projectStatusLabel(p.status) }}</span>
                </div>
                <span class="tech-muted tech-num">{{ p.projectNo }}</span>
              </div>
              <h3>{{ p.title }}</h3>
              <div class="tech-card-meta">
                <span>{{ stageLabel(p.stage) }}</span>
                <span v-if="p.amount">¥{{ formatAmount(p.amount) }}</span>
                <span v-if="p.commissionRate">{{ p.commissionRate }}% {{ isEn ? 'success fee' : '成交分成' }}</span>
              </div>
              <div class="progress-row">
                <a-progress :percent="Number(p.progress || 0)" :show-info="false" size="small" />
                <span class="tech-num">{{ p.progress || 0 }}%</span>
              </div>
              <div class="next-action">
                <Sparkles :size="15" :stroke-width="2" aria-hidden="true" />
                <span>{{ p.nextAction || (isEn ? 'Waiting for the next step' : '等待下一步安排') }}</span>
              </div>
              <div class="tech-card-foot">
                <button type="button" class="tech-link" @click="toggleDetail(p)">
                  {{ expandedId === p.projectId ? (isEn ? 'Hide timeline' : '收起时间轴') : (isEn ? 'View timeline' : '查看时间轴') }}
                </button>
                <button
                  v-if="p.status === 'ACTIVE'"
                  type="button"
                  class="tech-btn tech-btn-primary tech-btn-sm"
                  :disabled="advancingId === p.projectId"
                  @click="advance(p)"
                >
                  {{ isEn ? 'Complete current milestone' : '完成当前里程碑' }}
                </button>
              </div>
              <div v-if="expandedId === p.projectId" class="timeline-box">
                <a-spin :spinning="detailLoading">
                  <TechProjectTimeline :milestones="milestones" />
                  <div v-if="team.length" class="team-row">
                    <span class="tech-muted">{{ isEn ? 'Delivery team' : '陪跑团队' }}</span>
                    <span v-for="t in team" :key="t.name" class="tech-chip">{{ t.name }} · {{ t.role }}</span>
                  </div>
                </a-spin>
              </div>
            </article>
          </div>
          <div v-else-if="!loading" class="tech-empty">
            <ArrowLeftRight :size="42" :stroke-width="1.4" aria-hidden="true" />
            <b>{{ isEn ? 'No projects yet' : '还没有陪跑项目' }}</b>
            <span>{{ isEn ? 'Publish a demand or apply for a service to create one.' : '发布技术需求或申请任一服务，系统会自动创建陪跑项目。' }}</span>
            <div class="tech-row tech-mt" style="justify-content:center">
              <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="go('/tech/demands', { publish: 1 })">{{ isEn ? 'Post a demand' : '发布需求' }}</button>
              <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="go('/tech/hosting')">{{ isEn ? 'Apply hosting' : '申请托管' }}</button>
            </div>
          </div>
        </a-spin>
      </section>

      <section class="tech-section">
        <TechSectionHead
          eyebrow="MY RECORDS"
          :title="isEn ? 'Applications & deals' : '我的申请与交易'"
          :desc="isEn ? 'Demands, consultations, appraisals, hosting and deal intents in one ledger.' : '需求、咨询、评价、托管与交易意向的统一台账。'"
        />
        <a-tabs v-model:activeKey="activeTab" size="small">
          <a-tab-pane key="demands" :tab="isEn ? 'Demands' : '需求'">
            <div v-if="demands.length" class="tech-grid">
              <article v-for="d in demands" :key="d.demandId" class="tech-card">
                <div class="tech-card-top">
                  <span class="tech-chip cyan">{{ d.industry }}</span>
                  <span class="tech-chip" :class="statusTone(d.status)">{{ demandStatusLabel(d.status) }}</span>
                </div>
                <h3>{{ d.title }}</h3>
                <p class="clamp-2">{{ d.description }}</p>
                <div class="tech-card-meta">
                  <span>{{ d.demandNo }}</span>
                  <span v-if="d.budgetMax">¥{{ formatAmount(d.budgetMin) }} – ¥{{ formatAmount(d.budgetMax) }}</span>
                </div>
              </article>
            </div>
            <div v-else class="tech-empty"><b>{{ isEn ? 'No demands' : '暂无需求记录' }}</b></div>
          </a-tab-pane>

          <a-tab-pane key="consultations" :tab="isEn ? 'Consultations' : '专家咨询'">
            <div v-if="consultations.length" class="tech-grid">
              <article v-for="c in consultations" :key="c.consultId" class="tech-card">
                <div class="tech-card-top">
                  <span class="tech-chip indigo">{{ consultModeLabel(c.mode) }}</span>
                  <span class="tech-chip" :class="statusTone(c.status)">{{ consultStatusLabel(c.status) }}</span>
                </div>
                <h3>{{ c.topic }}</h3>
                <p class="clamp-2">{{ c.description }}</p>
                <div class="tech-card-meta">
                  <span>{{ shortTime(c.scheduledAt) }}</span>
                  <span v-if="c.amount">¥{{ formatAmount(c.amount) }}</span>
                </div>
              </article>
            </div>
            <div v-else class="tech-empty"><b>{{ isEn ? 'No consultations' : '暂无咨询记录' }}</b></div>
          </a-tab-pane>

          <a-tab-pane key="appraisals" :tab="isEn ? 'Appraisals' : '成果评价'">
            <div v-if="appraisals.length" class="tech-grid">
              <article v-for="a in appraisals" :key="a.appraisalId" class="tech-card">
                <div class="tech-card-top">
                  <span class="tech-chip amber">{{ a.standardCode }}</span>
                  <span class="tech-chip" :class="statusTone(a.status)">{{ appraisalStatusLabel(a.status) }}</span>
                </div>
                <h3>{{ a.appraisalNo }}</h3>
                <p>{{ a.disclaimer }}</p>
                <div class="tech-card-meta">
                  <span v-if="a.conclusionLevel">{{ a.conclusionLevel }}</span>
                  <span v-if="a.reportNo">{{ a.reportNo }}</span>
                </div>
              </article>
            </div>
            <div v-else class="tech-empty"><b>{{ isEn ? 'No appraisals' : '暂无评价申请' }}</b></div>
          </a-tab-pane>

          <a-tab-pane key="hostings" :tab="isEn ? 'Hosting' : '托管'">
            <div v-if="hostings.length" class="tech-grid">
              <article v-for="h in hostings" :key="h.hostingId" class="tech-card">
                <div class="tech-card-top">
                  <span class="tech-chip cyan">{{ hostingTypeLabel(h.hostingType) }}</span>
                  <span class="tech-chip" :class="statusTone(h.status)">{{ hostingStatusLabel(h.status) }}</span>
                </div>
                <h3>{{ h.targetName }}</h3>
                <p class="clamp-2">{{ h.scope }}</p>
                <div class="tech-card-meta">
                  <span>{{ h.startDate }} → {{ h.endDate }}</span>
                  <span v-if="h.feeAmount">¥{{ formatAmount(h.feeAmount) }}</span>
                </div>
              </article>
            </div>
            <div v-else class="tech-empty"><b>{{ isEn ? 'No hosting records' : '暂无托管记录' }}</b></div>
          </a-tab-pane>

          <a-tab-pane key="deals" :tab="isEn ? 'Deals' : '交易'">
            <div v-if="deals.length" class="tech-grid">
              <article v-for="d in deals" :key="d.dealId" class="tech-card">
                <div class="tech-card-top">
                  <span class="tech-chip indigo">{{ dealTypeLabel(d.dealType) }}</span>
                  <span class="tech-chip" :class="statusTone(d.status)">{{ dealStatusLabel(d.status) }}</span>
                </div>
                <h3>¥{{ formatAmount(d.amount) }}</h3>
                <p>{{ d.dealNo }}</p>
                <div class="tech-card-meta">
                  <span>{{ paymentModeLabel(d.paymentMode) }}</span>
                  <span v-if="d.commissionAmount">服务费 ¥{{ formatAmount(d.commissionAmount) }}</span>
                </div>
                <div class="tech-note tech-mt">
                  <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
                  <div>
                    <strong>{{ isEn ? 'Escrow & evidence' : '资金监管与存证' }}</strong>
                    <span>{{ isEn
                      ? 'Settlement is handled by licensed partners; the platform only charges a service fee.'
                      : '资金监管与结算由持牌合作机构办理，平台只收取服务费，不归集交易本金。' }}</span>
                  </div>
                </div>
              </article>
            </div>
            <div v-else class="tech-empty"><b>{{ isEn ? 'No deals' : '暂无交易记录' }}</b></div>
          </a-tab-pane>
        </a-tabs>
      </section>
    </template>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftRight, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import TechProjectTimeline from '../../components/techservice/TechProjectTimeline.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const loading = ref(false)
const counts = ref({})
const projects = ref([])
const projectFilter = ref('ACTIVE')
const expandedId = ref(0)
const milestones = ref([])
const team = ref([])
const detailLoading = ref(false)
const advancingId = ref(0)
const activeTab = ref('demands')

const demands = ref([])
const consultations = ref([])
const appraisals = ref([])
const hostings = ref([])
const deals = ref([])

const heroStats = computed(() => [
  { label: isEn.value ? 'Projects' : '陪跑项目', value: counts.value.projects || 0 },
  { label: isEn.value ? 'In progress' : '进行中', value: counts.value.active || 0 },
  { label: isEn.value ? 'Deals' : '交易', value: counts.value.deals || 0 },
  { label: isEn.value ? 'Hosting' : '托管', value: counts.value.hostings || 0 }
])
const filterOptions = computed(() => [
  { label: isEn.value ? 'Active' : '进行中', value: 'ACTIVE' },
  { label: isEn.value ? 'Completed' : '已完成', value: 'COMPLETED' },
  { label: isEn.value ? 'All' : '全部', value: 'ALL' }
])
const filteredProjects = computed(() => {
  if (projectFilter.value === 'ALL') return projects.value
  return projects.value.filter((p) => p.status === projectFilter.value)
})

function projectTypeLabel(type) {
  const map = {
    MATCH: isEn.value ? 'Matching' : '供需匹配',
    CONSULT: isEn.value ? 'Consultation' : '专家咨询',
    APPRAISAL: isEn.value ? 'Appraisal' : '成果评价',
    HOSTING: isEn.value ? 'Hosting' : '托管服务',
    TRANSACTION: isEn.value ? 'Transaction' : '技术交易'
  }
  return map[type] || type
}
function projectStatusLabel(status) {
  return status === 'COMPLETED' ? (isEn.value ? 'Completed' : '已完成') : (isEn.value ? 'Active' : '进行中')
}
function projectTone(status) {
  return status === 'COMPLETED' ? 'green' : 'amber'
}
function stageLabel(stage) {
  const map = {
    INTAKE: isEn.value ? 'Intake' : '受理',
    DIAGNOSIS: isEn.value ? 'Diagnosis' : '需求诊断',
    MATCHING: isEn.value ? 'Matching' : '资源匹配',
    DUE_DILIGENCE: isEn.value ? 'Due diligence' : '尽职调查',
    CONTRACT: isEn.value ? 'Contracting' : '合同签署',
    EXECUTION: isEn.value ? 'Execution' : '交付执行',
    ACCEPTANCE: isEn.value ? 'Acceptance' : '验收',
    CLOSED: isEn.value ? 'Closed' : '已归档'
  }
  return map[stage] || stage
}
function statusTone(status) {
  if (['COMPLETED', 'DONE', 'ACTIVE', 'CONFIRMED', 'REPORT_ISSUED', 'SIGNED'].includes(status)) return 'green'
  if (['APPLIED', 'PENDING', 'SUBMITTED', 'MATERIAL_REVIEW', 'EXPERT_REVIEW', 'INTENT', 'ESCROWED'].includes(status)) return 'amber'
  if (['CLOSED', 'CANCELLED', 'REJECTED', 'TERMINATED'].includes(status)) return ''
  return 'indigo'
}
function demandStatusLabel(s) {
  const map = { MATCHING: isEn.value ? 'Matching' : '匹配中', IN_PROGRESS: isEn.value ? 'In progress' : '对接中', CLOSED: isEn.value ? 'Closed' : '已关闭' }
  return map[s] || s
}
function consultModeLabel(m) {
  const map = { VIDEO: isEn.value ? 'Video' : '视频', PHONE: isEn.value ? 'Phone' : '电话', OFFLINE: isEn.value ? 'Offline' : '线下' }
  return map[m] || m
}
function consultStatusLabel(s) {
  const map = { PENDING: isEn.value ? 'Pending' : '待确认', CONFIRMED: isEn.value ? 'Confirmed' : '已确认', COMPLETED: isEn.value ? 'Completed' : '已完成', CANCELLED: isEn.value ? 'Cancelled' : '已取消' }
  return map[s] || s
}
function appraisalStatusLabel(s) {
  const map = {
    SUBMITTED: isEn.value ? 'Submitted' : '已提交',
    MATERIAL_REVIEW: isEn.value ? 'Material review' : '材料预审',
    EXPERT_REVIEW: isEn.value ? 'Expert review' : '专家评审',
    REPORT_ISSUED: isEn.value ? 'Report issued' : '报告已出具',
    REJECTED: isEn.value ? 'Rejected' : '已退回'
  }
  return map[s] || s
}
function hostingTypeLabel(t) {
  const map = { PATENT: isEn.value ? 'Patent' : '专利托管', AGENCY: isEn.value ? 'Agency' : '机构托管', SAAS: isEn.value ? 'SaaS' : 'SaaS 托管' }
  return map[t] || t
}
function hostingStatusLabel(s) {
  const map = {
    APPLIED: isEn.value ? 'Applied' : '已申请',
    ACTIVE: isEn.value ? 'Active' : '托管中',
    SUSPENDED: isEn.value ? 'Suspended' : '已暂停',
    COMPLETED: isEn.value ? 'Completed' : '已完成',
    CANCELLED: isEn.value ? 'Cancelled' : '已取消'
  }
  return map[s] || s
}
function dealTypeLabel(t) {
  const map = { ASSIGN: isEn.value ? 'Assignment' : '转让', LICENSE: isEn.value ? 'License' : '许可', OPEN_LICENSE: isEn.value ? 'Open license' : '开放许可', SERVICE: isEn.value ? 'Service' : '技术服务' }
  return map[t] || t
}
function dealStatusLabel(s) {
  const map = {
    INTENT: isEn.value ? 'Intent' : '意向',
    SIGNED: isEn.value ? 'Signed' : '已签约',
    ESCROWED: isEn.value ? 'Escrowed' : '资金监管中',
    DELIVERING: isEn.value ? 'Delivering' : '交割中',
    COMPLETED: isEn.value ? 'Completed' : '已完成',
    CANCELLED: isEn.value ? 'Cancelled' : '已取消'
  }
  return map[s] || s
}
function paymentModeLabel(m) {
  const map = { ESCROW: isEn.value ? 'Escrow' : '资金监管', INSTALLMENT: isEn.value ? 'Installment' : '分期支付', PAY_AFTER_USE: isEn.value ? 'Pay after use' : '先使用后付费' }
  return map[m] || m
}
function formatAmount(value) {
  const n = Number(value || 0)
  if (!n) return '0'
  // 英文态不输出「万」：DOM 翻译引擎只处理文本节点，模板里的 `¥100万–¥300万`
  // 会一半中一半英，所以数量单位必须在这里按语言分岔，而不是指望词典兜底
  if (!isEn.value) return n >= 10000 ? `${(n / 10000).toFixed(n >= 100000 ? 0 : 1)}万` : String(n)
  if (n >= 1e6) return `${(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M`
  if (n >= 1e3) return `${(n / 1e3).toFixed(n >= 1e4 ? 0 : 1)}K`
  return String(n)
}
function shortTime(value) {
  return String(value || '').replace('T', ' ').slice(0, 16)
}
function go(path, query) {
  router.push(query ? { path, query } : path)
}

async function toggleDetail(project) {
  if (expandedId.value === project.projectId) {
    expandedId.value = 0
    return
  }
  expandedId.value = project.projectId
  milestones.value = []
  team.value = []
  detailLoading.value = true
  try {
    const data = await techApi.project(project.projectId)
    milestones.value = data?.milestones || []
    const members = []
    if (data?.broker) members.push({ name: data.broker.name, role: isEn.value ? 'Broker' : '技术经纪人' })
    if (data?.expert) members.push({ name: data.expert.name, role: isEn.value ? 'Expert' : '责任专家' })
    team.value = members
  } catch (e) {
    milestones.value = []
  } finally {
    detailLoading.value = false
  }
}

async function advance(project) {
  advancingId.value = project.projectId
  try {
    const data = await techApi.advanceProject(project.projectId)
    Object.assign(project, data?.project || {})
    milestones.value = data?.milestones || milestones.value
    message.success(isEn.value ? 'Milestone completed' : '里程碑已完成，已进入下一节点')
    await loadSummary()
  } catch (e) {
    // request wrapper已提示
  } finally {
    advancingId.value = 0
  }
}

async function loadSummary() {
  const data = await techApi.workspace()
  counts.value = data?.counts || {}
  projects.value = await techApi.projects()
}

async function loadRecords() {
  const [d, c, a, h, l] = await Promise.allSettled([
    techApi.myDemands(),
    techApi.myConsultations(),
    techApi.myAppraisals(),
    techApi.myHostings(),
    techApi.myDeals()
  ])
  demands.value = d.status === 'fulfilled' ? d.value || [] : []
  consultations.value = c.status === 'fulfilled' ? c.value || [] : []
  appraisals.value = a.status === 'fulfilled' ? a.value || [] : []
  hostings.value = h.status === 'fulfilled' ? h.value || [] : []
  deals.value = l.status === 'fulfilled' ? l.value || [] : []
}

onMounted(async () => {
  if (!user.logged) return
  loading.value = true
  try {
    await Promise.all([loadSummary(), loadRecords()])
  } catch (e) {
    message.error(isEn.value ? 'Failed to load workspace' : '工作台数据加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.project-list { display: grid; grid-template-columns: 1fr; gap: 14px; }
.project-card h3 { margin-top: 10px; }
.progress-row { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.progress-row :deep(.ant-progress) { flex: 1; margin: 0; }
.next-action {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(14, 165, 233, 0.07);
  color: #0e7490;
  font-size: 12.5px;
}
.timeline-box { margin-top: 16px; padding-top: 16px; border-top: 1px dashed rgba(15, 23, 42, 0.1); }
.team-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 6px; }
.clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
