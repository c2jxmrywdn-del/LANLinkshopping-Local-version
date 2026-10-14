<template>
  <div>
    <div v-if="promos.length" class="promo-bar">
      <span class="promo-title">🔥 促销进行中</span>
      <span v-for="p in promos" :key="p.promoId" class="promo-item">{{ p.title }}</span>
    </div>
    <div class="toolbar">
      <a-input-search v-model:value="keyword" placeholder="搜索商品名称" style="width:320px"
                      allow-clear @search="reload" />
      <a-select v-model:value="indId" style="width:160px" placeholder="全部行业" allow-clear @change="onIndustryChange">
        <a-select-option v-for="i in industries" :key="i.indId" :value="i.indId">{{ i.name }}</a-select-option>
      </a-select>
      <a-select v-model:value="sort" style="width:160px" @change="reload">
        <a-select-option value="">综合排序</a-select-option>
        <a-select-option value="sales">销量优先</a-select-option>
        <a-select-option value="priceAsc">价格升序</a-select-option>
        <a-select-option value="priceDesc">价格降序</a-select-option>
      </a-select>
    </div>

    <a-spin :spinning="loading">
      <a-row :gutter="16">
        <a-col :span="6" v-for="p in list" :key="p.prodId">
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
      <a-empty v-if="!loading && list.length === 0" description="暂无商品" />
    </a-spin>

    <div class="pager">
      <a-pagination v-model:current="current" :total="total" :page-size="size" @change="reload" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { productApi, homeApi, promotionApi } from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'

const route = useRoute()
const cart = useCartStore()
const user = useUserStore()

// 促销横幅（营销中台·促销系统）
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
onMounted(async () => {
  industries.value = await homeApi.industries()
  promotionApi.list().then(d => { promos.value = d || [] }).catch(() => {})
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
             border: 1px solid rgba(245,158,11,.35); border-radius: 8px;
             padding: 8px 14px; margin-bottom: 14px; font-size: 13px; }
.promo-title { font-weight: 700; color: #b45309; }
.promo-item { color: var(--ll-gray, #4b5563); }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.prod-card { margin-bottom: 16px; }
:deep(.interactive-product-card) { margin-bottom: 16px; }
.thumb { height: 120px; background: var(--ll-thumb-bg); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 40px; color: var(--ll-thumb-fg); margin-bottom: 8px; overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.ptitle { font-size: 14px; height: 40px; overflow: hidden; }
.pprice { color: #e4393c; font-weight: 700; font-size: 18px; margin: 4px 0; }
.pvip { color: #b45309; font-weight: 600; font-size: 13px; }
.pmeta { color: #999; font-size: 12px; margin-bottom: 8px; }
:deep(.product-card-meta) { color: rgba(255,255,255,.84); font-size: 12px; line-height: 1.45; text-shadow: 0 1px 8px rgba(0,0,0,.2); }
:deep(.product-card-vip) { color: #F7D894; font-size: 12px; font-weight: 750; }
:deep(.interactive-product-card .ant-btn) { min-height: 34px; border-radius: 9px; font-weight: 700; }
.pager { text-align: center; margin: 24px 0; }
</style>
