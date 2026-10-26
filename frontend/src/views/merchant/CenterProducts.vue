<template>
  <div class="prod-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>商品管理</h1>
        <p>发布、编辑商品并跟踪审核进度，审核通过后即可上架销售。</p>
      </div>
      <div class="hero-actions">
        <a-button size="large" class="pill" @click="$router.push('/price-monitor')">◔ 价格监控</a-button>
        <a-button size="large" class="pill" @click="$router.push('/merchant/center/products/import')">⇪ 批量导入</a-button>
        <a-button size="large" type="primary" class="pill" @click="publishOpen = true">＋ 发布商品</a-button>
      </div>
    </section>

    <!-- 筛选工具条 -->
    <div class="toolbar">
      <a-input-search v-model:value="keyword" placeholder="搜索商品名称" allow-clear class="search"
                      @pressEnter="onSearch" @search="onSearch" />
      <a-select v-model:value="reviewStatus" class="filter" @change="onFilter">
        <a-select-option :value="null">全部审核状态</a-select-option>
        <a-select-option :value="0">待审核</a-select-option>
        <a-select-option :value="1">审核通过</a-select-option>
        <a-select-option :value="2">审核驳回</a-select-option>
      </a-select>
      <a-select v-model:value="status" class="filter" @change="onFilter">
        <a-select-option :value="null">全部上架状态</a-select-option>
        <a-select-option :value="1">在售</a-select-option>
        <a-select-option :value="0">已下架</a-select-option>
      </a-select>
      <a-button @click="reset">重置</a-button>
      <a-button @click="load">刷新</a-button>
    </div>

    <a-card :bordered="false" class="panel">
      <a-table :data-source="rows" :columns="cols" row-key="prodId" :loading="loading" class="mc-table"
               :scroll="{ x: 1080 }"
               :pagination="{ current, pageSize: pageSize, total, showSizeChanger: false,
                              onChange: (p) => { current = p; load() },
                              showTotal: (t) => `共 ${t} 件商品` }">
        <template #emptyText>
          <a-empty description="店铺还没有商品">
            <a-button type="primary" @click="publishOpen = true">发布第一件商品</a-button>
          </a-empty>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'prod'">            <button type="button" class="prod-cell" @click="openDetail(record)">
              <span class="thumb">
                <img v-if="record.coverUrl" :src="record.coverUrl" :alt="record.title" loading="lazy" />
                <i v-else>{{ (record.title || '商').slice(0, 1) }}</i>
              </span>
              <span class="pt">
                <b>{{ record.title }}</b>
                <small>{{ record.brand || '无品牌' }} · {{ record.spec || '无规格' }}</small>
              </span>
            </button>
          </template>
          <template v-else-if="column.key === 'price'">¥{{ money(record.price) }}</template>
          <template v-else-if="column.key === 'stock'">
            <span :class="{ low: record.stock <= 10 }">{{ record.stock }}</span>
          </template>
          <template v-else-if="column.key === 'rv'">
            <a-tooltip v-if="record.reviewStatus === 2 && record.rejectReason" :title="'驳回原因：' + record.rejectReason">
              <a-tag :color="REVIEW_STATUS[record.reviewStatus]?.color">{{ REVIEW_STATUS[record.reviewStatus]?.text }} ⓘ</a-tag>
            </a-tooltip>
            <a-tag v-else :color="REVIEW_STATUS[record.reviewStatus]?.color">{{ REVIEW_STATUS[record.reviewStatus]?.text }}</a-tag>
          </template>
          <template v-else-if="column.key === 'sale'">
            <a-switch v-if="record.reviewStatus === 1" size="small"
                      :checked="record.status === 1" checked-children="售" un-checked-children="停"
                      :loading="record._switching" @change="(v) => toggleSale(record, v)" />
            <a-tooltip v-else title="审核通过后可上架">
              <a-switch size="small" disabled />
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" size="small" @click="$router.push(`/merchant/center/products/${record.prodId}/edit`)">编辑</a-button>
              <a-button v-if="record.status === 1 && record.reviewStatus === 1" type="link" size="small"
                        @click="$router.push('/product/' + record.prodId)">商城页</a-button>
            </a-space>
          </template>
          <template v-else-if="column.key === 'time'">{{ shortTime(record.createTime) }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- 发布商品 -->
    <ProductPublishForm v-model:open="publishOpen" @published="load" />

    <!-- ===== L4：商品详情抽屉 ===== -->
    <a-drawer v-model:open="detailOpen" title="商品详情" width="460" placement="right" class="prod-drawer">
      <template v-if="detail">
        <div class="d-hero">
          <img v-if="detail.coverUrl" :src="detail.coverUrl" alt="" />
          <div v-else class="d-hero-fallback">{{ (detail.title || '商').slice(0, 1) }}</div>
          <div class="d-hero-meta">
            <h3>{{ detail.title }}</h3>
            <div class="d-tags">
              <a-tag :color="REVIEW_STATUS[detail.reviewStatus]?.color">{{ REVIEW_STATUS[detail.reviewStatus]?.text }}</a-tag>
              <a-tag :color="detail.status === 1 ? 'green' : 'default'">{{ detail.status === 1 ? '在售中' : '未上架' }}</a-tag>
            </div>
            <div class="d-price">¥{{ money(detail.price) }}<small>库存 {{ detail.stock }} · 已售 {{ detail.sales || 0 }}</small></div>
          </div>
        </div>

        <a-alert v-if="detail.reviewStatus === 2" type="error" show-icon class="d-alert"
                 :message="'驳回原因：' + (detail.rejectReason || '不符合平台规范')"
                 description="修改内容并重新提交后，商品将再次进入平台审核。" />

        <a-descriptions :column="1" size="small" bordered class="d-desc">
          <a-descriptions-item label="商品编号">#{{ detail.prodId }}</a-descriptions-item>
          <a-descriptions-item label="品牌">{{ detail.brand || '—' }}</a-descriptions-item>
          <a-descriptions-item label="规格">{{ detail.spec || '—' }}</a-descriptions-item>
          <a-descriptions-item label="行业 / 分类">{{ detail.indId }} / {{ detail.catId }}</a-descriptions-item>
          <a-descriptions-item label="发布时间">{{ shortTime(detail.createTime) }}</a-descriptions-item>
          <a-descriptions-item label="最近修改">{{ shortTime(detail.updateTime) }}</a-descriptions-item>
          <a-descriptions-item label="商品描述">{{ detail.detail || '—' }}</a-descriptions-item>
        </a-descriptions>

        <div class="d-actions">
          <a-button v-if="detail.reviewStatus === 1" :type="detail.status === 1 ? 'default' : 'primary'" block
                    @click="toggleSale(detail, detail.status !== 1); detailOpen = false">
            {{ detail.status === 1 ? '下架商品' : '上架商品' }}
          </a-button>
          <a-button type="primary" block @click="detailOpen = false; $router.push(`/merchant/center/products/${detail.prodId}/edit`)">
            编辑商品
          </a-button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { productApi } from '../../api'
import { REVIEW_STATUS, fmtMoney } from '../../utils/merchantStatus'
import ProductPublishForm from '../../components/merchant/ProductPublishForm.vue'

const route = useRoute()
const rows = ref([])
const loading = ref(false)
const publishOpen = ref(false)
const keyword = ref('')
const reviewStatus = ref(null)
const status = ref(null)
const current = ref(1)
const pageSize = 10
const total = ref(0)
const detailOpen = ref(false)
const detail = ref(null)

const cols = [
  { title: '商品', key: 'prod' },
  { title: '价格', key: 'price', width: 100 },
  { title: '库存', key: 'stock', width: 80 },
  { title: '销量', dataIndex: 'sales', width: 76 },
  { title: '审核状态', key: 'rv', width: 104 },
  { title: '在售', key: 'sale', width: 74 },
  { title: '操作', key: 'op', width: 128 },
  { title: '发布时间', key: 'time', width: 104 }
]

const money = fmtMoney
function shortTime(t) {
  return (t || '').replace('T', ' ').slice(0, 16)
}

function onSearch() { current.value = 1; load() }
function onFilter() { current.value = 1; load() }
function reset() {
  keyword.value = ''; reviewStatus.value = null; status.value = null
  current.value = 1; load()
}

async function load() {
  loading.value = true
  try {
    const page = await productApi.myList({
      current: current.value, size: pageSize,
      keyword: keyword.value || undefined,
      reviewStatus: reviewStatus.value ?? undefined,
      status: status.value ?? undefined
    })
    rows.value = (page.records || []).map(r => ({ ...r, _switching: false }))
    total.value = page.total || 0
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

/** 自主上下架：二次确认，防止误触 */
function toggleSale(record, next) {
  const doIt = async () => {
    record._switching = true
    try {
      await productApi.myStatus(record.prodId, next ? 1 : 0)
      record.status = next ? 1 : 0
      if (detail.value?.prodId === record.prodId) detail.value.status = record.status
      message.success(next ? '商品已上架，买家可以购买了' : '商品已下架')
    } catch (e) { /* 拦截器已提示 */ }
    record._switching = false
  }
  if (next) return doIt()
  Modal.confirm({
    title: '确认下架该商品？',
    content: `下架后「${record.title}」将不可被买家浏览与购买，可随时重新上架。`,
    okText: '确认下架', cancelText: '取消',
    onOk: doIt
  })
}

function openDetail(record) {
  detail.value = record
  detailOpen.value = true
}

// 工作台待办跳转（?reviewStatus=0/2）进入时自动应用筛选
watch(() => route.query.reviewStatus, (v) => {
  if (v !== undefined && v !== '') {
    reviewStatus.value = Number(v)
    current.value = 1
  }
}, { immediate: true })

onMounted(load)
</script>

<style scoped>
.prod-page { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #9b2d20; margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; }
.pill { border-radius: 999px; height: 40px; padding-inline: 18px; }

.toolbar { display: flex; gap: 10px; flex-wrap: wrap; }
.search { width: 260px; }
.filter { width: 150px; }

.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.prod-cell { display: flex; gap: 11px; align-items: center; border: 0; background: transparent; font: inherit; text-align: left; cursor: pointer; padding: 0; max-width: 100%; }
.thumb { width: 46px; height: 46px; border-radius: 10px; background: var(--mc-soft, #f5f5f7); display: grid; place-items: center; overflow: hidden; flex-shrink: 0; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb i { font-style: normal; color: var(--mc-faint, #b9c3d4); font-size: 16px; }
.pt { min-width: 0; }
.pt b { display: block; font-size: 13px; color: var(--mc-ink, #1d1d1f); font-weight: 500; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pt small { color: var(--mc-faint, #a1a1a6); font-size: 11.5px; }
.low { color: #c2410c; font-weight: 600; }

/* 抽屉 */
.d-hero { display: flex; gap: 14px; margin-bottom: 16px; }
.d-hero img, .d-hero-fallback { width: 96px; height: 96px; border-radius: 14px; object-fit: cover; background: var(--mc-soft, #f5f5f7); display: grid; place-items: center; font-size: 30px; color: var(--mc-faint, #b9c3d4); flex-shrink: 0; }
.d-hero-meta { min-width: 0; }
.d-hero-meta h3 { margin: 2px 0 8px; font-size: 16px; letter-spacing: -.01em; color: var(--mc-ink, #1d1d1f); line-height: 1.4; }
.d-tags { display: flex; gap: 6px; margin-bottom: 10px; }
.d-price { font-size: 20px; font-weight: 750; color: var(--mc-ink, #1d1d1f); letter-spacing: -.02em; }
.d-price small { margin-left: 10px; font-size: 11.5px; color: var(--mc-muted2, #86868b); font-weight: 400; }
.d-alert { margin-bottom: 14px; }
.d-desc { margin-bottom: 18px; }
.d-actions { display: flex; flex-direction: column; gap: 10px; }

@media (max-width: 700px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .hero-actions .pill { flex: 1; }
  .search { width: 100%; }
  .filter { flex: 1; width: auto; min-width: 130px; }
  .pt b { max-width: 150px; }
}
</style>
