<template>
  <!--
    首页导航二级分栏（悬停「首页」弹出，交互与样式对齐 IndustryCategoryMenu 商城分栏）：
    三栏 —— 页面导览（本页专区滚动定位）/ 平台服务（跨页快捷入口）/ 快捷开始（角色感知入口）。
    ≤767px 隐藏（移动端导航为横向滑动，无悬停交互）。
  -->
  <div class="home-mega" @click.stop>
    <!-- 栏 A：页面导览（按页面自上而下的板块顺序排列；回到页首作为末位工具项） -->
    <section class="mega-pane">
      <div class="pane-head">
        <div><strong>页面导览</strong><small>HOMEPAGE · SECTIONS</small></div>
        <span class="syncing">当前页</span>
      </div>
      <div class="nav-grid">
        <button v-for="s in sections" :key="s.id" type="button" class="nav-item" @click.stop="goSection(s.id)">
          <span>{{ s.label }}</span><i>›</i>
        </button>
      </div>
      <button type="button" class="nav-item back-top" @click.stop="goSection('top')">
        <span>回到页首</span><i>↑</i>
      </button>
      <div class="pane-tip">在首页时点击可直接滚动到对应板块</div>
    </section>

    <!-- 栏 B：平台服务（跨页快捷入口） -->
    <section class="mega-pane">
      <div class="pane-head">
        <div><strong>平台服务</strong><small>SERVICES</small></div>
      </div>
      <div class="service-grid">
        <button v-for="s in services" :key="s.path" type="button" class="service-item" @click.stop="go(s.path)">
          <span class="service-ico" :class="'tint-' + s.tint">
            <component :is="s.icon" :size="16" :stroke-width="2" />
          </span>
          <span class="service-copy"><b>{{ s.label }}</b><small>{{ s.desc }}</small></span>
          <i>›</i>
        </button>
      </div>
    </section>

    <!-- 栏 C：快捷开始（与中栏服务去重：聚焦个人交易动作 + 角色感知商户入口） -->
    <section class="mega-pane quick-pane">
      <div class="pane-head">
        <div><strong>快捷开始</strong><small>QUICK START</small></div>
      </div>
      <button type="button" class="all-entry" @click.stop="go('/mall')">
        <span>进入商城</span><i>→</i>
      </button>
      <button type="button" class="all-entry" @click.stop="go('/orders')">
        <span>我的订单</span><i>→</i>
      </button>
      <button type="button" class="all-entry" @click.stop="go('/wallet')">
        <span>我的钱包</span><i>→</i>
      </button>
      <button type="button" class="all-entry" @click.stop="go(user.hasPerm('merchant:manage') || user.hasPerm('product:publish') ? '/merchant/center' : '/merchant')">
        <span>{{ user.hasPerm('merchant:manage') || user.hasPerm('product:publish') ? '商户中心' : '商户入驻' }}</span><i>→</i>
      </button>
      <button type="button" class="all-entry" @click.stop="go('/about')">
        <span>了解平台</span><i>→</i>
      </button>
    </section>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../../store/user'
// 服务图标：Lucide 图标体系（21st.dev shadcn 生态标配，与全站 lucide-vue-next 同源）
import { MonitorPlay, LineChart, Gift, ReceiptText } from 'lucide-vue-next'

const props = defineProps({
  /** 面板关闭回调：交给 MainLayout 统一管理开关状态 */
  close: { type: Function, required: true }
})
const router = useRouter()
const user = useUserStore()

/** 页面导览顺序 = 首页板块真实上下顺序（轮播 → 每日行情 → 行业解决方案 → 热销推荐） */
const sections = [
  { id: 'pulse', label: '每日行情' },
  { id: 'industries', label: '行业解决方案' },
  { id: 'hot', label: '热销推荐' }
]

/** tint 决定图标色块底色（柔和浅色系，Apple 设置页分组图标风格）；顺序按买家动线：发现 → 优惠 → 决策 → 支付 */
const services = [
  { path: '/live', label: '直播广场', desc: '生产线实时直播与讲解', icon: MonitorPlay, tint: 'sky' },
  { path: '/activity', label: '活动中心', desc: '领券、限时秒杀与积分', icon: Gift, tint: 'gold' },
  { path: '/price-monitor', label: '价格情报台', desc: '价格走势 · AI 实时分析', icon: LineChart, tint: 'indigo' },
  { path: '/credit-term', label: '企业账期', desc: '授信 · 账单 · 还款', icon: ReceiptText, tint: 'teal' }
]

function go(path) {
  props.close()
  router.push(path)
}

/** 页面导览：在首页时平滑滚动到专区；在其他页则先回首页再定位 */
function goSection(id) {
  props.close()
  if (id === 'top') {
    if (router.currentRoute.value.path === '/') window.scrollTo({ top: 0, behavior: 'smooth' })
    else router.push('/')
    return
  }
  const jump = () => {
    requestAnimationFrame(() => {
      const el = document.getElementById(id)
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
  if (router.currentRoute.value.path === '/') jump()
  else {
    router.push('/')
    setTimeout(jump, 450)
  }
}
</script>

<style scoped>
.home-mega {
  position: absolute;
  left: 0;
  top: 58px;
  width: min(880px, calc(100vw - 40px));
  display: grid;
  grid-template-columns: 250px minmax(360px, 1fr) 230px;
  background: rgba(255,255,255,.975);
  border: 1px solid rgba(148,163,184,.22);
  border-radius: 0 0 20px 20px;
  box-shadow: 0 22px 58px rgba(15,23,42,.22);
  backdrop-filter: blur(22px) saturate(125%);
  overflow: hidden;
  z-index: 120;
  line-height: 1.5;
  animation: homeMegaIn .16s ease-out;
}
@keyframes homeMegaIn { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: translateY(0); } }

.mega-pane { min-height: 380px; padding: 18px 16px; }
.mega-pane + .mega-pane { border-left: 1px solid rgba(148,163,184,.16); }
.quick-pane { background: linear-gradient(180deg, rgba(249,247,242,.9), rgba(255,255,255,.4)); }

.pane-head { display: flex; align-items: baseline; justify-content: space-between; padding: 0 4px 12px; }
.pane-head strong { display: block; font-size: 15px; color: var(--ll-ink, #0f172a); letter-spacing: -.01em; }
.pane-head small { display: block; margin-top: 3px; color: #a0aec0; font-size: 9px; letter-spacing: .14em; }
.syncing { color: #a0aec0; font-size: 10px; }

.all-entry { display: flex; align-items: center; justify-content: space-between; width: 100%;
  border: 0; background: rgba(30,110,184,.06); border-radius: 12px; padding: 12px 14px;
  margin-bottom: 8px; font: inherit; font-size: 13.5px; font-weight: 650; color: var(--ll-ink, #0f172a);
  cursor: pointer; transition: background .18s ease, transform .18s ease; }
.all-entry:hover { background: rgba(14,165,233,.10); transform: translateX(2px); }
.all-entry i { font-style: normal; color: var(--ll-azure, #0EA5E9); }

.nav-grid, .service-grid { display: grid; gap: 4px; }
.nav-item.back-top { margin-top: 6px; color: #a0aec0; }
.nav-item.back-top:hover { color: var(--ll-navy, #13233a); }
.nav-item { display: flex; align-items: center; justify-content: space-between; width: 100%;
  border: 0; background: transparent; text-align: left; font: inherit; font-size: 13.5px;
  color: var(--ll-gray, #4b5563); padding: 11px 12px; border-radius: 12px; cursor: pointer;
  transition: background .18s ease, color .18s ease, transform .18s ease; }
.nav-item:hover { background: rgba(14,165,233,.10); color: var(--ll-navy, #13233a); transform: translateX(2px); }
.nav-item i { font-style: normal; color: #c4cad4; }

.service-item { display: flex; align-items: center; gap: 11px; width: 100%;
  border: 0; background: transparent; text-align: left; font: inherit;
  padding: 11px 12px; border-radius: 12px; cursor: pointer;
  transition: background .18s ease, transform .18s ease; }
.service-item:hover { background: rgba(14,165,233,.10); transform: translateX(2px); }
/* 服务图标块：Lucide 图标 + 按服务区分的柔和浅色圆角底（21st.dev 图标块模式） */
.service-ico { width: 36px; height: 36px; border-radius: 11px; display: grid; place-items: center;
  flex-shrink: 0; transition: transform .18s ease, box-shadow .18s ease; }
.service-item:hover .service-ico { transform: scale(1.08); box-shadow: 0 5px 14px rgba(15, 23, 42, .12); }
.tint-sky { color: #0369a1; background: linear-gradient(135deg, #e0f2fe, #bae6fd); }
.tint-indigo { color: #4338ca; background: linear-gradient(135deg, #e0e7ff, #c7d2fe); }
.tint-gold { color: #92650e; background: linear-gradient(135deg, #fef3c7, #fde68a); }
.tint-teal { color: #0f766e; background: linear-gradient(135deg, #ccfbf1, #99f6e4); }
[data-theme='dark'] .tint-sky { color: #7dd3fc; background: rgba(14, 165, 233, .16); }
[data-theme='dark'] .tint-indigo { color: #a5b4fc; background: rgba(99, 102, 241, .16); }
[data-theme='dark'] .tint-gold { color: var(--ll-brand-gold, #C8A45C); background: rgba(200, 164, 92, .14); }
[data-theme='dark'] .tint-teal { color: #5eead4; background: rgba(20, 184, 166, .14); }
.service-copy { flex: 1; min-width: 0; }
.service-copy b { display: block; font-size: 13px; font-weight: 650; color: var(--ll-ink, #0f172a); }
.service-copy small { display: block; margin-top: 2px; color: #a0aec0; font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.service-item:hover .service-copy b { color: var(--ll-navy, #13233a); }
.service-item i { font-style: normal; color: #c4cad4; }

.pane-tip { margin: 12px 4px 0; padding-top: 11px; border-top: 1px dashed rgba(148,163,184,.3);
  color: #a0aec0; font-size: 10.5px; line-height: 1.6; }

@media (max-width: 1100px) {
  .home-mega { width: min(760px, calc(100vw - 28px)); grid-template-columns: 220px minmax(300px, 1fr) 200px; }
}
@media (max-width: 767px) {
  .home-mega { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .home-mega { animation: none; }
}
</style>
