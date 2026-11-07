<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">CONTRACTS & SETTLEMENT · 合同与结算</span>
        <h1>把对接结果，落成可验收的合同。</h1>
        <p>
          六类技术合同模板、本地演示签署、服务费台账与项目验收。平台只登记服务费，不归集交易本金；
          正式签署与登记须由持牌电子签服务与技术合同登记机构办理。
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="scrollToForm">生成合同</button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/workspace')">返回工作台</button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="TEMPLATES"
        title="六类技术合同模板"
        desc="技术开发 / 转让 / 许可 / 咨询 / 服务 / 中介。模板只提供条款框架，正式签署前须经法务审核。"
      />
      <div class="tech-grid">
        <article v-for="t in templates" :key="t.code" class="tech-card">
          <div class="tech-card-top">
            <span class="tech-chip cyan">{{ t.contractType }}</span>
            <span class="tech-chip">{{ t.code }}</span>
          </div>
          <h3>{{ t.name }}</h3>
          <p>{{ t.description }}</p>
          <div class="tech-card-meta clause-tags">
            <span v-for="c in t.clauses.slice(0, 4)" :key="c" class="tech-chip">{{ c }}</span>
          </div>
          <div class="tech-card-foot">
            <span class="tech-muted">默认分成 {{ t.defaultCommissionRate }}%</span>
            <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="useTemplate(t)">使用模板</button>
          </div>
        </article>
      </div>
    </section>

    <section id="tech-contract-form" class="tech-section">
      <TechSectionHead eyebrow="CREATE" title="生成合同" desc="选择陪跑项目与合同类型，生成草稿后可在线签署。" />
      <div v-if="!user.logged" class="tech-empty">
        <b>登录后生成与签署合同</b>
        <span>合同、服务费与验收记录均与账号绑定。</span>
        <button type="button" class="tech-btn tech-btn-primary tech-mt" @click="go('/login')">去登录</button>
      </div>
      <div v-else class="tech-form-card">
        <a-form layout="vertical">
          <div class="tech-form-grid">
            <a-form-item label="关联陪跑项目" required>
              <a-select v-model:value="form.projectId" :options="projectOptions" placeholder="选择我的项目" @change="onProjectChange" />
            </a-form-item>
            <a-form-item label="合同类型" required>
              <a-select v-model:value="form.templateCode" :options="templateOptions" placeholder="选择模板" @change="onTemplateChange" />
            </a-form-item>
            <a-form-item label="合同相对方">
              <a-input v-model:value="form.partyB" placeholder="留空默认平台运营方，或填写合作方名称" />
            </a-form-item>
            <a-form-item label="合同金额（元）">
              <a-input-number v-model:value="form.amount" :min="0" :step="1000" style="width: 100%" />
            </a-form-item>
          </div>
          <div class="tech-note">
            <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
            <div>
              <strong>签署与登记边界</strong>
              <span>当前为本地演示签署（内容哈希 + 签署编号），不构成法律意义上的电子签名；
                技术合同认定登记由登记机构办理，平台只做材料辅导，不收取登记费用、不出具登记证明。</span>
            </div>
          </div>
          <div class="tech-form-actions">
            <button type="button" class="tech-btn tech-btn-primary" :disabled="creating" @click="submitContract">
              {{ creating ? '生成中…' : '生成合同草稿' }}
            </button>
          </div>
        </a-form>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead eyebrow="MY CONTRACTS" title="我的合同" desc="草稿可签署；已签署合同可登记服务费并提交验收。">
        <template #action>
          <button v-if="user.logged" type="button" class="tech-link" @click="reload">刷新 →</button>
        </template>
      </TechSectionHead>
      <a-spin :spinning="loading">
        <div v-if="contracts.length" class="tech-grid">
          <article v-for="c in contracts" :key="c.contractNo" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip cyan">{{ c.contractType }}</span>
              <span class="tech-chip" :class="statusTone(c.status)">{{ statusLabel(c.status) }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="tech-card-meta">
              <span>{{ c.contractNo }}</span>
              <span>甲方：{{ c.partyA }}</span>
              <span>乙方：{{ c.partyB }}</span>
            </div>
            <div class="tech-card-meta">
              <span v-if="c.amount">合同金额 ¥{{ formatAmount(c.amount) }}</span>
              <span v-if="c.commissionRate">服务分成 {{ c.commissionRate }}%</span>
              <span v-if="c.signedAt">签署于 {{ shortTime(c.signedAt) }}</span>
            </div>
            <div class="tech-card-foot">
              <span class="tech-muted">{{ c.fileHash ? ('存证 ' + c.fileHash.slice(0, 12) + '…') : '未签署' }}</span>
              <a-space>
                <button v-if="c.status === 'DRAFT'" type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="sign(c)">签署合同</button>
                <button v-if="c.status === 'SIGNED'" type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="openFee(c)">登记服务费</button>
                <button v-if="c.status === 'SIGNED' && c.projectId" type="button" class="tech-btn tech-btn-light tech-btn-sm" @click="openAccept(c)">提交验收</button>
              </a-space>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <b>{{ user.logged ? '还没有合同' : '登录后查看合同' }}</b>
          <span>先选择陪跑项目并生成合同草稿。</span>
        </div>
      </a-spin>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="SERVICE REVIEW"
        title="履约评价"
        desc="项目验收完成后，对责任专家或技术经纪人做五维评分；评分只用于平台派单权重与分级，不对外排名。"
      >
        <template #action>
          <button v-if="user.logged" type="button" class="tech-link" @click="loadProjects">刷新 →</button>
        </template>
      </TechSectionHead>
      <a-spin :spinning="reviewLoading">
        <div v-if="completedProjects.length" class="tech-grid">
          <article v-for="p in completedProjects" :key="p.projectId" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip green">已完成</span>
              <span class="tech-chip">{{ p.projectNo }}</span>
            </div>
            <h3>{{ p.title }}</h3>
            <div class="tech-card-meta">
              <span>进度 {{ p.progress || 0 }}%</span>
              <span v-if="p.amount">金额 ¥{{ formatAmount(p.amount) }}</span>
            </div>
            <div class="tech-card-foot">
              <span class="tech-muted">{{ p.nextAction || '项目已归档' }}</span>
              <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="openReview(p)">评价服务</button>
            </div>
          </article>
        </div>
        <div v-else-if="!reviewLoading" class="tech-empty">
          <b>{{ user.logged ? '暂无可评价项目' : '登录后查看待评价项目' }}</b>
          <span>项目验收通过并归档后，可在这里提交履约评价。</span>
        </div>
      </a-spin>
    </section>

    <section class="tech-section">
      <TechSectionHead eyebrow="SERVICE FEES" title="服务费台账" desc="只登记平台服务费；交易本金走银行存管/持牌分账/交易所结算。" />
      <a-spin :spinning="feeLoading">
        <div v-if="fees.length" class="tech-grid">
          <article v-for="f in fees" :key="f.feeNo" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip indigo">{{ feeTypeLabel(f.feeType) }}</span>
              <span class="tech-chip" :class="feeStatusTone(f.status)">{{ feeStatusLabel(f.status) }}</span>
            </div>
            <h3>¥{{ formatAmount(f.amount) }}</h3>
            <div class="tech-card-meta">
              <span>{{ f.feeNo }}</span>
              <span>合同 {{ f.contractNo }}</span>
            </div>
            <div class="tech-card-meta">
              <span v-if="f.paidAt">到账 {{ shortTime(f.paidAt) }}</span>
              <span v-if="f.payChannel">渠道 {{ f.payChannel }}</span>
              <span v-if="f.invoiceNo">发票 {{ f.invoiceNo }}</span>
            </div>
          </article>
        </div>
        <div v-else-if="!feeLoading" class="tech-empty">
          <b>暂无服务费记录</b>
          <span>合同签署后可登记服务费，由运营确认到账。</span>
        </div>
      </a-spin>
    </section>

    <a-modal v-model:open="feeOpen" title="登记服务费" :confirm-loading="saving" ok-text="提交" cancel-text="取消" @ok="submitFee">
      <a-form layout="vertical">
        <a-form-item label="费用类型" required>
          <a-select v-model:value="feeForm.feeType" :options="feeTypeOptions" />
        </a-form-item>
        <a-form-item label="金额（元）" required>
          <a-input-number v-model:value="feeForm.amount" :min="1" :step="100" style="width: 100%" />
        </a-form-item>
        <a-alert type="info" show-icon message="平台只收取服务费，不代收交易本金；到账由运营后台确认。" />
      </a-form>
    </a-modal>

    <a-modal v-model:open="acceptOpen" title="提交项目验收" :confirm-loading="saving" ok-text="提交验收" cancel-text="取消" @ok="submitAccept">
      <a-form layout="vertical">
        <a-form-item label="验收结论" required>
          <a-select v-model:value="acceptForm.result" :options="[{ label: '通过', value: 'PASS' }, { label: '不通过', value: 'REJECT' }]" />
        </a-form-item>
        <a-form-item label="满意度（1-5）">
          <a-rate v-model:value="acceptForm.score" :count="5" />
        </a-form-item>
        <a-form-item label="验收说明">
          <a-textarea v-model:value="acceptForm.note" :rows="3" :maxlength="500" show-count />
        </a-form-item>
        <a-alert type="warning" show-icon message="验收通过后项目将归档为已完成；验收前须存在已签署合同。" />
      </a-form>
    </a-modal>

    <a-modal v-model:open="reviewOpen" title="提交履约评价" :confirm-loading="saving" ok-text="提交评价" cancel-text="取消" @ok="submitReview">
      <a-form layout="vertical">
        <a-form-item label="评价对象" required>
          <a-select v-model:value="reviewForm.targetKey" :options="reviewTargetOptions" placeholder="选择专家或技术经纪人" />
        </a-form-item>
        <div class="review-scores">
          <a-form-item label="响应速度"><a-rate v-model:value="reviewForm.scoreResponse" :count="5" /></a-form-item>
          <a-form-item label="专业能力"><a-rate v-model:value="reviewForm.scoreProfessional" :count="5" /></a-form-item>
          <a-form-item label="交付质量"><a-rate v-model:value="reviewForm.scoreDelivery" :count="5" /></a-form-item>
          <a-form-item label="沟通协作"><a-rate v-model:value="reviewForm.scoreCommunication" :count="5" /></a-form-item>
          <a-form-item label="结果达成"><a-rate v-model:value="reviewForm.scoreResult" :count="5" /></a-form-item>
        </div>
        <a-form-item label="评价内容">
          <a-textarea v-model:value="reviewForm.comment" :rows="3" :maxlength="500" show-count placeholder="可填写亮点、改进建议与复购意愿" />
        </a-form-item>
        <a-alert type="info" show-icon message="同一项目、同一对象只能评价一次；评分用于派单权重，不对外发布排名。" />
      </a-form>
    </a-modal>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'

const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const templates = ref([])
const projects = ref([])
const contracts = ref([])
const fees = ref([])
const loading = ref(false)
const feeLoading = ref(false)
const creating = ref(false)
const saving = ref(false)

const form = reactive({ projectId: undefined, templateCode: undefined, partyB: '', amount: undefined })
const feeOpen = ref(false)
const feeTarget = ref(null)
const feeForm = reactive({ feeType: 'SERVICE', amount: 1000 })
const acceptOpen = ref(false)
const acceptTarget = ref(null)
const acceptForm = reactive({ result: 'PASS', score: 5, note: '' })

const reviewOpen = ref(false)
const reviewLoading = ref(false)
const reviewProject = ref(null)
const reviewTargets = ref([])
const reviewForm = reactive({
  targetKey: undefined,
  scoreResponse: 5,
  scoreProfessional: 5,
  scoreDelivery: 5,
  scoreCommunication: 5,
  scoreResult: 5,
  comment: ''
})

const completedProjects = computed(() => projects.value.filter((p) => p.status === 'COMPLETED'))
const reviewTargetOptions = computed(() => reviewTargets.value.map((t) => ({ label: t.label, value: t.key })))

const templateOptions = computed(() => templates.value.map((t) => ({ label: `${t.name}（${t.contractType}）`, value: t.code })))
const projectOptions = computed(() => projects.value.map((p) => ({ label: `${p.projectNo} · ${p.title}`, value: p.projectId })))
const feeTypeOptions = [
  { label: '经纪费', value: 'BROKERAGE' },
  { label: '服务费', value: 'SERVICE' },
  { label: '评价组织费', value: 'EVALUATION' },
  { label: '订阅费', value: 'SUBSCRIPTION' }
]

function statusLabel(s) {
  return { DRAFT: '草稿', PENDING_SIGN: '待签署', SIGNED: '已签署', ARCHIVED: '已归档' }[s] || s
}
function statusTone(s) {
  return { DRAFT: 'amber', PENDING_SIGN: 'amber', SIGNED: 'green', ARCHIVED: '' }[s] || ''
}
function feeStatusLabel(s) {
  return { PENDING: '待支付', PAID: '已到账', REFUNDED: '已退款' }[s] || s
}
function feeStatusTone(s) {
  return { PENDING: 'amber', PAID: 'green', REFUNDED: '' }[s] || ''
}
function feeTypeLabel(t) {
  return { BROKERAGE: '经纪费', SERVICE: '服务费', EVALUATION: '评价组织费', SUBSCRIPTION: '订阅费' }[t] || t
}
function formatAmount(v) {
  const n = Number(v || 0)
  // 英文态改用 K/M：「100 万」这类数量单位在模板文本里，DOM 翻译引擎碰不到
  if (!isEn.value) return n >= 10000 ? `${(n / 10000).toFixed(1)} 万` : String(n)
  if (n >= 1e6) return `${(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M`
  if (n >= 1e3) return `${(n / 1e3).toFixed(n >= 1e4 ? 0 : 1)}K`
  return String(n)
}
function shortTime(v) {
  return String(v || '').replace('T', ' ').slice(0, 16)
}
function go(path) {
  router.push(path)
}
function scrollToForm() {
  document.getElementById('tech-contract-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function useTemplate(t) {
  form.templateCode = t.code
  scrollToForm()
}
function onProjectChange(projectId) {
  const p = projects.value.find((x) => x.projectId === projectId)
  if (p && form.amount === undefined) form.amount = Number(p.amount || 0)
}
function onTemplateChange(code) {
  const t = templates.value.find((x) => x.code === code)
  if (t && !form.partyB) form.partyB = ''
}

async function reload() {
  if (!user.logged) return
  loading.value = true
  feeLoading.value = true
  try {
    const [list, feeList, mine] = await Promise.all([
      techApi.contractTemplates(),
      techApi.myServiceFees(),
      techApi.myContracts()
    ])
    templates.value = list || []
    fees.value = feeList || []
    contracts.value = mine || []
  } catch (e) {
    contracts.value = []
    fees.value = []
  } finally {
    loading.value = false
    feeLoading.value = false
  }
}

async function loadProjects() {
  if (!user.logged) return
  try {
    projects.value = (await techApi.projects()) || []
  } catch (e) {
    projects.value = []
  }
}

async function submitContract() {
  if (!user.logged) return go('/login')
  if (!form.projectId || !form.templateCode) {
    message.warning('请选择陪跑项目与合同类型')
    return
  }
  creating.value = true
  try {
    await techApi.createContract({
      projectId: form.projectId,
      templateCode: form.templateCode,
      partyB: form.partyB || null,
      amount: form.amount ?? null
    })
    message.success('合同草稿已生成')
    form.partyB = ''
    await reload()
  } catch (e) {
    // request wrapper 已提示
  } finally {
    creating.value = false
  }
}

async function sign(c) {
  try {
    await techApi.signContract(c.contractNo)
    message.success('合同已签署（演示签署）')
    await reload()
  } catch (e) {
    // request wrapper 已提示
  }
}

function openFee(c) {
  feeTarget.value = c
  feeForm.feeType = 'SERVICE'
  feeForm.amount = Math.max(1, Math.round(Number(c.amount || 0) * 0.15))
  feeOpen.value = true
}

async function submitFee() {
  if (!feeTarget.value) return
  saving.value = true
  try {
    await techApi.createServiceFee(feeTarget.value.contractNo, { ...feeForm })
    message.success('服务费已登记，等待运营确认到账')
    feeOpen.value = false
    await reload()
  } catch (e) {
    // request wrapper 已提示
  } finally {
    saving.value = false
  }
}

function openAccept(c) {
  acceptTarget.value = c
  acceptForm.result = 'PASS'
  acceptForm.score = 5
  acceptForm.note = ''
  acceptOpen.value = true
}

async function submitAccept() {
  if (!acceptTarget.value?.projectId) return
  saving.value = true
  try {
    await techApi.acceptProject(acceptTarget.value.projectId, { ...acceptForm })
    message.success('验收已提交')
    acceptOpen.value = false
    await Promise.all([reload(), loadProjects()])
  } catch (e) {
    // request wrapper 已提示
  } finally {
    saving.value = false
  }
}

async function openReview(project) {
  reviewProject.value = project
  reviewTargets.value = []
  reviewLoading.value = true
  try {
    const [detail, mine] = await Promise.all([
      techApi.project(project.projectId),
      techApi.myProjectReviews(project.projectId)
    ])
    const reviewed = new Set((mine || []).map((r) => `${r.targetType}:${r.targetId}`))
    const targets = []
    if (detail.expert) {
      targets.push({
        key: `EXPERT:${detail.expert.expertId}`,
        label: `责任专家 · ${detail.expert.name}`,
        type: 'EXPERT',
        id: detail.expert.expertId
      })
    }
    if (detail.broker) {
      targets.push({
        key: `BROKER:${detail.broker.expertId}`,
        label: `技术经纪人 · ${detail.broker.name}`,
        type: 'BROKER',
        id: detail.broker.expertId
      })
    }
    reviewTargets.value = targets.filter((t) => !reviewed.has(t.key))
    if (!reviewTargets.value.length) {
      message.info(targets.length ? '该项目服务对象均已评价' : '该项目尚未指派专家或经纪人，暂无可评价对象')
      return
    }
    Object.assign(reviewForm, {
      targetKey: reviewTargets.value[0].key,
      scoreResponse: 5,
      scoreProfessional: 5,
      scoreDelivery: 5,
      scoreCommunication: 5,
      scoreResult: 5,
      comment: ''
    })
    reviewOpen.value = true
  } catch (e) {
    // request wrapper 已提示
  } finally {
    reviewLoading.value = false
  }
}

async function submitReview() {
  if (!reviewProject.value || !reviewForm.targetKey) {
    message.warning('请选择评价对象')
    return
  }
  const target = reviewTargets.value.find((t) => t.key === reviewForm.targetKey)
  if (!target) return
  saving.value = true
  try {
    await techApi.createReview(reviewProject.value.projectId, {
      targetType: target.type,
      targetId: target.id,
      scoreResponse: reviewForm.scoreResponse,
      scoreProfessional: reviewForm.scoreProfessional,
      scoreDelivery: reviewForm.scoreDelivery,
      scoreCommunication: reviewForm.scoreCommunication,
      scoreResult: reviewForm.scoreResult,
      comment: reviewForm.comment || null
    })
    message.success('评价已提交')
    reviewOpen.value = false
  } catch (e) {
    // request wrapper 已提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([reload(), loadProjects()])
})
</script>

<style scoped>
.clause-tags { max-height: 72px; overflow: hidden; }
.review-scores { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
@media (max-width: 640px) { .review-scores { grid-template-columns: 1fr; } }
</style>
