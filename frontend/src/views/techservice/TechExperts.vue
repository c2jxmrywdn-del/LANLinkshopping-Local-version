<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">EXPERT NETWORK · INVITE ONLY</span>
        <h1>{{ isEn ? 'Experts and brokers, reviewed before listing.' : '定向邀请的专家与技术经纪人。' }}</h1>
        <p>
          {{ isEn
            ? 'Experts provide technical judgement; brokers own the delivery path. Every profile is reviewed before it appears in the pool.'
            : '专家负责技术判断，技术经纪人负责交付路径。所有入驻资料经平台核验后展示，避免“只看头衔、不看交付”。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="go('/tech/workspace')">
            {{ isEn ? 'My projects' : '我的陪跑项目' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/demands')">
            {{ isEn ? 'Post a demand' : '发布技术需求' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="RESOURCE POOL"
        :title="isEn ? 'Find the right person for the next step' : '找到能把下一步走对的人'"
        :desc="isEn
          ? 'Filter by role, industry and keyword. Consultation booking creates a tracked project and an in-site message.'
          : '按角色、行业与关键词筛选；预约咨询会生成可跟踪的陪跑项目与站内消息。'"
      />
      <div class="tech-toolbar">
        <SegmentedControl v-model="type" :options="typeOptions" :aria-label="isEn ? 'Role filter' : '角色筛选'" />
        <a-input
          v-model:value="keyword"
          class="grow"
          allow-clear
          :placeholder="isEn ? 'Search name, domain or organization' : '搜索姓名、技术领域或所属机构'"
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
        <div v-if="experts.length" class="tech-grid">
          <TechExpertCard
            v-for="expert in experts"
            :key="expert.expertId"
            :expert="expert"
            @view="openExpert(expert)"
            @consult="openConsult(expert)"
          />
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <Users :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No matching experts' : '暂无匹配专家' }}</b>
          <span>{{ isEn ? 'Try a broader keyword or another industry.' : '试试更宽泛的关键词，或切换行业。' }}</span>
        </div>
      </a-spin>

      <div v-if="total > size" class="tech-pagination">
        <a-pagination
          v-model:current="page"
          :total="total"
          :page-size="size"
          :show-size-changer="false"
          @change="reload"
        />
      </div>
    </section>

    <a-drawer v-model:open="expertOpen" :width="'min(560px, 94vw)'" :title="expertDetail?.expert?.name || (isEn ? 'Expert profile' : '专家详情')">
      <div v-if="expertDetail" class="tech-drawer-body">
        <h3>
          {{ expertDetail.expert.name }}
          <BadgeCheck v-if="expertDetail.expert.verified" :size="17" :stroke-width="2.2" style="color: var(--ll-primary)" />
        </h3>
        <p class="sub">{{ expertDetail.expert.title }} · {{ expertDetail.expert.organization }}</p>
        <div class="tech-row tech-mt">
          <span v-for="tag in splitTags(expertDetail.expert)" :key="tag" class="tech-chip">{{ tag }}</span>
        </div>
        <dl class="tech-kv">
          <dt>{{ isEn ? 'Rating' : '服务评分' }}</dt>
          <dd>{{ expertDetail.expert.rating }} / 5.0</dd>
          <dt>{{ isEn ? 'Response' : '平均响应' }}</dt>
          <dd>{{ expertDetail.expert.responseHours }} {{ isEn ? 'hours' : '小时' }}</dd>
          <dt>{{ isEn ? 'Services' : '服务次数' }}</dt>
          <dd>{{ expertDetail.expert.serviceCount }}</dd>
          <dt>{{ isEn ? 'Completed consults' : '已完成咨询' }}</dt>
          <dd>{{ expertDetail.completedConsultations || 0 }}</dd>
          <dt>{{ isEn ? 'Consult fee' : '咨询参考价' }}</dt>
          <dd>¥{{ expertDetail.expert.consultFee }} / {{ isEn ? 'session' : '次' }}</dd>
        </dl>
        <h4>{{ isEn ? 'Profile' : '个人简介' }}</h4>
        <p class="sub">{{ expertDetail.expert.bio }}</p>
        <h4>{{ isEn ? 'Credentials' : '资质与履历' }}</h4>
        <p class="sub">{{ expertDetail.expert.credentials }}</p>
        <h4>{{ isEn ? 'Service products' : '服务产品' }}</h4>
        <div v-if="expertDetail.services?.length" class="tech-stack tech-mt">
          <div v-for="service in expertDetail.services" :key="service.serviceId" class="tech-card">
            <div class="tech-card-top">
              <b>{{ service.title }}</b>
              <span class="tech-chip cyan">{{ service.priceMode }}</span>
            </div>
            <p class="tech-mt">{{ service.subtitle }}</p>
            <div class="tech-card-foot">
              <span class="tech-price">¥{{ service.price }}<small>/{{ isEn ? 'session' : '次' }}</small></span>
              <span class="tech-muted">{{ service.durationDays }} {{ isEn ? 'days' : '天' }}</span>
            </div>
          </div>
        </div>
        <p v-else class="tech-muted">{{ isEn ? 'No public service product yet.' : '暂未公开服务产品。' }}</p>
        <button type="button" class="tech-btn tech-btn-primary tech-mt" @click="openConsult(expertDetail.expert)">
          {{ isEn ? 'Book a consultation' : '预约咨询' }}
        </button>
      </div>
    </a-drawer>

    <a-modal
      v-model:open="consultOpen"
      :title="isEn ? 'Book a consultation' : '预约技术咨询'"
      :ok-text="isEn ? 'Submit' : '提交预约'"
      :cancel-text="isEn ? 'Cancel' : '取消'"
      :confirm-loading="submitting"
      width="min(560px, 94vw)"
      @ok="submitConsult"
    >
      <a-form layout="vertical">
        <a-form-item :label="isEn ? 'Expert' : '咨询对象'">
          <a-input :value="consultExpert?.name" disabled />
        </a-form-item>
        <a-form-item :label="isEn ? 'Topic' : '咨询主题'" required>
          <a-input v-model:value="consultForm.topic" :placeholder="isEn ? 'What decision are you trying to make?' : '您希望解决什么技术决策问题？'" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Context' : '背景说明'">
          <a-textarea v-model:value="consultForm.description" :rows="3" :placeholder="isEn ? 'Current process, constraints, expected outcome...' : '现有工艺、约束条件、期望结果……'" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Mode' : '咨询方式'">
          <a-select v-model:value="consultForm.mode" :options="modeOptions" />
        </a-form-item>
      </a-form>
      <div class="tech-note">
        <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
        <div>
          <strong>{{ isEn ? 'Demo environment' : '演示环境说明' }}</strong>
          <span v-if="isEn">
            Submitting creates a consultation record, a tracked project and an in-site message. No real payment is
            collected; the final schedule is confirmed by the expert or broker.
          </span>
          <span v-else>
            提交后会生成咨询记录、陪跑项目与站内消息，不进行真实支付；最终时间由专家或技术经纪人确认。
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
import { BadgeCheck, ShieldCheck, Users } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import TechExpertCard from '../../components/techservice/TechExpertCard.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const loading = ref(true)
const experts = ref([])
const total = ref(0)
const page = ref(1)
const size = 9
const type = ref('ALL')
const industry = ref(undefined)
const keyword = ref('')

const typeOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'Experts' : '专家', value: 'EXPERT' },
  { label: isEn.value ? 'Brokers' : '技术经纪人', value: 'BROKER' }
])
const industryOptions = computed(() => ['智能制造', '新材料', '电子信息', '新能源', '绿色低碳']
  .map((v) => ({ label: v, value: v })))
const modeOptions = computed(() => [
  { label: isEn.value ? 'Video' : '视频', value: 'VIDEO' },
  { label: isEn.value ? 'Phone' : '电话', value: 'PHONE' },
  { label: isEn.value ? 'Offline' : '线下', value: 'OFFLINE' }
])

const expertOpen = ref(false)
const expertDetail = ref(null)

const consultOpen = ref(false)
const consultExpert = ref(null)
const submitting = ref(false)
const consultForm = reactive({ topic: '', description: '', mode: 'VIDEO' })

function splitTags(expert) {
  return `${expert.industries || ''},${expert.domains || ''}`
    .split(',').map((s) => s.trim()).filter(Boolean).slice(0, 4)
}

async function reload(p = 1) {
  page.value = p
  loading.value = true
  try {
    const res = await techApi.experts({
      page: p,
      size,
      type: type.value === 'ALL' ? undefined : type.value,
      industry: industry.value || undefined,
      keyword: keyword.value || undefined
    })
    experts.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    experts.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openExpert(expert) {
  try {
    expertDetail.value = await techApi.expert(expert.expertId)
    expertOpen.value = true
    if (String(route.query.consult || '') === '1') {
      openConsult(expertDetail.value.expert)
      router.replace({ path: route.path, query: {} })
    }
  } catch (e) {
    expertDetail.value = null
  }
}

function openConsult(expert) {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  consultExpert.value = expert
  consultForm.topic = isEn.value ? `Technical consultation with ${expert.name}` : `与 ${expert.name} 的技术咨询`
  consultForm.description = ''
  consultForm.mode = 'VIDEO'
  consultOpen.value = true
}

async function submitConsult() {
  if (!consultForm.topic.trim()) {
    message.warning(isEn.value ? 'Please enter a topic' : '请填写咨询主题')
    return
  }
  submitting.value = true
  try {
    await techApi.createConsultation({
      expertId: consultExpert.value.expertId,
      topic: consultForm.topic,
      description: consultForm.description,
      mode: consultForm.mode
    })
    message.success(isEn.value ? 'Consultation submitted' : '咨询预约已提交，已生成陪跑项目')
    consultOpen.value = false
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

watch([type, industry], () => reload(1))

onMounted(async () => {
  await reload(1)
  const expertId = route.query.expert
  if (expertId) {
    await openExpert({ expertId })
  }
})
</script>

<style scoped>
.tech-pagination { display: flex; justify-content: center; margin-top: 22px; }
.tech-stack { display: flex; flex-direction: column; gap: 12px; }
.tech-drawer-body h4 { margin: 22px 0 6px; font-size: 14px; }
</style>
