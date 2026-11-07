<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">APPRAISAL · THIRD-PARTY CONCLUSION</span>
        <h1>{{ isEn ? 'The platform prepares the evidence. The institution issues the conclusion.' : '平台准备证据链，机构出具结论。' }}</h1>
        <p>
          {{ isEn
            ? 'We help structure technical reports, novelty search and IP materials, then coordinate with a qualified evaluation institution. The platform does not issue appraisal conclusions itself.'
            : '平台协助梳理技术报告、查新与知识产权材料，并对接具备相应能力的评价机构；平台自身不出具评价结论，也不使用“国家认定”等表述。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="scrollToApply">
            {{ isEn ? 'Start an application' : '提交鉴定申请' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/experts')">
            {{ isEn ? 'Talk to a broker' : '先找经纪人诊断' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="PROCESS"
        :title="isEn ? 'Four checkpoints, one evidence chain' : '四个节点，一条证据链'"
        :desc="isEn
          ? 'Material readiness is usually the bottleneck. A broker helps avoid rework before the institution review starts.'
          : '材料完备度通常是最耗时的环节；技术经纪人会在机构评审前帮助减少返工。'"
      />
      <div class="tech-process">
        <div v-for="(step, i) in processSteps" :key="step.title" class="tech-process-step" data-reveal="fade-up" :data-delay="i * 60">
          <em>{{ String(i + 1).padStart(2, '0') }}</em>
          <b>{{ step.title }}</b>
          <span>{{ step.desc }}</span>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="PARTNER INSTITUTIONS"
        :title="isEn ? 'Evaluation institutions in the network' : '评价机构网络（拟合作/参考）'"
        :desc="isEn
          ? 'Institution profiles are shown for reference. Qualification numbers, scope and fees are verified before a formal engagement.'
          : '机构资料仅作参考；正式委托前将核验执业/备案信息、评价范围与费用，并以书面协议约定。'"
      />
      <div class="tech-grid">
        <article v-for="org in institutions" :key="org.orgId" class="tech-card">
          <div class="tech-card-top">
            <span class="tech-icon-chip">{{ org.logoText || org.name.slice(0, 2) }}</span>
            <span class="tech-chip" :class="org.partnershipStatus === 'PARTNER' ? 'green' : 'amber'">
              {{ org.partnershipStatus === 'PARTNER' ? (isEn ? 'Partner' : '已签约') : (isEn ? 'Reference' : '拟合作/参考') }}
            </span>
          </div>
          <h3>{{ org.name }}</h3>
          <p>{{ org.intro }}</p>
          <div class="tech-card-meta">
            <span>{{ org.region }}</span>
            <span>{{ org.qualifications }}</span>
          </div>
        </article>
      </div>
    </section>

    <section id="tech-appraisal-apply" class="tech-section">
      <TechSectionHead
        eyebrow="APPLICATION"
        :title="isEn ? 'Submit an appraisal request' : '提交成果鉴定/评价申请'"
        :desc="isEn
          ? 'The application creates a tracked project. The platform will confirm material scope and the institution quote before any formal review.'
          : '提交后会生成可跟踪的陪跑项目；平台将在正式评审前确认材料范围与机构报价。'"
      />
      <div class="tech-form-card">
        <a-form layout="vertical">
          <div class="tech-form-grid">
            <a-form-item class="full" :label="isEn ? 'Appraisal object' : '鉴定/评价对象'" required>
              <a-input v-model:value="form.title" :placeholder="isEn ? 'Achievement or technology name' : '成果或技术名称'" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Institution' : '合作评价机构'" required>
              <a-select v-model:value="form.institutionId" :options="institutionOptions" :placeholder="isEn ? 'Select an institution' : '请选择机构'" />
            </a-form-item>
            <a-form-item :label="isEn ? 'Expected use' : '评价用途'">
              <a-select v-model:value="form.expectedUse" :options="useOptions" />
            </a-form-item>
            <a-form-item class="full" :label="isEn ? 'Scope & materials' : '评价范围与材料说明'">
              <a-textarea v-model:value="form.scope" :rows="4" :placeholder="isEn ? 'Technical report, novelty search, IP list, testing reports...' : '技术报告、查新报告、知识产权清单、检测报告……'" />
            </a-form-item>
          </div>
          <div class="tech-note">
            <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
            <div>
              <strong>{{ isEn ? 'No official-conclusion claim' : '不使用“官方认定”表述' }}</strong>
              <span v-if="isEn">
                The platform does not issue appraisal conclusions or guarantee a rating. The institution independently
                issues the report; the platform only coordinates materials and process.
              </span>
              <span v-else>
                平台不出具评价结论，也不承诺评价等级；报告由机构独立出具，平台仅提供材料组织与流程协调服务。
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
        eyebrow="MY APPLICATIONS"
        :title="isEn ? 'My appraisal requests' : '我的鉴定申请'"
        :desc="isEn ? 'Track material review, expert review and report issuance.' : '跟踪材料预审、专家评审与报告出具进度。'"
      >
        <template #action>
          <button v-if="user.logged" type="button" class="tech-link" @click="loadMine">
            {{ isEn ? 'Refresh →' : '刷新 →' }}
          </button>
        </template>
      </TechSectionHead>
      <div v-if="!user.logged" class="tech-empty">
        <FileCheck :size="42" :stroke-width="1.4" aria-hidden="true" />
        <b>{{ isEn ? 'Sign in to track applications' : '登录后查看鉴定进度' }}</b>
        <span>{{ isEn ? 'Your applications and project timeline are stored in the workspace.' : '申请记录与项目时间线会进入交易工作台。' }}</span>
        <button type="button" class="tech-btn tech-btn-primary tech-mt" @click="go('/login')">{{ isEn ? 'Sign in' : '去登录' }}</button>
      </div>
      <a-spin v-else :spinning="mineLoading">
        <div v-if="mine.length" class="tech-grid">
          <article v-for="item in mine" :key="item.appraisalId" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip cyan">{{ item.standardCode }}</span>
              <span class="tech-chip" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span>
            </div>
            <h3>{{ item.appraisalNo }}</h3>
            <p>{{ item.disclaimer }}</p>
            <div v-if="item.conclusionLevel" class="tech-row tech-mt">
              <span class="tech-chip amber">{{ item.conclusionLevel }}</span>
              <span v-if="item.reportNo" class="tech-chip">{{ item.reportNo }}</span>
            </div>
            <div v-if="item.techScore" class="tech-score-grid">
              <div class="tech-score"><b>{{ item.techScore }}</b><span>{{ isEn ? 'Tech' : '技术' }}</span></div>
              <div class="tech-score"><b>{{ item.economicScore }}</b><span>{{ isEn ? 'Economic' : '经济' }}</span></div>
              <div class="tech-score"><b>{{ item.legalScore }}</b><span>{{ isEn ? 'Legal' : '法律' }}</span></div>
              <div class="tech-score"><b>{{ item.maturityScore }}</b><span>{{ isEn ? 'Maturity' : '成熟度' }}</span></div>
            </div>
            <div class="tech-card-foot">
              <span class="tech-muted">{{ formatTime(item.createTime) }}</span>
              <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="go('/tech/workspace')">
                {{ isEn ? 'Open project' : '查看项目' }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!mineLoading" class="tech-empty">
          <FileCheck :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No appraisal requests yet' : '还没有鉴定申请' }}</b>
          <span>{{ isEn ? 'Submit the first request above.' : '在上方提交第一条申请。' }}</span>
        </div>
      </a-spin>
    </section>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { FileCheck, ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { ensureTechUser } from '../../utils/techAuth'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'

const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const organizations = ref([])
const mine = ref([])
const mineLoading = ref(false)
const submitting = ref(false)
const form = reactive({ title: '', institutionId: undefined, expectedUse: '高企申报', scope: '' })

const institutions = computed(() => organizations.value.filter((o) => o.orgType === 'EVALUATION' || o.orgType === 'EXCHANGE'))
const institutionOptions = computed(() => institutions.value.map((o) => ({ label: o.name, value: o.orgId })))
const useOptions = computed(() => [
  { label: isEn.value ? 'High-tech enterprise application' : '高企申报', value: '高企申报' },
  { label: isEn.value ? 'Project acceptance' : '项目验收', value: '项目验收' },
  { label: isEn.value ? 'Financing' : '融资', value: '融资' },
  { label: isEn.value ? 'Deal pricing' : '交易定价', value: '交易定价' },
  { label: isEn.value ? 'Award application' : '奖项申报', value: '奖项申报' },
  { label: isEn.value ? 'Other' : '其他', value: '其他' }
])
const processSteps = computed(() => [
  { title: isEn.value ? 'Material scoping' : '材料范围确认', desc: isEn.value ? 'Confirm the evaluation object, use case and evidence list.' : '确认评价对象、用途与证据清单。' },
  { title: isEn.value ? 'Pre-review' : '材料预审', desc: isEn.value ? 'Broker checks completeness and consistency before submission.' : '技术经纪人检查材料完备性与一致性。' },
  { title: isEn.value ? 'Expert review' : '专家评审', desc: isEn.value ? 'The institution organizes independent expert review.' : '由机构组织独立专家评审。' },
  { title: isEn.value ? 'Report issued' : '报告出具', desc: isEn.value ? 'The institution issues the report and handles objections.' : '机构出具报告并处理异议。' }
])

function statusLabel(status) {
  // 取值域以 TechAppraisalService.APPRAISAL_STATUSES 为准，缺 REJECTED 会让驳回单裸显英文
  const map = {
    SUBMITTED: isEn.value ? 'Submitted' : '已提交',
    MATERIAL_REVIEW: isEn.value ? 'Material review' : '材料预审',
    EXPERT_REVIEW: isEn.value ? 'Expert review' : '专家评审',
    REPORT_ISSUED: isEn.value ? 'Report issued' : '报告已出具',
    REJECTED: isEn.value ? 'Rejected' : '已驳回'
  }
  return map[status] || status
}
function statusTone(status) {
  if (status === 'REPORT_ISSUED') return 'green'
  if (status === 'EXPERT_REVIEW') return 'cyan'
  if (status === 'MATERIAL_REVIEW') return 'amber'
  return ''
}
function formatTime(value) {
  return String(value || '').replace('T', ' ').slice(0, 16)
}
function scrollToApply() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/appraisal' } })
    return
  }
  document.getElementById('tech-appraisal-apply')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function go(path) {
  router.push(path)
}

async function loadOrganizations() {
  try {
    organizations.value = await techApi.organizations()
  } catch (e) {
    organizations.value = []
  }
}
async function loadMine() {
  if (!user.logged) return
  mineLoading.value = true
  try {
    mine.value = await techApi.myAppraisals()
  } catch (e) {
    mine.value = []
  } finally {
    mineLoading.value = false
  }
}

async function submit() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/appraisal' } })
    return
  }
  if (!form.title.trim() || !form.institutionId) {
    message.warning(isEn.value ? 'Please enter the object and select an institution' : '请填写评价对象并选择机构')
    return
  }
  submitting.value = true
  try {
    await techApi.createAppraisal({
      title: form.title,
      institutionId: form.institutionId,
      expectedUse: form.expectedUse,
      scope: form.scope
    })
    message.success(isEn.value ? 'Application submitted' : '鉴定申请已提交，陪跑项目已创建')
    form.title = ''
    form.scope = ''
    await loadMine()
    router.push('/tech/workspace')
  } catch (e) {
    // request wrapper shows the error
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadOrganizations(), ensureTechUser().then(() => loadMine())])
})
</script>
