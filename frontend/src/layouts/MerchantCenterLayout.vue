<template>
  <div class="mc-wrap">
    <!-- ===== 手机端：横向滑动导航（≤991px 时替代侧栏） ===== -->
    <div v-if="approved" class="mc-mobile-nav">
      <button v-for="item in NAV" :key="item.path" type="button" class="mc-chip"
              :class="{ on: isActive(item) }" @click="go(item)">
        {{ item.label }}
      </button>
    </div>

    <div class="mc-grid">
      <!-- ===== 桌面端侧栏 ===== -->
      <aside v-if="approved" class="mc-side">
        <div class="mc-store">
          <UserAvatar :src="user.user?.avatar || ''" :name="storeName" :initials="orgInitialsOf(storeName)"
                      role="merchant" :size="46" :ring="false" class="mc-logo" />
          <div class="mc-store-meta">
            <b :title="storeName">{{ storeName }}</b>
            <a-tag :color="MERCHANT_REVIEW[reviewStatus]?.color" class="mc-store-tag">
              {{ MERCHANT_REVIEW[reviewStatus]?.text || '已通过' }}
            </a-tag>
          </div>
        </div>
        <nav class="mc-nav">
          <button v-for="item in NAV" :key="item.path" type="button" class="mc-nav-item"
                  :class="{ on: isActive(item) }" @click="go(item)">
            <span>{{ item.label }}</span>
            <i v-if="item.path === '/merchant/center/orders' && dash.pendingShip"
               class="nav-badge">{{ dash.pendingShip }}</i>
          </button>
        </nav>
        <div class="mc-side-foot">
          <p>商户服务专线</p>
          <b>400-888-0 LAN</b>
          <span>工作日 9:00 - 18:00</span>
        </div>
      </aside>

      <!-- ===== 主内容区 ===== -->
      <section class="mc-body">
        <!-- 首次加载 -->
        <div v-if="loading" class="mc-state">
          <a-skeleton active avatar paragraph :rows="6" />
        </div>

        <!-- 未入驻：引导页 -->
        <div v-else-if="state === 'none'" class="mc-state">
          <div class="gate-card">
            <div class="gate-ico"><Store :size="38" :stroke-width="1.6" /></div>
            <h2>开通您的商户店铺</h2>
            <p>完成入驻审核后，即可发布商品、管理订单、查看经营数据，<br class="pc-br" />并获得企业账期、专属客服等 B2B 服务。</p>
            <div class="gate-feats">
              <div><b>商品经营</b><span>发布/编辑商品，平台审核保障合规</span></div>
              <div><b>订单履约</b><span>一键发货，全程物流轨迹跟踪</span></div>
              <div><b>经营分析</b><span>流量来源、转化漏斗、经营健康度</span></div>
              <div><b>资金结算</b><span>收入明细与退款一目了然</span></div>
            </div>
            <a-button type="primary" size="large" class="gate-btn" @click="$router.push('/merchant')">开始入驻申请</a-button>
          </div>
        </div>

        <!-- 审核中 -->
        <div v-else-if="state === 'reviewing'" class="mc-state">
          <div class="gate-card">
            <div class="gate-ico review"><Clock :size="38" :stroke-width="1.6" /></div>
            <h2>入驻申请审核中</h2>
            <p>我们正在核验您的企业资质与纳税记录，通常在 1-3 个工作日内完成。<br class="pc-br" />审核结果将通过站内消息通知您。</p>
            <a-button class="gate-btn" @click="$router.push('/me/messages')">查看站内消息</a-button>
          </div>
        </div>

        <!-- 已驳回 -->
        <div v-else-if="state === 'rejected'" class="mc-state">
          <div class="gate-card">
            <div class="gate-ico reject"><Ban :size="38" :stroke-width="1.6" /></div>
            <h2>入驻申请未通过</h2>
            <a-alert type="error" show-icon class="gate-alert"
                     :message="'驳回原因：' + (merchant.rejectReason || '资质材料不符合要求')" />
            <p>请核对驳回原因后联系平台客服，由运营协助重新发起入驻流程。</p>
            <div class="gate-actions">
              <a-button class="gate-btn" @click="$router.push('/me/messages')">查看站内消息</a-button>
              <a-button type="primary" class="gate-btn" @click="$router.push('/me')">联系客服</a-button>
            </div>
          </div>
        </div>

        <!-- 已通过：渲染各页面 -->
        <router-view v-else />
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { merchantApi, merchantCenterApi } from '../api'
import { MERCHANT_REVIEW } from '../utils/merchantStatus'
import { useUserStore } from '../store/user'
import UserAvatar from '../components/ui/UserAvatar.vue'
import { orgInitialsOf } from '../utils/avatarIdentity'
import { Store, Ban, Clock } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

/** 商户中心二级导航（二/三级页面从此进入） */
const NAV = [
  { path: '/merchant/center', label: '工作台', exact: true },
  { path: '/merchant/center/products', label: '商品管理' },
  { path: '/merchant/center/marketing', label: '营销活动' },
  { path: '/merchant/center/orders', label: '订单管理' },
  { path: '/merchant/center/logistics', label: '物流发货' },
  { path: '/merchant/center/promotion', label: '流量推广' },
  { path: '/merchant/center/traffic', label: '流量分析' },
  { path: '/merchant/center/finance', label: '资金结算' },
  { path: '/merchant/center/store', label: '店铺设置' }
]

const loading = ref(true)
const merchant = ref(null)
const dash = ref({})
const state = computed(() => {
  const m = merchant.value
  if (!m) return 'none'
  const s = m.reviewStatus
  if (s === 1) return 'approved'
  return s === 2 ? 'rejected' : 'reviewing'
})
const approved = computed(() => state.value === 'approved')
const reviewStatus = computed(() => merchant.value?.reviewStatus ?? 1)
/** 店铺名自动检测：企业名（summary 接口）→ 账号昵称 → 兜底文案 */
const storeName = computed(() =>
  dash.value?.store?.entName || user.user?.nickname || '我的店铺')

function isActive(item) {
  return item.exact ? route.path === item.path : route.path.startsWith(item.path)
}
function go(item) {
  if (route.path !== item.path) router.push(item.path)
}

async function loadGate() {
  loading.value = true
  try {
    merchant.value = await merchantApi.my()
  } catch (e) {
    merchant.value = null
  } finally {
    loading.value = false
  }
}

/** 拉取工作台数据用于侧栏角标（待发货数），静默失败不影响导航 */
async function loadDashBadge() {
  try {
    dash.value = (await merchantCenterApi.summary()) || {}
  } catch (e) { /* 角标失败可忽略 */ }
}

onMounted(async () => {
  await loadGate()
  if (approved.value) loadDashBadge()
})

// 门店状态可能因审核通过而变化（站内消息/刷新），路由切换时复查一次
watch(() => route.path, () => {
  if (!approved.value && !loading.value) loadGate()
})

defineExpose({ merchant, reload: loadGate })
</script>

<style>
/* ===== 商户中心主题别名（各页面统一引用 --mc-* 变量，浅色默认 + 深色覆盖） =====
   选中态/图表柱色与商城二级分栏（IndustryCategoryMenu）同源：天蓝 #0EA5E9 + 海军蓝 --ll-navy */
.mc-wrap {
  --mc-card: #fff;
  --mc-ink: #1d1d1f;
  --mc-text2: #3a3a3c;
  --mc-muted: #6e6e73;
  --mc-muted2: #86868b;
  --mc-faint: #a1a1a6;
  --mc-accent: #9b2d20;
  --mc-border: #e8e6e1;
  --mc-border-soft: #f0eee9;
  --mc-soft: #f5f5f7;
  --mc-tint: #faf9f6;
  --mc-decor: #f5f0e8;
  --mc-bar: #13233a;
  --mc-select-bg: rgba(14, 165, 233, .10);
  --mc-select-ink: var(--ll-navy, #13233a);
  /* 图表用色取柔和天蓝（sky-300→400），避免高饱和深蓝带来的生硬对比 */
  --mc-chart-grad: linear-gradient(180deg, #7dd3fc 0%, #38bdf8 100%);
  --mc-chart-solid: #38bdf8;
  --mc-chart-muted: #9db4cc;
}
[data-theme='dark'] .mc-wrap {
  --mc-card: #16202e;
  --mc-ink: #e8edf5;
  --mc-text2: #c6cfdd;
  --mc-muted: #9aa7b8;
  --mc-muted2: #8fa0b5;
  --mc-faint: #6d7d93;
  --mc-accent: #ff7161;
  --mc-border: #263349;
  --mc-border-soft: #223047;
  --mc-soft: #1a2536;
  --mc-tint: #1d2a3d;
  --mc-decor: rgba(255, 255, 255, 0.07);
  --mc-bar: #5b7089;
  --mc-select-bg: rgba(14, 165, 233, .18);
  --mc-select-ink: #7dd3fc;
  --mc-chart-grad: linear-gradient(180deg, #38bdf8 0%, #0ea5e9 100%);
  --mc-chart-solid: #38bdf8;
  --mc-chart-muted: #3d5a78;
}
</style>

<style scoped>
.mc-wrap { max-width: 1200px; margin: 0 auto; }
.mc-grid { display: grid; grid-template-columns: 228px 1fr; gap: 20px; align-items: start; }

/* ===== 侧栏（Apple 设置页风格：白卡 + 分组列表） ===== */
.mc-side { position: sticky; top: 88px; display: flex; flex-direction: column; gap: 14px; }
.mc-store { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 16px; display: flex; gap: 12px; align-items: center; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mc-logo { flex-shrink: 0; }
.mc-store-meta { min-width: 0; }
.mc-store-meta b { display: block; font-size: 14px; color: var(--mc-ink, #1d1d1f); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; letter-spacing: -.01em; }
.mc-store-tag { margin: 5px 0 0; font-size: 11px; line-height: 16px; padding: 0 7px; border-radius: 999px; }
.mc-nav { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 8px; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); display: flex; flex-direction: column; gap: 2px; }
.mc-nav-item { position: relative; display: flex; align-items: center; gap: 10px; width: 100%; border: 0; background: transparent; text-align: left; font: inherit; font-size: 13.5px; color: var(--mc-text2, #3a3a3c); padding: 10px 12px; border-radius: 10px; cursor: pointer; transition: background .18s ease, color .18s ease; }
.mc-nav-item:hover { background: var(--mc-soft, #f5f5f7); color: var(--mc-ink, #1d1d1f); }
/* 选中态：白底 + 海军蓝粗体（与商城分栏选中字色同源）+ 左侧天蓝指示条 */
.mc-nav-item.on { background: var(--mc-card, #fff); color: var(--mc-select-ink, #13233a); font-weight: 600; }
.mc-nav-item.on::before { content: ""; position: absolute; left: 0; top: 50%; transform: translateY(-50%); width: 3px; height: 16px; border-radius: 99px; background: var(--mc-chart-solid, #0ea5e9); }
.nav-badge { margin-left: auto; font-style: normal; font-size: 11px; line-height: 1; background: var(--mc-accent, #9b2d20); color: #fff; border-radius: 999px; padding: 4px 6px; font-weight: 600; }
.mc-side-foot { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 14px 16px; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mc-side-foot p { margin: 0; font-size: 11px; color: var(--mc-muted2, #86868b); }
.mc-side-foot b { display: block; font-size: 13px; color: var(--mc-ink, #1d1d1f); margin: 4px 0 2px; letter-spacing: -.01em; }
.mc-side-foot span { font-size: 11px; color: var(--mc-faint, #a1a1a6); }

/* ===== 主内容 ===== */
.mc-body { min-width: 0; }
.mc-state { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 18px; padding: 26px; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.gate-card { max-width: 560px; margin: 26px auto; text-align: center; padding: 12px 0 6px; }
.gate-ico { width: 72px; height: 72px; margin: 0 auto 18px; border-radius: 22px; background: var(--mc-soft, #f5f5f7); display: grid; place-items: center; font-size: 34px; }
.gate-ico.review { background: var(--mc-tint, #fff7e8); }
.gate-ico.reject { background: var(--mc-tint, #fdeeee); }
.gate-card h2 { margin: 0 0 10px; font-size: 24px; font-weight: 700; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); }
.gate-card p { margin: 0 0 20px; color: var(--mc-muted, #6e6e73); font-size: 14px; line-height: 1.7; }
.gate-alert { text-align: left; margin-bottom: 16px; }
.gate-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 24px; text-align: left; }
.gate-feats div { background: var(--mc-soft, #f5f5f7); border-radius: 14px; padding: 13px 15px; }
.gate-feats b { display: block; font-size: 13px; color: var(--mc-ink, #1d1d1f); margin-bottom: 4px; }
.gate-feats span { font-size: 12px; color: var(--mc-muted, #6e6e73); line-height: 1.5; }
.gate-actions { display: flex; gap: 10px; justify-content: center; }
.gate-btn { min-width: 180px; border-radius: 999px; }

/* ===== 手机端横向导航 ===== */
.mc-mobile-nav { display: none; }

/* ===== 响应式 ===== */
@media (max-width: 991px) {
  .mc-grid { grid-template-columns: 1fr; gap: 14px; }
  .mc-side { display: none; }
  .mc-mobile-nav { display: flex; gap: 8px; overflow-x: auto; padding: 2px 2px 8px; scrollbar-width: none; }
  .mc-mobile-nav::-webkit-scrollbar { display: none; }
  .mc-chip { flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--mc-border, #e3e1dc); background: var(--mc-card, #fff); color: var(--mc-text2, #3a3a3c); font: inherit; font-size: 13px; padding: 8px 14px; border-radius: 999px; cursor: pointer; transition: .18s ease; }
  .mc-chip.on { background: var(--mc-card, #fff); border-color: var(--mc-chart-solid, #0ea5e9); color: var(--mc-select-ink, #13233a); font-weight: 600; }
  .mc-state { padding: 18px; border-radius: 16px; }
  .gate-card h2 { font-size: 20px; }
  .gate-feats { grid-template-columns: 1fr; }
  .gate-btn { width: 100%; min-width: 0; }
  .pc-br { display: none; }
}
</style>
