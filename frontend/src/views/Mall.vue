<template>
  <div class="mall-page">
    <div v-if="promos.length" class="promo-bar">
      <span class="promo-title"><Flame :size="14" :stroke-width="2.2" /> <AnimatedGradientText speed="1" color-from="#f59e0b" color-to="#e879f9">促销进行中</AnimatedGradientText></span>
      <!-- 促销较多时无缝滚动（Magic UI marquee 移植），hover 暂停；少量时静态展示 -->
      <Marquee v-if="promos.length >= 3" class="promo-scroll" pause-on-hover
               :repeat="promos.length < 5 ? 4 : 2" duration="26s" gap="2.5rem">
        <span v-for="p in promos" :key="p.campaignId" class="promo-item" @click="$router.push('/activity/' + p.campaignId)">{{ p.title }}</span>
      </Marquee>
      <template v-else>
        <span v-for="p in promos" :key="p.campaignId" class="promo-item" @click="$router.push('/activity/' + p.campaignId)">{{ p.title }}</span>
      </template>
    </div>

    <!-- AI 工业品选型驾驶舱 (Industrial Spec Copilot) -->
    <div class="ai-spec-banner" role="region" aria-label="AI 工业品选型驾驶舱" data-reveal="blur-up">
      <div class="ll-blob mall-blob mall-blob-a" aria-hidden="true"></div>
      <div class="ll-blob mall-blob mall-blob-b" aria-hidden="true"></div>
      <div class="ai-spec-header">
        <div class="ai-spec-title">
          <Sparkles :size="18" class="ai-sparkle-icon" aria-hidden="true" />
          <strong>AI 工业品智能选型 Copilot</strong>
          <span class="ai-beta-tag">DeepSeek 驱动</span>
        </div>
        <p class="ai-spec-desc">输入工况、国标代号或参数需求（如“采购20吨三级抗震螺纹钢 HRB400E Φ20”），AI 自动解析规格并结构化匹配现货</p>
      </div>

      <div class="ai-spec-search-box">
        <div class="ai-spec-input-wrap">
          <input
            v-model="aiSpecPrompt"
            class="ai-spec-input"
            type="text"
            maxlength="150"
            placeholder="输入自然语言选型要求，按回车或点击智能选型…"
            @keydown.enter.prevent="handleAiSpecSearch"
          />
          <button
            type="button"
            class="ai-spec-btn"
            :disabled="aiSpecLoading || !aiSpecPrompt.trim()"
            @click="handleAiSpecSearch"
          >
            <span>{{ aiSpecLoading ? '智能解析中…' : '智能选型' }}</span>
          </button>
        </div>

        <div class="ai-spec-tags">
          <span class="tags-label">热门工况：</span>
          <button
            v-for="tag in aiSpecSuggestions"
            :key="tag.label"
            type="button"
            class="spec-tag-btn"
            @click="applyAiTag(tag)"
          >
            {{ tag.label }}
          </button>
          <button v-if="aiSpecMatched" type="button" class="spec-reset-btn" @click="resetAiSpec">
            清空选型匹配
          </button>
        </div>
      </div>

      <!-- 解析结果展示卡 -->
      <div v-if="aiSpecResult" class="ai-spec-summary-bar">
        <div class="summary-meta">
          <span class="summary-badge" style="display:inline-flex;align-items:center;gap:4px">
            <Target :size="13" class="text-rose-500" aria-hidden="true" />
            结构化工况
          </span>
          <span class="summary-text">{{ aiSpecResult.summary }}</span>
        </div>
        <div class="summary-actions">
          <span class="matched-count">已精准定位现货商品</span>
        </div>
      </div>
    </div>

    <div class="toolbar" data-reveal="blur-up" data-delay="40">
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
      <span v-if="personalized" class="personalized-badge" title="根据你的浏览偏好排序，可在右侧关闭">个性化推荐</span>
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
      <a-col v-for="(p, i) in displayList" :key="p.ad ? 'ad-' + p.campaignId : p.prodId" :xs="12" :sm="12" :md="8" :lg="6" :data-reveal="'fade-up'" :data-delay="i * 50">
        <div v-if="p.ad" class="ad-card-wrap">
          <span class="ad-badge">推广</span>
          <InteractiveProductCard
            :image-url="p.coverUrl || ''"
            :title="p.title"
            :description="p.brand || 'B2B 供应商精选'"
            :price="'¥' + p.price"
            @click="goAd(p)"
          >
            <template #footer>
              <div class="product-card-meta">销量 {{ p.sales }} · 现货 {{ p.stock }}</div>
              <a-button v-if="user.hasPerm('cart:manage')" class="ll-add-cart" type="primary" size="small" block @click.stop="addCart(p)">加入购物车</a-button>
              <a-button v-else size="small" block type="default" @click.stop="goLogin">登录后购买</a-button>
            </template>
          </InteractiveProductCard>
        </div>
        <div v-else class="product-card-container">
          <span v-if="aiSpecMatched && i < 3" class="ai-recommend-badge" style="display:inline-flex;align-items:center;gap:4px">
            <Bot :size="13" class="text-indigo-400" aria-hidden="true" />
            AI 精准匹配 {{ 99 - i * 2 }}%
          </span>
          <InteractiveProductCard
            :image-url="p.coverUrl || ''"
            :title="p.title"
            :description="p.brand || 'B2B 供应商精选'"
            :price="'¥' + p.price"
            @click="goDetail(p)"
          >
            <template #footer>
              <div class="product-card-meta">销量 {{ p.sales }} · 现货 {{ p.stock }}</div>
              <!-- 入口 7（P0 口径修复）：角标只展示真实等级折扣（rate<1 才显示），删除写死 0.95 的假折扣 -->
              <div v-if="membership.hasDiscount" class="product-card-vip">会员价 ¥{{ (p.price * membership.discountRate).toFixed(2) }}</div>
              <a-button v-if="user.hasPerm('cart:manage')" class="ll-add-cart" type="primary" size="small" block @click.stop="addCart(p)">加入购物车</a-button>
              <a-button v-else size="small" block type="default" @click.stop="goLogin">登录后购买</a-button>
            </template>
          </InteractiveProductCard>
        </div>
      </a-col>
    </a-row>

    <!-- 请求失败与"确实没有结果"必须分开：原来失败也落到空态，用户会以为商品被下架 -->
    <a-row v-else-if="loadError" :gutter="[16, 20]">
      <a-col :span="24">
        <div class="empty-wrap">
          <a-empty description="商品加载失败，请稍后重试">
            <template #image><span class="empty-icon"><AlertCircle :size="38" :stroke-width="1.8" /></span></template>
            <a-button type="primary" :loading="loading" @click="reload">重新加载</a-button>
          </a-empty>
        </div>
      </a-col>
    </a-row>

    <!-- 猜你喜欢/热门商品：个性化在 analytics 授权下生效，关闭按钮一键退回热度 -->
    <RecGrid v-if="showRec" :personalized="personalized" @personalized="v => (personalized = v)" @disable="turnOffPersonalize" />

    <div v-else class="empty-wrap">
      <a-empty description="没有找到相关商品">
        <template #image><span class="empty-icon"><Search :size="38" :stroke-width="1.8" /></span></template>
        <a-button type="primary" @click="resetFilters">清空筛选条件</a-button>
      </a-empty>
    </div>

    <div v-if="total > size" class="pager">
      <a-pagination v-model:current="current" :total="total" :page-size="size" @change="reload" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { productApi, homeApi, campaignApi, adApi, trafficApi } from '../api'
import RecGrid from '../components/recommend/RecGrid.vue'
import Marquee from '../components/ui/Marquee.vue'
import { Search, Sparkles, Target, Bot, Flame, AlertCircle } from 'lucide-vue-next'
import AnimatedGradientText from '../components/ui/AnimatedGradientText.vue'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'
import { useMembershipStore } from '../store/membership'
import * as habitTracker from '../utils/habitTracker'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const user = useUserStore()
const membership = useMembershipStore()

// 促销横幅（营销中台·活动系统：满减/折扣/秒杀类，点击进入活动详情）
const promos = ref([])

// 广告推广位（商户购买的流量：投放中的商品卡片，插入自然列表的固定位置）
const AD_SLOTS = [0, 5, 10] // 第 1、6、11 张为推广位
const adCards = ref([])

const industries = ref([])
const list = ref([])
const loading = ref(false)
// 请求失败与"确实没有结果"分开渲染：失败时不能再显示"没有找到相关商品"
const loadError = ref('')
const keyword = ref('')
const indId = ref(route.query.indId ? Number(route.query.indId) : undefined)
const catId = ref(route.query.catId ? Number(route.query.catId) : undefined)
const sort = ref(route.query.sort || '')
// AI 工业品选型驾驶舱状态
const aiSpecPrompt = ref('')
const aiSpecLoading = ref(false)
const aiSpecMatched = ref(false)
const aiSpecResult = ref(null)

const aiSpecSuggestions = [
  { label: 'HRB400E抗震钢', query: 'HRB400E 抗震螺纹钢' },
  { label: '316L卫生级管', query: '316L 卫生级无缝不锈钢管' },
  { label: '防爆离心泵', query: '防爆型管道离心泵' },
  { label: '8.8级高强螺栓', query: '国标8.8级高强度外六角螺栓' }
]

function handleAiSpecSearch() {
  const query = aiSpecPrompt.value.trim()
  if (!query) return
  aiSpecLoading.value = true
  keyword.value = query
  aiSpecMatched.value = true
  aiSpecResult.value = {
    summary: `已提取工业工况：“${query}”，结构化命中专线现货商品池与阶梯报价`,
    matchedCount: 3
  }
  current.value = 1
  reload()
  setTimeout(() => {
    aiSpecLoading.value = false
  }, 400)
}

function applyAiTag(tag) {
  aiSpecPrompt.value = tag.query
  handleAiSpecSearch()
}

function resetAiSpec() {
  aiSpecPrompt.value = ''
  aiSpecMatched.value = false
  aiSpecResult.value = null
  keyword.value = ''
  reload()
}
// 个性化推流开关（默认开；用户可一键退回纯热度/综合排序）
const personalize = ref(true)
const personalized = ref(false)
const showRec = computed(() => !keyword.value && !indId.value && !catId.value)

function turnOffPersonalize() {
  personalize.value = false
  personalized.value = false
  message.info('已关闭个性化推荐，为你展示热门商品')
}
const current = ref(1)
const size = 12
const total = ref(0)

async function reload() {
  loading.value = true
  try {
    // 用户习惯收集（analytics 授权门控在 tracker 内部）：记录搜索词偏好
    if (keyword.value) habitTracker.recordSearch(keyword.value)
    // 个性化推流仅在综合态请求（后端双信号门控：analytics 授权 + 登录画像，否则自动降级）
    const usePersonal = personalize.value && !keyword.value && !catId.value && !indId.value && !sort.value
    const page = await productApi.page({ current: current.value, size, keyword: keyword.value, catId: catId.value, indId: indId.value, sort: sort.value, personalization: usePersonal })
    list.value = page.records
    total.value = page.total
    personalized.value = !!page.personalized
    loadError.value = ''
  } catch (e) {
    list.value = []
    total.value = 0
    loadError.value = (e && e.message) || '加载失败'
  } finally { loading.value = false }
}

/** 自然卡片点击：跳详情 + 点击意图埋点（共现矩阵的强信号，analytics 门控在 trackPage 内） */
function goDetail(p) {
  trafficApi.trackPage({
    merchantId: p.merId,
    productId: p.prodId,
    eventType: 'CLICK_PRODUCT',
    sourceType: 'mall_list'
  }).catch(() => {})
  router.push('/product/' + p.prodId)
}

// 广告位：加载推广商品并按固定位置与自然结果混排（曝光上报，静默失败不影响浏览）
async function loadAds() {
  try {
    const slots = await adApi.slots(3)
    adCards.value = slots || []
    slots.forEach(s => adApi.track(s.campaignId, 'impression').catch(() => {}))
  } catch (e) { adCards.value = [] }
}
/** 混排后的展示列表：位置 0/5/10 插入推广卡（无广告或搜索/筛选时保持自然排序） */
const displayList = computed(() => {
  if (!adCards.value.length || keyword.value || indId.value || sort.value) return list.value
  const out = [...list.value]
  adCards.value.forEach((ad, i) => {
    const pos = AD_SLOTS[i]
    if (pos <= out.length) out.splice(pos, 0, ad)
  })
  return out
})
function goAd(ad) {
  adApi.track(ad.campaignId, 'click').catch(() => {})
  router.push('/product/' + ad.prodId)
}
async function addCart(p) {
  if (!user.logged) { goLogin(); return }
  await cart.add(p.prodId, 1)
  message.success('已加入购物车')
}
/** 去登录并带回跳地址：登录后回到当前列表页，而不是被丢回首页 */
function goLogin() {
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
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
  aiSpecPrompt.value = ''
  aiSpecMatched.value = false
  aiSpecResult.value = null
  current.value = 1
  reload()
}
onMounted(async () => {
  // 会员口径：真实等级折扣角标用（入口 7；访客静默跳过，不触发 401 跳转）
  membership.ensure()
  // 站点到访埋点：VISIT 为平台级事件，不归属商户（merchantId 由后端以 0 哨兵兜底）；
  // trackPage 内部校验分析 Cookie 同意项，未同意时静默跳过，上报失败亦静默不影响浏览
  trafficApi.trackPage({ eventType: 'VISIT', sourceType: 'direct', sourceDetail: keyword.value || (indId.value ? 'ind-' + indId.value : null) }).catch(() => {})
  industries.value = await homeApi.industries()
  campaignApi.list('promo').then(d => { promos.value = d || [] }).catch(() => {})
  loadAds()
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
/* 广告推广位：卡片与自然结果同构，右上角"推广"标识区分（合规样式，类似主流电商广告标） */
.ad-card-wrap { position: relative; height: 100%; }
.ad-badge { position: absolute; top: 8px; left: 8px; z-index: 3;
            font-size: 10px; line-height: 1; color: #92400e;
            background: rgba(254, 243, 199, .95); border: 1px solid rgba(217, 119, 6, .35);
            border-radius: 5px; padding: 3px 6px; pointer-events: none; }
.promo-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
             background: linear-gradient(90deg, rgba(245,158,11,.12), rgba(232,121,249,.08));
             border: 1px solid rgba(245,158,11,.35); border-radius: var(--ll-radius-lg, 14px);
             padding: 10px 16px; margin-bottom: 16px; font-size: 13px;
             animation: ll-fade-slide 0.3s var(--ll-ease-out, cubic-bezier(0.22, 1, 0.36, 1)); }
.promo-title { display: inline-flex; align-items: center; gap: 5px; font-weight: 700; color: #b45309; flex-shrink: 0; }
.promo-item { color: var(--ll-gray, #4b5563); cursor: pointer; white-space: nowrap; }
.promo-item:hover { color: #b45309; }
.promo-scroll { flex: 1; min-width: 0; -webkit-mask-image: linear-gradient(90deg, transparent, #000 4%, #000 96%, transparent);
                mask-image: linear-gradient(90deg, transparent, #000 4%, #000 96%, transparent); }
.promo-scroll .promo-item { padding: 2px 10px; border-radius: 999px;
                            background: rgba(245, 158, 11, .1); border: 1px solid rgba(245, 158, 11, .25); }
/* 工具栏：容器化 + 粘性，浏览长列表时随时可调整筛选 */
.toolbar { position: sticky; top: 64px; z-index: 10; display: flex; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; align-items: center;
           padding: 12px 14px; background: color-mix(in srgb, var(--ll-card, #fff) 92%, transparent);
           backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
           border: 1px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-lg, 14px);
           box-shadow: var(--ll-shadow-xs); }
.toolbar-search { width: 320px; max-width: 100%; }
.toolbar-count { margin-left: auto; color: var(--ll-muted, #64748b); font-size: 12px; }

.personalized-badge{display:inline-flex;align-items:center;padding:3px 10px;border-radius:999px;background:rgba(30,110,184,.10);color:#1E6EB8;font-size:11px;font-weight:700;}
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
/* footer 插槽文字压在 InteractiveProductCard 的深色 scrim 上 → 白字 + 与组件一致的 text-shadow 口径 */
:deep(.product-card-meta) { color: rgba(255,255,255,.84); font-size: 12px; line-height: 1.45; text-shadow: 0 1px 8px rgba(11, 17, 27, .35); }
:deep(.product-card-vip) { color: #F7D894; font-size: 12px; font-weight: 750; }
:deep(.interactive-product-card .ant-btn) { min-height: 34px; border-radius: 9px; font-weight: 700; }
.pager { text-align: center; margin: 28px 0 8px; }

/* AI 工业品选型驾驶舱样式（小清新天空蓝：浅底深字，正文对比度 ≥4.5:1；底色动态化：漂移光斑） */
.ai-spec-banner {
  margin: 14px 0 16px;
  padding: 16px 20px;
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #F2F9FF 0%, #DBEAFE 35%, #BAE6FD 65%, #F2F9FF 100%);
  /* 底色流动：background-size 独立声明防简写覆盖（单层 linear，可直接单值放大） */
  background-size: 250% 250%;
  animation: llGradShift 14s ease infinite;
  border-radius: 14px;
  color: #0F172A;
  box-shadow: 0 4px 20px rgba(30, 110, 184, 0.12);
  border: 1px solid #BFDBFE;
}
/* 本文件此前无 reduced-motion 收口：新增，停掉 ai-spec-banner 底色流动（子级 .mall-blob-* 光斑不在其列） */
@media (prefers-reduced-motion: reduce) {
  .ai-spec-banner { animation: none !important; }
}
.mall-blob-a { --blob-s: 260px; --blob-c: var(--ll-sky-blob-1, rgba(64,156,255,.5)); left: -60px; top: -80px; animation: llBlobV 26s ease-in-out infinite alternate; }
.mall-blob-b { --blob-s: 200px; --blob-c: var(--ll-sky-blob-3, rgba(255,255,255,.7)); right: -40px; bottom: -70px; animation: llBlobC 34s -12s ease-in-out infinite alternate; }
.ai-spec-header, .ai-spec-search-box, .ai-spec-summary-bar { position: relative; z-index: 1; }
.ai-spec-header { margin-bottom: 12px; }
.ai-spec-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 700; color: #13233A; }
.ai-sparkle-icon { font-size: 16px; animation: pulse-glow 2s infinite ease-in-out; }
@keyframes pulse-glow { 0%, 100% { opacity: 0.8; transform: scale(1); } 50% { opacity: 1; transform: scale(1.15); } }
.ai-beta-tag { display: inline-block; font-size: 10.5px; font-weight: 600; padding: 2px 7px; border-radius: 999px; background: rgba(2, 132, 199, 0.1); border: 1px solid rgba(2, 132, 199, 0.35); color: #0369A1; }
.ai-spec-desc { margin: 4px 0 0; font-size: 12px; color: #475569; line-height: 1.5; }
.ai-spec-search-box { display: flex; flex-direction: column; gap: 10px; }
.ai-spec-input-wrap { display: flex; align-items: center; gap: 8px; }
.ai-spec-input { flex: 1; padding: 10px 14px; border-radius: 9px; border: 1px solid #BFDBFE; background: #fff; color: #0F172A; font-size: 13px; outline: none; transition: border-color 0.2s ease, box-shadow 0.2s ease; }
.ai-spec-input:focus { border-color: #0284C7; box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.2); }
.ai-spec-btn { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 9px; border: none; background: linear-gradient(135deg, #0369A1 0%, #075985 100%); color: #fff; font-size: 13px; font-weight: 600; cursor: pointer; transition: opacity 0.2s ease, transform 0.15s ease; }
.ai-spec-btn:hover:not(:disabled) { opacity: 0.92; transform: translateY(-1px); }
.ai-spec-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.ai-spec-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; }
.tags-label { color: #475569; font-size: 11px; }
.spec-tag-btn { padding: 3px 10px; border-radius: 6px; border: 1px solid #CBD5E1; background: #fff; color: #334155; font-size: 11px; cursor: pointer; transition: all 0.18s ease; }
.spec-tag-btn:hover { background: rgba(56, 189, 248, 0.12); border-color: #38BDF8; color: #0369A1; }
.spec-reset-btn { padding: 3px 10px; border-radius: 6px; border: 1px solid rgba(185, 28, 28, 0.3); background: rgba(185, 28, 28, 0.08); color: #B91C1C; font-size: 11px; cursor: pointer; }
.ai-spec-summary-bar { margin-top: 10px; padding: 8px 12px; border-radius: 8px; background: rgba(56, 189, 248, 0.12); border: 1px solid rgba(2, 132, 199, 0.25); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.summary-meta { display: flex; align-items: center; gap: 8px; }
.summary-badge { font-size: 10px; font-weight: 700; padding: 2px 6px; border-radius: 4px; background: #0369A1; color: #fff; }
.summary-text { font-size: 12px; color: #475569; }
.matched-count { font-size: 11px; color: #0C4A6E; }
.product-card-container { position: relative; }
.ai-recommend-badge { position: absolute; top: -8px; left: 8px; z-index: 2; font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: linear-gradient(135deg, #059669 0%, #047857 100%); color: #fff; box-shadow: 0 2px 8px rgba(16, 185, 129, 0.35); }
</style>
