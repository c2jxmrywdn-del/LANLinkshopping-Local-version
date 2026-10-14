<template>
  <div>
    <a-card title="我的商品">
      <template #extra>
        <a-space>
          <a-button @click="load">刷新</a-button>
          <a-button type="primary" @click="publishOpen = true">＋ 发布商品</a-button>
        </a-space>
      </template>

      <a-alert v-if="notMerchant" type="warning" show-icon style="margin-bottom:16px"
               message="尚未成为入驻商户"
               description="完成商户入驻并通过审核后，即可发布与管理商品。">
        <template #action>
          <a-button size="small" type="primary" @click="$router.push('/merchant')">去入驻</a-button>
        </template>
      </a-alert>

      <a-table v-else :data-source="rows" :columns="cols" row-key="prodId" :loading="loading"
               :pagination="{ current, pageSize: 10, total, onChange: (p) => { current = p; load() } }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'prod'">
            <div class="prod-cell">
              <div class="thumb">
                <img v-if="record.coverUrl" :src="record.coverUrl" :alt="record.title" loading="lazy" />
                <span v-else>{{ (record.title || '').slice(0, 2) }}</span>
              </div>
              <div class="pt">
                <div class="pt-title">{{ record.title }}</div>
                <div class="pt-meta">{{ record.brand || '—' }} · {{ record.spec || '无规格' }}</div>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'price'">¥{{ record.price }}</template>
          <template v-else-if="column.key === 'stock'">{{ record.stock }}</template>
          <template v-else-if="column.key === 'rv'">
            <a-tooltip v-if="record.reviewStatus === 2 && record.rejectReason" :title="'驳回原因：' + record.rejectReason">
              <a-tag :color="rvColor(record.reviewStatus)">{{ rvText(record.reviewStatus) }} ⓘ</a-tag>
            </a-tooltip>
            <a-tag v-else :color="rvColor(record.reviewStatus)">{{ rvText(record.reviewStatus) }}</a-tag>
            <div v-if="record.reviewStatus === 1" class="saleable">{{ record.status === 1 ? '可售中' : '已下架' }}</div>
          </template>
          <template v-else-if="column.key === 'op'"><a-button type="link" size="small" @click="$router.push('/product/' + record.prodId)">查看商品</a-button></template>
          <template v-else-if="column.key === 'time'">{{ (record.createTime || '').replace('T', ' ') }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- 发布商品组件（含草稿/表单校验/图片上传） -->
    <ProductPublishForm v-model:open="publishOpen" @published="load" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { productApi } from '../api'
import ProductPublishForm from '../components/merchant/ProductPublishForm.vue'

const rows = ref([])
const loading = ref(false)
const notMerchant = ref(false)
const publishOpen = ref(false)
const current = ref(1)
const total = ref(0)

const cols = [
  { title: '商品', key: 'prod' },
  { title: '价格', key: 'price', width: 110 },
  { title: '库存', key: 'stock', width: 90 },
  { title: '销量', dataIndex: 'sales', width: 90 },
  { title: '审核状态', key: 'rv', width: 140 },
  { title: '操作', key: 'op', width: 100 },
  { title: '发布时间', key: 'time', width: 170 }
]

/** 审核状态展示：待审核(审核中)/审核通过/审核驳回 */
function rvText(s) {
  return { 0: '待审核 · 审核中', 1: '审核通过', 2: '审核驳回' }[s] ?? '未知'
}
function rvColor(s) {
  return { 0: 'orange', 1: 'green', 2: 'red' }[s] ?? 'default'
}

async function load() {
  loading.value = true
  try {
    const page = await productApi.myList({ current: current.value, size: 10 })
    rows.value = page.records || []
    total.value = page.total || 0
    notMerchant.value = false
  } catch (e) {
    // 「请先完成商户入驻」等业务错误：切换为引导视图
    notMerchant.value = true
    rows.value = []
  } finally { loading.value = false }
}

onMounted(load)
</script>

<style scoped>
.prod-cell { display: flex; gap: 10px; align-items: center; }
.thumb { width: 48px; height: 48px; border-radius: 6px; background: var(--ll-thumb-bg, #eef1f6);
         display: flex; align-items: center; justify-content: center; overflow: hidden;
         font-size: 16px; color: var(--ll-thumb-fg, #b9c3d4); flex-shrink: 0; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.pt-title { font-size: 13px; }
.pt-meta { font-size: 12px; color: var(--ll-muted, #64748b); }
.saleable { font-size: 12px; color: var(--ll-muted, #64748b); margin-top: 2px; }
</style>
