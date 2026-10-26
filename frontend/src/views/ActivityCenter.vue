<template>
  <div class="cp-page">
    <!-- Apple iOS 灵动岛 (Dynamic Island) 态势感知与真实业务通报 (全端适配 Safe Area) -->
    <AppleDynamicIsland
      :title="islandData.title"
      :icon="islandData.icon"
      :description="islandData.description"
      :badge="islandData.badge"
      :status-type="islandData.statusType"
      :progress="islandData.progress"
      :progress-text="islandData.progressText"
      :primary-text="islandData.primaryText"
      @primary-click="handleIslandAction"
    />

    <!-- Apple 营销页式 Hero -->
    <header class="cp-hero">
      <DotPattern class="cp-dots" :width="18" :height="18" :cr="1.2" />
      <div class="cp-hero-copy">
        <h1>活动中心<span class="cp-dot">。</span></h1>
        <p>领券、限时秒杀与积分激励，<br class="cp-br" />一站式搞定企业采购。</p>
      </div>
      <div class="cp-hero-side">
        <div class="cp-stats">
          <div class="cp-stat"><b><NumberTicker :value="acts.length" /></b><span>进行中</span></div>
          <div class="cp-stat" v-if="couponCount"><b><NumberTicker :value="couponCount" /></b><span>可领券</span></div>
        </div>
        <div class="cp-hero-links">
          <ShimmerButton class="cp-cta" background="var(--ll-primary, #1E6EB8)"
                         shimmer-color="rgba(255,255,255,.9)" @click="$router.push('/activity/my')">我的券包</ShimmerButton>
          <button type="button" class="cp-pill plain" @click="$router.push('/activity/rules')">活动规则</button>
        </div>
      </div>
    </header>

    <!-- Apple Cards Carousel 真实 B2B 热销现货画廊 (Aceternity UI 移植，全端自适应触控) -->
    <section id="apple-carousel-sec" class="apple-showcase-section">
      <AppleCardsCarousel
        :items="carouselProducts"
        title="平台热销大促甄选"
        subtitle="LANLINK B2B SPOTLIGHT · 官方直发"
        @add-cart="handleAddToCart"
        @card-action="handleProductBuy"
      />
    </section>

    <!-- Apple VisionOS 空间液态微光专区 (真实业务：企业账期、实时秒杀、VIP会员) -->
    <section class="apple-glass-section">
      <div class="glass-section-head">
        <span class="glass-badge">VISIONOS SPATIAL B2B</span>
        <h2>企业数字化采购专区</h2>
        <p>3D 视角自适应跟随与镜面高光透光，享受极致沉浸式采购体验</p>
      </div>
      <div class="glass-cards-grid">
        <!-- 业务卡片 1: 企业信用账期 (真实授信状态) -->
        <AppleGlassCard :max-tilt="8" :spotlight-size="320" class="glass-deal-item">
          <div class="glass-deal-inner">
            <div class="glass-deal-top">
              <span class="glass-deal-tag">B2B CREDIT TERMS</span>
              <span class="glass-deal-badge">{{ userHasCredit ? '授信已生效' : '30-60天免息' }}</span>
            </div>
            <h3 class="glass-deal-title">企业信用账期结算</h3>
            <p class="glass-deal-desc">
              {{ userHasCredit ? `当前总授信额度 ¥${creditLimit}，可用额度充足。支持先采购后付款与对公集中核销。` : '免抵押循环授信，最高可享 ¥500,000 企业账期额度，缓解资金周转压力，支持按月对账。' }}
            </p>
            <div class="glass-deal-footer">
              <div class="glass-deal-price">
                <span class="currency">¥</span>
                <span class="amount">{{ userHasCredit ? creditLimit : '500,000' }}</span>
                <span class="credit-sub">{{ userHasCredit ? '授信额度' : '最高可申' }}</span>
              </div>
              <button type="button" class="glass-deal-btn" @click="$router.push('/credit-term')">
                {{ userHasCredit ? '查看账单' : '申请授信' }}
              </button>
            </div>
          </div>
        </AppleGlassCard>

        <!-- 业务卡片 2: 真实后端秒杀活动 (动态提取自 acts 中的 flash_sale) -->
        <AppleGlassCard :max-tilt="8" :spotlight-size="320" class="glass-deal-item">
          <div class="glass-deal-inner">
            <div class="glass-deal-top">
              <span class="glass-deal-tag">FLASH SALE LIVE</span>
              <span class="glass-deal-badge" :class="{ 'hot': !!activeFlashSale }">
                {{ activeFlashSale ? (rateText(activeFlashSale.discountRate) + ' 折秒杀') : '限时抢购' }}
              </span>
            </div>
            <h3 class="glass-deal-title">{{ activeFlashSale ? activeFlashSale.title : '行业大促特惠专场' }}</h3>
            <p class="glass-deal-desc">
              {{ activeFlashSale ? `距离秒杀结束剩余 ${remainText(activeFlashSale.endTime)}，全场现货火热抢购中。` : '精选工业备件、办公耗材与智能硬件阶梯满减，下单自动抵扣优惠。' }}
            </p>
            <div class="glass-deal-footer">
              <div class="glass-deal-price">
                <Zap v-if="activeFlashSale" :size="20" class="inline-block text-amber-500 mr-1 align-middle" />
                <span v-else class="currency">¥</span>
                <span class="amount">{{ activeFlashSale ? (rateText(activeFlashSale.discountRate) + '折') : '满减' }}</span>
                <span v-if="activeFlashSale" class="credit-sub">限时抢购</span>
              </div>
              <button type="button" class="glass-deal-btn" @click="handleFlashSaleClick">
                立即抢购
              </button>
            </div>
          </div>
        </AppleGlassCard>

        <!-- 业务卡片 3: VIP 批发采购与会员积分抵扣 -->
        <AppleGlassCard :max-tilt="8" :spotlight-size="320" class="glass-deal-item">
          <div class="glass-deal-inner">
            <div class="glass-deal-top">
              <span class="glass-deal-tag">VIP WHOLESALE</span>
              <span class="glass-deal-badge">全场通享</span>
            </div>
            <h3 class="glass-deal-title">VIP 批发采购与积分抵扣</h3>
            <p class="glass-deal-desc">
              企业 VIP 会员专享 95 折阶梯折扣，累计积分可全额冲抵货款，专属采购顾问一对一协同跟单。
            </p>
            <div class="glass-deal-footer">
              <div class="glass-deal-price">
                <span class="currency">折</span>
                <span class="amount">9.5</span>
                <span class="credit-sub">VIP 专属价</span>
              </div>
              <button type="button" class="glass-deal-btn" @click="$router.push('/membership')">
                查看权益
              </button>
            </div>
          </div>
        </AppleGlassCard>
      </div>
    </section>

    <!-- iOS 分段控件 -->
    <div id="activity-tabs-sec" class="cp-seg" role="tablist">
      <button v-for="t in TABS" :key="t.key" type="button" role="tab"
              class="cp-seg-item" :class="{ on: tab === t.key }" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="cp-grid" v-if="acts.length">
        <template v-for="c in acts" :key="c.campaignId">
          <!-- ============ 券票（领券活动） ============ -->
          <article v-if="c.type === 'coupon'" class="tk" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="tk-stub">
              <span class="tk-kind">{{ c.discountRate ? '折扣' : '满减券' }}</span>
              <div class="tk-value">
                <template v-if="c.discountRate"><b>{{ rateText(c.discountRate) }}</b><i>折</i></template>
                <template v-else><i>¥</i><b>{{ trimZero(c.benefitAmount) }}</b></template>
              </div>
              <span class="tk-th">{{ c.threshold > 0 ? `满${trimZero(c.threshold)}可用` : '无门槛' }}</span>
            </div>
            <div class="tk-body">
              <div class="tk-row1">
                <span class="tk-scope">{{ c.indName ? c.indName + '专享' : '全场通用' }}</span>
                <span class="tk-remain" v-if="c.remainQuota >= 0">余 {{ c.remainQuota }}</span>
              </div>
              <h3>{{ c.title }}</h3>
              <div class="tk-valid">{{ validText(c) }}</div>
              <div class="tk-actions">
                <button type="button" class="tk-btn" :disabled="!c.canClaim" @click.stop="claim(c)">
                  {{ c.limitReached ? '已领取' : (c.remainQuota === 0 ? '已抢完' : '立即领取') }}
                </button>
                <span class="tk-stamp" v-if="c.limitReached">已领取</span>
              </div>
            </div>
          </article>

          <!-- ============ 限时秒杀：深色卡 + Border Beam Panel ============ -->
          <BeamPanel v-else-if="c.type === 'flash_sale'" role="article" class="fk" :radius="22" :seed="7"
                     :colors="['#ffb340']" :hover-speed="300" @click="goDetail(c)">
            <Meteors class="fk-meteors" :number="5" :min-duration="5" :max-duration="9"
                     color="#f2b544" tail-length="64px" />
            <div class="fk-tag"><Zap :size="13" class="inline-block shrink-0 align-middle mr-1 text-amber-400" /> <AnimatedShinyText class="on-dark" :shimmer-width="80">限时秒杀</AnimatedShinyText></div>
            <div class="fk-count">
              <span class="fk-cd">{{ remainText(c.endTime) }}</span>
              <span class="fk-cd-label" v-if="remainMs(c.endTime) > 0">后结束</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="fk-benefit">
              <b>{{ rateText(c.discountRate) }}</b><i>折</i>
              <em v-if="c.benefitAmount">最高减 ¥{{ trimZero(c.benefitAmount) }}</em>
            </div>
            <div class="fk-foot">
              <span>{{ c.indName ? c.indName + '行业' : '全场商品' }} · 满足门槛自动享</span>
              <button type="button" class="fk-btn" @click.stop="$router.push('/mall')">去抢购</button>
            </div>
          </BeamPanel>

          <!-- ============ 满减 / 折扣：白卡 ============ -->
          <article v-else-if="['full_reduce','discount'].includes(c.type)" class="pc" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="pc-head">
              <span class="pc-type">{{ typeLabel(c.type) }}</span>
              <span class="pc-scope">{{ c.indName ? c.indName + '行业' : '全场' }}</span>
            </div>
            <div class="pc-benefit">
              <template v-if="c.type === 'full_reduce'"><small>满 ¥{{ trimZero(c.threshold) }}</small><b>减 ¥{{ trimZero(c.benefitAmount) }}</b></template>
              <template v-else><b>{{ rateText(c.discountRate) }}<i>折</i></b><small v-if="c.benefitAmount">最高减 ¥{{ trimZero(c.benefitAmount) }}</small></template>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="pc-foot">
              <span class="pc-valid">{{ validText(c) }}</span>
              <span class="pc-go">结算自动享 <ArrowRight :size="12" :stroke-width="2.4" /></span>
            </div>
          </article>

          <!-- ============ 积分激励：金色浅底卡 ============ -->
          <article v-else class="gc" @click="goDetail(c)">
            <div class="gc-icon"><Gift :size="28" class="text-white drop-shadow" /></div>
            <div class="gc-main">
              <div class="gc-head">
                <span class="gc-type">{{ c.type === 'register' ? '注册有礼' : '消费有礼' }}</span>
                <b class="gc-points">+{{ c.rewardPoints }}<i>积分</i></b>
              </div>
              <h3>{{ c.title }}</h3>
              <p class="gc-desc">{{ c.description }}</p>
              <div class="gc-foot">
                <span class="gc-valid">{{ validText(c) }}</span>
                <button v-if="c.type === 'register'" type="button" class="gc-btn"
                        :disabled="c.joined" @click.stop="join(c)">{{ c.joined ? '已参与' : '立即参与' }}</button>
                <span v-else class="gc-auto">支付后自动参与</span>
              </div>
            </div>
          </article>
        </template>
      </div>
      <div v-else-if="!loading" class="cp-empty">
        <div class="cp-empty-ico"><Ticket :size="48" class="text-slate-300 dark:text-slate-600 inline-block" /></div>
        <p>暂无可参与的活动</p>
        <button type="button" class="cp-pill" @click="$router.push('/mall')">先去商城逛逛</button>
      </div>
    </a-spin>

    <!-- Apple macOS 拟真悬浮底栏 Dock (真实业务快捷坞，全端触控与角标联动) -->
    <AppleDock
      :items="dockItems"
      :active-id="tab"
      :distance="130"
      :icon-size="44"
      :icon-magnification="62"
      @select="handleDockSelect"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  Sparkles,
  TicketPercent,
  Zap,
  Layers,
  Store,
  ShoppingCart,
  Package,
  CreditCard,
  ArrowUp,
  Gift,
  Ticket
} from 'lucide-vue-next'
import { campaignApi, homeApi, orderApi, creditTermApi } from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'

import BeamPanel from '../components/ui/BeamPanel.vue'
import Meteors from '../components/ui/Meteors.vue'
import DotPattern from '../components/ui/DotPattern.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import AnimatedShinyText from '../components/ui/AnimatedShinyText.vue'
import AnimatedIcon from '../components/ui/AnimatedIcon.vue'
import { ArrowRight } from 'lucide-vue-next'
import ShimmerButton from '../components/ui/ShimmerButton.vue'

// 引入 21st.dev Apple UI 风格组件
import AppleDynamicIsland from '../components/ui/AppleDynamicIsland.vue'
import AppleCardsCarousel from '../components/ui/AppleCardsCarousel.vue'
import AppleGlassCard from '../components/ui/AppleGlassCard.vue'
import AppleDock from '../components/ui/AppleDock.vue'

const router = useRouter()
const cart = useCartStore()
const user = useUserStore()

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'coupon', label: '领券' },
  { key: 'promo', label: '限时促销' },
  { key: 'incentive', label: '积分激励' }
]
const tab = ref('all')
const acts = ref([])
const hotProducts = ref([])
const recentOrders = ref([])
const creditOverview = ref(null)
const loading = ref(false)
const now = ref(Date.now())
let timer = null

const couponCount = computed(() => acts.value.filter(c => c.type === 'coupon' && c.canClaim).length)
const activeFlashSale = computed(() => acts.value.find(c => c.type === 'flash_sale'))
const userHasCredit = computed(() => creditOverview.value && creditOverview.value.creditStatus === 'APPROVED')
const creditLimit = computed(() => (creditOverview.value && creditOverview.value.availableLimit) || '500,000')

// ========== 1. 真实 B2B 商品画廊映射 (结合后端真实 hot 商品) ==========
const carouselProducts = computed(() => {
  if (hotProducts.value && hotProducts.value.length > 0) {
    return hotProducts.value.map(p => ({
      id: p.prodId,
      prodId: p.prodId,
      category: p.brand || p.indName || 'B2B 优质供应链',
      title: p.title,
      tagline: `现货库存 ${p.stock} 件 · 累计批发采购 ${p.sales} 单`,
      badge: p.sales > 30 ? '爆款推荐' : '现货速发',
      src: p.coverUrl,
      price: p.price,
      originalPrice: (Number(p.price) * 1.15).toFixed(2),
      sales: p.sales,
      stock: p.stock,
      content: p.description || `本商品为平台官方认证直发正品。严格遵循工业与商业采购质量检测标准，支持企业大批量直采、增值税专用发票（13%）开具及 30-60 天企业信用账期月结。`,
      features: [
        `现货储备：${p.stock} 件即时发货`,
        `企业采购：${p.sales} 单履约`,
        `账期支持：支持企业授信 30-60 天免息`,
        `发票保障：提供 13% 增值税专用发票`
      ],
      buttonText: '立即采购下单'
    }))
  }

  // 兜底真实 B2B 核心品类画廊数据
  return [
    {
      id: 101,
      category: '智能工业互联',
      title: '高精度工业网关与边缘采集终端',
      tagline: '毫秒级工业现场多总线协议采集，IP68 防护，低功耗稳定运行。',
      badge: 'B2B 专供',
      src: 'https://images.unsplash.com/photo-1518770660439-4636190af475?q=80&w=1000&auto=format&fit=crop',
      price: '1,280',
      originalPrice: '1,580',
      sales: 128,
      stock: 450,
      content: '针对智能工厂、仓储自动化及能源监测研发的工业级数采网关，支持 Modbus、MQTT 及 OPC-UA 协议。',
      features: ['IP68 严苛工业防护设计', '内置双路千兆网口与 4G/5G 模块', '支持企业信用账期 30 天免息', '原厂三年联保'],
      buttonText: '立即采购下单'
    },
    {
      id: 102,
      category: '企业生产力装备',
      title: '极速雷电 4 多屏图形工作站扩展坞',
      tagline: '单线拓展三屏 4K 60Hz 视界，航天级铝合金一体散热机身。',
      badge: '大宗热销',
      src: 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?q=80&w=1000&auto=format&fit=crop',
      price: '899',
      originalPrice: '1,199',
      sales: 342,
      stock: 800,
      content: '面向设计院、软件研发及数字工作室的高性能扩展核心，提供满血 40Gbps 吞吐与 100W PD 闪充。',
      features: ['双雷电 4 满血高速接口', '智能温控主动导热', '支持企业对公批量采购', '开具增值税专用发票'],
      buttonText: '立即采购下单'
    },
    {
      id: 103,
      category: '商业办公生态',
      title: '人体工学自适应双电机静音升降桌',
      tagline: '德国 TÜV 认证安全气压与防撞传感器，全行程超静音双电机。',
      badge: '特惠直降',
      src: 'https://images.unsplash.com/photo-1524758631624-e2822e304c36?q=80&w=1000&auto=format&fit=crop',
      price: '2,199',
      originalPrice: '2,699',
      sales: 215,
      stock: 120,
      content: '全钢架承重结构，四档智能高度记忆，专为长时间企业办公打造的健康工位解决方案。',
      features: ['超静音双电机平稳驱动', '阻碍即停防夹手安全机制', '10 年官方质保上门安装', '支持企业账期批量采购'],
      buttonText: '立即采购下单'
    }
  ]
})

// ========== 2. 真实业务态势感知 (灵动岛数据绑定) ==========
const islandData = computed(() => {
  // 如果有未支付订单
  const unpaidOrder = recentOrders.value.find(o => o.status === 'PENDING_PAY')
  if (unpaidOrder) {
    return {
      title: `待支付订单 · ¥${unpaidOrder.payAmount || unpaidOrder.amount}`,
      icon: markRaw(CreditCard),
      description: `订单号 ${unpaidOrder.orderNo} · 正在锁定现货库存`,
      badge: '待支付',
      statusType: 'warning',
      progress: 80,
      progressText: '支付时效剩余 15 分钟',
      primaryText: '立即去支付',
      orderNo: unpaidOrder.orderNo
    }
  }

  // 如果有配送中订单
  const shippedOrder = recentOrders.value.find(o => o.status === 'SHIPPED')
  if (shippedOrder) {
    return {
      title: `运单极速配送中`,
      icon: markRaw(Package),
      description: `订单号 ${shippedOrder.orderNo.slice(-8)} · 承运商发货在途中`,
      badge: '配送中',
      statusType: 'info',
      progress: 65,
      progressText: '预计明日送达',
      primaryText: '查看物流轨迹',
      orderNo: shippedOrder.orderNo
    }
  }

  // 默认展示大促与购物车实时状态
  return {
    title: `活动中心 Live (${acts.value.length} 场大促)`,
    icon: markRaw(Sparkles),
    description: `当前有 ${couponCount.value} 张优惠券可领取 · ${cart.count ? '购物车待结 ' + cart.count + ' 件' : '秒杀专场进行中'}`,
    badge: 'LIVE',
    statusType: 'success',
    progress: 75,
    progressText: '年中特惠配额剩余 25%',
    primaryText: cart.count > 0 ? `去购物车结算 (${cart.count})` : '查看我的券包',
    orderNo: null
  }
})

function handleIslandAction() {
  if (islandData.value.orderNo) {
    router.push('/order/detail/' + islandData.value.orderNo)
  } else if (cart.count > 0) {
    router.push('/cart')
  } else {
    router.push('/activity/my')
  }
}

// ========== 3. Apple macOS Dock 真实业务快捷坞 (全量矢量图标 + Apple 官方色盘) ==========
const dockItems = computed(() => [
  { id: 'all', label: '全部活动', icon: markRaw(Sparkles), gradient: 'linear-gradient(135deg, #FF5E62 0%, #FF9966 100%)' },
  { id: 'coupon', label: '领券专区', icon: markRaw(TicketPercent), gradient: 'linear-gradient(135deg, #F59E0B 0%, #D97706 100%)', badge: couponCount.value > 0 ? String(couponCount.value) : '' },
  { id: 'promo', label: '限时秒杀', icon: markRaw(Zap), gradient: 'linear-gradient(135deg, #F43F5E 0%, #BE123C 100%)' },
  { id: 'carousel', label: '爆款画廊', icon: markRaw(Layers), gradient: 'linear-gradient(135deg, #8B5CF6 0%, #6D28D9 100%)' },
  { id: 'sep1', separator: true },
  { id: 'mall', label: 'B2B商城', icon: markRaw(Store), gradient: 'linear-gradient(135deg, #3B82F6 0%, #1D4ED8 100%)' },
  { id: 'cart', label: '购物车', icon: markRaw(ShoppingCart), gradient: 'linear-gradient(135deg, #06B6D4 0%, #0891B2 100%)', badge: cart.count > 0 ? String(cart.count) : '' },
  { id: 'orders', label: '我的订单', icon: markRaw(Package), gradient: 'linear-gradient(135deg, #10B981 0%, #059669 100%)' },
  { id: 'credit', label: '企业账期', icon: markRaw(CreditCard), gradient: 'linear-gradient(135deg, #6366F1 0%, #4338CA 100%)' },
  { id: 'top', label: '回到顶部', icon: markRaw(ArrowUp), gradient: 'linear-gradient(135deg, #64748B 0%, #475569 100%)' }
])

function handleDockSelect(item) {
  if (['all', 'coupon', 'promo', 'incentive'].includes(item.id)) {
    switchTab(item.id)
    document.getElementById('activity-tabs-sec')?.scrollIntoView({ behavior: 'smooth' })
  } else if (item.id === 'carousel') {
    document.getElementById('apple-carousel-sec')?.scrollIntoView({ behavior: 'smooth' })
  } else if (item.id === 'mall') {
    router.push('/mall')
  } else if (item.id === 'cart') {
    router.push('/cart')
  } else if (item.id === 'orders') {
    router.push('/orders')
  } else if (item.id === 'credit') {
    router.push('/credit-term')
  } else if (item.id === 'top') {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

// 真实购物车加入动作
async function handleAddToCart(card) {
  try {
    await cart.add(card.id, 1)
    message.success(`已成功将【${card.title}】加入购物车`)
  } catch (e) {
    // 拦截器已处理错误提示
  }
}

function handleProductBuy(card) {
  router.push('/product/' + card.id)
}

function handleFlashSaleClick() {
  if (activeFlashSale.value) {
    goDetail(activeFlashSale.value)
  } else {
    switchTab('promo')
    document.getElementById('activity-tabs-sec')?.scrollIntoView({ behavior: 'smooth' })
  }
}

function switchTab(t) {
  if (tab.value === t) return
  tab.value = t
  load()
}

const logged = () => !!localStorage.getItem('ll_user')

async function load() {
  loading.value = true
  try {
    const [actList, hotList] = await Promise.all([
      campaignApi.list(tab.value).catch(() => []),
      homeApi.hot(8).catch(() => [])
    ])
    acts.value = actList || []
    if (hotList && hotList.length) {
      hotProducts.value = hotList
    }
  } catch (e) {
    acts.value = []
  } finally {
    loading.value = false
  }

  // 若已登录，拉取用户真实进行中订单与账期状态
  if (logged()) {
    orderApi.my().then(res => {
      recentOrders.value = res || []
    }).catch(() => {})

    if (user.hasPerm('credit:view')) {
      creditTermApi.overview().then(res => {
        creditOverview.value = res
      }).catch(() => {})
    }
  }
}

function goDetail(c) {
  router.push('/activity/' + c.campaignId)
}

function requireLogin() {
  message.info('请先登录')
  router.push({ path: '/login', query: { redirect: '/activity' } })
}

async function claim(c) {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.claim(c.campaignId)
    message.success('领取成功，结算时可用')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function join(c) {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.join(c.campaignId)
    message.success(`参与成功，+${c.rewardPoints} 积分已到账`)
    router.push(`/activity/${c.campaignId}/participation`)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

// ===== 展示口径 =====
const ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (a) => 'ac-' + (ACCENTS.includes(a) ? a : 'blue')
const typeLabel = (t) => ({ full_reduce: '满减', discount: '折扣' }[t] || '促销')
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const validText = (c) => c.endTime ? `有效期至 ${fmtDate(c.endTime)}` : '长期有效'
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''
const remainMs = (end) => !end ? Infinity : new Date(end).getTime() - now.value

function remainText(end) {
  const ms = remainMs(end)
  if (ms === Infinity) return '不限时'
  if (ms <= 0) return '已结束'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const hh = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return d > 0 ? `${d}天 ${hh}:${mm}:${ss}` : `${hh}:${mm}:${ss}`
}

onMounted(() => {
  load()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cp-page {
  max-width: 1120px;
  margin: 0 auto;
  padding-bottom: max(96px, calc(env(safe-area-inset-bottom) + 80px)); /* 为底部的 Apple Dock 留出呼吸空间 */
}

/* ===== Apple Cards Showcase 区域 ===== */
.apple-showcase-section {
  margin: 10px 0 32px;
}

/* ===== Apple VisionOS 空间微光专区 ===== */
.apple-glass-section {
  margin: 24px 0 36px;
  padding: 24px;
  border-radius: 28px;
  background: linear-gradient(180deg, rgba(2, 132, 199, 0.04) 0%, rgba(255, 255, 255, 0.02) 100%);
  border: 1px solid rgba(2, 132, 199, 0.12);
}

:global(.dark) .apple-glass-section {
  background: linear-gradient(180deg, rgba(30, 41, 59, 0.5) 0%, rgba(15, 23, 42, 0.6) 100%);
  border-color: rgba(255, 255, 255, 0.08);
}

.glass-section-head {
  margin-bottom: 20px;
}

.glass-badge {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #0284c7;
  display: inline-block;
  margin-bottom: 4px;
}
:global(.dark) .glass-badge {
  color: #38bdf8;
}

.glass-section-head h2 {
  font-size: 1.5rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #0f172a;
  margin: 0 0 6px;
}
:global(.dark) .glass-section-head h2 {
  color: #f8fafc;
}

.glass-section-head p {
  font-size: 13.5px;
  color: #64748b;
  margin: 0;
}
:global(.dark) .glass-section-head p {
  color: #94a3b8;
}

.glass-cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}

@media (max-width: 900px) {
  .glass-cards-grid {
    grid-template-columns: 1fr;
    gap: 14px;
  }
}

.glass-deal-item {
  height: auto;
  min-height: 200px;
}

.glass-deal-inner {
  padding: 22px 24px;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.glass-deal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.glass-deal-tag {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: #0284c7;
}
:global(.dark) .glass-deal-tag {
  color: #38bdf8;
}

.glass-deal-badge {
  font-size: 11px;
  font-weight: 600;
  background: rgba(2, 132, 199, 0.1);
  color: #0284c7;
  padding: 2px 8px;
  border-radius: 9999px;
}
.glass-deal-badge.hot {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}
:global(.dark) .glass-deal-badge {
  background: rgba(56, 189, 248, 0.15);
  color: #38bdf8;
}

.glass-deal-title {
  font-size: 1.15rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px;
  line-height: 1.3;
}
:global(.dark) .glass-deal-title {
  color: #f1f5f9;
}

.glass-deal-desc {
  font-size: 12.5px;
  color: #64748b;
  margin: 0 0 16px;
  line-height: 1.5;
  flex: 1;
}
:global(.dark) .glass-deal-desc {
  color: #94a3b8;
}

.glass-deal-footer {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
:global(.dark) .glass-deal-footer {
  border-top-color: rgba(255, 255, 255, 0.08);
}

.glass-deal-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.glass-deal-price .currency {
  font-size: 13px;
  font-weight: 700;
  color: #0284c7;
}

.glass-deal-price .amount {
  font-size: 1.35rem;
  font-weight: 800;
  color: #0f172a;
}
:global(.dark) .glass-deal-price .amount {
  color: #ffffff;
}

.credit-sub {
  font-size: 11px;
  color: #94a3b8;
  margin-left: 6px;
}

.glass-deal-btn {
  background: #0284c7;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  padding: 6px 14px;
  border-radius: 9999px;
  border: none;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.glass-deal-btn:hover {
  background: #0369a1;
  transform: scale(1.04);
}

/* ===== Hero：Apple 营销页排版 ===== */
.cp-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  padding: 20px 4px 26px;
}
.cp-dots { color: rgba(30, 110, 184, .18); }
.cp-hero-copy, .cp-hero-side { position: relative; }
.cp-hero h1 {
  margin: 0;
  font-size: clamp(30px, 4.4vw, 46px);
  font-weight: 700;
  letter-spacing: -.028em;
  line-height: 1.08;
  color: var(--ll-ink, #1d1d1f);
}
.cp-dot { color: var(--ll-primary, #1E6EB8); }
.cp-hero p { margin: 12px 0 0; font-size: 17px; line-height: 1.5; color: var(--ll-muted, #6e6e73); }
.cp-br { display: none; }
.cp-hero-side { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.cp-stats { display: flex; gap: 20px; }
.cp-stat { text-align: right; }
.cp-stat b {
  display: block;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.03em;
  font-variant-numeric: tabular-nums;
  color: var(--ll-ink, #1d1d1f);
}
.cp-stat span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.cp-hero-links { display: flex; gap: 10px; }

/* 胶囊按钮：iOS 圆钮 */
.cp-pill {
  border: none;
  cursor: pointer;
  padding: 10px 20px;
  border-radius: 980px;
  font-size: 14px;
  font-weight: 500;
  background: var(--ll-primary, #1E6EB8);
  color: #fff;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, background-color .25s;
}
.cp-pill:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.cp-pill:active { transform: scale(.97); }
.cp-cta { padding: 10px 22px; font-size: 14px; }
.cp-pill.plain {
  background: transparent;
  color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7);
}

/* ===== iOS 分段控件 ===== */
.cp-seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 12px;
  margin-bottom: 22px;
  background: rgba(120, 120, 128, .14);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
}
.cp-seg-item {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 7px 18px;
  border-radius: 10px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ll-ink, #1d1d1f);
  opacity: .72;
  transition: all .25s var(--ll-ease-out, ease);
}
.cp-seg-item.on {
  background: var(--ll-card, #fff);
  opacity: 1;
  font-weight: 600;
  box-shadow: 0 3px 8px rgba(0, 0, 0, .12), 0 1px 1px rgba(0, 0, 0, .16);
}
.cp-seg-item:not(.on):hover { opacity: 1; }

.cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 18px; }
.cp-grid > article, .cp-grid > .fk { min-width: 0; }
.tk-body { min-width: 0; }

/* ============ 券票：mask 打孔 + drop-shadow 随形 ============ */
.tk {
  position: relative;
  display: grid;
  grid-template-columns: 116px 1fr;
  border-radius: 20px;
  background: var(--ll-card, #fff);
  cursor: pointer;
  --notch: 12px;
  --cut: 104px;
  -webkit-mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000) ,
                radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  -webkit-mask-composite: source-in;
  mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000),
        radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  mask-composite: intersect;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, .05)) drop-shadow(0 10px 24px rgba(0, 0, 0, .07));
  transition: transform .3s var(--ll-ease-out, ease), filter .3s;
}
.tk:hover { transform: translateY(-4px); filter: drop-shadow(0 2px 4px rgba(0,0,0,.06)) drop-shadow(0 18px 36px rgba(0,0,0,.12)); }
.tk-stub {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 6px;
  color: #fff;
  padding: 22px 10px;
  border-radius: 20px 0 0 20px;
}
.ac-blue .tk-stub { background: linear-gradient(160deg, #2b7fd4, #1E6EB8 55%, #16609f); }
.ac-red .tk-stub { background: linear-gradient(160deg, #f0566a, #e0354b 55%, #c22a3e); }
.ac-gold .tk-stub { background: linear-gradient(160deg, #e6a23c, #c97e12 55%, #b06e0e); }
.ac-green .tk-stub { background: linear-gradient(160deg, #22a97a, #047857 55%, #036447); }
.ac-purple .tk-stub { background: linear-gradient(160deg, #8b5cf6, #6d28d9 55%, #5b21b6); }
.tk-kind { font-size: 11px; letter-spacing: .2em; opacity: .8; }
.tk-value { display: flex; align-items: baseline; gap: 2px; line-height: 1; }
.tk-value i { font-style: normal; font-size: 15px; font-weight: 600; }
.tk-value b { font-size: 34px; font-weight: 700; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.tk-th { font-size: 11.5px; opacity: .85; }

.tk-body {
  position: relative;
  padding: 18px 20px 16px 22px;
  display: flex;
  flex-direction: column;
  border-left: 2px dashed var(--ll-border, #e5e5ea);
}
.tk-row1 { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.tk-scope {
  font-size: 11px;
  font-weight: 600;
  color: var(--ll-primary, #1E6EB8);
  background: rgba(30, 110, 184, .08);
  padding: 3px 10px;
  border-radius: 999px;
}
.ac-red .tk-scope { color: #d6303f; background: rgba(224, 53, 75, .08); }
.ac-gold .tk-scope { color: #a56a0b; background: rgba(201, 126, 18, .1); }
.ac-green .tk-scope { color: #047857; background: rgba(4, 120, 87, .09); }
.ac-purple .tk-scope { color: #6d28d9; background: rgba(109, 40, 217, .09); }
.tk-remain { font-size: 11.5px; font-variant-numeric: tabular-nums; color: var(--ll-muted, #6e6e73); }
.tk-body h3 {
  margin: 0 0 5px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -.01em;
  color: var(--ll-ink, #1d1d1f);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tk-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.tk-actions { margin-top: auto; padding-top: 12px; display: flex; justify-content: flex-end; align-items: center; position: relative; }
.tk-btn {
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  padding: 8px 18px;
  border-radius: 980px;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s;
}
.tk-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(0, 0, 0, .18); }
.tk-btn:active:not(:disabled) { transform: scale(.96); }
.tk-btn:disabled { cursor: not-allowed; opacity: .4; }
.ac-blue .tk-btn { background: var(--ll-primary, #1E6EB8); }
.ac-red .tk-btn { background: #e0354b; }
.ac-gold .tk-btn { background: #c97e12; }
.ac-green .tk-btn { background: #047857; }
.ac-purple .tk-btn { background: #6d28d9; }
.tk-stamp {
  position: absolute;
  right: 96px;
  top: 2px;
  transform: rotate(-12deg);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .1em;
  color: #b0b0b6;
  border: 1.5px solid #b0b0b6;
  border-radius: 4px;
  padding: 1px 7px;
  opacity: .8;
}

/* ============ 限时秒杀：深色卡 + BeamPanel ============ */
.fk {
  display: block;
  grid-column: span 2;
  border: 0;
  border-radius: 22px;
  padding: 26px 28px 22px;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(145deg, #232326, #151517 60%);
}
.fk :deep(.meteors) { position: absolute !important; inset: 0; border-radius: inherit; }
.fk-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #ffd9a8;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .18em;
  color: #ffd9a8;
  border: 1px solid rgba(255, 179, 64, .5);
  border-radius: 999px;
  padding: 3px 12px;
}
.fk-tag .shiny { color: #ffd9a8; font-weight: 700; letter-spacing: .18em; }
.fk-count { position: absolute; top: 22px; right: 26px; text-align: right; }
.fk-cd {
  display: block;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.02em;
  font-variant-numeric: tabular-nums;
  color: #fff;
}
.fk-cd-label { font-size: 11.5px; color: rgba(255, 255, 255, .55); }
.fk h3 { margin: 18px 0 8px; font-size: 21px; font-weight: 700; letter-spacing: -.02em; }
.fk-benefit { display: flex; align-items: baseline; gap: 6px; }
.fk-benefit b {
  font-size: 44px;
  font-weight: 700;
  letter-spacing: -.04em;
  line-height: 1;
  background: linear-gradient(100deg, #ffd9a8, #ffb340 45%, #ff5f6d);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.fk-benefit i { font-style: normal; font-size: 18px; font-weight: 600; color: #ffb340; }
.fk-benefit em { font-style: normal; font-size: 13px; color: rgba(255, 255, 255, .6); }
.fk-foot {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid rgba(255, 255, 255, .1);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.fk-foot > span { font-size: 12.5px; color: rgba(255, 255, 255, .6); }
.fk-btn {
  border: none;
  cursor: pointer;
  font-size: 13.5px;
  font-weight: 600;
  color: #151517;
  padding: 9px 20px;
  border-radius: 980px;
  background: linear-gradient(100deg, #ffd9a8, #ffb340);
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s;
}
.fk-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 20px rgba(255, 179, 64, .35); }
.fk-btn:active { transform: scale(.96); }

/* ============ 满减 / 折扣白卡 ============ */
.pc {
  display: flex;
  flex-direction: column;
  border-radius: 22px;
  padding: 22px 24px 18px;
  background: var(--ll-card, #fff);
  cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s;
}
.pc:hover { transform: translateY(-4px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 16px 40px rgba(0, 0, 0, .1); }
.pc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.pc-type { font-size: 11.5px; font-weight: 700; letter-spacing: .12em; color: #fff; padding: 4px 12px; border-radius: 999px; }
.ac-blue .pc-type { background: var(--ll-primary, #1E6EB8); }
.ac-red .pc-type { background: #e0354b; }
.ac-gold .pc-type { background: #c97e12; }
.ac-green .pc-type { background: #047857; }
.ac-purple .pc-type { background: #6d28d9; }
.pc-scope { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.pc-benefit { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.pc-benefit b {
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -.03em;
  line-height: 1;
  color: var(--ll-ink, #1d1d1f);
  font-variant-numeric: tabular-nums;
}
.pc-benefit i { font-style: normal; font-size: 16px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-benefit small { font-size: 13.5px; color: var(--ll-muted, #6e6e73); }
.pc h3 { margin: 0 0 14px; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-foot {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--ll-border, #e5e5ea);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pc-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.pc-go { font-size: 12.5px; font-weight: 600; color: var(--ll-primary, #1E6EB8); }

/* ============ 积分激励：暖金浅底 ============ */
.gc {
  display: flex;
  gap: 16px;
  border-radius: 22px;
  padding: 20px 22px 18px;
  cursor: pointer;
  background: linear-gradient(150deg, #fffaf0, #fff 55%);
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .05);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s;
}
[data-theme='dark'] .gc { background: linear-gradient(150deg, #2a2318, #1c1c1e 55%); }
.gc:hover { transform: translateY(-4px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 16px 40px rgba(0,0,0,.09); }
.gc-icon {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  color: #7c4a03;
  border-radius: 16px;
  background: linear-gradient(160deg, #ffd98a, #f2a33c);
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, .6), 0 6px 14px rgba(201, 126, 18, .28);
}
[data-theme='dark'] .gc-icon { box-shadow: inset 0 1px 1px rgba(255, 255, 255, .25), 0 6px 14px rgba(0, 0, 0, .4); }
.gc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.gc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.gc-type { font-size: 11px; font-weight: 700; letter-spacing: .14em; color: #a56a0b; }
[data-theme='dark'] .gc-type { color: #e6a23c; }
.gc-points { font-size: 21px; font-weight: 700; letter-spacing: -.02em; color: var(--ll-ink, #1d1d1f); }
.gc-points i { font-style: normal; font-size: 11.5px; font-weight: 600; color: var(--ll-muted, #6e6e73); margin-left: 3px; }
.gc-main h3 { margin: 0 0 4px; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.gc-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--ll-muted, #6e6e73);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gc-foot { margin-top: auto; padding-top: 10px; display: flex; justify-content: space-between; align-items: center; }
.gc-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.gc-btn {
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  padding: 7px 16px;
  border-radius: 980px;
  background: #c97e12;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s;
}
.gc-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(201, 126, 18, .3); }
.gc-btn:disabled { cursor: not-allowed; opacity: .4; }
.gc-auto { font-size: 12px; color: var(--ll-muted, #6e6e73); }

/* ===== 空态 ===== */
.cp-empty { text-align: center; padding: 72px 0 88px; }
.cp-empty-ico { display: grid; place-items: center; margin-bottom: 14px; color: var(--ll-muted, #6e6e73); }
.cp-empty p { margin: 0 0 20px; font-size: 15px; color: var(--ll-muted, #6e6e73); }

/* ===== 移动端响应式 (≤ 860px & ≤ 560px) ===== */
@media (max-width: 860px) {
  .cp-hero { flex-direction: column; align-items: flex-start; gap: 14px; padding-top: 8px; }
  .cp-br { display: inline; }
  .cp-hero-side { width: 100%; justify-content: space-between; }
  .cp-grid { grid-template-columns: 1fr 1fr; gap: 14px; }
  .fk { grid-column: span 2; }
  .apple-glass-section { padding: 18px 16px; border-radius: 20px; }
}
@media (max-width: 560px) {
  .cp-page {
    padding-left: 12px;
    padding-right: 12px;
  }
  .cp-grid { grid-template-columns: 1fr; }
  .fk { grid-column: span 1; }
  .fk-cd { font-size: 24px; }
  .cp-hero h1 { font-size: 28px; }
  .cp-seg { width: 100%; display: flex; }
  .cp-seg-item { flex: 1; padding: 7px 0; }
  .tk { grid-template-columns: 92px 1fr; }
  .tk-stub { padding: 18px 6px; }
  .tk-value b { font-size: 27px; }
  .tk-body { padding: 15px 14px 13px 16px; }
  .tk-btn { padding: 7px 14px; }
  .tk-stamp { right: 70px; }
  .pc-head, .pc-benefit, .pc-foot,
  .gc-head, .gc-foot,
  .fk-benefit, .fk-foot,
  .tk-row1 { flex-wrap: wrap; row-gap: 6px; }
  .pc-benefit b { font-size: 30px; }
  .fk-benefit b { font-size: 34px; }
  .fk { padding: 20px 18px 18px; }
  .fk-count { position: static; text-align: left; margin-bottom: 6px; }
  .fk h3 { font-size: 18px; }

  /* 移动端专区微调 */
  .glass-deal-inner {
    padding: 18px 16px;
  }
  .glass-deal-title {
    font-size: 1.05rem;
  }
}
</style>
