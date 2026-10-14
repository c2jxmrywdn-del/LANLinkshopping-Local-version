<template>
  <div>
    <a-row :gutter="16">
      <a-col :span="6"><a-card><a-statistic title="商户总数" :value="stats.total"/></a-card></a-col>
      <a-col :span="6"><a-card><a-statistic title="待审核" :value="stats.pending" :value-style="{color:'#faad14'}"/></a-card></a-col>
      <a-col :span="6"><a-card><a-statistic title="已通过" :value="stats.passed" :value-style="{color:'#52c41a'}"/></a-card></a-col>
      <a-col :span="6"><a-card><a-statistic title="在架商品" :value="stats.products" :value-style="{color:'#1677ff'}"/></a-card></a-col>
    </a-row>

    <a-card title="最近商户" style="margin-top:16px">
      <a-table :data-source="recent" :columns="cols" row-key="merId" size="small" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'cap'">¥{{ record.regCapital }}</template>
          <template v-else-if="column.key === 'st'">
            <a-tag :color="stColor[record.reviewStatus]">{{ stText[record.reviewStatus] }}</a-tag>
          </template>
        </template>
      </a-table>
      <a-button type="link" @click="$router.push({name:'admin-merchants'})">前往商户管理 →</a-button>
    </a-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { merchantApi, productApi } from '../../api'

const stats = reactive({ total: 0, pending: 0, passed: 0, products: 0 })
const recent = ref([])
const stText = { 0: '待审核', 1: '已通过', 2: '已驳回' }
const stColor = { 0: 'orange', 1: 'green', 2: 'red' }
const cols = [
  { title: '企业ID', dataIndex: 'entId', width: 90 },
  { title: '主体类型', dataIndex: 'regType', width: 120 },
  { title: '注册资本', key: 'cap', width: 140 },
  { title: '入驻方式', dataIndex: 'joinType', width: 110 },
  { title: '状态', key: 'st', width: 100 }
]

onMounted(async () => {
  const list = await merchantApi.adminList() || []
  recent.value = list.slice(0, 6)
  stats.total = list.length
  stats.pending = list.filter(m => m.reviewStatus === 0).length
  stats.passed = list.filter(m => m.reviewStatus === 1).length
  const page = await productApi.page({ current: 1, size: 1 })
  stats.products = page.total || 0
})
</script>
