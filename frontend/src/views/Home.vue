<template>
  <div class="home-page">
    <section class="brand-hero">
      <svg width="0" height="0" style="position:absolute" aria-hidden="true"><filter id="ll-refract" x="-20%" y="-20%" width="140%" height="140%"><feTurbulence type="fractalNoise" baseFrequency="0.008 0.011" numOctaves="2" seed="92" result="noise"/><feGaussianBlur in="noise" stdDeviation="1.5" result="map"/><feDisplacementMap in="SourceGraphic" in2="map" scale="55" xChannelSelector="R" yChannelSelector="G"/></filter></svg>
      <div class="brand-ambient ambient-a"></div><div class="brand-ambient ambient-b"></div><div class="brand-ambient ambient-c"></div><div class="brand-grid" aria-hidden="true"></div>
      <div class="brand-sweep" aria-hidden="true"></div><div class="brand-horizon" aria-hidden="true"></div>
      <div class="brand-pane pane-a" aria-hidden="true"><i class="pane-tint"></i></div><div class="brand-pane pane-b" aria-hidden="true"><i class="pane-tint"></i></div>
      <div class="brand-hero-copy">
        <span class="brand-eyebrow" data-reveal="blur-up">LANLINK SHOPPING · CONNECTED COMMERCE</span>
        <BrandWordmark class="home-wordmark" size="clamp(38px, 5vw, 58px)" light data-reveal="blur-up" data-delay="40" />
        <h1 data-reveal="blur-up" data-delay="80">让每一次连接，都产生价值。</h1>
        <p data-reveal="blur-up" data-delay="120">连接商品、采购、商户与增长，让交易从发现开始，在同一个网络里持续发生。</p>
        <div class="brand-actions" data-reveal="blur-up" data-delay="160"><a-button type="primary" class="brand-primary" @click="$router.push('/mall')">进入商城 <span>→</span></a-button><a-button class="brand-ghost" @click="$router.push('/about')">了解平台</a-button></div>
      </div>
      <BrandSymbol class="brand-orbit" size="410px" />
    </section>
    <div class="brand-promise" data-reveal="fade-up"><span>01</span><b>DISCOVER</b><em>→</em><span>02</span><b>TRANSACT</b><em>→</em><span>03</span><b>GROW</b></div>

    <!-- 活动入口条：进行中的促销活动（取前 3 条）+ 活动中心入口卡；无活动时整条不渲染 -->
    <section v-if="promoActs.length" class="promo-strip" data-reveal="fade-up">
      <div class="promo-strip-head">
        <span class="promo-strip-eyebrow"><Zap :size="13" :stroke-width="2.2" /> 限时活动</span>
        <span class="promo-strip-title">平台活动进行中</span>
      </div>
      <div class="promo-strip-row">
        <button v-for="c in promoActs" :key="c.campaignId" type="button" class="promo-strip-card" @click="$router.push('/activity/' + c.campaignId)">
          <span class="psc-top">
            <i class="psc-type" :class="'kt-' + (c.accent || 'blue')">{{ promoTypeLabel(c.type) }}</i>
            <span class="psc-window">{{ promoWindow(c) }}</span>
          </span>
          <b class="psc-title" :title="c.title">{{ c.title }}</b>
          <span class="psc-th">{{ promoThreshold(c) }}</span>
        </button>
        <button type="button" class="promo-strip-card entry" @click="$router.push('/activity')">
          <span class="psc-entry-ico"><Sparkles :size="18" :stroke-width="2" /></span>
          <b class="psc-title">活动中心</b>
          <span class="psc-entry-sub">领券 · 秒杀 · 积分激励</span>
          <span class="psc-entry-go">查看全部活动 <ArrowRight :size="12" :stroke-width="2.4" /></span>
        </button>
      </div>
      <!-- 活动域入口第二行：领券中心 / 任务中心 / 大促会场（登录态不强制，未登录在页内领取/上报时引导） -->
      <div class="promo-strip-row entries">
        <button type="button" class="promo-strip-card entry" @click="$router.push('/coupons')">
          <span class="psc-entry-ico"><Ticket :size="18" :stroke-width="2" /></span>
          <b class="psc-title">领券中心</b>
          <span class="psc-entry-sub">海量券池一键批量领取</span>
          <span class="psc-entry-go">进入领券中心 <ArrowRight :size="12" :stroke-width="2.4" /></span>
        </button>
        <button type="button" class="promo-strip-card entry" @click="$router.push('/tasks')">
          <span class="psc-entry-ico"><ListChecks :size="18" :stroke-width="2" /></span>
          <b class="psc-title">任务中心</b>
          <span class="psc-entry-sub">做任务赢积分与成长值</span>
          <span class="psc-entry-go">去做任务 <ArrowRight :size="12" :stroke-width="2.4" /></span>
        </button>
        <button type="button" class="promo-strip-card entry" @click="$router.push('/venue')">
          <span class="psc-entry-ico"><Flame :size="18" :stroke-width="2" /></span>
          <b class="psc-title">大促会场</b>
          <span class="psc-entry-sub">行业大促一站逛齐</span>
          <span class="psc-entry-go">进入会场 <ArrowRight :size="12" :stroke-width="2.4" /></span>
        </button>
        <!-- 入口 1（设计稿 §2）：常驻会员中心入口卡，与领券/任务/会场同构；副标不提积分加速（×2 属身份轨） -->
        <button type="button" class="promo-strip-card entry" @click="$router.push('/membership')">
          <span class="psc-entry-ico"><Crown :size="18" :stroke-width="2" /></span>
          <b class="psc-title">会员中心</b>
          <span class="psc-entry-sub">等级折扣 · 成长升级</span>
          <span class="psc-entry-go">进入会员中心 <ArrowRight :size="12" :stroke-width="2.4" /></span>
        </button>
      </div>
    </section>

    <BannerCarousel />

    <div id="pulse"><HomeMarketPulse /></div>

    <h2 id="industries" class="sec" data-reveal><span>02</span> 行业解决方案</h2>
    <a-row v-if="loadingIndustries" :gutter="[16, 16]">
      <a-col v-for="n in 8" :key="n" :xs="12" :sm="12" :md="8" :lg="6">
        <div class="ll-skeleton ind-skeleton"></div>
      </a-col>
    </a-row>
    <a-row v-else :gutter="[16, 16]">
      <a-col v-for="(ind, i) in industries" :key="ind.indId" :xs="12" :sm="12" :md="8" :lg="6" :data-reveal="'fade-up'" :data-delay="i * 40">
        <a-card hoverable class="ind-card" @click="$router.push({ name: 'mall', query: { indId: ind.indId } })">
          <div class="ind-name" :title="ind.name">{{ ind.name }}</div>
          <div class="ind-desc" :title="'查看'+ind.name+'专区商品'">查看{{ ind.name }}专区商品 →</div>
        </a-card>
      </a-col>
    </a-row>

    <div id="hot" class="section-head"><h2 class="sec" data-reveal><span>03</span> 热销推荐</h2><button class="section-link" type="button" @click="$router.push('/mall')">查看全部商品 →</button></div>
    <a-row v-if="loadingHot" :gutter="[16, 20]">
      <a-col v-for="n in 8" :key="n" :xs="12" :sm="12" :md="8" :lg="6">
        <div class="ll-skeleton-card home-skeleton-card" aria-hidden="true">
          <div class="ll-skeleton ll-skeleton-card__img"></div>
          <div class="ll-skeleton-card__body">
            <div class="ll-skeleton ll-skeleton-card__line"></div>
            <div class="ll-skeleton ll-skeleton-card__line ll-skeleton-card__line--short"></div>
          </div>
        </div>
      </a-col>
    </a-row>
    <a-row v-else :gutter="[16, 20]">
      <a-col v-for="(p, i) in hot" :key="p.prodId" :xs="12" :sm="12" :md="8" :lg="6" :data-reveal="'fade-up'" :data-delay="i * 70">
        <InteractiveProductCard
          :image-url="p.coverUrl || ''"
          :title="p.title"
          :description="p.brand || 'LANLINK SELECTION'"
          :price="'¥' + p.price"
          @click="$router.push('/product/' + p.prodId)"
        >
          <template #footer>
            <div class="product-card-meta">销量 {{ p.sales }} · 库存 {{ p.stock }}</div>
          </template>
        </InteractiveProductCard>
      </a-col>
    </a-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { homeApi, trafficApi, campaignApi } from '../api'
import { Zap, Sparkles, ArrowRight, Ticket, ListChecks, Flame, Crown } from 'lucide-vue-next'
import BannerCarousel from '../components/BannerCarousel.vue'
import BrandWordmark from '../components/BrandWordmark.vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'
import HomeMarketPulse from '../components/pricemonitor/HomeMarketPulse.vue'
const industries = ref([])
const hot = ref([])
const loadingIndustries = ref(true)
const loadingHot = ref(true)

// ===== 活动入口条：campaignApi.list('promo') 取前 3 条，空数据整条隐藏 =====
const promoActs = ref([])
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const promoTypeLabel = (t) => ({ full_reduce: '满减', discount: '折扣', flash_sale: '限时秒杀', coupon: '领券' }[t] || '活动')
const promoWindow = (c) => `${c.startTime ? String(c.startTime).slice(0, 10) : '即日起'} ~ ${c.endTime ? String(c.endTime).slice(0, 10) : '长期'}`
const promoThreshold = (c) => {
  if (c.type === 'full_reduce') return `满 ¥${trimZero(c.threshold)} 减 ¥${trimZero(c.benefitAmount)}`
  if (c.type === 'discount') return `${rateText(c.discountRate)} 折${c.benefitAmount ? ` · 最高减 ¥${trimZero(c.benefitAmount)}` : ''}`
  if (c.type === 'flash_sale') return `${rateText(c.discountRate)} 折 · 限时抢购`
  if (c.type === 'coupon') return c.threshold > 0 ? `满 ¥${trimZero(c.threshold)} 可用` : '无门槛领取'
  return '点击查看活动详情'
}

onMounted(async () => {
  // 站点到访埋点：VISIT 为平台级事件，不归属商户（merchantId 由后端以 0 哨兵兜底）；
  // trackPage 内部校验分析 Cookie 同意项，未同意时静默跳过，上报失败亦静默不影响浏览
  trafficApi.trackPage({ eventType: 'VISIT' }).catch(() => {})
  try { industries.value = await homeApi.industries() }
  finally { loadingIndustries.value = false }
  try { hot.value = await homeApi.hot(8) }
  finally { loadingHot.value = false }
  // 活动入口条：加载失败/空数据时静默隐藏整条，不影响首页主流程
  try { promoActs.value = ((await campaignApi.list('promo')) || []).slice(0, 3) }
  catch (e) { promoActs.value = [] }
})
</script>

<style scoped>
.sec { margin: 24px 0 18px; position: relative; z-index: 2; }
/* ===== 活动入口条：Apple 风玻璃卡片（3 条活动 + 1 张活动中心入口） ===== */
.promo-strip { margin: 0 0 42px; }
.promo-strip-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; }
.promo-strip-eyebrow { display: inline-flex; align-items: center; gap: 5px; font-size: 10px; font-weight: 800; letter-spacing: .13em; color: var(--ll-brand-gold-strong, #8A6A2F); text-transform: uppercase; }
.promo-strip-title { font-size: 18px; font-weight: 750; letter-spacing: -.02em; color: var(--ll-ink, #13233a); }
.promo-strip-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
/* 活动域入口行（领券中心/任务中心/大促会场）：3 列等宽 */
.promo-strip-row.entries { grid-template-columns: repeat(3, 1fr); }
.promo-strip-card { display: flex; flex-direction: column; gap: 8px; min-height: 118px; text-align: left; font: inherit; cursor: pointer; padding: 15px 17px; border-radius: 18px; border: 1px solid rgba(128, 96, 31, .16); background: linear-gradient(180deg, rgba(255, 255, 255, .97), rgba(249, 247, 242, .9)); box-shadow: 0 12px 30px rgba(19, 35, 58, .07), inset 0 1px 0 rgba(255, 255, 255, .9); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); transition: transform .22s ease, box-shadow .22s ease, border-color .22s ease; }
.promo-strip-card:hover { transform: translateY(-3px); box-shadow: 0 16px 34px rgba(19, 35, 58, .12), inset 0 1px 0 rgba(255, 255, 255, .9); }
.promo-strip-card:active { transform: translateY(-1px); }
.psc-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.psc-type { font-style: normal; font-size: 11px; font-weight: 700; padding: 3px 9px; border-radius: 999px; white-space: nowrap; }
.kt-blue { color: #1E6EB8; background: rgba(30, 110, 184, .1); }
.kt-red { color: #c22a3e; background: rgba(224, 53, 75, .1); }
.kt-gold { color: var(--ll-brand-gold-strong, #80601F); background: rgba(201, 126, 18, .12); }
.kt-green { color: #047857; background: rgba(4, 120, 87, .1); }
.kt-purple { color: #6d28d9; background: rgba(109, 40, 217, .1); }
.psc-window { font-size: 10.5px; color: var(--ll-muted, #667085); font-variant-numeric: tabular-nums; white-space: nowrap; }
.psc-title { font-size: 13.5px; font-weight: 650; color: var(--ll-ink, #13233a); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.psc-th { margin-top: auto; font-size: 12px; font-weight: 700; color: #9b2d20; }
.promo-strip-card.entry { background: linear-gradient(135deg, rgba(30, 110, 184, .08), rgba(14, 165, 233, .05)); border-color: rgba(30, 110, 184, .25); }
.psc-entry-ico { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; color: #1E6EB8; background: rgba(30, 110, 184, .12); margin-bottom: 2px; }
.psc-entry-sub { font-size: 11.5px; color: var(--ll-muted, #667085); }
.psc-entry-go { margin-top: auto; padding-top: 4px; display: inline-flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 650; color: #1E6EB8; }
[data-theme='dark'] .promo-strip-card { background: linear-gradient(180deg, rgba(30, 41, 59, .6), rgba(15, 23, 42, .55)); border-color: rgba(255, 255, 255, .1); box-shadow: 0 10px 26px rgba(0, 0, 0, .3), inset 0 1px 0 rgba(255, 255, 255, .06); }
[data-theme='dark'] .promo-strip-card.entry { background: linear-gradient(135deg, rgba(30, 110, 184, .2), rgba(14, 165, 233, .1)); }
@media (max-width: 1000px) { .promo-strip-row { grid-template-columns: repeat(2, 1fr); } .promo-strip-row.entries { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 560px) { .promo-strip { margin-bottom: 28px; } .promo-strip-row { grid-template-columns: 1fr; } .promo-strip-row.entries { grid-template-columns: 1fr; } .psc-window { display: none; } }
.ind-card { text-align: center; height: 116px; margin-bottom: 16px; }
.ind-card :deep(.ant-card-body) { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 12px 16px; }
.ind-name { font-size: 18px; font-weight: 700; color: var(--ll-ink); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ind-desc { color: var(--ll-muted); font-size: 13px; margin-top: 6px; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 行业卡骨架：与真实卡片同高，避免加载完成时布局跳动 */
.ind-skeleton { height: 116px; margin-bottom: 16px; border-radius: var(--ll-radius-lg, 14px); }
/* 热销卡骨架：贴近 3:4 商品卡比例 */
.home-skeleton-card { min-height: 320px; display: flex; flex-direction: column; margin-bottom: 16px; }
.home-skeleton-card .ll-skeleton-card__img { aspect-ratio: auto; flex: 1; }
.home-page :deep(.interactive-product-card) { margin-bottom: 16px; }
.thumb { height: 120px; background: var(--ll-thumb-bg); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 40px; color: var(--ll-thumb-fg); margin-bottom: 8px; overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.ptitle { font-size: 14px; height: 40px; overflow: hidden; }
.pprice { color: var(--ll-price); font-weight: 700; font-size: 18px; margin-top: 4px; }
.pmeta { color: var(--ll-gray); font-size: 12px; }
.home-page{color:var(--ll-ink)}
/* 侧栏导航跳转时避开 sticky 头部（64px）遮挡 */
#industries,#hot{scroll-margin-top:84px}
.brand-hero{min-height:430px;position:relative;overflow:hidden;border-radius:28px;padding:60px 68px;display:flex;align-items:center;isolation:isolate;border:1px solid rgba(255,255,255,.12);background:radial-gradient(920px 520px at 82% 14%,rgba(255,255,255,.65),transparent 64%),radial-gradient(780px 480px at 102% 92%,rgba(14,165,233,.16),transparent 62%),radial-gradient(640px 440px at 6% 104%,rgba(30,110,184,.12),transparent 60%),radial-gradient(560px 360px at 14% -8%,rgba(125,211,252,.20),transparent 60%),linear-gradient(135deg,#F2F9FF 0%,#D6EBFB 30%,#C4E3F9 55%,#E3F1FD 80%,#F2F9FF 100%);/* 底色流动：background-size 独立声明防简写覆盖；radial 光斑层保持 auto（层级尺寸=定位区，位移动画对其零偏移），仅 linear 段放大流动 */background-size:auto,auto,auto,auto,300% 300%;animation:llGradShift 18s ease infinite;border:1px solid #C9E2F6;box-shadow:0 24px 70px rgba(30,110,184,.14)}
/* 极光：浅天蓝底的高光漂移（天空系低饱和 conic），方案 B 整体旋转（llHaloRotate 合成层友好）。
   360° 旋转下矩形 box 角部会外露，故改用居中正方形（边长 150% 宽 ≥ hero 对角线）保证全角度覆盖 */
.brand-hero::before{content:"";position:absolute;left:50%;top:50%;width:150%;aspect-ratio:1;translate:-50% -50%;z-index:0;background:conic-gradient(from 140deg at 68% 32%,rgba(186,230,253,.5),rgba(125,211,252,.35),rgba(255,255,255,.4),rgba(186,230,253,.5));filter:blur(64px);opacity:.55;animation:llHaloRotate 40s linear infinite;will-change:transform}
.brand-hero::after{content:"";position:absolute;inset:0;z-index:1;pointer-events:none;opacity:.08;mix-blend-mode:soft-light;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.55'/%3E%3C/svg%3E");background-size:180px 180px}
.brand-grid{position:absolute;inset:0;z-index:1;opacity:.12;background-image:linear-gradient(rgba(19,35,58,.06) 1px,transparent 1px),linear-gradient(90deg,rgba(19,35,58,.06) 1px,transparent 1px);background-size:44px 44px;mask-image:linear-gradient(90deg,black,transparent 75%)}
/* 漂移光斑：参照 ll-blob 原语——颜色走 --blob-c、静态径向渐变底，transform 交给 llBlobV/llBlobC 帧驱动 */
.brand-ambient{position:absolute;z-index:0;border-radius:999px;filter:blur(40px);pointer-events:none;background:radial-gradient(circle,var(--blob-c,rgba(255,255,255,.55)) 0%,transparent 70%);will-change:transform}.ambient-a{width:340px;height:340px;right:24%;top:-140px;--blob-c:rgba(125,211,252,.5);animation:llBlobV 22s ease-in-out infinite alternate}.ambient-b{width:400px;height:400px;right:-110px;bottom:-190px;--blob-c:rgba(255,255,255,.55);animation:llBlobC 30s -12s ease-in-out infinite alternate}.ambient-c{width:360px;height:360px;left:8%;top:18%;--blob-c:var(--ll-sky-blob-4);animation:llBlobV 26s -8s ease-in-out infinite alternate}
/* 斜向扫光：借鉴 open-design.ai 高光带，周期性掠过，为浅天蓝底增加流动的高光层次 */
.brand-sweep{position:absolute;top:-20%;bottom:-20%;left:-34%;width:44%;z-index:2;pointer-events:none;mix-blend-mode:screen;background:linear-gradient(115deg,transparent 16%,rgba(255,255,255,.16) 40%,rgba(236,250,255,.34) 50%,rgba(186,230,253,.22) 58%,transparent 84%);transform:translateX(0) skewX(-14deg);animation:sheenSweep 7.5s ease-in-out 1.8s infinite}
/* 底部地平光：天蓝→青色的细光带，让宣传区底部有“舞台灯”层次 */
.brand-horizon{position:absolute;left:5%;right:5%;bottom:0;height:1px;z-index:1;pointer-events:none;background:linear-gradient(90deg,transparent,rgba(56,189,248,.45) 30%,rgba(14,165,233,.4) 62%,transparent);filter:drop-shadow(0 -6px 16px rgba(56,189,248,.30));opacity:.75}
/* 展示玻璃窗：两块漂浮玻璃画框（纯装饰），升级 .ll-glass--day 同等参数——
   padding-box 白浊填充 + border-box 140deg 渐变描边 + day blur/saturate + 三段阴影 */
.brand-pane{position:absolute;z-index:1;overflow:hidden;border-radius:var(--ll-glass-day-radius,14px);border:1px solid transparent;background:linear-gradient(140deg,rgba(255,255,255,.30),rgba(214,236,250,.12) 52%,rgba(255,255,255,.18)) padding-box,linear-gradient(140deg,rgba(255,255,255,.9),rgba(255,255,255,.16) 38%,rgba(255,255,255,.05) 62%,rgba(255,255,255,.55)) border-box;box-shadow:0 10px 34px rgba(30,110,184,.14),inset 0 3px 16px rgba(255,255,255,.26),inset 0 1px 0 rgba(255,255,255,.7);backdrop-filter:blur(14px) saturate(170%) brightness(1.03);-webkit-backdrop-filter:blur(14px) saturate(170%) brightness(1.03);pointer-events:none}
.brand-pane::before{content:"";position:absolute;top:10px;left:12px;width:6px;height:6px;border-radius:50%;background:rgba(255,255,255,.55);box-shadow:10px 0 0 rgba(255,255,255,.38),20px 0 0 rgba(255,255,255,.24)}
/* 玻璃面自己的高光扫过：面是 190px 级的小卡，靠自身掠光才像"液态玻璃"，不能只指望整块 hero 的扫光路过 */
.brand-pane::after{content:"";position:absolute;inset:0;border-radius:inherit;background:linear-gradient(115deg,transparent 34%,rgba(255,255,255,.42) 47%,rgba(186,230,253,.22) 54%,transparent 68%);transform:translateX(-130%) skewX(-12deg);animation:paneSheen 6s ease-in-out infinite;pointer-events:none}
.pane-b::after{animation-delay:2.4s}
@keyframes paneSheen{0%{transform:translateX(-130%) skewX(-12deg)}55%,100%{transform:translateX(130%) skewX(-12deg)}}
/* 卡内动态底色：参考 21st.dev @minhxthanh/liquid-effect-animation 的"模糊色团漂移"动效语言
   （https://21st.dev/@minhxthanh/components/liquid-effect-animation，作者 Le Thanh，License 未标注，
   仅借鉴思路、未复制其源码）。色团取项目天蓝/金令牌，压到低透明度，只做氛围不做主体。 */
.pane-tint{position:absolute;inset:-35%;pointer-events:none;opacity:.85;filter:blur(16px) saturate(135%);background:radial-gradient(42% 58% at 30% 34%,rgba(125,211,252,.55),transparent 70%),radial-gradient(40% 52% at 74% 70%,rgba(200,164,92,.30),transparent 72%),radial-gradient(48% 60% at 56% 22%,rgba(186,230,253,.5),transparent 70%);animation:paneTintDrift 16s ease-in-out infinite alternate}
.pane-b .pane-tint{animation-duration:19s;animation-delay:-7s;opacity:.7}
@keyframes paneTintDrift{0%{transform:translate3d(-6%,4%,0) rotate(-4deg) scale(1)}50%{transform:translate3d(5%,-5%,0) rotate(3deg) scale(1.08)}100%{transform:translate3d(-2%,6%,0) rotate(-2deg) scale(1.02)}}
.pane-a{width:190px;height:118px;right:23%;top:11%;animation:paneFloatA 8s ease-in-out infinite,llLiquidRadius 9s ease-in-out infinite}
.pane-b{width:150px;height:94px;right:4%;bottom:12%;animation:paneFloatB 10s 1.2s ease-in-out infinite,llLiquidRadius 9s -4.5s ease-in-out infinite}
/* SVG 真折射单件：仅声明支持 SVG 引用滤镜的引擎（Chromium）启用 feDisplacementMap 位移折射，
   其余引擎维持上方 blur(16px) saturate(150%) 保底；pane-a 为 190x118 纯装饰无文字面 */
@supports (backdrop-filter: url(#ll-refract)) {
  .brand-pane.pane-a { backdrop-filter: url(#ll-refract) brightness(150%); }
}
.brand-hero-copy{position:relative;z-index:3;max-width:650px}.brand-eyebrow{color:#1E6EB8;font-size:11px;font-weight:800;letter-spacing:.17em}.home-wordmark{margin-top:18px}
.brand-hero h1{margin:22px 0 12px;color:#13233A;font-size:clamp(30px,4vw,48px);line-height:1.12;letter-spacing:-.045em;font-weight:750}.brand-hero p{margin:0;max-width:610px;color:#4A6B8A;font-size:15px;line-height:1.9}.brand-actions{display:flex;gap:12px;margin-top:26px}.brand-primary{border:0;background:linear-gradient(100deg,var(--ll-brand-gold),#F59E0B);color:var(--ll-brand-base);font-weight:750}.brand-primary:hover,.brand-primary:focus{color:var(--ll-brand-base);filter:brightness(1.06)}.brand-ghost{color:#13233A;background:rgba(255,255,255,.6);border-color:rgba(30,110,184,.3);backdrop-filter:blur(12px)}.brand-ghost:hover,.brand-ghost:focus{color:#13233A;background:rgba(255,255,255,.9);border-color:rgba(30,110,184,.55)}
.brand-orbit{position:absolute;z-index:2;right:45px;top:10px}
.brand-promise{display:flex;align-items:center;justify-content:center;gap:10px;margin:14px 0 34px;padding:13px 16px;border:1px solid rgba(128,96,31,.24);border-radius:14px;background:linear-gradient(180deg,rgba(255,255,255,.96),rgba(249,247,242,.84));color:#667085;font-size:10px;letter-spacing:.13em;box-shadow:0 12px 30px rgba(19,35,58,.07),inset 0 1px 0 rgba(255,255,255,.9);backdrop-filter:blur(14px)}.brand-promise span{color:var(--ll-brand-red);font-weight:800}.brand-promise b{color:var(--ll-brand-base)}.brand-promise em{font-style:normal;color:var(--ll-brand-gold-strong);margin:0 8px}.sec span{font-size:10px;color:var(--ll-brand-gold-strong);letter-spacing:.12em;margin-right:8px;vertical-align:middle}.section-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.section-link{border:0;background:transparent;color:var(--ll-brand-blue);font:inherit;font-size:12px;cursor:pointer}.home-page :deep(.product-card-meta){color:rgba(255,255,255,.84);font-size:12px;line-height:1.5;text-shadow:0 1px 8px rgba(0,0,0,.2)}
@keyframes sheenSweep{0%{transform:translateX(0) skewX(-14deg);opacity:0}10%{opacity:1}52%{opacity:1}62%{transform:translateX(430%) skewX(-14deg);opacity:0}100%{transform:translateX(430%) skewX(-14deg);opacity:0}}
@keyframes paneFloatA{0%,100%{transform:rotate(-7deg) translate(0,0)}50%{transform:rotate(-7deg) translate(-10px,14px)}}
@keyframes paneFloatB{0%,100%{transform:rotate(5deg) translate(0,0)}50%{transform:rotate(5deg) translate(12px,-10px)}}
/* 深色模式：承诺条表面翻转，保持可读性 */
[data-theme='dark'] .brand-promise{background:rgba(22,32,46,.72);border-color:var(--ll-border);color:var(--ll-muted)}
[data-theme='dark'] .brand-promise b{color:var(--ll-ink)}
/* 深色模式下页面底色转深，加深金会失去对比：回调为亮金（≥6.9:1） */
[data-theme='dark'] .sec span{color:var(--ll-brand-gold)}
[data-theme='dark'] .brand-promise em{color:var(--ll-brand-gold)}
@media(max-width:1000px){.brand-hero{padding:48px 42px}.brand-orbit{right:-100px;opacity:.38}.brand-pane{display:none}}@media(max-width:640px){.brand-hero{min-height:500px;border-radius:20px;padding:34px 24px;align-items:flex-start}.brand-hero h1{font-size:34px}.brand-orbit{right:-80px;top:auto;bottom:-70px;transform:scale(.683);transform-origin:right bottom}.brand-promise{justify-content:flex-start;overflow-x:auto;white-space:nowrap}.brand-actions{flex-wrap:wrap}.section-head{align-items:flex-end}}
/* 手机竖屏：宣传主视觉与行业板块按比例缩小，避免品牌词/标题溢出卡片 */
@media(max-width:767px){
  .brand-hero{min-height:360px;border-radius:18px;padding:26px 20px;align-items:flex-start}
  .brand-hero::before{filter:blur(48px);opacity:.6;animation:none}
  .ambient-c{display:none}
  .brand-hero::after{opacity:.1}
  .home-wordmark{font-size:clamp(24px,7.2vw,32px)!important;margin-top:12px!important}
  .brand-eyebrow{font-size:10px;letter-spacing:.13em}
  .brand-hero h1{font-size:22px!important;line-height:1.35;margin:14px 0 8px}
  .brand-hero p{font-size:12.5px;line-height:1.7}
  .brand-actions{gap:10px;margin-top:18px}
  .brand-actions :deep(.ant-btn){height:36px;padding-inline:14px;font-size:13px}
  .brand-promise{margin:12px 0 22px;padding:10px 12px;font-size:9px}
  .brand-orbit{opacity:.28;transform:scale(.56);bottom:-80px;right:-70px}
  .sec{margin:18px 0 14px}
  /* 移动端头部两行更高（≈94px），chips 与锚点避让随之上调 */
  .home-rail-chips{top:92px}
  #industries,#hot{scroll-margin-top:96px}
  .section-head{margin-top:18px}
  .ind-card,.ind-skeleton{height:96px}
  .ind-name{font-size:15px}
  .ind-desc{font-size:11px;margin-top:4px}
}
@media(prefers-reduced-motion:reduce){.ambient-a,.ambient-b,.ambient-c,.orbit-dot,.brand-sweep,.brand-pane,.brand-pane::after,.pane-tint,.brand-hero::before,.brand-hero::after{animation:none}.brand-hero{animation:none!important}}
/* perf-tier 本地补口：motion.css 全局收口已含 [class*="pane-"] 与 .brand-hero::before，
   但 .brand-ambient 光斑（未带 .ll-blob 类）不在其清单内，此处补齐 suspended 停帧 */
[data-perf-tier="suspended"] .ambient-a,[data-perf-tier="suspended"] .ambient-b,[data-perf-tier="suspended"] .ambient-c{animation:none}
</style>
