<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">OPEN LICENSE · 开放许可专区</span>
        <h1>让专利先被用起来，再谈收益。</h1>
        <p>
          专区公示开放许可专利与许可条件，提供声明要点、意向书、计价说明与备案材料模板。
          平台只做信息公示、材料模板与流程陪跑；备案由具备资质的知识产权服务机构代办，平台不出具备案证明。
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="go('/tech/demands')">提交实施意向</button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/achievements')">浏览成果库</button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="OPEN LICENSE PATENTS"
        title="开放许可专利"
        desc="许可方式为开放许可、已上架的专利成果；许可费与条件以权利人声明为准，平台不代为定价。"
      >
        <template #action>
          <button type="button" class="tech-link" @click="reload">刷新 →</button>
        </template>
      </TechSectionHead>
      <a-spin :spinning="loading">
        <div v-if="kits.length" class="tech-grid">
          <article v-for="k in kits" :key="k.achievementId" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip green">开放许可</span>
              <span class="tech-chip" :class="k.verified ? 'cyan' : 'amber'">{{ k.verified ? '已核验' : '待核验' }}</span>
            </div>
            <h3>{{ k.title }}</h3>
            <p>{{ k.summary }}</p>
            <div class="tech-card-meta">
              <span v-if="k.patentNo">专利号 {{ k.patentNo }}</span>
              <span>{{ k.industry || '—' }}</span>
              <span>{{ k.techField || '—' }}</span>
            </div>
            <div class="tech-card-foot">
              <span class="tech-price">{{ priceText(k) }}</span>
              <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="go('/tech/demands')">提交实施意向</button>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <b>暂无开放许可专利</b>
          <span>权利人可在成果库将许可方式设为开放许可，材料包模板可先行下载参考。</span>
        </div>
      </a-spin>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="MATERIAL KIT"
        title="开放许可材料包"
        desc="以下模板由平台提供，填写后可由合作机构核验并代办备案；平台不代替行政机关受理。"
      />
      <div class="tech-bento">
        <article class="tech-card span-2">
          <div class="tech-card-top"><span class="tech-chip indigo">材料清单</span></div>
          <h3>五份材料，一次备齐</h3>
          <ul class="plain-list">
            <li v-for="doc in documents" :key="doc">{{ doc }}</li>
          </ul>
        </article>
        <article class="tech-card tint">
          <div class="tech-card-top"><span class="tech-chip cyan">办理流程</span></div>
          <h3>从声明到备案</h3>
          <ol class="plain-list ordered">
            <li v-for="step in process" :key="step">{{ step }}</li>
          </ol>
        </article>
      </div>
      <div class="tech-note">
        <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>与政府公共服务的分工</strong>
          <span>{{ complianceNote }}</span>
        </div>
      </div>
    </section>
  </TechPageShell>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'

const router = useRouter()

const kits = ref([])
const loading = ref(false)

const fallbackDocuments = [
  '专利开放许可声明（要点模板）',
  '许可意向书（被许可方填写）',
  '许可费计价说明（一次总付 / 提成 / 入门费+提成）',
  '实施情况与改进说明（年度更新）',
  '备案材料清单（由合作机构核验代办）'
]
const fallbackProcess = [
  '权利人声明开放许可条件与许可费标准',
  '平台专区公示，意向方在线提交实施计划',
  '双方确认许可范围、期限、地域与费用',
  '签署许可合同并完成平台存证',
  '合作机构代办备案，平台跟踪实施反馈'
]
const fallbackNote = '平台只做信息公示、材料模板与流程陪跑；开放许可备案由具备资质的知识产权服务机构代办，平台不出具备案证明，也不承诺许可成交结果。'

const documents = ref(fallbackDocuments)
const process = ref(fallbackProcess)
const complianceNote = ref(fallbackNote)

function priceText(k) {
  const mode = { QUOTE: '按需报价', FIXED: '固定价', NEGOTIABLE: '面议', FREE: '免费' }[k.priceMode] || k.priceMode
  if (!k.expectedPrice) return mode
  return `参考许可费 ¥${Number(k.expectedPrice).toLocaleString()} · ${mode}`
}
function go(path) {
  router.push(path)
}

async function reload() {
  loading.value = true
  try {
    kits.value = (await techApi.openLicense()) || []
    if (kits.value.length) {
      documents.value = kits.value[0].documents?.length ? kits.value[0].documents : fallbackDocuments
      process.value = kits.value[0].process?.length ? kits.value[0].process : fallbackProcess
      complianceNote.value = kits.value[0].complianceNote || fallbackNote
    }
  } catch (e) {
    kits.value = []
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.plain-list { margin: 12px 0 0; padding-left: 18px; color: var(--ll-muted); line-height: 2; font-size: 13px; }
.plain-list.ordered { list-style: decimal; }
</style>
