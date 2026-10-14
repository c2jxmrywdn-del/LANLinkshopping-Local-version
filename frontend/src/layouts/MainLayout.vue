<template>
  <a-layout style="min-height: 100vh">
    <a-layout-header class="header">
      <div class="header-inner">
        <div class="logo" @click="$router.push('/')">
          <img src="/logo-256.png" alt="LANLinkshopping" class="logo-img" />
          <span class="logo-text ll-wordmark"><span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span></span>
        </div>
        <div class="nav-shell" ref="navShell" @mouseleave="scheduleCloseMallMenu">
          <a-menu
            v-model:selectedKeys="selectedKeys"
            mode="horizontal"
            class="nav"
            theme="dark"
            :ellipsis="false"
            @click="onNav"
          >
            <a-menu-item key="home">首页</a-menu-item>
            <a-menu-item key="mall" @mouseenter="openMallMenu">商城</a-menu-item>
            <a-menu-item key="promotions">优惠</a-menu-item>
            <a-menu-item v-if="user.hasPerm('credit:view')" key="credit-term">账期</a-menu-item>
            <a-menu-item v-if="user.hasPerm('order:view')" key="orders">我的订单</a-menu-item>
            <a-menu-item v-if="user.isAdmin" key="admin/merchants">商户管理</a-menu-item>
            <a-menu-item v-else-if="user.logged && !user.isMerchant" key="merchant">商户入驻</a-menu-item>
            <a-menu-item v-if="user.hasPerm('merchant:manage')" key="merchant/traffic">流量管理</a-menu-item>
            <a-menu-item v-if="user.hasPerm('product:publish')" key="merchant/products">我的商品</a-menu-item>
            <a-menu-item key="about">关于我们</a-menu-item>
          </a-menu>

          <IndustryCategoryMenu
            v-show="mallMenuOpen"
            :visible="mallMenuOpen"
            :selected-industry-id="route.query.indId || 0"
            @select-all="openMallAll"
            @select-industry="openMallIndustry"
            @select-category="openMallCategory"
            @quick="handleMallQuick"
            @mouseenter="openMallMenu"
            @mouseleave="scheduleCloseMallMenu"
          />

          <span class="nav-indicator" :style="indicatorStyle" aria-hidden="true"></span>
        </div>
        <div class="right">
          <a-badge v-if="user.logged" :count="user.unread" :overflow-count="99">
            <a-button type="text" class="ll-tap" style="color:#fff" @pointerdown="onTap" @click.stop="goMessages">🔔 消息</a-button>
          </a-badge>
          <a-badge :count="cart.count" :overflow-count="99">
            <a-button type="text" class="ll-tap" style="color:#fff" @pointerdown="onTap" @click.stop="goCart">🛒 购物车</a-button>
          </a-badge>
          <template v-if="user.logged">
            <a-dropdown>
              <a class="ll-ripple-host ll-tap user-trigger" style="color:#fff" @pointerdown="onTap">
                <UserAvatar
                  :src="user.user.avatar || ''"
                  :name="user.user.nickname"
                  :gender="user.user.gender"
                  :role="user.isAdmin ? 'admin' : user.isMerchant ? 'merchant' : 'user'"
                  :size="28"
                  :ring="false"
                  class="header-avatar"
                />
                <span v-if="user.isVip" class="id-badge id-vip" title="VIP 用户">VIP</span>
                <span v-else-if="user.isAdmin" class="id-badge id-admin" title="平台管理员">ADMIN</span>
                <span v-else-if="user.isMerchant" class="id-badge id-merchant" title="入驻商户">商户</span>
                <span class="header-user-name">{{ user.user.nickname }} ▾</span>
              </a>
              <template #overlay>
                <a-menu>
                  <a-menu-item disabled>
                    <div class="id-scope">当前身份：<b>{{ user.identityName }}</b><br/>{{ user.identityScope }}</div>
                  </a-menu-item>
                  <a-menu-divider />
                  <a-menu-item @click="$router.push('/wallet')">💰 我的钱包</a-menu-item><a-menu-item v-if="user.hasPerm('credit:view')" @click="$router.push('/credit-term')">企业账期</a-menu-item>
                  <a-menu-item @click="$router.push('/activity')">🎯 活动中心</a-menu-item>
                  <a-menu-item @click="$router.push('/membership')"><MembershipIcon :size="18" class="membership-menu-icon" /> 会员中心</a-menu-item>
                  <a-menu-item @click="$router.push('/me')">个人资料</a-menu-item><a-menu-item @click="$router.push('/me/messages')">消息中心</a-menu-item><a-menu-item @click="$router.push('/me/address')">收货地址</a-menu-item><a-menu-item @click="$router.push('/me/login-log')">登录安全</a-menu-item><a-menu-item @click="$router.push('/me/settings')">账户设置</a-menu-item>
                  <a-menu-item @click="doLogout">退出登录</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </template>
          <template v-else>
            <!-- 深色顶栏上使用白字白边（WCAG 对比度达标） -->
            <a-button type="primary" ghost style="color:#fff;border-color:#fff" @click="$router.push('/login')">登录 / 注册</a-button>
          </template>
        </div>
      </div>
    </a-layout-header>
    <a-layout-content class="content">
      <main><router-view /></main>
    </a-layout-content>
    <a-layout-footer class="footer" @click="secretTap">
      <div class="footer-inner">
        <div class="footer-brand">
          <div class="footer-wordmark ll-wordmark"><span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span></div>
          <p>让每一次连接，都产生价值。</p>
          <small>CONNECTED COMMERCE · B2B DEMO PLATFORM</small>
        </div>
        <div class="footer-links">
          <button type="button" @click.stop="$router.push('/mall')">商城</button>
          <button type="button" @click.stop="$router.push('/about')">关于我们</button>
          <button type="button" @click.stop="$router.push({ path: '/about', hash: '#contact' })">联系我们</button>
          <button type="button" @click.stop="$router.push({ name: 'privacy-cookies' })">隐私与 Cookie</button>
          <button type="button" @click.stop="$router.push({ name: 'about', query: { legal: 'ip' }, hash: '#terms' })">知识产权声明</button>
        </div>
        <AuthorCard variant="compact" />
      </div>
    </a-layout-footer>
    <SupportChat />
  </a-layout>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'
import { useSettingsStore } from '../store/settings'
import { tapFeedback } from '../utils/feedback'
import IndustryCategoryMenu from '../components/IndustryCategoryMenu.vue'
import AuthorCard from '../components/AuthorCard.vue'
import UserAvatar from '../components/ui/UserAvatar.vue'
import MembershipIcon from '../components/ui/MembershipIcon.vue'
import SupportChat from '../components/SupportChat.vue'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const cart = useCartStore()
const settings = useSettingsStore()

const NAV_PATHS = [
  { key: 'home', match: (p) => p === '/' },
  { key: 'mall', match: (p) => p === '/mall' || p.startsWith('/product') },
  { key: 'promotions', match: (p) => p.startsWith('/promotions') },
  { key: 'credit-term', match: (p) => p.startsWith('/credit-term') },
  { key: 'orders', match: (p) => p.startsWith('/orders') },
  { key: 'merchant/products', match: (p) => p.startsWith('/merchant/products') },
  { key: 'merchant/traffic', match: (p) => p.startsWith('/merchant/traffic') },
  { key: 'merchant', match: (p) => p === '/merchant' },
  { key: 'about', match: (p) => p === '/about' },
]
const selectedKeys = computed(() => {
  const hit = NAV_PATHS.find((n) => n.match(route.path))
  return hit ? [hit.key] : []
})

const mallMenuOpen = ref(false)
let mallCloseTimer = null

function openMallMenu() {
  if (mallCloseTimer) {
    clearTimeout(mallCloseTimer)
    mallCloseTimer = null
  }
  mallMenuOpen.value = true
}

function scheduleCloseMallMenu() {
  if (mallCloseTimer) clearTimeout(mallCloseTimer)
  mallCloseTimer = window.setTimeout(() => {
    mallMenuOpen.value = false
  }, 90)
}

function openMallAll() {
  mallMenuOpen.value = false
  router.push({ name: 'mall' })
}

function openMallIndustry(industry) {
  mallMenuOpen.value = false
  router.push({ name: 'mall', query: { indId: industry.indId } })
}

function openMallCategory(category) {
  mallMenuOpen.value = false
  router.push({
    name: 'mall',
    query: {
      indId: category.industryId || category.indId,
      catId: category.catId
    }
  })
}

function handleMallQuick(action) {
  mallMenuOpen.value = false
  if (action === 'sales') router.push({ name: 'mall', query: { sort: 'sales' } })
  else if (action === 'promotions') router.push({ name: 'promotions' })
  else router.push({ name: 'mall' })
}

// 自定义滑动位置指示条：JS 定位到选中项下方，随路由/窗口尺寸平滑移动
const navShell = ref(null)
const indicatorStyle = ref({ left: '0px', width: '0px', opacity: 0 })
function updateIndicator() {
  const shell = navShell.value
  if (!shell) return
  const sel = shell.querySelector('.ant-menu-item-selected')
  if (!sel) { indicatorStyle.value = { ...indicatorStyle.value, opacity: 0 }; return }
  const sRect = shell.getBoundingClientRect()
  const iRect = sel.getBoundingClientRect()
  indicatorStyle.value = { left: `${iRect.left - sRect.left}px`, width: `${iRect.width}px`, opacity: 1 }
}
watch(selectedKeys, () => nextTick(updateIndicator))

onMounted(() => {
  if (!user.user) user.fetchMe()
  if (user.logged) cart.load()
  nextTick(updateIndicator)
  window.addEventListener('resize', updateIndicator)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateIndicator)
  if (mallCloseTimer) clearTimeout(mallCloseTimer)
})

function goMessages() {
  if (!user.logged) return router.push({ name: 'login', query: { redirect: '/me/messages' } })
  router.push({ name: 'account-messages' })
}
function goCart() {
  if (!user.logged) return router.push({ name: 'login', query: { redirect: '/cart' } })
  router.push({ name: 'cart' })
}

function onNav({ key }) {
  if (key === 'home') router.push('/')
  else router.push('/' + key)
}

// 导航模块触达反馈：150ms 缩放+高亮 + 移动端震动（消息/购物车/我的）
function onTap(e) {
  tapFeedback(e.currentTarget)
}
async function doLogout() {
  await user.logout()
  settings.clear() // 退出后恢复默认外观
  router.push('/login')
}

// 隐形管理入口：连续点击页脚 10 次（2.5 秒内）且当前为平台运营才进入后台。
// 对普通用户完全无提示——非管理员点了没有任何反应。
let taps = 0
let lastTap = 0
function secretTap() {
  const now = Date.now()
  if (now - lastTap > 2500) taps = 0
  lastTap = now
  taps++
  if (taps >= 10) {
    taps = 0
    if (user.isAdmin) router.push({ name: 'admin-dash' })
  }
}
</script>

<style scoped>
.header { background: var(--ll-navy); padding: 0; position: sticky; top: 0; z-index: 100; }
.header-inner { max-width: 1200px; margin: 0 auto; display: flex; align-items: center; height: 64px; }
.logo { display: flex; align-items: center; gap: 9px; color: #fff; font-size: 21px; font-weight: 700; cursor: pointer; margin-right: 32px; white-space: nowrap; }
.logo-img { width: 36px; height: 36px; border-radius: 8px; display: block; }
.nav-shell { position: relative; flex: 1; min-width: 0; overflow: visible; }
.nav { background: transparent; border-bottom: none; }
/* 导航交互态：未选中柔白，选中文字用亮青 --ll-cyan（可读性更佳）+ 指示条 --ll-accent-gradient 天蓝→青，与后台激活色同源 */
.header :deep(.ant-menu-horizontal) { background: transparent; border-bottom: none; }
.header :deep(.ant-menu-horizontal .ant-menu-item) { color: rgba(255, 255, 255, 0.72); transition: color 0.25s ease; }
.header :deep(.ant-menu-horizontal .ant-menu-item:hover) { color: #fff; }
.header :deep(.ant-menu-horizontal .ant-menu-item-selected) { color: var(--ll-cyan, #22D3EE); font-weight: 600; }
/* 自定义滑动位置指示条：JS 定位到选中项下方，0.28s 平滑移动 */
.nav-indicator {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 0;
  height: 3px;
  background: var(--ll-accent-gradient);
  border-radius: 3px 3px 0 0;
  pointer-events: none;
  transition: left 0.28s cubic-bezier(0.4, 0, 0.2, 1), width 0.28s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease;
}
.right { display: flex; align-items: center; gap: 16px; }
.user-trigger { display: inline-flex; align-items: center; gap: 7px; }
.header-avatar { vertical-align: middle; }
.membership-menu-icon { margin-right: 7px; }
.header-user-name { white-space: nowrap; }
/* 身份徽章 */
.id-badge { display: inline-block; font-size: 11px; font-weight: 700; line-height: 1;
            padding: 3px 6px; border-radius: 4px; margin-right: 6px; vertical-align: 1px; }
.id-vip { background: #b45309; color: #fff; }       /* 琥珀：VIP */
.id-admin { background: #1e6eb8; color: #fff; }     /* 天蓝：管理员 */
.id-merchant { background: #0f766e; color: #fff; }  /* 青绿：商户 */
.id-scope { font-size: 12px; color: var(--ll-gray, #4b5563); line-height: 1.6; white-space: normal; }
.id-scope b { color: var(--ll-ink, #0f172a); }
.content { max-width: 1200px; margin: 0 auto; width: 100%; padding: 24px 16px; }
.footer{padding:0 16px!important;color:#4b5563!important;background:var(--ll-page);border-top:1px solid #D9D3C7}.footer-inner{max-width:1200px;margin:0 auto;min-height:150px;padding:30px 0 24px;display:grid;grid-template-columns:1.4fr .7fr 1.4fr;gap:30px;align-items:center}.footer-brand{border-left:3px solid var(--ll-amber);padding-left:16px}.footer-wordmark{font-size:24px;line-height:1}.footer-wordmark .ll-lan{color:var(--ll-navy)}.footer-wordmark .ll-link{color:var(--ll-amber-strong)}.footer-wordmark .ll-shopping{color:var(--ll-navy)}.footer-brand p{margin:9px 0 4px;color:var(--ll-navy);font-size:13px;font-weight:700}.footer-brand small{color:#7B8492;font-size:9px;letter-spacing:.13em}.footer-links{display:flex;justify-content:center;gap:8px;flex-wrap:wrap}.footer-links button{border:0;background:transparent;color:#667085;cursor:pointer;font:inherit;font-size:12px;padding:7px 9px;border-radius:8px;transition:.2s ease}.footer-links button:hover{color:var(--ll-navy);background:rgba(200,164,92,.12)}.footer-meta{text-align:right;color:#667085;font-size:11px;line-height:1.8}.footer-meta span{color:#98A2B3}

/* 响应式：平板/移动端收紧间距与字号，导航色变与滑动指示条在各尺寸均生效 */
@media (max-width: 991px) {
  .logo { margin-right: 16px; }
  .header-inner { gap: 8px; }
}
@media (max-width: 767px) {
  /* 移动端：头部两行布局——品牌+操作区在上，导航独占一行可横向滑动（防 flex 压缩塌缩为 0） */
  .header { height: auto; }
  .header-inner { flex-wrap: wrap; height: auto; padding: 6px 12px; }
  .nav-shell { flex-basis: 100%; order: 3; overflow-x: auto; scrollbar-width: none; }
  .nav-shell::-webkit-scrollbar { display: none; }
  .nav { white-space: nowrap; }
  .logo { margin-right: 10px; }
  .logo-text { display: none; }
  .header :deep(.ant-menu-horizontal .ant-menu-item) { font-size: 14px; padding-inline: 12px; }
   .right { gap: 10px; }
  .footer-inner{grid-template-columns:1fr;gap:18px;padding:24px 0}.footer-links{justify-content:flex-start}.footer-meta{text-align:left;grid-column:auto}
}
</style>
