<template>
  <div class="msg-panel">
    <div class="toolbar">
      <a-radio-group v-model:value="type" button-style="solid" size="small" @change="reload">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button value="order">订单</a-radio-button>
        <a-radio-button value="promotion">促销</a-radio-button>
        <a-radio-button value="system">系统</a-radio-button>
        <a-radio-button value="price">价格</a-radio-button>

      </a-radio-group>
      <a-space>
        <a-button size="small" @click="reload">刷新</a-button>
        <a-button size="small" type="primary" ghost :disabled="!hasUnread" :loading="readingAll" @click="readAll">
          全部标为已读
        </a-button>
      </a-space>
    </div>

    <!-- 商家咨询：买家 IM 会话列表（回访历史咨询；商户走商户中心客户消息页，不在此显示） -->
    <template v-if="showChatBlock">
      <a-divider orientation="left" orientation-margin="0" class="chat-divider">商家咨询</a-divider>
      <a-spin :spinning="chatLoading">
        <a-empty v-if="!chatLoading && !chatSessions.length" description="暂无商家咨询记录，可在商品页点击「联系商家」发起" class="empty" />
        <div v-else class="chat-rows">
          <button v-for="s in chatSessions" :key="s.sessionId" type="button" class="chat-row" @click="openChat(s)">
            <span class="chat-avatar">{{ (s.peerName || '商').slice(0, 1) }}</span>
            <span class="chat-main">
              <span class="chat-peer">{{ s.peerName || '商家' }}<i class="chat-prod">{{ s.prodTitle }}</i></span>
              <small class="chat-last">{{ s.lastMsg || '（无消息）' }}</small>
            </span>
            <span class="chat-side">
              <b v-if="s.unreadCount" class="chat-badge">{{ s.unreadCount }}</b>
              <small class="chat-time">{{ fmt(s.updateTime) }}</small>
            </span>
          </button>
        </div>
      </a-spin>
    </template>

    <a-divider v-if="showChatBlock" style="margin: 4px 0 12px">站内消息</a-divider>

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && records.length === 0" description="暂无消息" class="empty" />
      <a-list v-else :data-source="records" item-layout="horizontal" class="list">
        <template #renderItem="{ item }">
          <a-list-item class="item" :class="{ unread: item.readFlag === 0 }">
            <a-list-item-meta>
              <template #title>
                <div class="title-row">
                  <a-badge v-if="item.readFlag === 0" status="processing" />
                  <span class="title-text">{{ item.title }}</span>
                  <a-tag :color="typeColor(item.type)">{{ typeName(item.type) }}</a-tag>
                </div>
              </template>
              <template #description>
                <div class="desc">
                  <div class="content">{{ item.content }}</div>
                  <div class="meta">
                    <span class="time">{{ fmt(item.createTime) }}</span>
                    <span v-if="item.relatedNo" class="no">关联单号：{{ item.relatedNo }}</span>
                    <a-button
                      v-if="item.readFlag === 0"
                      type="link"
                      size="small"
                      class="read-btn"
                      @click="markRead(item)"
                    >标为已读</a-button>
                  </div>
                </div>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>

      <div v-if="total > size" class="pager">
        <a-pagination
          v-model:current="page"
          :page-size="size"
          :total="total"
          size="small"
          show-less-items
          @change="load"
        />
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { userApi, chatApi } from '../../api'
import { useUserStore } from '../../store/user'

const user = useUserStore()
const router = useRouter()

// 商家咨询会话（仅非商户角色显示：商户有自己的客户消息页）
const showChatBlock = computed(() => user.logged && user.user?.roleCode !== 'merchant')
const chatLoading = ref(false)
const chatSessions = ref([])

async function loadChatSessions() {
  if (!showChatBlock.value) return
  chatLoading.value = true
  try {
    const data = await chatApi.sessions()
    chatSessions.value = Array.isArray(data) ? data : (data?.records || [])
  } catch (e) { /* 拦截器已提示 */ }
  chatLoading.value = false
}

function openChat(s) {
  router.push('/chat/' + s.sessionId)
}

// 登录态由 App 级 /auth/me 异步水合：immediate 兼顾已就绪与挂载后才就绪两种时序
watch(showChatBlock, (v) => { if (v) loadChatSessions() }, { immediate: true })

const loading = ref(false)
const readingAll = ref(false)
const type = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref([])
const unread = ref(0)

const hasUnread = computed(() => unread.value > 0)

const TYPE_NAME = { price: '价格', order: '订单', promotion: '促销', system: '系统' }
const TYPE_COLOR = { price: 'gold', order: 'blue', promotion: 'magenta', system: 'default' }
function typeName(t) { return TYPE_NAME[t] || t || '通知' }
function typeColor(t) { return TYPE_COLOR[t] || 'default' }

function fmt(d) {
  if (!d) return ''
  const s = String(d).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

async function load() {
  loading.value = true
  try {
    const data = await userApi.messagePage({ page: page.value, size: size.value, type: type.value || undefined })
    records.value = data.records || []
    total.value = data.total || 0
    unread.value = data.unread || 0
    // 与顶栏角标保持同步
    user.setUnread(unread.value)
  } catch (e) {
    /* 请求拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

async function markRead(item) {
  try {
    await userApi.messageRead(item.id)
    item.readFlag = 1
    unread.value = Math.max(0, unread.value - 1)
    user.setUnread(unread.value)
  } catch (e) { /* ignore */ }
}

async function readAll() {
  readingAll.value = true
  try {
    await userApi.messageReadAll()
    records.value.forEach((m) => { m.readFlag = 1 })
    unread.value = 0
    user.setUnread(0)
    message.success('已全部标为已读')
  } catch (e) {
    /* ignore */
  } finally {
    readingAll.value = false
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.msg-panel { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.empty { padding: 32px 0; }
.list { max-height: 560px; overflow-y: auto; }
.item { align-items: flex-start; }
.item.unread { background: var(--ll-page); }
.title-row { display: flex; align-items: center; gap: 8px; }
.title-text { font-weight: 600; color: var(--ll-ink); }
.desc { display: flex; flex-direction: column; gap: 4px; }
.content { color: var(--ll-muted); font-size: 13px; }
.meta { display: flex; align-items: center; gap: 14px; font-size: 12px; color: var(--ll-gray2); }
.meta .no { color: var(--ll-muted); }
.read-btn { padding: 0 4px; height: auto; }
.pager { display: flex; justify-content: center; padding: 8px 0; }

/* 商家咨询会话行（与 CenterChat 列表风格一致的轻量版） */
.chat-divider { margin: 4px 0 10px; }
.chat-rows { display: flex; flex-direction: column; }
.chat-row { display: flex; align-items: center; gap: 10px; width: 100%; border: 0; background: transparent;
  font: inherit; text-align: left; padding: 11px 8px; cursor: pointer; border-bottom: 1px solid var(--ll-border, #e5e2da);
  border-radius: 8px; transition: background .15s ease; }
.chat-row:hover { background: var(--ll-page, #f5f6f8); }
.chat-avatar { width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0; display: grid; place-items: center;
  background: linear-gradient(135deg, #EAF5FE, #D6ECFA); color: #fff; font-weight: 700; font-size: 14px; }
.chat-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.chat-peer { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 650; color: var(--ll-ink); }
.chat-prod { font-style: normal; font-weight: 400; font-size: 11px; color: var(--ll-gray2, #98a2b3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-last { font-size: 12px; color: var(--ll-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-side { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.chat-badge { font-size: 10.5px; line-height: 1; color: #fff; background: var(--red, #d93025);
  border-radius: 999px; padding: 3px 6px; font-weight: 700; }
.chat-time { font-size: 10px; color: var(--ll-gray2, #98a2b3); }
</style>
