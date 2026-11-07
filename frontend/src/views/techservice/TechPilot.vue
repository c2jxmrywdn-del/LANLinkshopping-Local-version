<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">PILOT & VERIFICATION · 概念验证 / 中试</span>
        <h1>先验证，再放大。</h1>
        <p>
          汇聚概念验证、中试线与共享实验室资源，支持按工艺、设备与区域筛选并在线预约。
          验证与中试由机构线下实施并出具结果，平台只做资源展示与预约撮合，不承诺验证结论。
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="scrollToList">浏览资源</button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/open-license')">开放许可专区</button>
        </div>
      </div>
    </section>

    <section id="tech-pilot-list" class="tech-section">
      <TechSectionHead
        eyebrow="RESOURCE LIBRARY"
        title="中试与验证资源库"
        desc="资源由合作机构提交并经运营核验后上架；设备清单、能力范围与计价方式公开可查。"
      >
        <template #action>
          <div class="filters">
            <a-select v-model:value="type" style="width: 150px" :options="typeOptions" @change="reload" />
            <a-select v-model:value="region" allow-clear placeholder="全部区域" style="width: 130px" :options="regionOptions" @change="reload" />
          </div>
        </template>
      </TechSectionHead>
      <a-spin :spinning="loading">
        <div v-if="resources.length" class="tech-grid">
          <article v-for="r in resources" :key="r.resourceId" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip cyan">{{ typeLabel(r.resourceType) }}</span>
              <span class="tech-chip">{{ r.region || '长三角' }}</span>
            </div>
            <h3>{{ r.name }}</h3>
            <p>{{ r.capabilities || '能力范围待机构补充。' }}</p>
            <div class="tech-card-meta">
              <span>{{ r.orgName }}</span>
              <span v-if="r.equipment">设备：{{ r.equipment }}</span>
            </div>
            <div class="tech-card-foot">
              <span class="tech-price">{{ priceText(r) }}</span>
              <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="openBooking(r)">预约验证</button>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <b>暂无匹配的中试资源</b>
          <span>调整筛选条件，或通过需求大厅提交验证需求由平台匹配。</span>
          <button type="button" class="tech-btn tech-btn-outline tech-mt" @click="go('/tech/demands')">提交需求</button>
        </div>
      </a-spin>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="MY BOOKINGS"
        title="我的预约"
        desc="预约提交后由运营与合作机构确认排期；如涉及陪跑项目，可关联项目统一跟踪。"
      >
        <template #action>
          <button v-if="user.logged" type="button" class="tech-link" @click="loadBookings">刷新 →</button>
        </template>
      </TechSectionHead>
      <a-spin :spinning="bookingLoading">
        <div v-if="myBookings.length" class="tech-grid">
          <article v-for="b in myBookings" :key="b.bookingNo" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip indigo">{{ b.bookingNo }}</span>
              <span class="tech-chip" :class="bookingTone(b.status)">{{ bookingLabel(b.status) }}</span>
            </div>
            <h3>{{ b.resourceName || ('资源 #' + b.resourceId) }}</h3>
            <p>{{ b.requirement }}</p>
            <div class="tech-card-meta">
              <span v-if="b.scheduledDate">期望日期 {{ b.scheduledDate }}</span>
              <span v-if="b.projectId">关联项目 #{{ b.projectId }}</span>
            </div>
            <div v-if="b.adminNote" class="tech-card-meta">
              <span>机构反馈：{{ b.adminNote }}</span>
            </div>
          </article>
        </div>
        <div v-else-if="!bookingLoading" class="tech-empty">
          <b>{{ user.logged ? '还没有预约记录' : '登录后查看预约' }}</b>
          <span>选择资源并提交验证需求，即可跟踪排期与机构反馈。</span>
        </div>
      </a-spin>
    </section>

    <a-modal v-model:open="bookingOpen" title="预约中试 / 验证资源" :confirm-loading="saving" ok-text="提交预约" cancel-text="取消" @ok="submitBooking">
      <a-form layout="vertical">
        <a-form-item label="资源">
          <a-input :value="target ? `${target.name} · ${target.orgName}` : ''" disabled />
        </a-form-item>
        <a-form-item label="验证需求" required>
          <a-textarea
            v-model:value="bookingForm.requirement"
            :rows="4"
            :maxlength="1000"
            show-count
            placeholder="说明样品、工艺条件、期望验证指标与交付形式"
          />
        </a-form-item>
        <div class="tech-form-grid">
          <a-form-item label="期望日期">
            <a-input v-model:value="bookingForm.scheduledDate" placeholder="YYYY-MM-DD，可留空" />
          </a-form-item>
          <a-form-item label="关联陪跑项目">
            <a-select v-model:value="bookingForm.projectId" allow-clear :options="projectOptions" placeholder="可不选" />
          </a-form-item>
        </div>
        <a-alert
          type="warning"
          show-icon
          message="预约仅为意向登记，费用、周期与结果由机构评估后另行确认；平台不承诺验证通过或中试成功。"
        />
      </a-form>
    </a-modal>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const resources = ref([])
const loading = ref(false)
const type = ref('')
const region = ref(undefined)

const bookingOpen = ref(false)
const saving = ref(false)
const target = ref(null)
const bookingForm = reactive({ requirement: '', scheduledDate: '', projectId: undefined })
const myBookings = ref([])
const bookingLoading = ref(false)
const projects = ref([])

const typeOptions = [
  { label: '全部类型', value: '' },
  { label: '中试线', value: 'PILOT' },
  { label: '概念验证', value: 'VERIFICATION' },
  { label: '共享实验室', value: 'LAB' }
]
const regionOptions = computed(() => {
  const set = new Set(resources.value.map((r) => r.region).filter(Boolean))
  return [...set].map((r) => ({ label: r, value: r }))
})
const projectOptions = computed(() => projects.value.map((p) => ({
  label: `${p.projectNo} · ${p.title}`,
  value: p.projectId
})))

function typeLabel(t) {
  return { PILOT: '中试线', VERIFICATION: '概念验证', LAB: '共享实验室' }[t] || t
}
function bookingLabel(s) {
  return { APPLIED: '待确认', ACCEPTED: '已排期', COMPLETED: '已完成', REJECTED: '已拒绝' }[s] || s
}
function bookingTone(s) {
  return { APPLIED: 'amber', ACCEPTED: 'green', COMPLETED: 'indigo', REJECTED: 'red' }[s] || ''
}
function priceText(r) {
  const mode = { QUOTE: '按需报价', FIXED: '固定价', NEGOTIABLE: '面议', FREE: '免费' }[r.priceMode] || r.priceMode
  if (!r.price) return mode
  return `¥${Number(r.price).toLocaleString()} · ${mode}`
}
function go(path) {
  router.push(path)
}
function scrollToList() {
  document.getElementById('tech-pilot-list')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function reload() {
  loading.value = true
  try {
    resources.value = (await techApi.pilotResources({
      type: type.value || undefined,
      region: region.value || undefined
    })) || []
  } catch (e) {
    resources.value = []
  } finally {
    loading.value = false
  }
}

async function loadBookings() {
  if (!user.logged) return
  bookingLoading.value = true
  try {
    myBookings.value = (await techApi.myPilotBookings()) || []
  } catch (e) {
    myBookings.value = []
  } finally {
    bookingLoading.value = false
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

function openBooking(resource) {
  if (!user.logged) {
    message.info('登录后可提交预约')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  target.value = resource
  bookingForm.requirement = ''
  bookingForm.scheduledDate = ''
  bookingForm.projectId = undefined
  bookingOpen.value = true
}

async function submitBooking() {
  if (!target.value) return
  if (!bookingForm.requirement.trim()) {
    message.warning('请填写验证需求')
    return
  }
  saving.value = true
  try {
    await techApi.createPilotBooking({
      resourceId: target.value.resourceId,
      requirement: bookingForm.requirement.trim(),
      scheduledDate: bookingForm.scheduledDate || null,
      projectId: bookingForm.projectId || null
    })
    message.success('预约已提交，等待机构确认')
    bookingOpen.value = false
    await loadBookings()
  } catch (e) {
    // request wrapper 已提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([reload(), loadBookings(), loadProjects()])
})
</script>

<style scoped>
.filters { display: flex; align-items: center; gap: 8px; }
</style>
