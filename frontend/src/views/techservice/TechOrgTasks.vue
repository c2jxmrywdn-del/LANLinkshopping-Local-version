<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">ORG WORKBENCH · 机构工作台</span>
        <h1>机构交付任务，一处接收、一处提交。</h1>
        <p>
          平台把评价、代理与交付任务派发到机构工作台；机构提交交付说明与过程材料，平台验收后进入履约评价。
          验收结论由合作机构出具，平台只做过程留痕与交付验收。
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="reload">刷新任务</button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/contracts')">合同与结算</button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="MY TASKS"
        title="我的机构任务"
        desc="仅展示分配给当前账号的任务；提交后由平台运营验收，驳回可补充材料后重新提交。"
      >
        <template #action>
          <button type="button" class="tech-link" @click="reload">刷新 →</button>
        </template>
      </TechSectionHead>
      <a-spin :spinning="loading">
        <div v-if="tasks.length" class="tech-grid">
          <article v-for="t in tasks" :key="t.taskNo" class="tech-card">
            <div class="tech-card-top">
              <span class="tech-chip cyan">{{ taskTypeLabel(t.taskType) }}</span>
              <span class="tech-chip" :class="statusTone(t.status)">{{ statusLabel(t.status) }}</span>
            </div>
            <h3>{{ t.title }}</h3>
            <p>{{ t.scope || '未填写任务范围，按平台派单说明执行。' }}</p>
            <div class="tech-card-meta">
              <span>{{ t.taskNo }}</span>
              <span v-if="t.projectId">项目 #{{ t.projectId }}</span>
              <span v-if="t.dueDate">截止 {{ t.dueDate }}</span>
            </div>
            <div v-if="t.deliverableText" class="tech-note">
              <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
              <div>
                <strong>已提交交付说明</strong>
                <span>{{ t.deliverableText }}</span>
                <span class="mono">存证 {{ (t.deliverableHash || '').slice(0, 16) }}…</span>
              </div>
            </div>
            <div v-if="t.reviewNote" class="tech-card-meta">
              <span>验收意见：{{ t.reviewNote }}</span>
            </div>
            <div class="tech-card-foot">
              <span class="tech-muted">{{ t.submittedAt ? ('提交于 ' + shortTime(t.submittedAt)) : '待提交' }}</span>
              <button
                v-if="canSubmit(t.status)"
                type="button"
                class="tech-btn tech-btn-primary tech-btn-sm"
                @click="openSubmit(t)"
              >
                {{ t.status === 'REJECTED' ? '补充并重新提交' : '提交交付物' }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <b>暂无机构任务</b>
          <span>运营派单后会出现在这里；如长时间未收到任务，请联系平台运营。</span>
        </div>
      </a-spin>
    </section>

    <a-modal v-model:open="submitOpen" title="提交交付物" :confirm-loading="saving" ok-text="提交" cancel-text="取消" @ok="submitDeliverable">
      <a-form layout="vertical">
        <a-form-item label="任务">
          <a-input :value="target ? `${target.taskNo} · ${target.title}` : ''" disabled />
        </a-form-item>
        <a-form-item label="交付说明" required>
          <a-textarea
            v-model:value="deliverableText"
            :rows="5"
            :maxlength="2000"
            show-count
            placeholder="说明完成内容、交付物位置、关键结论与待确认事项"
          />
        </a-form-item>
        <a-alert type="info" show-icon message="提交后会生成内容哈希用于过程留痕；平台验收不代表对结论的行政认可。" />
      </a-form>
    </a-modal>
  </TechPageShell>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ShieldCheck } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'

const router = useRouter()
const user = useUserStore()

const tasks = ref([])
const loading = ref(false)
const saving = ref(false)
const submitOpen = ref(false)
const target = ref(null)
const deliverableText = ref('')

function taskTypeLabel(type) {
  return { EVALUATION: '评价协作', DELIVERY: '交付执行', AGENCY: '代理服务', APPRAISAL: '成果评价' }[type] || type
}
function statusLabel(status) {
  return {
    ASSIGNED: '待接收',
    IN_PROGRESS: '进行中',
    SUBMITTED: '已提交待验收',
    ACCEPTED: '已验收',
    REJECTED: '已驳回'
  }[status] || status
}
function statusTone(status) {
  return { ASSIGNED: 'amber', IN_PROGRESS: 'indigo', SUBMITTED: 'cyan', ACCEPTED: 'green', REJECTED: 'red' }[status] || ''
}
function canSubmit(status) {
  return ['ASSIGNED', 'IN_PROGRESS', 'REJECTED'].includes(status)
}
function shortTime(v) {
  return String(v || '').replace('T', ' ').slice(0, 16)
}
function go(path) {
  router.push(path)
}

async function reload() {
  if (!user.logged) return
  loading.value = true
  try {
    tasks.value = (await techApi.myOrgTasks()) || []
  } catch (e) {
    tasks.value = []
  } finally {
    loading.value = false
  }
}

function openSubmit(task) {
  target.value = task
  deliverableText.value = ''
  submitOpen.value = true
}

async function submitDeliverable() {
  if (!target.value) return
  if (!deliverableText.value.trim()) {
    message.warning('请填写交付说明')
    return
  }
  saving.value = true
  try {
    await techApi.submitOrgTask(target.value.taskNo, { deliverableText: deliverableText.value.trim() })
    message.success('交付物已提交，等待平台验收')
    submitOpen.value = false
    await reload()
  } catch (e) {
    // request wrapper 已提示
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
</style>
