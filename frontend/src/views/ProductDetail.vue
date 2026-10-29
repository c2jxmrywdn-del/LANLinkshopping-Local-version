<template>
  <div class="product-page">
    <div v-if="p" class="crumbs">
      <span @click="router.push('/mall')">商城</span>
      <i>/</i>
      <span>{{ p.brand || '商品' }}</span>
      <i>/</i>
      <b>{{ p.title }}</b>
    </div>

    <a-spin :spinning="loading">
      <template v-if="p">
        <section class="product-shell">
          <div class="media-column">
            <!-- 生产线直播（商户导入直播流；直播中/预告/结束三态） -->
            <div v-if="liveRoom" class="live-slot" data-reveal="scale">
              <LivePlayerCard :room="liveRoom" :merchant-name="liveRoom.merchantName || (p.brand || '商户')" />
            </div>
            <div class="gallery" data-reveal>
              <!-- 京东式主图放大镜：悬停出取景框，右侧同步局部放大图 + 规格文字 -->
              <div class="gallery-main" :class="{ 'is-zoomable': zoomEnabled }"
                   @mouseenter="startZoom" @mousemove="onZoomMove" @mouseleave="stopZoom">
                <img v-if="activeImage" ref="mainImgRef" :key="activeIndex" :src="activeImage" :alt="p.title" class="gallery-img" decoding="async" />
                <div v-else class="image-placeholder">{{ p.title.slice(0, 2) }}</div>
                <span v-if="p.stock <= 0" class="sold-out">暂时缺货</span>
                <div v-if="zooming" class="zoom-lens" :style="lensStyle" aria-hidden="true"></div>
              </div>

              <div v-if="zooming" ref="previewRef" class="zoom-preview" :style="previewStyle" aria-hidden="true">
                <div class="zoom-preview-img" :style="previewImgStyle"></div>
                <dl class="zoom-spec">
                  <div><dt>规格</dt><dd>{{ p.spec || '标准规格' }}</dd></div>
                  <div><dt>品牌</dt><dd>{{ p.brand || '—' }}</dd></div>
                  <div><dt>库存</dt><dd>{{ p.stock > 0 ? p.stock + ' 件' : '暂时缺货' }}</dd></div>
                </dl>
              </div>

              <div v-if="gallery.length > 1" class="thumb-list">
                <button v-for="(img, i) in gallery" :key="img + i"
                        class="thumb" :class="{ active: activeIndex === i }"
                        @click="selectImage(i)">
                  <img :src="img" :alt="p.title + '预览图'" loading="lazy" />
                </button>
              </div>
            </div>

            <div class="trust-row">
              <span><CheckCircle2 :size="13" class="text-emerald-500 inline-block align-middle mr-1" /> 正品保障</span>
              <span><CheckCircle2 :size="13" class="text-emerald-500 inline-block align-middle mr-1" /> 安全支付</span>
              <span><CheckCircle2 :size="13" class="text-emerald-500 inline-block align-middle mr-1" /> 售后支持</span>
              <span><CheckCircle2 :size="13" class="text-emerald-500 inline-block align-middle mr-1" /> 商户认证</span>
            </div>
          </div>

          <div class="buy-column">
            <div class="eyebrow">{{ p.brand || '精选商品' }}</div>
            <h1>{{ p.title }}</h1>
            <p v-if="p.subtitle" class="subtitle">{{ p.subtitle }}</p>
            <div class="tts-row">
              <button type="button" class="tts-intro" :class="{ 'is-on': introSpeaking }"
                :aria-label="introSpeaking ? '停止语音介绍' : '播放语音介绍'"
                :title="introSpeaking ? '停止语音介绍' : '播放语音介绍'"
                @click="toggleIntro">
                <Square v-if="introSpeaking" :size="14" class="inline-block align-middle mr-1" />
                <Volume2 v-else :size="15" class="inline-block align-middle mr-1" />
                <span>{{ introSpeaking ? '停止播报' : '语音介绍' }}</span>
              </button>
            </div>

            <button v-if="liveRoom" type="button" class="live-badge" @click="router.push('/live/' + liveRoom.roomId)">
              <i class="live-dot" aria-hidden="true"></i>
              <span class="live-badge-text">正在直播讲解</span>
              <span class="live-badge-meta"><Eye :size="12" :stroke-width="2.2" /> {{ liveRoom.viewerCount || 0 }}</span>
              <ChevronRight :size="14" :stroke-width="2.2" />
            </button>

            <div class="price-card">
              <div class="price-label">到手价</div>
              <div class="price-line">
                <span class="currency">¥</span><strong>{{ money(p.price) }}</strong>
                <span v-if="user.hasPerm('vip:discount')" class="vip-badge">VIP 专享</span>
              </div>
              <div v-if="user.hasPerm('vip:discount')" class="vip-copy">
                VIP 价 ¥{{ money(Number(p.price) * 0.95) }} · 已自动为你应用会员折扣
              </div>
            </div>

            <!-- 价格智能监控：走势 vs 行业基准 + 降价提醒（买家侧，公开数据） -->
            <div class="ptrend-card" v-if="trendLoaded && trendSeries.length">
              <button type="button" class="ptrend-head" @click="ptrendOpen = !ptrendOpen">
                <span class="ptrend-title"><TrendingUp :size="14" :stroke-width="2.2" /> 价格走势 · 对标行业</span>
                <ChevronDown :size="14" class="ptrend-caret" :class="{ open: ptrendOpen }" />
              </button>
              <div v-show="ptrendOpen" class="ptrend-body">
                <PriceChart :series="trendSeries" :height="170" />
                <div class="ptrend-alert">
                  <template v-if="!alertCreated">
                    <a-input-number v-model:value="alertPrice" size="small" :min="0.01" :precision="2"
                                    :placeholder="money(p.price)" style="flex:1;min-width:110px" />
                    <button type="button" class="ptrend-btn" :disabled="alertSaving" @click="subscribeAlert">
                      <BellPlus :size="13" :stroke-width="2.2" /> 降价提醒
                    </button>
                  </template>
                  <span v-else class="ptrend-on"><BellRing :size="13" :stroke-width="2.2" /> 已开启降价提醒，到价站内通知</span>
                </div>
              </div>
            </div>

            <div class="decision-list">
              <div class="decision-row">
                <span class="label">销量</span>
                <span class="value">{{ p.sales || 0 }}+ 件已售</span>
              </div>
              <div class="decision-row">
                <span class="label">库存</span>
                <span class="value" :class="{ danger: p.stock <= 5 }">
                  {{ p.stock > 0 ? (p.stock <= 5 ? '仅剩 ' + p.stock + ' 件' : p.stock + ' 件') : '暂时缺货' }}
                </span>
              </div>
              <div class="decision-row">
                <span class="label">规格</span>
                <span class="value">{{ p.spec || '标准规格' }}</span>
              </div>
            </div>

            <div class="service-card">
              <div><span class="service-icon"><AppleIcon name="truck" :size="15" /></span><b>配送</b><span>下单后按商户规则发货</span></div>
              <div><span class="service-icon"><AppleIcon name="reset" :size="15" /></span><b>售后</b><span>以商品及商户售后政策为准</span></div>
            </div>

            <div class="purchase-area">
              <div class="quantity-line">
                <span>购买数量</span>
                <a-input-number v-model:value="qty" :min="1" :max="Math.max(1, p.stock || 1)" :disabled="p.stock <= 0" />
              </div>
              <div class="action-row">
                <a-button v-if="user.hasPerm('cart:manage')" class="cart-btn" size="large"
                          :disabled="p.stock <= 0" @click="addCart">
                  加入购物车
                </a-button>
                <a-button v-if="user.hasPerm('order:create')" class="buy-btn" type="primary" size="large"
                          :disabled="p.stock <= 0" @click="buyNow">
                  立即购买
                </a-button>
                <a-button v-if="!user.logged" class="buy-btn" type="primary" size="large" @click="router.push('/login')">
                  登录后购买
                </a-button>
              </div>
              <!-- 联系商家：不受库存/登录限制，携带商品与商家上下文进入对话 -->
              <button v-if="p.merId" type="button" class="contact-merchant" @click="contactMerchant">
                <MessageCircle :size="14" :stroke-width="2" />
                联系商家
              </button>
              <p class="secure-note"><Lock :size="13" class="inline-block mr-1 align-middle text-slate-400" /> 交易过程受平台安全机制保护</p>
            </div>
          </div>
        </section>

        <section class="detail-shell">
          <div class="detail-nav">
            <a href="#description" :class="{ active: activeSection === 'description' }" @click="jumpTo($event, 'description')">商品介绍</a>
            <a href="#parameters" :class="{ active: activeSection === 'parameters' }" @click="jumpTo($event, 'parameters')">规格参数</a>
            <a href="#service" :class="{ active: activeSection === 'service' }" @click="jumpTo($event, 'service')">服务说明</a>
          </div>

          <div id="description" class="detail-section">
            <div class="section-heading">
              <span>PRODUCT STORY</span>
              <h2>商品介绍</h2>
            </div>
            <div class="detail-copy">{{ p.detail || '暂无详细介绍' }}</div>
          </div>

          <div id="parameters" class="detail-section">
            <div class="section-heading">
              <span>SPECIFICATIONS</span>
              <h2>规格参数</h2>
            </div>
            <div class="parameter-grid">
              <div><span>品牌</span><b>{{ p.brand || '—' }}</b></div>
              <div><span>规格</span><b>{{ p.spec || '标准规格' }}</b></div>
              <div><span>库存</span><b>{{ p.stock || 0 }}</b></div>
              <div><span>累计销量</span><b>{{ p.sales || 0 }}</b></div>
              <div><span>商品编号</span><b>{{ p.prodId }}</b></div>
              <div><span>商户编号</span><b>{{ p.merId || '—' }}</b></div>
            </div>
          </div>

          <div id="service" class="detail-section service-section">
            <div class="section-heading">
              <span>SERVICE</span>
              <h2>服务说明</h2>
            </div>
            <div class="service-grid">
              <div><b>正品与交易</b><p>商品信息以商户实际发布内容为准，订单通过平台流程完成。</p></div>
              <div><b>配送与履约</b><p>配送时效、运费及具体履约规则以商户页面及订单信息为准。</p></div>
              <div><b>售后服务</b><p>如遇商品或订单问题，可根据平台及商户售后规则申请处理。</p></div>
            </div>
          </div>

          <!-- 看了又看：共现协同过滤推荐（冷启动回退同类目热度） -->
          <div id="similar" class="detail-section similar-section">
            <div class="section-heading">
              <span>RECOMMEND</span>
              <h2>看了又看</h2>
            </div>
            <div v-if="similarItems.length" class="similar-grid">
              <div v-for="s in similarItems" :key="s.prodId" class="similar-card"
                   @click="$router.push('/product/' + s.prodId)">
                <img :src="s.coverUrl || ''" :alt="s.title" loading="lazy" />
                <p class="similar-name">{{ s.title }}</p>
                <span class="similar-price">¥{{ s.price }}</span>
              </div>
            </div>
            <p v-else class="similar-empty">暂无相关推荐</p>
          </div>
        </section>

        <div class="mobile-buybar">
          <div><small>合计</small><strong>¥{{ money(Number(p.price) * qty * vipRate) }}</strong></div>
          <button v-if="p.merId" type="button" class="contact-merchant mobile" aria-label="联系商家" @click="contactMerchant">
            <MessageCircle :size="17" :stroke-width="2" />
            <span class="cm-label">联系商家</span>
          </button>
          <a-button v-if="user.hasPerm('cart:manage')" class="cart-btn" @click="addCart" :disabled="p.stock <= 0">加购</a-button>
          <a-button type="primary" class="buy-btn" @click="user.logged ? buyNow() : router.push('/login')" :disabled="p.stock <= 0">
            {{ user.logged ? '立即购买' : '登录购买' }}
          </a-button>
        </div>
      </template>

      <a-empty v-else-if="!loading" description="商品不存在或已下架" />
    </a-spin>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CheckCircle2, Volume2, Square, Lock ,TrendingUp, ChevronDown, BellPlus, BellRing, Eye, ChevronRight, MessageCircle} from 'lucide-vue-next'
import PriceChart from '../components/ui/PriceChart.vue'
import AppleIcon from '../components/ui/AppleIcon.vue'
import LivePlayerCard from '../components/product/LivePlayerCard.vue'
import { chatApi } from '../api'
import { message } from 'ant-design-vue'
import { productApi, trafficApi, priceMonitorApi, liveApi } from '../api'
import * as habitTracker from '../utils/habitTracker'
import { recommendApi } from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'
import { translateDataText, locale } from '../i18n'
import { speakText, stopSpeaking } from '../utils/tts'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const user = useUserStore()
const p = ref(null)
const qty = ref(1)
const loading = ref(true)
const similarItems = ref([])
const activeIndex = ref(0)
const introSpeaking = ref(false)

// 直播中徽章：该商品正在某直播间讲解时展示（匿名可访问，silent 吸收游客 401）
const liveRoom = ref(null)

/** 商品语音介绍：标题 + 品牌 + 规格 + 详情，英文模式下先按词表翻译 */
function toggleIntro() {
  if (introSpeaking.value) {
    stopSpeaking()
    introSpeaking.value = false
    return
  }
  if (!p.value) return
  // 逐段翻译后再拼接：整段拼接会被「防中英混杂」护栏整体回退成中文
  const parts = [p.value.title, p.value.brand, p.value.spec, p.value.subtitle, p.value.detail]
    .filter(Boolean)
    .map(part => translateDataText(String(part)))
  const text = parts.join(locale.value === 'en-US' ? '. ' : '。')
  introSpeaking.value = speakText(text, {
    rate: 0.95,
    onEnd: () => { introSpeaking.value = false }
  })
}

// 详情导航滚动联动：IntersectionObserver 追踪当前可见区块
const activeSection = ref('description')
let sectionObserver = null
function setupSectionObserver() {
  if (typeof IntersectionObserver === 'undefined') return
  sectionObserver = new IntersectionObserver((entries) => {
    // 取可见比例最大的区块作为当前激活项
    const visible = entries.filter(e => e.isIntersecting)
    if (!visible.length) return
    visible.sort((a, b) => b.intersectionRatio - a.intersectionRatio)
    activeSection.value = visible[0].target.id
  }, { rootMargin: '-20% 0px -55% 0px', threshold: [0, 0.2, 0.5] })
  ;['description', 'parameters', 'service'].forEach((id) => {
    const el = document.getElementById(id)
    if (el) sectionObserver.observe(el)
  })
}
function jumpTo(e, id) {
  e.preventDefault()
  activeSection.value = id
  const el = document.getElementById(id)
  if (!el) return
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
}
onBeforeUnmount(() => {
  if (sectionObserver) sectionObserver.disconnect()
  window.removeEventListener('resize', updateZoomEnabled)
  stopSpeaking()
})

const vipRate = computed(() => user.hasPerm('vip:discount') ? 0.95 : 1)
const gallery = computed(() => {
  const raw = [p.value?.coverUrl, ...(Array.isArray(p.value?.images) ? p.value.images : [])]
  return [...new Set(raw.filter(Boolean))]
})
const activeImage = computed(() => gallery.value[activeIndex.value] || '')

// ===== 主图放大镜（京东式）：取景框跟随鼠标，右侧同步放大 + 规格文字 =====
const mainImgRef = ref(null)
const previewRef = ref(null)
const zooming = ref(false)
const zoomEnabled = ref(false)
const lensStyle = ref({ opacity: 0 })
const previewStyle = ref({})
const previewImgStyle = ref({ opacity: 0 })
let zoomBox = null
let zoomRect = null

function updateZoomEnabled() {
  zoomEnabled.value = !!window.matchMedia?.('(hover: hover) and (pointer: fine)').matches
    && window.innerWidth >= 900
  if (!zoomEnabled.value) stopZoom()
}

function startZoom() {
  const img = mainImgRef.value
  if (!zoomEnabled.value || !img || !img.naturalWidth) return
  const rect = img.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  // object-fit: contain 下图片实际显示区域小于容器，需按内容尺寸计算，避免放大到留白
  const scale = Math.min(rect.width / img.naturalWidth, rect.height / img.naturalHeight)
  const dispW = img.naturalWidth * scale
  const dispH = img.naturalHeight * scale
  zoomRect = rect
  zoomBox = {
    dispW,
    dispH,
    offsetX: (rect.width - dispW) / 2,
    offsetY: (rect.height - dispH) / 2
  }
  previewStyle.value = { '--zoom-ratio': `${dispW} / ${dispH}` }
  previewImgStyle.value = { opacity: 0 }
  zooming.value = true
}

function onZoomMove(e) {
  if (!zooming.value || !zoomBox || !zoomRect) return
  const { dispW, dispH, offsetX, offsetY } = zoomBox
  const lensW = Math.max(96, dispW * 0.32)
  const lensH = Math.max(96, dispH * 0.32)
  const mx = e.clientX - zoomRect.left - offsetX
  const my = e.clientY - zoomRect.top - offsetY
  // 鼠标落在图片留白区时隐藏取景框与放大图，避免出现无意义空白
  if (mx < 0 || my < 0 || mx > dispW || my > dispH) {
    lensStyle.value = { opacity: 0 }
    previewImgStyle.value = { opacity: 0 }
    return
  }
  const x = Math.min(Math.max(0, mx - lensW / 2), Math.max(0, dispW - lensW))
  const y = Math.min(Math.max(0, my - lensH / 2), Math.max(0, dispH - lensH))
  lensStyle.value = {
    opacity: 1,
    width: `${lensW}px`,
    height: `${lensH}px`,
    transform: `translate(${offsetX + x}px, ${offsetY + y}px)`
  }
  // 放大倍数 = 预览区宽度 / 取景框宽度，背景位置按同一比例反向偏移
  const previewW = previewRef.value?.clientWidth || 420
  const ratio = previewW / lensW
  previewImgStyle.value = {
    opacity: 1,
    backgroundImage: `url("${activeImage.value}")`,
    backgroundSize: `${dispW * ratio}px ${dispH * ratio}px`,
    backgroundPosition: `${-x * ratio}px ${-y * ratio}px`
  }
}

function stopZoom() {
  zooming.value = false
  zoomBox = null
  zoomRect = null
}

function selectImage(i) {
  stopZoom()
  activeIndex.value = i
}

function money(value) {
  return Number(value || 0).toFixed(2)
}

// ===== 价格智能监控：走势（公开）+ 降价提醒（登录） =====
const trendLoaded = ref(false)
const ptrendOpen = ref(false)
const trendSeries = ref([])
const alertPrice = ref(null)
const alertSaving = ref(false)
const alertCreated = ref(false)

async function loadPriceTrend() {
  try {
    const t = await priceMonitorApi.trend(route.params.id, 30)
    const product = (t?.series || []).map(x => ({ label: x.day, value: Number(x.price) }))
    if (t?.product?.price && product.length) {
      const today = new Date().toISOString().slice(0, 10)
      if (!product.some(x => x.label === today)) product.push({ label: today, value: Number(t.product.price) })
    }
    const bench = (t?.benchmark || []).map(b => ({ label: String(b.day), value: Number(b.benchmark) }))
    const out = []
    if (product.length) out.push({ name: '商品价', color: '#1E6EB8', data: product })
    if (bench.length) out.push({ name: '行业基准', color: '#c97e12', data: bench, dashed: true, width: 1.6, fill: false })
    trendSeries.value = out
    // 已有订阅则直接显示"已开启"态
    try {
      const mine = await priceMonitorApi.productAlerts(route.params.id, { silent: true })
      alertCreated.value = (mine || []).some(a => a.conditionType === 'BELOW')
    } catch (e) { /* 未登录静默 */ }
  } catch (e) { /* 走势加载失败静默：不阻塞商品主流程 */ } finally {
    trendLoaded.value = true
  }
}

async function subscribeAlert() {
  if (needLogin()) return
  const threshold = alertPrice.value ?? Number(p.value?.price)
  if (!threshold || threshold <= 0) return
  alertSaving.value = true
  try {
    await priceMonitorApi.subscribe({ prodId: Number(route.params.id), conditionType: 'BELOW', threshold })
    alertCreated.value = true
    message.success('已开启降价提醒，价格到点站内通知')
  } catch (e) { /* 拦截器已提示 */ } finally { alertSaving.value = false }
}

onMounted(async () => {
  loadPriceTrend()
  updateZoomEnabled()
  window.addEventListener('resize', updateZoomEnabled)
  // 直播中徽章：匿名可访问；silent 吸收游客 401；从直播间跳转时写入归因标记供结算携带
  liveApi.roomByProduct(route.params.id, { silent: true })
    .then(r => { liveRoom.value = r || null })
    .catch(() => { liveRoom.value = null })
  if (route.query.liveId) {
    try {
      sessionStorage.setItem('ll_live_attr', JSON.stringify({
        liveId: Number(route.query.liveId),
        prodId: Number(route.params.id),
        ts: Date.now()
      }))
    } catch (e) { /* 存储不可用时忽略 */ }
  }
  try {
    p.value = await productApi.detail(route.params.id)
    if (p.value?.merId) {
      trafficApi.trackPage({
        merchantId: p.value.merId,
        productId: p.value.prodId,
        eventType: 'VIEW_PRODUCT',
        sourceType: route.query.source || 'direct',
        sourceDetail: route.query.keyword || null
      }).catch(() => {})
    }
    // 用户习惯收集（analytics 授权门控在 tracker 内部）：商品浏览偏好
    try {
      habitTracker.recordProductView({
        prodId: p.value.prodId,
        title: p.value.title,
        categoryName: p.value.catName || p.value.categoryName || null
      })
    } catch (e) { /* 收集永不影响浏览 */ }
    // 看了又看：静默失败不影响详情页
    recommendApi.similar(route.params.id, 6).then(r => { similarItems.value = r?.items || [] }).catch(() => {})
    nextTick(setupSectionObserver)
  } catch (e) {
    message.error('商品信息加载失败')
  } finally {
    loading.value = false
  }
})

function needLogin() {
  if (!user.logged) {
    message.warning('请先登录')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return true
  }
  return false
}

/** 联系商家：未登录先登录；已登录则携带商家与商品上下文 find-or-create 会话后进入对话页 */
async function contactMerchant() {
  if (needLogin()) return
  if (!p.value?.merId) { message.error('该商品缺少商家信息，暂时无法联系'); return }
  try {
    const s = await chatApi.start(p.value.merId, p.value.prodId)
    router.push('/chat/' + s.sessionId)
  } catch (e) { /* 拦截器已提示 */ }
}

async function addCart() {
  if (needLogin()) return
  await cart.add(p.value.prodId, qty.value)
  trafficApi.trackPage({
    merchantId: p.value.merId,
    productId: p.value.prodId,
    eventType: 'ADD_CART',
    sourceType: 'product_detail'
  }).catch(() => {})
  message.success('已加入购物车')
}

async function buyNow() {
  if (needLogin()) return
  await cart.add(p.value.prodId, qty.value)
  trafficApi.trackPage({
    merchantId: p.value.merId,
    productId: p.value.prodId,
    eventType: 'CHECKOUT',
    sourceType: 'product_detail'
  }).catch(() => {})
  router.push('/cart')
}
</script>

<style scoped>
.product-page {
.similar-section{margin-top:26px}.similar-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.similar-card{border:1px solid #edf0f3;border-radius:12px;overflow:hidden;cursor:pointer;transition:.18s ease;background:#fff}.similar-card:hover{transform:translateY(-2px);box-shadow:0 10px 24px rgba(15,23,42,.08)}.similar-card img{width:100%;height:120px;object-fit:cover;display:block;background:#eef1f5}.similar-name{margin:8px 10px 4px;color:#13233A;font-size:12px;line-height:1.4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:33px}.similar-price{display:block;padding:0 10px 10px;color:#C2410C;font-weight:800;font-size:13px}.similar-empty{color:#98A2B3;font-size:12px;margin:8px 0}@media (max-width:767px){.similar-grid{grid-template-columns:repeat(2,1fr)}.similar-card img{height:100px}}
 --ink:#18212f; --muted:#6b7280; --line:#e8e5de; --paper:#fbf9f1; --red:#9b2d20; --gold:#8A6A2F; max-width:1240px; margin:0 auto; padding:8px 20px 90px; color:var(--ink); }
.crumbs { display:flex; gap:10px; align-items:center; padding:10px 0 18px; color:#4b5563; font-size:13px; }
.crumbs span { cursor:pointer; }.crumbs b { color:#343a45; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.crumbs i { font-style:normal; color:#c7c3bb; }
.product-shell { display:grid; grid-template-columns:minmax(0,1.04fr) minmax(420px,.96fr); gap:48px; background:#fff; border:1px solid var(--line); border-radius:20px; padding:24px; box-shadow:0 10px 35px rgba(24,33,47,.05); }
.gallery-main { position:relative; aspect-ratio:1/1; max-height:570px; border-radius:16px; background:var(--paper); overflow:hidden; display:flex; align-items:center; justify-content:center; }
.gallery-main img { width:100%; height:100%; object-fit:contain; }.image-placeholder { font-size:80px; color:#a79f90; }
/* 京东式放大镜：取景框 + 右侧放大预览（含规格文字） */
.gallery { position:relative; }
.gallery-main.is-zoomable { cursor:crosshair; }
.zoom-lens { position:absolute; left:0; top:0; z-index:2; pointer-events:none; border:1px solid rgba(24,33,47,.45); border-radius:3px; background:rgba(255,255,255,.28); box-shadow:inset 0 0 0 1px rgba(255,255,255,.55); will-change:transform,width,height; transition:opacity .12s ease; }
.zoom-preview { position:absolute; left:calc(100% + 18px); top:0; z-index:6; width:min(420px, 33vw); overflow:hidden; pointer-events:none; background:#fff; border:1px solid var(--line); border-radius:16px; box-shadow:0 26px 60px rgba(24,33,47,.2); animation:zoomIn .14s ease-out; }
.zoom-preview-img { width:100%; aspect-ratio:var(--zoom-ratio, 1); background-color:var(--paper); background-repeat:no-repeat; transition:opacity .12s ease; }
.zoom-spec { margin:0; padding:12px 14px; display:grid; gap:7px; border-top:1px solid var(--line); background:#fffdf8; }
.zoom-spec div { display:flex; align-items:baseline; gap:10px; font-size:12px; line-height:1.5; }
.zoom-spec dt { flex:0 0 42px; color:#8d8579; }
.zoom-spec dd { margin:0; min-width:0; color:#303743; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
@keyframes zoomIn { from { opacity:0; transform:translateX(-4px); } to { opacity:1; transform:translateX(0); } }
@media (max-width:1100px) { .zoom-preview { width:min(340px, 30vw); } }
@media (max-width:900px) { .zoom-preview, .zoom-lens { display:none; } }
/* 图集切换：淡入过渡（key 变化触发） */
.gallery-img { animation: galleryFade .38s cubic-bezier(.22,1,.36,1); }
@keyframes galleryFade { from { opacity:0; transform:scale(1.015); } to { opacity:1; transform:scale(1); } }
.sold-out { position:absolute; top:18px; left:18px; background:#18212f; color:#fff; padding:6px 11px; border-radius:99px; font-size:12px; }
.thumb-list { display:flex; gap:10px; margin-top:12px; overflow:auto; }.thumb { flex:0 0 68px; width:68px; height:68px; padding:3px; border:1px solid var(--line); border-radius:10px; background:#fff; cursor:pointer; }.thumb.active { border:2px solid var(--ink); padding:2px; }.thumb img { width:100%; height:100%; object-fit:cover; border-radius:6px; }
.trust-row { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin-top:18px; color:#68707c; font-size:12px; }.trust-row span { text-align:center; }.trust-row strong { color:#3f6b4b; margin-right:4px; }
.buy-column { padding:8px 4px; }.eyebrow { color:var(--gold); text-transform:uppercase; letter-spacing:.12em; font-size:11px; font-weight:800; }.buy-column h1 { margin:8px 0 8px; font-size:30px; line-height:1.3; letter-spacing:-.02em; }.subtitle { margin:0 0 18px; color:var(--muted); line-height:1.7; }
.tts-row { margin:0 0 16px; }
.tts-intro { display:inline-flex; align-items:center; gap:6px; height:32px; padding:0 13px; border:1px solid var(--line, #d9d3c7); border-radius:999px; background:#fff; color:var(--ink, #13233a); font:inherit; font-size:12px; font-weight:600; cursor:pointer; transition:background .18s ease,border-color .18s ease,color .18s ease; }
.tts-intro:hover { border-color:var(--gold, #c8a45c); background:#fbf7ee; }
.tts-intro.is-on { border-color:var(--gold, #c8a45c); color:var(--gold, #a97f2d); background:#fdf8ea; }
.price-card { padding:17px 18px; background:linear-gradient(135deg,#fbf9f1,#f6f1e7); border-radius:14px; }.price-label { color:#4b5563; font-size:12px; }.price-line { display:flex; align-items:baseline; gap:3px; color:var(--red); }.price-line strong { font-size:38px; letter-spacing:-.04em; }.currency { font-size:18px; font-weight:800; }.vip-badge { margin-left:10px; background:#efe4c8; color:#765c25; padding:4px 7px; border-radius:5px; font-size:11px; font-weight:700; }.vip-copy { margin-top:4px; color:#846b35; font-size:12px; }
.decision-list { margin:20px 0; border-top:1px solid var(--line); }.decision-row { display:flex; gap:18px; padding:13px 0; border-bottom:1px solid var(--line); font-size:13px; }.decision-row .label { flex:0 0 48px; color:#4b5563; }.decision-row .value { color:#303743; font-weight:600; }.decision-row .danger { color:var(--red); }
.service-card { padding:14px 16px; border:1px solid var(--line); border-radius:12px; background:#fffdf8; }.service-card div { display:flex; align-items:center; gap:9px; font-size:12px; margin:7px 0; }.service-card b { width:32px; }.service-card span:last-child { color:#4b5563; }.service-icon { color:var(--gold); font-weight:800; }
.purchase-area { margin-top:20px; }.quantity-line { display:flex; align-items:center; gap:14px; color:#555; font-size:13px; margin-bottom:13px; }.action-row { display:flex; gap:10px; }.cart-btn,.buy-btn { min-width:150px; height:46px; border-radius:9px; font-weight:700; }.cart-btn { border-color:#9b2d20; color:#9b2d20; }.buy-btn { background:#18212f; border-color:#18212f; }.secure-note { margin:10px 0 0; color:#4b5563; font-size:11px; }
/* 联系商家：描边胶囊按钮，不依赖库存/登录状态 */
.contact-merchant { display:inline-flex; align-items:center; gap:6px; width:100%; margin-top:10px;
  height:40px; border:1px solid var(--line,#e8e5de); border-radius:9px; background:#fff;
  color:var(--ink,#18212f); font:inherit; font-size:13px; font-weight:600; cursor:pointer;
  transition:border-color .18s ease, background .18s ease, color .18s ease; }
.contact-merchant:hover { border-color:var(--ll-azure,#0EA5E9); color:var(--ll-azure,#0EA5E9); background:rgba(14,165,233,.05); }
.contact-merchant.mobile { width:40px; height:40px; padding:0; justify-content:center; margin-top:0; border-radius:10px; }
.detail-shell { margin-top:24px; background:#fff; border:1px solid var(--line); border-radius:20px; overflow:hidden; }.detail-nav { display:flex; gap:34px; padding:0 28px; border-bottom:1px solid var(--line); position:sticky; top:64px; z-index:2; background:rgba(255,255,255,.94); backdrop-filter:blur(10px); }.detail-nav a { padding:18px 0; font-size:14px; font-weight:700; color:#70757e; border-bottom:2px solid transparent; transition:color .2s ease, border-color .2s ease; }.detail-nav a:hover { color:var(--ink); }.detail-nav a.active { color:var(--ink); border-bottom-color:var(--ink); }.detail-section { padding:40px 42px; border-bottom:1px solid var(--line); scroll-margin-top:130px; }.detail-section:last-child { border-bottom:0; }.section-heading span { color:var(--gold); font-size:10px; font-weight:800; letter-spacing:.16em; }.section-heading h2 { margin:5px 0 22px; font-size:24px; }.detail-copy { white-space:pre-wrap; color:#525965; line-height:2; font-size:14px; max-width:900px; }.parameter-grid { display:grid; grid-template-columns:repeat(3,1fr); border-top:1px solid var(--line); border-left:1px solid var(--line); }.parameter-grid div { min-height:62px; padding:12px 15px; border-right:1px solid var(--line); border-bottom:1px solid var(--line); display:flex; flex-direction:column; gap:5px; }.parameter-grid span { color:#4b5563; font-size:11px; }.parameter-grid b { font-size:13px; font-weight:600; }.service-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:14px; }.service-grid div { padding:18px; border:1px solid var(--line); border-radius:12px; background:#fffdf8; }.service-grid b { font-size:14px; }.service-grid p { color:#4b5563; line-height:1.7; font-size:12px; margin:8px 0 0; }
.mobile-buybar { display:none; }
@media (max-width:900px) { .product-shell { grid-template-columns:1fr; gap:24px; padding:16px; }.gallery-main { max-height:none; }.buy-column { padding:0; }.detail-section { padding:30px 24px; }.parameter-grid { grid-template-columns:repeat(2,1fr); }.service-grid { grid-template-columns:1fr; } }
@media (max-width:600px) { .product-page { padding:0 12px 124px; }.crumbs { padding:8px 2px 12px; font-size:12px; }.product-shell { border-radius:14px; padding:10px; border-left:0; border-right:0; box-shadow:none; margin:0 -12px; }.buy-column h1 { font-size:23px; }.price-line strong { font-size:32px; }.trust-row { grid-template-columns:repeat(2,1fr); gap:12px; }.action-row { display:none; }.detail-shell { margin-top:14px; border-radius:14px; }.detail-nav { gap:20px; padding:0 18px; overflow:auto; }.detail-nav a { white-space:nowrap; }.detail-section { padding:26px 18px; }.parameter-grid { grid-template-columns:1fr; }.mobile-buybar { display:flex; position:fixed; z-index:20; left:0; right:0; bottom:0; padding:9px 12px calc(9px + env(safe-area-inset-bottom)); background:rgba(255,255,255,.97); backdrop-filter:blur(12px); border-top:1px solid var(--line); align-items:center; gap:8px; box-shadow:0 -5px 20px rgba(0,0,0,.08); }.mobile-buybar > div { flex:1; display:flex; flex-direction:column; }.mobile-buybar small { color:#4b5563; font-size:10px; }.mobile-buybar strong { color:var(--red); font-size:18px; }.mobile-buybar .cart-btn,.mobile-buybar .buy-btn { min-width:72px; height:40px; }.mobile-buybar .buy-btn { color:#fff; }
  /* PC 版联系按钮在手机上隐藏：底部操作条悬浮胶囊已是唯一入口，避免双按钮 */
  .purchase-area .contact-merchant { display:none; }
  /* 移动端「联系商家」：底部操作条上方右侧悬浮胶囊，保留文字文案且不挤压加购/购买按钮 */
  .mobile-buybar .contact-merchant.mobile { position:absolute; right:12px; bottom:calc(100% + 8px); width:auto; height:36px; padding:0 13px; gap:6px; font-size:12px; border-radius:999px; box-shadow:0 8px 22px rgba(19,35,58,.16); }
  .mobile-buybar .contact-merchant.mobile .cm-label { display:inline; } }
.ptrend-card { margin: 14px 0 4px; border: 1px solid var(--ll-border, #e5e7eb);
  border-radius: var(--ll-radius-lg, 14px); background: var(--ll-card-soft, #fbfcfe); overflow: hidden; }
.ptrend-head { width: 100%; display: flex; justify-content: space-between; align-items: center;
  border: none; background: transparent; cursor: pointer; padding: 11px 14px; }
.ptrend-title { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600;
  color: var(--ll-ink, #1d1d1f); }
.ptrend-title svg { color: var(--ll-primary, #1E6EB8); }
.ptrend-caret { transition: transform .25s var(--ll-ease-out, ease); color: var(--ll-muted, #64748b); }
.ptrend-caret.open { transform: rotate(180deg); }
.ptrend-body { padding: 0 14px 12px; }
.ptrend-alert { display: flex; gap: 8px; margin-top: 8px; align-items: center; flex-wrap: wrap; }
.ptrend-btn { display: inline-flex; align-items: center; gap: 5px; border: none; cursor: pointer;
  font-size: 12.5px; font-weight: 600; color: #fff; padding: 6px 14px; border-radius: 999px;
  background: var(--ll-primary, #1E6EB8); transition: transform .2s var(--ll-ease-out, ease), box-shadow .2s; }
.ptrend-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(30,110,184,.3); }
.ptrend-btn:disabled { opacity: .55; cursor: not-allowed; }
.ptrend-on { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: #047857; font-weight: 600; }

/* 直播中徽章条（价格卡上方）：红点闪烁 + 观看人数，点击进入直播间 */
.live-badge {
  display: flex; align-items: center; gap: 8px; width: 100%;
  margin: 0 0 10px; padding: 9px 13px;
  border: 1px solid rgba(155, 45, 32, .18);
  border-radius: 12px; cursor: pointer;
  background: linear-gradient(90deg, rgba(155, 45, 32, .08), rgba(155, 45, 32, .03));
  font: inherit; text-align: left;
  transition: transform .2s ease, box-shadow .2s ease;
}
.live-badge:hover { transform: translateY(-2px); box-shadow: 0 1px 1px rgba(0,0,0,.04), 0 8px 20px rgba(0,0,0,.08); }
.live-dot { flex-shrink: 0; width: 7px; height: 7px; border-radius: 50%; background: var(--ll-brand-red, #9B2D20); animation: live-dot-blink 1.2s ease-in-out infinite; }
@keyframes live-dot-blink { 0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(155,45,32,.4); } 50% { opacity: .35; box-shadow: 0 0 0 4px rgba(155,45,32,0); } }
.live-badge-text { font-size: 13px; font-weight: 700; color: var(--ll-brand-red, #9B2D20); letter-spacing: -.01em; }
.live-badge-meta { display: inline-flex; align-items: center; gap: 3px; margin-left: auto; font-size: 12px; color: var(--ll-muted, #64748B); font-variant-numeric: tabular-nums; }
.live-badge > svg:last-child { color: var(--ll-muted, #64748B); flex-shrink: 0; }
@media (max-width: 860px) { .live-badge-text { font-size: 12.5px; } }
</style>
