<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">POLICY & ECOSYSTEM · 政策与生态</span>
        <h1>{{ isEn ? 'Policy provides the entrance. Delivery creates the outcome.' : '政策提供入口，落地创造结果。' }}</h1>
        <p>
          {{ isEn
            ? 'We do not compete with public service platforms. Government and public institutions bring policy access and traffic; we take on diagnosis, brokerage, verification and deal execution.'
            : '我们不与公共服务平台争夺入口。政府与公共机构提供政策入口与流量，我们负责诊断、经纪、验证与交易执行，形成互补而非竞争。' }}
        </p>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="POLICY LANDSCAPE"
        :title="isEn ? 'Policies we align with' : '我们主动对接的政策体系'"
        :desc="isEn
          ? 'Policy references are listed for context. Alignment with any policy does not imply government endorsement of the platform.'
          : '政策条目仅作业务对齐说明；接入政策体系不代表政府对本平台的认定或背书。'"
      >
        <template #action>
          <SegmentedControl v-model="region" :options="regionOptions" :aria-label="isEn ? 'Region' : '地区'" />
        </template>
      </TechSectionHead>

      <a-spin :spinning="loading">
        <div v-if="filteredPolicies.length" class="policy-list">
          <article v-for="p in filteredPolicies" :key="p.policyId" class="tech-card policy-card">
            <div class="tech-card-top">
              <span class="tech-chip indigo">{{ p.category }}</span>
              <span class="tech-chip">{{ p.region }}</span>
            </div>
            <h3>{{ p.title }}</h3>
            <p>{{ p.summary }}</p>
            <div class="tech-card-meta">
              <span>{{ p.issuer }}</span>
              <span v-if="p.docNo">{{ p.docNo }}</span>
              <span v-if="p.publishDate">{{ p.publishDate }}</span>
            </div>
            <div class="tech-card-foot">
              <span v-if="p.highlight" class="tech-chip cyan">{{ p.highlight }}</span>
              <a v-if="p.url" :href="p.url" target="_blank" rel="noopener noreferrer" class="tech-link">
                {{ isEn ? 'Official source ↗' : '政策原文 ↗' }}
              </a>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <BookOpen :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No policies in this region' : '该地区暂无政策条目' }}</b>
          <span>{{ isEn ? 'Switch the region or check later.' : '切换地区查看，或稍后再来。' }}</span>
        </div>
      </a-spin>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="ECOSYSTEM"
        :title="isEn ? 'Institutions we work with' : '生态机构与合作定位'"
        :desc="isEn
          ? 'Exchange venues, evaluation institutions, agencies and SaaS providers. Cooperation is subject to a written agreement; listings do not imply endorsement.'
          : '技术交易场所、评价机构、知识产权服务机构与 SaaS 服务商。合作以书面协议为准，展示不代表官方背书。'"
      />
      <div class="tech-grid">
        <article v-for="org in organizations" :key="org.orgId" class="tech-card">
          <div class="tech-card-top">
            <div class="org-identity">
              <span class="org-badge">{{ org.logoText || org.name.slice(0, 2) }}</span>
              <div>
                <h3 style="margin:0">{{ org.name }}</h3>
                <span class="tech-muted">{{ typeLabel(org.orgType) }} · {{ org.region }}</span>
              </div>
            </div>
            <span class="tech-chip" :class="org.partnershipStatus === 'PARTNER' ? 'green' : 'amber'">
              {{ org.partnershipStatus === 'PARTNER' ? (isEn ? 'Partner' : '已签约') : (isEn ? 'Reference' : '拟合作/参考') }}
            </span>
          </div>
          <p class="tech-mt">{{ org.intro }}</p>
          <div class="tech-card-meta">
            <span>{{ org.qualifications }}</span>
          </div>
          <div class="tech-card-foot">
            <span class="tech-muted">{{ org.licenseNo }}</span>
            <a v-if="org.website" :href="org.website" target="_blank" rel="noopener noreferrer" class="tech-link">
              {{ isEn ? 'Website ↗' : '机构网站 ↗' }}
            </a>
          </div>
        </article>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="BOUNDARY"
        :title="isEn ? 'What we do, what we never do' : '业务边界：能做什么，绝不做什么'"
        :desc="isEn
          ? 'Every service line has a hard boundary. This table is the single source of truth for product copy, sales scripts and admin review.'
          : '每条服务线都有硬边界。下表是产品文案、销售话术与后台审核的统一依据。'"
      />
      <a-spin :spinning="boundaryLoading">
        <div class="boundary-table">
          <div class="boundary-row boundary-head">
            <span>{{ isEn ? 'Service line' : '服务线' }}</span>
            <span>{{ isEn ? 'We do' : '平台可做' }}</span>
            <span>{{ isEn ? 'We never do' : '绝不触碰' }}</span>
            <span>{{ isEn ? 'Partner / qualification' : '资质 / 合作方' }}</span>
          </div>
          <div v-for="item in boundaryItems" :key="item.module" class="boundary-row">
            <span class="module">{{ item.module }}</span>
            <span class="allow">{{ item.allowed }}</span>
            <span class="deny">{{ item.forbidden }}</span>
            <span class="partner">{{ item.partner }}<em v-if="item.qualification"> · {{ item.qualification }}</em></span>
          </div>
        </div>
        <div v-if="boundaryNotice" class="tech-note tech-mt">
          <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
          <div>
            <strong>{{ isEn ? 'Compliance notice' : '合规声明' }}</strong>
            <span>{{ boundaryNotice }}</span>
          </div>
        </div>
      </a-spin>
    </section>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { BookOpen, ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const { isEn } = useTechLang()

const loading = ref(false)
const policies = ref([])
const organizations = ref([])
const region = ref('ALL')
const boundaryLoading = ref(false)
const boundaryItems = ref([])
const boundaryNotice = ref('')

const regionOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'National' : '全国', value: '全国' },
  { label: isEn.value ? 'Shanghai' : '上海', value: '上海' },
  { label: isEn.value ? 'Hangzhou' : '杭州', value: '杭州' }
])
const filteredPolicies = computed(() =>
  region.value === 'ALL' ? policies.value : policies.value.filter((p) => p.region === region.value))

function typeLabel(type) {
  const map = {
    EXCHANGE: isEn.value ? 'Exchange venue' : '技术交易场所',
    EVALUATION: isEn.value ? 'Evaluation' : '成果评价机构',
    AGENCY: isEn.value ? 'IP agency' : '知识产权服务机构',
    SAAS: isEn.value ? 'SaaS provider' : 'SaaS 服务商',
    UNIVERSITY: isEn.value ? 'University TTO' : '高校技术转移机构',
    OTHER: isEn.value ? 'Public service' : '公共服务机构'
  }
  return map[type] || type
}

async function load() {
  loading.value = true
  try {
    const [p, o] = await Promise.all([techApi.policies(), techApi.organizations()])
    policies.value = p || []
    organizations.value = o || []
  } catch (e) {
    policies.value = []
    organizations.value = []
  } finally {
    loading.value = false
  }
}

async function loadBoundary() {
  boundaryLoading.value = true
  try {
    const data = await techApi.compliance()
    boundaryItems.value = data?.items || []
    boundaryNotice.value = data?.notice || ''
  } catch (e) {
    boundaryItems.value = []
  } finally {
    boundaryLoading.value = false
  }
}

onMounted(() => Promise.all([load(), loadBoundary()]))
</script>

<style scoped>
.policy-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; }
.org-identity { display: flex; align-items: center; gap: 10px; }
.org-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  color: #fff;
  background: #1E6EB8;
  font-size: 12px;
  font-weight: 700;
  flex: 0 0 auto;
}
.boundary-table {
  border: 1px solid rgba(15, 23, 42, 0.07);
  border-radius: 18px;
  overflow: hidden;
  background: #fff;
}
.boundary-row {
  display: grid;
  grid-template-columns: 1.1fr 1.4fr 1.4fr 1.2fr;
  gap: 14px;
  padding: 14px 18px;
  border-top: 1px solid rgba(15, 23, 42, 0.06);
  font-size: 12.5px;
  line-height: 1.75;
}
.boundary-row:first-child { border-top: 0; }
.boundary-head {
  background: rgba(15, 23, 42, 0.04);
  color: var(--ll-muted);
  font-weight: 700;
  font-size: 12px;
  letter-spacing: 0.02em;
}
.boundary-row .module { font-weight: 700; }
.boundary-row .allow { color: #047857; }
.boundary-row .deny { color: #9b2d20; }
.boundary-row .partner { color: var(--ll-muted); }
.boundary-row em { font-style: normal; color: #92400e; }
@media (max-width: 900px) {
  .boundary-row { grid-template-columns: 1fr; gap: 6px; }
  .boundary-head { display: none; }
  .boundary-row span::before {
    display: block;
    color: var(--ll-muted);
    font-size: 11px;
    font-weight: 600;
  }
}
html[data-theme='dark'] .boundary-table { background: #111827; border-color: rgba(255,255,255,.08); }
html[data-theme='dark'] .boundary-row { border-top-color: rgba(255,255,255,.06); }
html[data-theme='dark'] .boundary-head { background: rgba(255,255,255,.04); }
</style>
