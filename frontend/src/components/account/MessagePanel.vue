<template>
  <div class="msg-panel">
    <div class="toolbar">
      <a-radio-group v-model:value="type" button-style="solid" size="small" @change="reload">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button value="order">订单</a-radio-button>
        <a-radio-button value="promotion">促销</a-radio-button>
        <a-radio-button value="system">系统</a-radio-button>
      </a-radio-group>
      <a-space>
        <a-button size="small" @click="reload">刷新</a-button>
        <a-button size="small" type="primary" ghost :disabled="!hasUnread" :loading="readingAll" @click="readAll">
          全部标为已读
        </a-button>
      </a-space>
    </div>

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
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { userApi } from '../../api'
import { useUserStore } from '../../store/user'

const user = useUserStore()

const loading = ref(false)
const readingAll = ref(false)
const type = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref([])
const unread = ref(0)

const hasUnread = computed(() => unread.value > 0)

const TYPE_NAME = { order: '订单', promotion: '促销', system: '系统' }
const TYPE_COLOR = { order: 'blue', promotion: 'magenta', system: 'default' }
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

onMounted(load)
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
</style>
