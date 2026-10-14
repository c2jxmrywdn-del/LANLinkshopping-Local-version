<template>
  <div class="log-panel">
    <div class="toolbar">
      <span class="tip">近 {{ total }} 条登录安全记录（含成功与失败尝试），用于异地登录与风控自查。</span>
      <a-button size="small" @click="reload">刷新</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="records"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      size="small"
      :scroll="{ x: 640 }"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'success'">
          <a-tag :color="record.success === 1 ? 'green' : 'red'">
            {{ record.success === 1 ? '成功' : '失败' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'phone'">
          {{ maskPhone(record.phone) }}
        </template>
        <template v-else-if="column.key === 'userAgent'">
          <a-tooltip v-if="record.userAgent" :title="record.userAgent">
            <span class="ua">{{ shortUA(record.userAgent) }}</span>
          </a-tooltip>
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ fmt(record.createTime) }}
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { userApi } from '../../api'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const state = reactive({ page: 1, size: 10 })

const columns = [
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 150 },
  { title: '结果', dataIndex: 'success', key: 'success', width: 72 },
  { title: '说明', dataIndex: 'reason', key: 'reason', width: 180, ellipsis: true },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 120 },
  { title: '客户端 IP', dataIndex: 'clientIp', key: 'clientIp', width: 130, customRender: ({ text }) => text || '—' },
  { title: '设备 / 浏览器', dataIndex: 'userAgent', key: 'userAgent', ellipsis: true }
]

const pagination = computed(() => ({
  current: state.page,
  pageSize: state.size,
  total: total.value,
  size: 'small',
  showSizeChanger: false,
  showLessItems: true
}))

function fmt(d) {
  if (!d) return ''
  const s = String(d).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

function maskPhone(p) {
  if (!p) return '—'
  const s = String(p)
  if (s.length === 11) return s.slice(0, 3) + '****' + s.slice(7)
  return s
}

function shortUA(ua) {
  const s = String(ua)
  if (/Edg\//.test(s)) return 'Edge'
  if (/Chrome\//.test(s)) return 'Chrome'
  if (/Firefox\//.test(s)) return 'Firefox'
  if (/Safari\//.test(s)) return 'Safari'
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}

async function load() {
  loading.value = true
  try {
    const data = await userApi.loginLogPage({ page: state.page, size: state.size })
    records.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function reload() {
  state.page = 1
  load()
}

function onTableChange(pg) {
  state.page = pg.current || 1
  load()
}

onMounted(load)
</script>

<style scoped>
.log-panel { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.tip { font-size: 13px; color: var(--ll-gray2); }
.ua { cursor: default; }
</style>
