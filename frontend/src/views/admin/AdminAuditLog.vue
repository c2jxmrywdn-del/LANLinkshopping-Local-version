<template>
  <a-card title="审计日志">
    <template #extra>
      <a-space wrap>
        <a-input-search v-model:value="q.action" placeholder="操作类型（如 CACHE_CLEAR）" allow-clear style="width: 200px" @search="reload" />
        <a-input v-model:value="q.userId" placeholder="用户ID" allow-clear style="width: 110px" @pressEnter="reload" />
        <a-date-picker v-model:value="q.begin" placeholder="开始日期" value-format="YYYY-MM-DD" @change="reload" />
        <a-date-picker v-model:value="q.end" placeholder="结束日期" value-format="YYYY-MM-DD" @change="reload" />
        <a-button @click="reload">搜索</a-button>
        <a-button type="primary" :loading="exporting" @click="doExport">导出 CSV</a-button>
      </a-space>
    </template>
    <a-table :data-source="rows" :columns="cols" row-key="id" :loading="loading"
      :pagination="{ current: page, pageSize: size, total, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'], onChange: onPage, onShowSizeChange: onPage }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'time'">
          {{ (record.createTime || '').replace('T', ' ') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-tag :color="actionColor(record.action)">{{ record.action }}</a-tag>
        </template>
      </template>
    </a-table>
  </a-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const exporting = ref(false)
const q = reactive({ action: '', userId: '', begin: null, end: null })

const cols = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '操作时间', key: 'time', width: 170 },
  { title: '用户ID', dataIndex: 'userId', width: 90 },
  { title: '操作类型', key: 'action', width: 160 },
  { title: '操作内容', dataIndex: 'detail', ellipsis: true },
  { title: '客户端IP', dataIndex: 'clientIp', width: 150 },
  { title: '设备信息', dataIndex: 'userAgent', ellipsis: true }
]

const COLOR = {
  CHANGE_PASSWORD: 'orange', ENABLE_2FA: 'green', DISABLE_2FA: 'red',
  REVOKE_THIRD_AUTH: 'red', SETTINGS_RESET: 'orange', CACHE_CLEAR: 'blue'
}
function actionColor(a) { return COLOR[a] || 'default' }

function params(p = page.value, s = size.value) {
  const pms = { page: p, size: s }
  if (q.action) pms.action = q.action
  if (q.userId) pms.userId = q.userId
  if (q.begin) pms.beginDate = q.begin
  if (q.end) pms.endDate = q.end
  return pms
}

async function reload() {
  loading.value = true
  try {
    const data = await adminApi.auditPage(params())
    rows.value = data.records || []
    total.value = Number(data.total || 0)
    page.value = Number(data.page || 1)
    size.value = Number(data.size || 10)
  } finally { loading.value = false }
}
function onPage(p, s) { page.value = p; size.value = s || size.value; reload() }

async function doExport() {
  exporting.value = true
  try {
    const blob = await adminApi.auditExport(params(1, 100000))
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `audit_log_${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    message.success('已导出')
  } finally { exporting.value = false }
}

onMounted(reload)
</script>