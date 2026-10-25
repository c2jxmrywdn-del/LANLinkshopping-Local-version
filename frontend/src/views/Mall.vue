<template>
  <div class="mall-page">
    <div v-if="promos.length" class="promo-bar">
      <span class="promo-title">🔥 促销进行中</span>
      <span v-for="p in promos" :key="p.campaignId" class="promo-item" @click="$router.push('/activity/' + p.campaignId)">{{ p.title }}</span>
    </div>
    <div class="toolbar">
      <a-input-search v-model:value="keyword" placeholder="搜索商品名称" class="toolbar-search"
                      allow-clear @search="reload" />
      <a-select v-model:value="indId" style="width:160px" placeholder="全部行业" allow-clear @change="onIndustryChange">
        <a-select-option v-for="i in industries" :key="i.indId" :value="i.indId">{{ i.name }}</a-select-option>
      </a-select>
      <a-select v-model:value="sort" style="width:160px" @change="reload">
        <a-select-option value="">综合排序</a-select-option>
        <a-select-option value="sales">销量优先</a-select-option>
        <!-- 语义与后端排序一致：价格最高 = 从高到低（priceDesc），价格最低 = 从低到高（priceAsc） -->
        <a-select-option value="priceDesc">价格最高</a-select-option>
        <a-select-option value="priceAsc">价格最低</a-select-option>
      </a-select>
      <span v-if="!loading && total > 0" class="toolbar-count">共 {{ total }} 件商品</span>
    </div>

    <!-- 加载态：骨架屏卡片（首次加载与翻页均展示，替代遮罩式 loading） -->
    <a-row v-if="loading" :gutter="[16, 20]" aria-busy="true">
      <a-col v-for="n in 8" :key="n" :xs="12" :sm="12" :md="8" :lg="6">
        <div class="ll-skeleton-card mall-skeleton-card" aria-hidden="true">
          <div class="ll-skeleton ll-skeleton-card__img"></div>
          <div class="ll-skeleton-card__body">
            <div class="ll-skeleton ll-skeleton-card__line"></div>
            <div class="ll-skeleton ll-skeleton-card__line ll-skeleton-card__line--short"></div>
            <div class="ll-skeleton ll-skeleton-card__btn"></div>
          </div>
        </div>
      </a-col>
    </a-row>

    <a-row v-else-if="list.length" :gutter="[16, 20]">
      <a-col v-for="(p, i) in list" :key="p.prodId" :xs="12" :sm="12" :md="8" :lg="6" :data-reveal="'fade-up'" :data-delay="i * 50">
        <InteractiveProductCard
          :image-url="p.coverUrl || ''"
          :title="p.title"
          :description="p.brand || 'B2B 供应商精选'"
          :price="'¥' + p.price"
          @click="$router.push('/product/' + p.prodId)"
        >
          <template #footer>
            <div class="product-card-meta">销量 {{ p.sales }} · 现货 {{ p.stock }}</div>
            <div v-if="user.hasPerm('vip:discount')" class="product-card-vip">VIP 价 ¥{{ (p.price * 0.95).toFixed(2) }}</div>
            <a-button v-if="user.hasPerm('cart:manage')" class="ll-add-cart" type="primary" size="small" block @click.stop="addCart(p)">加入购物车</a-button>
            <a-button v-else size="small" block type="default" @click.stop="$router.push('/login')">登录后购买</a-button>
          </template>
        </InteractiveProductCard>
      </a-col>
    </a-row>

    <div v-else class="empty-wrap">
      <a-empty description="没有找到相关商品">
        <template #image><span class="empty-icon">🔍</span></template>
        <a-button type="primary" @click="resetFilters">清空筛选条件</a-button>
      </a-empty>
    </div>

    <div v-if="total > size" class="pager">
      <a-pagination v-model:current="current" :total="total" :page-size="size" @change="reload" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { productApi, homeApi, campaignApi } from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'

const route = useRoute()
const cart = useCartStore()
const user = useUserStore()

// 促销横幅（营销中台·活动系统：满减/折扣/秒杀类，点击进入活动详情）
const promos = ref([])

const industries = ref([])
const list = ref([])
const loading = ref(false)
const keyword = ref('')
const indId = ref(route.query.indId ? Number(route.query.indId) : undefined)
const catId = ref(route.query.catId ? Number(route.query.catId) : undefined)
const sort = ref(route.query.sort || '')
const current = ref(1)
const size = 12
const total = ref(0)

async function reload() {
  loading.value = true
  try {
    const page = await productApi.page({ current: current.value, size, keyword: keyword.value, catId: catId.value, indId: indId.value, sort: sort.value })
    list.value = page.records
    total.value = page.total
  } finally { loading.value = false }
}
async function addCart(p) {
  if (!user.logged) { message.warning('请先登录'); return }
  await cart.add(p.prodId, 1)
  message.success('已加入购物车')
}
function onIndustryChange() {
  catId.value = undefined
  current.value = 1
  reload()
}
function resetFilters() {
  keyword.value = ''
  indId.value = undefined
  catId.value = undefined
  sort.value = ''
  current.value = 1
  reload()
}
onMounted(async () => {
  industries.value = await homeApi.industries()
  campaignApi.list('promo').then(d => { promos.value = d || [] }).catch(() => {})
  reload()
})

watch(
  () => route.query,
  query => {
    const nextIndId = query.indId ? Number(query.indId) : undefined
    const nextCatId = query.catId ? Number(query.catId) : undefined
    const nextSort = query.sort || ''
    if (indId.value !== nextIndId || catId.value !== nextCatId || sort.value !== nextSort) {
      indId.value = nextIndId
      catId.value = nextCatId
      sort.value = nextSort
      current.value = 1
      reload()
    }
  },
  { deep: true }
)
</script>

<style scoped>
.promo-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
             background: linear-gradient(90deg, rgba(245,158,11,.12), rgba(232,121,249,.08));
             border: 1px solid rgba(245,158,11,.35); border-radius: var(--ll-radius-lg, 14px);
             padding: 10px 16px; margin-bottom: 16px; font-size: 13px;
             animation: ll-fade-slide 0.3s var(--ll-ease-out, cubic-bezier(0.22, 1, 0.36, 1)); }
.promo-title { font-weight: 700; color: #b45309; }
.promo-item { color: var(--ll-gray, #4b5563); }
/* 工具栏：容器化 + 粘性，浏览长列表时随时可调整筛选 */
.toolbar { position: sticky; top: 64px; z-index: 10; display: flex; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; align-items: center;
           padding: 12px 14px; background: color-mix(in srgb, var(--ll-card, #fff) 92%, transparent);
           backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
           border: 1px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-lg, 14px);
           box-shadow: var(--ll-shadow-xs); }
.toolbar-search { width: 320px; max-width: 100%; }
.toolbar-count { margin-left: auto; color: var(--ll-muted, #64748b); font-size: 12px; }
/* 骨架卡与真实商品卡同比例，避免加载完成时跳动 */
.mall-skeleton-card { min-height: 330px; display: flex; flex-direction: column; margin-bottom: 16px; }
.mall-skeleton-card .ll-skeleton-card__img { aspect-ratio: auto; flex: 1; }
/* 空状态 */
.empty-wrap { padding: 48px 0; text-align: center; }
.empty-icon { font-size: 52px; display: inline-block; }
/* 平板/手机：工具栏竖向流动，控件占满宽度；粘性顶部贴合移动端双行头部 */
@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: stretch; gap: 10px; top: 0; }
  .toolbar-search,
  .toolbar :deep(.ant-select) { width: 100% !important; }
  .toolbar-count { margin-left: 0; }
}
.prod-card { margin-bottom: 16px; }
:deep(.interactive-product-card) { margin-bottom: 16px; }
.thumb { height: 120px; background: var(--ll-thumb-bg); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 40px; color: var(--ll-thumb-fg); margin-bottom: 8px; overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.ptitle { font-size: 14px; height: 40px; overflow: hidden; }
.pprice { color: #e4393c; font-weight: 700; font-size: 18px; margin: 4px 0; }
.pvip { color: #b45309; font-weight: 600; font-size: 13px; }
.pmeta { color: var(--ll-gray, #4b5563); font-size: 12px; margin-bottom: 8px; }
:deep(.product-card-meta) { color: rgba(255,255,255,.84); font-size: 12px; line-height: 1.45; text-shadow: 0 1px 8px rgba(0,0,0,.2); }
:deep(.product-card-vip) { color: #F7D894; font-size: 12px; font-weight: 750; }
:deep(.interactive-product-card .ant-btn) { min-height: 34px; border-radius: 9px; font-weight: 700; }
.pager { text-align: center; margin: 28px 0 8px; }
</style>
