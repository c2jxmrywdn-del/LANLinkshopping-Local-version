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
            <div class="gallery">
              <div class="gallery-main">
                <img v-if="activeImage" :src="activeImage" :alt="p.title" decoding="async" />
                <div v-else class="image-placeholder">{{ p.title.slice(0, 2) }}</div>
                <span v-if="p.stock <= 0" class="sold-out">暂时缺货</span>
              </div>
              <div v-if="gallery.length > 1" class="thumb-list">
                <button v-for="(img, i) in gallery" :key="img + i"
                        class="thumb" :class="{ active: activeIndex === i }"
                        @click="activeIndex = i">
                  <img :src="img" :alt="p.title + '预览图'" loading="lazy" />
                </button>
              </div>
            </div>

            <div class="trust-row">
              <span><strong>✓</strong> 正品保障</span>
              <span><strong>✓</strong> 安全支付</span>
              <span><strong>✓</strong> 售后支持</span>
              <span><strong>✓</strong> 商户认证</span>
            </div>
          </div>

          <div class="buy-column">
            <div class="eyebrow">{{ p.brand || '精选商品' }}</div>
            <h1>{{ p.title }}</h1>
            <p v-if="p.subtitle" class="subtitle">{{ p.subtitle }}</p>

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
              <div><span class="service-icon">◇</span><b>配送</b><span>下单后按商户规则发货</span></div>
              <div><span class="service-icon">↺</span><b>售后</b><span>以商品及商户售后政策为准</span></div>
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
              <p class="secure-note">🔒 交易过程受平台安全机制保护</p>
            </div>
          </div>
        </section>

        <section class="detail-shell">
          <div class="detail-nav">
            <a href="#description">商品介绍</a>
            <a href="#parameters">规格参数</a>
            <a href="#service">服务说明</a>
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
        </section>

        <div class="mobile-buybar">
          <div><small>合计</small><strong>¥{{ money(Number(p.price) * qty * vipRate) }}</strong></div>
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
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { productApi, trafficApi } from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const user = useUserStore()
const p = ref(null)
const qty = ref(1)
const loading = ref(true)
const activeIndex = ref(0)

const vipRate = computed(() => user.hasPerm('vip:discount') ? 0.95 : 1)
const gallery = computed(() => {
  const raw = [p.value?.coverUrl, ...(Array.isArray(p.value?.images) ? p.value.images : [])]
  return [...new Set(raw.filter(Boolean))]
})
const activeImage = computed(() => gallery.value[activeIndex.value] || '')

function money(value) {
  return Number(value || 0).toFixed(2)
}

onMounted(async () => {
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
.product-page { --ink:#18212f; --muted:#6b7280; --line:#e8e5de; --paper:#fbf9f1; --red:#9b2d20; --gold:#b58b42; max-width:1240px; margin:0 auto; padding:8px 20px 90px; color:var(--ink); }
.crumbs { display:flex; gap:10px; align-items:center; padding:10px 0 18px; color:#8a8d93; font-size:13px; }
.crumbs span { cursor:pointer; }.crumbs b { color:#343a45; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.crumbs i { font-style:normal; color:#c7c3bb; }
.product-shell { display:grid; grid-template-columns:minmax(0,1.04fr) minmax(420px,.96fr); gap:48px; background:#fff; border:1px solid var(--line); border-radius:20px; padding:24px; box-shadow:0 10px 35px rgba(24,33,47,.05); }
.gallery-main { position:relative; aspect-ratio:1/1; max-height:570px; border-radius:16px; background:var(--paper); overflow:hidden; display:flex; align-items:center; justify-content:center; }
.gallery-main img { width:100%; height:100%; object-fit:contain; }.image-placeholder { font-size:80px; color:#a79f90; }
.sold-out { position:absolute; top:18px; left:18px; background:#18212f; color:#fff; padding:6px 11px; border-radius:99px; font-size:12px; }
.thumb-list { display:flex; gap:10px; margin-top:12px; overflow:auto; }.thumb { flex:0 0 68px; width:68px; height:68px; padding:3px; border:1px solid var(--line); border-radius:10px; background:#fff; cursor:pointer; }.thumb.active { border:2px solid var(--ink); padding:2px; }.thumb img { width:100%; height:100%; object-fit:cover; border-radius:6px; }
.trust-row { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin-top:18px; color:#68707c; font-size:12px; }.trust-row span { text-align:center; }.trust-row strong { color:#3f6b4b; margin-right:4px; }
.buy-column { padding:8px 4px; }.eyebrow { color:var(--gold); text-transform:uppercase; letter-spacing:.12em; font-size:11px; font-weight:800; }.buy-column h1 { margin:8px 0 8px; font-size:30px; line-height:1.3; letter-spacing:-.02em; }.subtitle { margin:0 0 18px; color:var(--muted); line-height:1.7; }
.price-card { padding:17px 18px; background:linear-gradient(135deg,#fbf9f1,#f6f1e7); border-radius:14px; }.price-label { color:#77736a; font-size:12px; }.price-line { display:flex; align-items:baseline; gap:3px; color:var(--red); }.price-line strong { font-size:38px; letter-spacing:-.04em; }.currency { font-size:18px; font-weight:800; }.vip-badge { margin-left:10px; background:#efe4c8; color:#765c25; padding:4px 7px; border-radius:5px; font-size:11px; font-weight:700; }.vip-copy { margin-top:4px; color:#846b35; font-size:12px; }
.decision-list { margin:20px 0; border-top:1px solid var(--line); }.decision-row { display:flex; gap:18px; padding:13px 0; border-bottom:1px solid var(--line); font-size:13px; }.decision-row .label { flex:0 0 48px; color:#8a8d93; }.decision-row .value { color:#303743; font-weight:600; }.decision-row .danger { color:var(--red); }
.service-card { padding:14px 16px; border:1px solid var(--line); border-radius:12px; background:#fffdf8; }.service-card div { display:flex; align-items:center; gap:9px; font-size:12px; margin:7px 0; }.service-card b { width:32px; }.service-card span:last-child { color:#777; }.service-icon { color:var(--gold); font-weight:800; }
.purchase-area { margin-top:20px; }.quantity-line { display:flex; align-items:center; gap:14px; color:#555; font-size:13px; margin-bottom:13px; }.action-row { display:flex; gap:10px; }.cart-btn,.buy-btn { min-width:150px; height:46px; border-radius:9px; font-weight:700; }.cart-btn { border-color:#9b2d20; color:#9b2d20; }.buy-btn { background:#18212f; border-color:#18212f; }.secure-note { margin:10px 0 0; color:#969aa1; font-size:11px; }
.detail-shell { margin-top:24px; background:#fff; border:1px solid var(--line); border-radius:20px; overflow:hidden; }.detail-nav { display:flex; gap:34px; padding:0 28px; border-bottom:1px solid var(--line); position:sticky; top:0; z-index:2; background:rgba(255,255,255,.94); backdrop-filter:blur(10px); }.detail-nav a { padding:18px 0; font-size:14px; font-weight:700; color:#70757e; }.detail-nav a:first-child { color:var(--ink); border-bottom:2px solid var(--ink); }.detail-section { padding:40px 42px; border-bottom:1px solid var(--line); scroll-margin-top:70px; }.detail-section:last-child { border-bottom:0; }.section-heading span { color:var(--gold); font-size:10px; font-weight:800; letter-spacing:.16em; }.section-heading h2 { margin:5px 0 22px; font-size:24px; }.detail-copy { white-space:pre-wrap; color:#525965; line-height:2; font-size:14px; max-width:900px; }.parameter-grid { display:grid; grid-template-columns:repeat(3,1fr); border-top:1px solid var(--line); border-left:1px solid var(--line); }.parameter-grid div { min-height:62px; padding:12px 15px; border-right:1px solid var(--line); border-bottom:1px solid var(--line); display:flex; flex-direction:column; gap:5px; }.parameter-grid span { color:#969aa1; font-size:11px; }.parameter-grid b { font-size:13px; font-weight:600; }.service-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:14px; }.service-grid div { padding:18px; border:1px solid var(--line); border-radius:12px; background:#fffdf8; }.service-grid b { font-size:14px; }.service-grid p { color:#777; line-height:1.7; font-size:12px; margin:8px 0 0; }
.mobile-buybar { display:none; }
@media (max-width:900px) { .product-shell { grid-template-columns:1fr; gap:24px; padding:16px; }.gallery-main { max-height:none; }.buy-column { padding:0; }.detail-section { padding:30px 24px; }.parameter-grid { grid-template-columns:repeat(2,1fr); }.service-grid { grid-template-columns:1fr; } }
@media (max-width:600px) { .product-page { padding:0 12px 82px; }.crumbs { padding:8px 2px 12px; font-size:12px; }.product-shell { border-radius:14px; padding:10px; border-left:0; border-right:0; box-shadow:none; margin:0 -12px; }.buy-column h1 { font-size:23px; }.price-line strong { font-size:32px; }.trust-row { grid-template-columns:repeat(2,1fr); gap:12px; }.action-row { display:none; }.detail-shell { margin-top:14px; border-radius:14px; }.detail-nav { gap:20px; padding:0 18px; overflow:auto; }.detail-nav a { white-space:nowrap; }.detail-section { padding:26px 18px; }.parameter-grid { grid-template-columns:1fr; }.mobile-buybar { display:flex; position:fixed; z-index:20; left:0; right:0; bottom:0; padding:9px 12px calc(9px + env(safe-area-inset-bottom)); background:rgba(255,255,255,.97); backdrop-filter:blur(12px); border-top:1px solid var(--line); align-items:center; gap:8px; box-shadow:0 -5px 20px rgba(0,0,0,.08); }.mobile-buybar > div { flex:1; display:flex; flex-direction:column; }.mobile-buybar small { color:#8a8d93; font-size:10px; }.mobile-buybar strong { color:var(--red); font-size:18px; }.mobile-buybar .cart-btn,.mobile-buybar .buy-btn { min-width:72px; height:40px; }.mobile-buybar .buy-btn { color:#fff; } }
</style>
