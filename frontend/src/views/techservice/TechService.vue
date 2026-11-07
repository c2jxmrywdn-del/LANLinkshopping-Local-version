<template>
  <TechPageShell>
    <section class="tech-hero" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">
          <Sparkles :size="13" :stroke-width="2.2" />
          {{ isEn ? 'TECH SERVICE HUB · TECH TRANSFER & IP OPERATIONS' : 'TECH SERVICE HUB · 技术交易与专利运营' }}
        </span>
        <h1>{{ isEn ? 'Turn technical needs into deliverable collaboration.' : '把技术需求，变成可落地的合作。' }}</h1>
        <p>
          {{ isEn
            ? 'Connect enterprise technical needs, university achievements, experts, technology brokers and qualified service institutions. One team accompanies you from diagnosis and matching to appraisal, hosting and deal execution.'
            : '连接企业技术需求、高校院所成果、专家与技术经纪人、专业服务机构，提供从诊断、匹配、鉴定、托管到交易交割的全流程陪跑。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="go('/tech/demands', { publish: 1 })">
            <Plus :size="16" :stroke-width="2.2" /> {{ isEn ? 'Post a demand' : '发布技术需求' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/achievements')">
            <BadgeCheck :size="16" :stroke-width="2.2" /> {{ isEn ? 'Browse achievements' : '浏览成果与专利' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/experts')">
            <Users :size="16" :stroke-width="2.2" /> {{ isEn ? 'Meet the experts' : '找专家/技术经纪人' }}
          </button>
        </div>
        <div class="tech-hero-stats">
          <div v-for="(s, i) in heroStats" :key="s.label" class="tech-hero-stat" :data-delay="i * 40">
            <b class="tech-num">
              <span v-if="s.prefix">{{ s.prefix }}</span>
              <NumberTicker :value="s.value" :decimal-places="s.decimal || 0" />
              <span v-if="s.suffix">{{ s.suffix }}</span>
            </b>
            <span>{{ s.label }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="ECOSYSTEM & POLICY"
        :title="isEn ? 'Policy-aligned, institution-connected' : '政策同频，机构协同'"
        :desc="isEn
          ? 'Policy references and institution profiles are shown for context. Cooperation with any institution is subject to a written agreement; platform listings do not constitute government recognition or endorsement.'
          : '政策依据与机构资料仅作背景展示；与任何机构的合作以书面协议为准，平台展示不构成政府认定或背书。'"
      />
      <Marquee :repeat="2" duration="46s" pause-on-hover gap="0.75rem">
        <div class="tech-marquee-row">
          <div v-for="org in organizations" :key="org.orgId" class="tech-logo-pill">
            <span>{{ org.logoText || org.name.slice(0, 2) }}</span>
            {{ org.name }}
            <small>{{ org.partnershipStatus === 'PARTNER' ? (isEn ? 'Partner' : '已签约') : (isEn ? 'Reference' : '拟合作/参考') }}</small>
          </div>
        </div>
      </Marquee>
      <div class="tech-note tech-mt">
        <Landmark :size="20" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>{{ isEn ? 'Our role in the public service system' : '我们在公共服务体系中的角色' }}</strong>
          <span v-if="isEn">
            Government and public platforms provide policy access and traffic; we focus on transaction conversion and
            deep delivery — demand diagnosis, technology brokerage, due diligence, hosting operations and deal execution.
          </span>
          <span v-else>
            政府与公共服务平台提供政策入口与流量，我们负责交易转化与深度交付——需求诊断、技术经纪、尽职调查、
            托管运营与交易执行，避免与公共服务体系直接竞争。
          </span>
        </div>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="SERVICE MATRIX"
        :title="isEn ? 'Eight service lines, one delivery spine' : '八大服务，一条交付主线'"
        :desc="isEn
          ? 'Each line can be used independently, or combined into a full-process engagement managed by a dedicated broker.'
          : '每条服务线都可独立使用，也可由专属技术经纪人组合成一次全流程委托。'"
      >
        <template #action>
          <button type="button" class="tech-link" @click="go('/tech/workspace')">
            {{ isEn ? 'Open workspace →' : '进入交易工作台 →' }}
          </button>
        </template>
      </TechSectionHead>
      <div class="tech-bento">
        <button
          v-for="card in serviceCards"
          :key="card.to"
          type="button"
          class="tech-bento-card"
          :class="card.span"
          @click="go(card.to)"
        >
          <span class="tech-icon-chip"><component :is="card.icon" :size="20" :stroke-width="1.9" /></span>
          <h3>{{ card.title }}</h3>
          <p>{{ card.desc }}</p>
          <span class="tech-link tech-mt">{{ isEn ? 'Explore →' : '了解服务 →' }}</span>
        </button>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="WHY DIFFERENT"
        :title="isEn ? 'Built around outcomes, not listings' : '不以挂牌量为目标，以真实成交为结果'"
        :desc="isEn
          ? 'The market already has enough listing platforms. The scarce capability is turning a patent into a verified, deliverable and bankable collaboration.'
          : '市场不缺信息撮合平台，缺的是把专利变成可验证、可交付、可融资合作的能力。'"
      />
      <div class="tech-grid">
        <article v-for="item in differentiators" :key="item.no" class="tech-card" data-reveal="fade-up" :data-delay="item.delay">
          <div class="tech-card-top">
            <span class="tech-icon-chip"><component :is="item.icon" :size="20" :stroke-width="1.9" /></span>
            <span class="tech-chip indigo">{{ item.no }}</span>
          </div>
          <h3>{{ item.title }}</h3>
          <p>{{ item.desc }}</p>
        </article>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="FULL-PROCESS ACCOMPANIMENT"
        :title="isEn ? 'A broker-led delivery path' : '技术经纪人主导的全流程陪跑'"
        :desc="isEn
          ? 'Every engagement is tracked as a project with milestones, deliverables and next actions in the workspace.'
          : '每一次委托都会生成带里程碑、交付物与下一步动作的项目，并在工作台持续跟踪。'"
      />
      <div class="tech-process">
        <div v-for="(step, i) in processSteps" :key="step.title" class="tech-process-step" data-reveal="fade-up" :data-delay="i * 60">
          <em>{{ String(i + 1).padStart(2, '0') }}</em>
          <b>{{ step.title }}</b>
          <span>{{ step.desc }}</span>
        </div>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="FEATURED EXPERTS"
        :title="isEn ? 'Invite-only experts and brokers' : '定向邀请的专家与技术经纪人'"
        :desc="isEn
          ? 'Every profile is reviewed before listing. Experts provide technical judgement; brokers own the delivery path.'
          : '专家与技术经纪人经定向邀请与资料核验后入驻；专家负责技术判断，经纪人负责交付路径。'"
      >
        <template #action>
          <button type="button" class="tech-link" @click="go('/tech/experts')">
            {{ isEn ? 'View all →' : '查看全部 →' }}
          </button>
        </template>
      </TechSectionHead>
      <div v-if="loading" class="tech-grid">
        <div v-for="i in 4" :key="i" class="tech-card"><a-skeleton active :paragraph="{ rows: 4 }" /></div>
      </div>
      <div v-else class="tech-grid">
        <TechExpertCard
          v-for="expert in experts"
          :key="expert.expertId"
          :expert="expert"
          @view="go('/tech/experts', { expert: expert.expertId })"
          @consult="go('/tech/experts', { expert: expert.expertId, consult: 1 })"
        />
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <div class="tech-split">
        <div>
          <TechSectionHead
            eyebrow="LIVE DEMANDS"
            :title="isEn ? 'Enterprise needs, translated' : '正在寻找解决方案的企业需求'"
          >
            <template #action>
              <button type="button" class="tech-link" @click="go('/tech/demands')">{{ isEn ? 'All →' : '全部 →' }}</button>
            </template>
          </TechSectionHead>
          <div v-if="!demands.length" class="tech-empty">{{ isEn ? 'No active demands yet.' : '暂无公开需求。' }}</div>
          <div v-else class="tech-stack">
            <TechDemandCard
              v-for="demand in demands"
              :key="demand.demandId"
              :demand="demand"
              @view="go('/tech/demands', { id: demand.demandId })"
              @contact="go('/tech/experts', { demand: demand.demandId })"
            />
          </div>
        </div>
        <div>
          <TechSectionHead
            eyebrow="DORMANT PATENTS & ACHIEVEMENTS"
            :title="isEn ? 'Assets waiting for a route to market' : '等待被激活的成果与沉睡专利'"
          >
            <template #action>
              <button type="button" class="tech-link" @click="go('/tech/achievements')">{{ isEn ? 'All →' : '全部 →' }}</button>
            </template>
          </TechSectionHead>
          <div v-if="!achievements.length" class="tech-empty">{{ isEn ? 'No listed achievements yet.' : '暂无公开成果。' }}</div>
          <div v-else class="tech-stack">
            <TechAchievementCard
              v-for="achievement in achievements"
              :key="achievement.achievementId"
              :achievement="achievement"
              @view="go('/tech/achievements', { id: achievement.achievementId })"
              @assess="go('/tech/achievements', { id: achievement.achievementId, assess: 1 })"
            />
          </div>
        </div>
      </div>
    </section>

    <section class="tech-section" data-reveal="fade-up">
      <TechSectionHead
        eyebrow="POLICY LIBRARY"
        :title="isEn ? 'Policy is part of the product' : '把政策依据做进产品'"
        :desc="isEn
          ? 'Open licensing, dynamic patent inventory, pre-application assessment and technology-contract registration are all supported by policy.'
          : '开放许可、专利动态盘点、申请前评估与技术合同登记，都有明确的政策与标准依据。'"
      >
        <template #action>
          <button type="button" class="tech-link" @click="go('/tech/policies')">{{ isEn ? 'Policy center →' : '政策专区 →' }}</button>
        </template>
      </TechSectionHead>
      <div class="tech-grid">
        <a
          v-for="policy in policies"
          :key="policy.policyId"
          class="tech-card tech-policy-card"
          :href="policy.url || undefined"
          target="_blank"
          rel="noopener noreferrer"
        >
          <div class="tech-card-top">
            <span class="tech-chip cyan">{{ policy.region }}</span>
            <span class="tech-chip">{{ policy.category }}</span>
          </div>
          <h3>{{ policy.title }}</h3>
          <p>{{ policy.summary }}</p>
          <div class="tech-card-foot">
            <span class="tech-muted">{{ policy.issuer }}</span>
            <span class="tech-link">{{ policy.highlight }} <ExternalLink :size="12" /></span>
          </div>
        </a>
      </div>
    </section>

    <section class="tech-cta" data-reveal="scale">
      <div>
        <span class="tech-eyebrow">{{ isEn ? 'START SMALL, PROVE THE DEAL' : '从小切口开始，把第一单跑通' }}</span>
        <h2>{{ isEn ? 'One patent. One need. One broker.' : '一件专利，一条需求，一位经纪人。' }}</h2>
        <p>
          {{ isEn
            ? 'Pilot in intelligent manufacturing and advanced materials, then replicate the playbook to adjacent industries.'
            : '先在智能制造与新材料做透，再把方法论复制到相邻行业；用真实成交验证模式，而不是用挂牌量证明存在感。' }}
        </p>
      </div>
      <div class="tech-cta-actions">
        <button type="button" class="tech-btn tech-btn-primary" @click="go('/tech/demands', { publish: 1 })">
          {{ isEn ? 'Post a demand' : '发布需求' }}
        </button>
        <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/hosting')">
          {{ isEn ? 'Host a patent' : '申请专利托管' }}
        </button>
      </div>
    </section>
  </TechPageShell>
</template>

<script setup>
import { computed, markRaw, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Sparkles,
  Plus,
  Users,
  BadgeCheck,
  Landmark,
  ExternalLink,
  MessageSquare,
  Stethoscope,
  Handshake,
  FileCheck,
  ArchiveRestore,
  Building2,
  Cloud,
  ArrowLeftRight,
  Target,
  Percent,
  Gauge,
  FlaskConical,
  ShieldCheck,
  Route,
  Search,
  CheckCircle,
  PackageCheck,
  TrendingUp
} from 'lucide-vue-next'
import { techApi } from '../../api'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import TechExpertCard from '../../components/techservice/TechExpertCard.vue'
import TechDemandCard from '../../components/techservice/TechDemandCard.vue'
import TechAchievementCard from '../../components/techservice/TechAchievementCard.vue'
import NumberTicker from '../../components/ui/NumberTicker.vue'
import Marquee from '../../components/ui/Marquee.vue'

const router = useRouter()
const { isEn } = useTechLang()

const loading = ref(true)
const overview = ref({})
const experts = computed(() => overview.value.experts || [])
const demands = computed(() => overview.value.demands || [])
const achievements = computed(() => overview.value.achievements || [])
const organizations = computed(() => overview.value.organizations || [])
const policies = computed(() => overview.value.policies || [])

const heroStats = computed(() => {
  const s = overview.value.stats || {}
  return [
    {
      label: isEn.value ? 'Experts & brokers' : '专家与技术经纪人',
      value: Number(s.experts || 0) + Number(s.brokers || 0),
      suffix: '+'
    },
    {
      label: isEn.value ? 'Active demands' : '在库技术需求',
      value: Number(s.demands || 0)
    },
    {
      label: isEn.value ? 'Achievements' : '成果与专利',
      value: Number(s.achievements || 0)
    },
    {
      label: isEn.value ? 'Deal value (¥10k)' : '累计交易额（万元）',
      value: Math.round(Number(s.dealAmount || 0) / 10000),
      prefix: '¥'
    }
  ]
})

const SERVICE_CARDS = [
  {
    icon: markRaw(MessageSquare),
    to: '/tech/exchange',
    span: 'span-6 dark', // legacy dark = 浅天蓝主推卡
    zh: '技术交流',
    en: 'Tech exchange',
    zhDesc: '专家专栏、问答与讨论，把“技术语言”翻译成企业能理解的问题与方案。',
    enDesc: 'Expert columns, Q&A and discussions that translate technical language into business decisions.'
  },
  {
    icon: markRaw(Stethoscope),
    to: '/tech/experts',
    span: 'span-6 tint',
    zh: '专业技术咨询',
    en: 'Expert consultation',
    zhDesc: '一对一预约专家或技术经纪人，先判断“值不值得做”，再决定投入。',
    enDesc: 'Book an expert or broker to judge feasibility before committing budget or resources.'
  },
  {
    icon: markRaw(Handshake),
    to: '/tech/demands',
    span: 'span-4',
    zh: '供需对接',
    en: 'Matchmaking',
    zhDesc: '企业需求与成果库双向匹配，AI 快筛 + 技术经纪人深谈。',
    enDesc: 'Two-way matching between demands and achievements, with AI screening and broker-led deep dives.'
  },
  {
    icon: markRaw(FileCheck),
    to: '/tech/appraisal',
    span: 'span-4',
    zh: '成果鉴定',
    en: 'Appraisal',
    zhDesc: '材料辅导、机构对接与流程跟踪；评价结论由合作机构独立出具。',
    enDesc: 'Material preparation, institution matching and process tracking; conclusions are issued by partner institutions.'
  },
  {
    icon: markRaw(ArchiveRestore),
    to: '/tech/hosting',
    span: 'span-4',
    zh: '沉睡专利盘活',
    en: 'Dormant patent activation',
    zhDesc: '权属与年费核查、技术画像、许可/转让方案与买家匹配。',
    enDesc: 'Ownership and annuity checks, technology profiling, licensing/assignment design and buyer matching.'
  },
  {
    icon: markRaw(Building2),
    to: '/tech/hosting',
    span: 'span-4',
    zh: '机构托管',
    en: 'Agency hosting',
    zhDesc: '严选知识产权服务机构，按里程碑验收，服务过程可追踪。',
    enDesc: 'Curated IP service institutions, milestone acceptance and traceable delivery.'
  },
  {
    icon: markRaw(Cloud),
    to: '/tech/hosting',
    span: 'span-4',
    zh: 'SaaS 工具托管',
    en: 'SaaS tool hosting',
    zhDesc: '专利监控、年费预警、竞品动态与 FTO 初筛，按订阅开通。',
    enDesc: 'Patent monitoring, annuity alerts, competitor tracking and FTO screening on subscription.'
  },
  {
    icon: markRaw(ArrowLeftRight),
    to: '/tech/workspace',
    span: 'span-4',
    zh: '收储-托管-交易',
    en: 'Acquisition-hosting-deal',
    zhDesc: '从意向、尽调、合同到交割的全链路项目；资金监管由持牌机构办理。',
    enDesc: 'Intent, due diligence, contract and closing in one project; escrow is handled by licensed institutions.'
  }
]
const serviceCards = computed(() => SERVICE_CARDS.map((c) => ({
  ...c,
  title: isEn.value ? c.en : c.zh,
  desc: isEn.value ? c.enDesc : c.zhDesc
})))

const DIFFERENTIATORS = [
  {
    no: '01',
    icon: markRaw(Target),
    zh: '垂直行业先做透',
    en: 'Vertical depth first',
    zhDesc: '先聚焦智能制造与新材料，形成技术路线图、买家画像与成交方法论，再复制到相邻行业。',
    enDesc: 'Start with intelligent manufacturing and advanced materials; build the technology map, buyer profiles and playbook before expanding.'
  },
  {
    no: '02',
    icon: markRaw(Percent),
    zh: '结果导向收费',
    en: 'Outcome-based pricing',
    zhDesc: '专利零门槛进场托管，成交后按 15–25% 分成；“不成交不收费”是获客与信任的起点。',
    enDesc: 'Zero-threshold hosting; 15–25% success fee. “No deal, no fee” is the starting point for trust and acquisition.'
  },
  {
    no: '03',
    icon: markRaw(Gauge),
    zh: '国标化 AI 快筛',
    en: 'Standard-aligned AI screening',
    zhDesc: '参考 GB/T 44731-2024 与 GB/T 42748-2023 的方法框架，先做法律—技术—经济三维快筛。',
    enDesc: 'A legal–technical–economic quick screen aligned with GB/T 44731-2024 and GB/T 42748-2023 methodology.'
  },
  {
    no: '04',
    icon: markRaw(FlaskConical),
    zh: '补位概念验证与中试',
    en: 'Validation & pilot gap',
    zhDesc: '不直接撮合“专利→企业”，而是牵线中试资源、共享实验室与二次开发团队，先验证再交易。',
    enDesc: 'Rather than pushing patents directly to buyers, connect pilot lines, shared labs and engineering teams before a deal.'
  },
  {
    no: '05',
    icon: markRaw(ShieldCheck),
    zh: '可信交易机制',
    en: 'Trust infrastructure',
    zhDesc: '权属前置核查、交易材料存证、合同模板与持牌机构资金监管，降低交易崩单概率。',
    enDesc: 'Ownership pre-checks, evidence hashing, contract templates and licensed escrow reduce deal failure.'
  }
]
const differentiators = computed(() => DIFFERENTIATORS.map((d, i) => ({
  ...d,
  title: isEn.value ? d.en : d.zh,
  desc: isEn.value ? d.enDesc : d.zhDesc,
  delay: i * 60
})))

const PROCESS = [
  {
    icon: markRaw(Search),
    zh: '需求诊断',
    en: 'Diagnosis',
    zhDesc: '把模糊需求拆成技术问题、验收指标与预算边界。',
    enDesc: 'Turn a vague need into technical questions, acceptance metrics and budget boundaries.'
  },
  {
    icon: markRaw(Route),
    zh: '资源匹配',
    en: 'Matching',
    zhDesc: 'AI 快筛成果与专家，技术经纪人组织深度对接。',
    enDesc: 'AI screens achievements and experts; the broker arranges deep conversations.'
  },
  {
    icon: markRaw(CheckCircle),
    zh: '尽调与方案',
    en: 'Diligence & plan',
    zhDesc: '权属、年费、许可状态与成熟度核查，形成交易/合作结构。',
    enDesc: 'Verify ownership, annuities, licenses and maturity; design the deal structure.'
  },
  {
    icon: markRaw(PackageCheck),
    zh: '签约与履约',
    en: 'Contract & delivery',
    zhDesc: '合同辅导、里程碑拆解与交付物验收，过程留痕。',
    enDesc: 'Contract guidance, milestone breakdown and deliverable acceptance with a full audit trail.'
  },
  {
    icon: markRaw(TrendingUp),
    zh: '验收与转化',
    en: 'Acceptance & scale',
    zhDesc: '验收、收益分配与后续融资/产业化资源对接。',
    enDesc: 'Acceptance, revenue sharing and follow-on financing or industrialization resources.'
  }
]
const processSteps = computed(() => PROCESS.map((p) => ({
  title: isEn.value ? p.en : p.zh,
  desc: isEn.value ? p.enDesc : p.zhDesc
})))

function go(path, query) {
  router.push({ path, query })
}

onMounted(async () => {
  try {
    overview.value = await techApi.overview()
  } catch (e) {
    overview.value = {}
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.tech-split { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 32px; align-items: start; }
.tech-stack { display: flex; flex-direction: column; gap: 12px; }
.tech-policy-card { text-decoration: none; color: inherit; }
.tech-policy-card h3 { font-size: 15px; }
.tech-policy-card .tech-card-foot { margin-top: 12px; }
.tech-cta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin: 46px 0 10px;
  padding: clamp(24px, 3.4vw, 42px);
  border-radius: 24px;
  color: #13233A;
  background: linear-gradient(135deg, #F2F9FF, #DBEAFE 60%, #BAE6FD);
  box-shadow: 0 24px 60px rgba(30, 110, 184, 0.14);
}
.tech-cta h2 { margin: 10px 0 8px; font-size: clamp(24px, 3vw, 34px); letter-spacing: -0.03em; }
.tech-cta p { margin: 0; max-width: 620px; color: #475569; font-size: 14px; line-height: 1.8; }
.tech-cta-actions { display: flex; flex-wrap: wrap; gap: 10px; flex: 0 0 auto; }
button.tech-bento-card { text-align: left; font: inherit; cursor: pointer; }
@media (max-width: 1000px) {
  .tech-split { grid-template-columns: 1fr; gap: 22px; }
  .tech-cta { flex-direction: column; align-items: flex-start; }
}
</style>
