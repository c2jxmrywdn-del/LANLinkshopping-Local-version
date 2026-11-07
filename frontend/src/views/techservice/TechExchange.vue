<template>
  <TechPageShell>
    <section class="tech-hero tech-hero-sm" data-reveal="blur-up">
      <div class="tech-hero-grid" aria-hidden="true"></div>
      <div class="tech-hero-copy">
        <span class="tech-eyebrow">TECH EXCHANGE · 技术交流</span>
        <h1>{{ isEn ? 'Where technology language gets translated.' : '把技术语言，翻译成生意语言。' }}</h1>
        <p>
          {{ isEn
            ? 'Practitioner notes, real questions and open discussions from universities, manufacturers and technology brokers. Business data stays in its original language.'
            : '来自高校、制造企业与技术经纪人的实战笔记、真实提问与开放讨论；业务数据保持原文，不做机器改写。' }}
        </p>
        <div class="tech-hero-actions">
          <button type="button" class="tech-btn tech-btn-primary" @click="openPublish">
            <PenLine :size="16" :stroke-width="2.2" /> {{ isEn ? 'Share a note' : '发布技术笔记' }}
          </button>
          <button type="button" class="tech-btn tech-btn-ghost" @click="go('/tech/experts')">
            <Users :size="16" :stroke-width="2.2" /> {{ isEn ? 'Ask an expert' : '向专家提问' }}
          </button>
        </div>
      </div>
    </section>

    <section class="tech-section">
      <TechSectionHead
        eyebrow="COMMUNITY"
        :title="isEn ? 'Notes, questions and discussions' : '笔记、问答与讨论'"
        :desc="isEn
          ? 'Exchange is for practical experience sharing. It is not a channel for official endorsement or transaction guarantees.'
          : '技术交流用于经验分享，不作为官方背书或成交担保的渠道。'"
      >
        <template #action>
          <SegmentedControl v-model="activeType" :options="typeOptions" :aria-label="isEn ? 'Content type' : '内容类型'" />
        </template>
      </TechSectionHead>

      <div class="tech-toolbar">
        <a-input
          v-model:value="keyword"
          class="grow"
          allow-clear
          :placeholder="isEn ? 'Search title, tag or keyword' : '搜索标题、标签或关键词'"
          @press-enter="reload(1)"
        >
          <template #prefix><Search :size="15" :stroke-width="2" /></template>
        </a-input>
        <a-select v-model:value="industry" allow-clear :placeholder="isEn ? 'Industry' : '行业'" :options="industryOptions" style="width: 150px" @change="reload(1)" />
        <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="reload(1)">{{ isEn ? 'Search' : '搜索' }}</button>
      </div>

      <a-spin :spinning="loading">
        <div v-if="posts.length" class="tech-grid">
          <article v-for="post in posts" :key="post.postId" class="tech-card post-card" @click="openDetail(post)">
            <div class="tech-card-top">
              <span class="tech-chip" :class="typeTone(post.postType)">{{ typeLabel(post.postType) }}</span>
              <span class="tech-muted tech-num">{{ post.viewCount || 0 }} {{ isEn ? 'views' : '阅读' }}</span>
            </div>
            <h3>{{ post.title }}</h3>
            <p class="clamp-2">{{ post.content }}</p>
            <div class="tech-card-meta">
              <span>{{ post.authorName }}</span>
              <span v-if="post.industry">{{ post.industry }}</span>
              <span v-if="post.createTime">{{ shortDate(post.createTime) }}</span>
            </div>
            <div class="tech-card-foot">
              <div class="tech-row">
                <span v-for="tag in splitTags(post.tags)" :key="tag" class="tech-chip">{{ tag }}</span>
              </div>
              <button type="button" class="tech-link" @click.stop="like(post)">
                <Heart :size="14" :stroke-width="2" /> {{ post.likeCount || 0 }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!loading" class="tech-empty">
          <MessageSquare :size="42" :stroke-width="1.4" aria-hidden="true" />
          <b>{{ isEn ? 'No notes yet' : '还没有内容' }}</b>
          <span>{{ isEn ? 'Publish the first practitioner note.' : '发布第一篇实战笔记，或换个关键词试试。' }}</span>
        </div>
      </a-spin>

      <div v-if="total > size" class="tech-pager">
        <a-pagination
          :current="page"
          :page-size="size"
          :total="Number(total)"
          :show-size-changer="false"
          size="small"
          @change="reload"
        />
      </div>
    </section>

    <a-modal
      v-model:open="publishOpen"
      :title="isEn ? 'Share a note' : '发布技术笔记'"
      :ok-text="isEn ? 'Publish' : '发布'"
      :cancel-text="isEn ? 'Cancel' : '取消'"
      :confirm-loading="publishing"
      width="640px"
      @ok="submitPost"
    >
      <a-form layout="vertical">
        <a-form-item :label="isEn ? 'Type' : '内容类型'" required>
          <a-select v-model:value="form.postType" :options="typeOptions" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Title' : '标题'" required>
          <a-input v-model:value="form.title" :maxlength="120" show-count :placeholder="isEn ? 'One sentence that states the practical value' : '一句话说清这篇内容能解决什么问题'" />
        </a-form-item>
        <a-form-item :label="isEn ? 'Body' : '正文'" required>
          <a-textarea v-model:value="form.content" :rows="6" :maxlength="2000" show-count :placeholder="isEn ? 'Context, evidence, constraints and what you learned' : '背景、证据、约束条件与你的结论'" />
        </a-form-item>
        <div class="tech-form-grid">
          <a-form-item :label="isEn ? 'Industry' : '行业'">
            <a-select v-model:value="form.industry" allow-clear :options="industryOptions" :placeholder="isEn ? 'Optional' : '可不填'" />
          </a-form-item>
          <a-form-item :label="isEn ? 'Tags' : '标签'">
            <a-input v-model:value="form.tags" :placeholder="isEn ? 'Comma separated, up to 5' : '逗号分隔，最多 5 个'" />
          </a-form-item>
        </div>
        <div class="tech-note">
          <ShieldCheck :size="18" :stroke-width="1.9" aria-hidden="true" />
          <div>
            <strong>{{ isEn ? 'Content boundary' : '内容边界' }}</strong>
            <span>{{ isEn
              ? 'Do not use government endorsement, guaranteed deal or guaranteed grant language. Violating content will be rejected.'
              : '请勿使用「政府指定」「官方背书」「保证成交」「包授权」等表述，命中会被系统拒绝发布。' }}</span>
          </div>
        </div>
      </a-form>
    </a-modal>

    <a-drawer v-model:open="detailOpen" :width="drawerWidth" :title="isEn ? 'Note detail' : '内容详情'" placement="right">
      <div v-if="current" class="tech-drawer-body">
        <div class="tech-row">
          <span class="tech-chip" :class="typeTone(current.postType)">{{ typeLabel(current.postType) }}</span>
          <span class="tech-chip" v-if="current.industry">{{ current.industry }}</span>
        </div>
        <h3 class="tech-mt">{{ current.title }}</h3>
        <div class="tech-muted">{{ current.authorName }} · {{ current.authorTitle }} · {{ shortDate(current.createTime) }}</div>
        <p class="detail-body">{{ current.content }}</p>
        <div class="tech-row tech-mt">
          <span v-for="tag in splitTags(current.tags)" :key="tag" class="tech-chip">{{ tag }}</span>
        </div>
        <div class="tech-card-foot">
          <span class="tech-muted">{{ current.viewCount || 0 }} {{ isEn ? 'views' : '阅读' }}</span>
          <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="like(current)">
            <Heart :size="14" :stroke-width="2" /> {{ current.likeCount || 0 }}
          </button>
        </div>
      </div>
    </a-drawer>
  </TechPageShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Heart, MessageSquare, PenLine, Search, ShieldCheck, Users } from 'lucide-vue-next'
import { techApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useTechLang } from '../../utils/techLang'
import TechPageShell from '../../components/techservice/TechPageShell.vue'
import TechSectionHead from '../../components/techservice/TechSectionHead.vue'
import SegmentedControl from '../../components/ui/SegmentedControl.vue'

const router = useRouter()
const user = useUserStore()
const { isEn } = useTechLang()

const loading = ref(false)
const posts = ref([])
const page = ref(1)
const size = ref(12)
const total = ref(0)
const activeType = ref('ALL')
const keyword = ref('')
const industry = ref()

const publishOpen = ref(false)
const publishing = ref(false)
const form = reactive({ postType: 'ARTICLE', title: '', content: '', industry: undefined, tags: '' })

const detailOpen = ref(false)
const current = ref(null)

const typeOptions = computed(() => [
  { label: isEn.value ? 'All' : '全部', value: 'ALL' },
  { label: isEn.value ? 'Articles' : '技术笔记', value: 'ARTICLE' },
  { label: isEn.value ? 'Questions' : '技术问答', value: 'QUESTION' },
  { label: isEn.value ? 'Discussions' : '开放讨论', value: 'DISCUSSION' }
])
const industryOptions = computed(() => ['智能制造', '新材料', '新能源', '电子信息', '绿色低碳', '数字经济']
  .map((v) => ({ label: v, value: v })))
const drawerWidth = computed(() => (window.innerWidth < 768 ? '100%' : 520))

function typeLabel(type) {
  const map = {
    ARTICLE: isEn.value ? 'Article' : '技术笔记',
    QUESTION: isEn.value ? 'Question' : '技术问答',
    DISCUSSION: isEn.value ? 'Discussion' : '开放讨论'
  }
  return map[type] || type
}
function typeTone(type) {
  return type === 'QUESTION' ? 'amber' : type === 'DISCUSSION' ? 'indigo' : 'cyan'
}
function splitTags(tags) {
  if (!tags) return []
  return String(tags).split(/[,，]/).map((t) => t.trim()).filter(Boolean).slice(0, 5)
}
function shortDate(value) {
  return String(value || '').replace('T', ' ').slice(0, 10)
}
function go(path) {
  router.push(path)
}

async function reload(nextPage = page.value) {
  loading.value = true
  page.value = nextPage
  try {
    const res = await techApi.posts({
      page: page.value,
      size: size.value,
      type: activeType.value === 'ALL' ? undefined : activeType.value,
      industry: industry.value || undefined,
      keyword: keyword.value || undefined
    })
    posts.value = res?.records || []
    total.value = Number(res?.total || 0)
  } catch (e) {
    posts.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function openPublish() {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/exchange' } })
    return
  }
  publishOpen.value = true
}

async function submitPost() {
  if (!form.title.trim() || !form.content.trim()) {
    message.warning(isEn.value ? 'Title and body are required' : '请填写标题与正文')
    return
  }
  publishing.value = true
  try {
    await techApi.createPost({
      postType: form.postType,
      title: form.title.trim(),
      content: form.content.trim(),
      industry: form.industry,
      tags: form.tags
    })
    message.success(isEn.value ? 'Published' : '已发布')
    publishOpen.value = false
    form.title = ''
    form.content = ''
    form.tags = ''
    await reload(1)
  } catch (e) {
    // request wrapper已提示
  } finally {
    publishing.value = false
  }
}

async function openDetail(post) {
  current.value = post
  detailOpen.value = true
  try {
    current.value = await techApi.post(post.postId)
  } catch (e) {
    // 保留列表中的快照
  }
}

async function like(post) {
  if (!user.logged) {
    router.push({ name: 'login', query: { redirect: '/tech/exchange' } })
    return
  }
  try {
    const updated = await techApi.likePost(post.postId)
    post.likeCount = updated?.likeCount ?? (post.likeCount || 0) + 1
    if (current.value?.postId === post.postId) current.value.likeCount = post.likeCount
  } catch (e) {
    // request wrapper已提示
  }
}

onMounted(() => reload(1))
</script>

<style scoped>
.post-card { cursor: pointer; }
.clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.detail-body { margin-top: 14px; white-space: pre-wrap; line-height: 1.9; font-size: 14px; }
.tech-pager { display: flex; justify-content: flex-end; margin-top: 18px; }
</style>
