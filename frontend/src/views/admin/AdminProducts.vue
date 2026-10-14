<template>
  <a-card title="商品管理">
    <template #extra>
      <a-space>
        <a-input-search v-model:value="keyword" placeholder="搜索商品名" style="width:200px" @search="load" allow-clear />
        <a-select v-model:value="rvStatus" style="width:140px" placeholder="全部审核状态" allow-clear @change="load">
          <a-select-option :value="0">待审核</a-select-option>
          <a-select-option :value="1">审核通过</a-select-option>
          <a-select-option :value="2">审核驳回</a-select-option>
        </a-select>
        <a-select v-model:value="status" style="width:120px" placeholder="可售状态" allow-clear @change="load">
          <a-select-option :value="1">可售</a-select-option>
          <a-select-option :value="0">不可售</a-select-option>
        </a-select>
      </a-space>
    </template>
    <a-table :data-source="rows" :columns="cols" row-key="prodId" :loading="loading"
             :pagination="{ current, pageSize: 20, total, onChange: (p) => { current = p; load() } }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'price'">¥{{ record.price }}</template>
        <template v-else-if="column.key === 'rv'">
          <a-tooltip v-if="record.reviewStatus === 2 && record.rejectReason" :title="'驳回原因：' + record.rejectReason">
            <a-tag color="red">审核驳回 ⓘ</a-tag>
          </a-tooltip>
          <a-tag v-else-if="record.reviewStatus === 1" color="green">审核通过</a-tag>
          <a-tag v-else-if="record.reviewStatus === 0" color="orange">待审核</a-tag>
          <a-tag v-else>未知</a-tag>
        </template>
        <template v-else-if="column.key === 'st'">
          <a-tag :color="record.status === 1 ? 'green' : 'default'">{{ record.status === 1 ? '可售' : '不可售' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space>
            <!-- 审核：待审核/驳回的商品可审核（通过→自动可售） -->
            <template v-if="record.reviewStatus !== 1">
              <a-button size="small" type="primary" @click="approve(record)">通过</a-button>
              <a-button size="small" danger @click="openReject(record)">驳回</a-button>
            </template>
            <!-- 可售性：已审核通过的商品由运营控制上下架 -->
            <a-button v-if="record.reviewStatus === 1 && record.status === 1" size="small" danger @click="toggle(record, 0)">下架</a-button>
            <a-button v-if="record.reviewStatus === 1 && record.status === 0" size="small" @click="toggle(record, 1)">上架</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 驳回原因输入 -->
    <a-modal v-model:open="rejectOpen" title="驳回商品" ok-text="确认驳回" cancel-text="取消"
             :confirm-loading="rejecting" @ok="doReject">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将展示给商户）" required>
          <a-textarea v-model:value="rejectReason" :rows="3" maxlength="200" show-count
                      placeholder="如：商品图不清晰 / 价格异常 / 分类不符" />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { productApi } from '../../api'

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const rvStatus = ref(null)
const status = ref(null)
const current = ref(1)
const total = ref(0)
const cols = [
  { title: 'ID', dataIndex: 'prodId', width: 70 },
  { title: '商品名称', dataIndex: 'title' },
  { title: '品牌', dataIndex: 'brand', width: 90 },
  { title: '价格', key: 'price', width: 100 },
  { title: '库存', dataIndex: 'stock', width: 80 },
  { title: '销量', dataIndex: 'sales', width: 80 },
  { title: '审核状态', key: 'rv', width: 110 },
  { title: '可售', key: 'st', width: 90 },
  { title: '操作', key: 'op', width: 190 }
]

async function load() {
  loading.value = true
  try {
    // 前端按审核状态过滤（接口按可售状态/关键词过滤）
    const page = await productApi.adminList({ current: current.value, size: 20, status: status.value, keyword: keyword.value })
    let list = page.records || []
    if (rvStatus.value !== null && rvStatus.value !== undefined) {
      list = list.filter(r => r.reviewStatus === rvStatus.value)
    }
    rows.value = list
    total.value = page.total || 0
  } finally { loading.value = false }
}

async function approve(record) {
  await productApi.adminReview(record.prodId, 1)
  message.success(`已通过「${record.title}」，商品自动转为可售`)
  load()
}

// 驳回（带原因）
const rejectOpen = ref(false)
const rejecting = ref(false)
const rejectReason = ref('')
const rejectingId = ref(null)
function openReject(record) {
  rejectingId.value = record.prodId
  rejectReason.value = ''
  rejectOpen.value = true
}
async function doReject() {
  if (!rejectReason.value.trim()) { message.warning('请填写驳回原因'); return }
  rejecting.value = true
  try {
    await productApi.adminReview(rejectingId.value, 2, rejectReason.value.trim())
    message.success('已驳回，原因将同步展示给商户')
    rejectOpen.value = false
    load()
  } finally { rejecting.value = false }
}

async function toggle(record, st) {
  await productApi.adminStatus(record.prodId, st)
  message.success(st === 1 ? '已上架' : '已下架')
  load()
}
onMounted(load)
</script>
