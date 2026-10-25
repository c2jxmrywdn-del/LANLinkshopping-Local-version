<template>
  <a-layout class="admin-root">
    <!-- 左侧导航栏：高对比度、清晰舒适 (支持极简浅白 / 经典科技蓝) -->
    <a-layout-sider
      :width="230"
      :theme="siderTheme === 'light' ? 'light' : 'dark'"
      class="admin-sider"
      :class="['sider-' + siderTheme, { 'mobile-open': mobileNavOpen }]"
    >
      <div class="brand-panel">
        <div class="brand-logo-row">
          <div class="brand-txt ll-wordmark" :class="{ 'll-wordmark-light-mode': siderTheme === 'light' }">
            <span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span>
          </div>
        </div>
        <div class="brand-badge-row">
          <span class="admin-chip">
            <span class="live-dot"></span> 运营管理工作台
          </span>
          <span class="version-chip">v2.4</span>
        </div>
      </div>

      <a-menu
        v-model:selectedKeys="selectedKeys"
        v-model:openKeys="openKeys"
        :theme="siderTheme === 'light' ? 'light' : 'dark'"
        mode="inline"
        class="custom-admin-menu"
        @click="onNav"
      >
        <a-menu-item key="admin-dash">
          <template #icon><DashboardOutlined /></template>
          <span>数据概览</span>
        </a-menu-item>
        <a-menu-item key="admin-merchants">
          <template #icon><ShopOutlined /></template>
          <span>商户管理</span>
        </a-menu-item>
        <a-menu-item key="admin-products">
          <template #icon><AppstoreOutlined /></template>
          <span>商品管理</span>
        </a-menu-item>
        <a-menu-item key="admin-campaigns">
          <template #icon><GiftOutlined /></template>
          <span>活动管理</span>
        </a-menu-item>
        <a-menu-item key="admin-payments">
          <template #icon><DollarCircleOutlined /></template>
          <span>交易管理</span>
        </a-menu-item>
        <a-menu-item key="admin-credit-terms">
          <template #icon><SafetyCertificateOutlined /></template>
          <span>账期审核</span>
        </a-menu-item>
        <a-menu-item key="admin-ai-models">
          <template #icon><ApiOutlined /></template>
          <span>外部大模型</span>
        </a-menu-item>
        <a-menu-item key="admin-audit">
          <template #icon><FileProtectOutlined /></template>
          <span>审计日志</span>
        </a-menu-item>
        <a-sub-menu key="admin-customer-communication">
          <template #icon><CommentOutlined /></template>
          <template #title>客户沟通中心</template>
          <a-menu-item key="admin-communications">沟通概览</a-menu-item>
          <a-menu-item key="admin-communications-inbox">会话工作台</a-menu-item>
          <a-menu-item key="admin-communications-learning">对话学习审核</a-menu-item>
          <a-menu-item key="admin-support-learning">学习沙盒</a-menu-item>
          <a-menu-item key="admin-communications-ai-config">AI 与密钥配置</a-menu-item>
        </a-sub-menu>
      </a-menu>

      <!-- 侧边栏底部安全指引 -->
      <div class="sider-footer">
        <div class="crypto-seal">
          <LockOutlined class="seal-icon" />
          <span>AES-256 数据加密保护中</span>
        </div>
      </div>
    </a-layout-sider>

    <!-- 移动端侧栏遮罩：点击关闭抽屉式导航 -->
    <div v-if="mobileNavOpen" class="admin-mobile-backdrop" @click="mobileNavOpen = false"></div>

    <a-layout class="admin-main-layout">
      <!-- 21st.dev 磨砂玻璃顶栏 -->
      <a-layout-header class="admin-glass-header">
        <div class="header-left">
          <button type="button" class="admin-nav-toggle" aria-label="打开导航" @click="mobileNavOpen = true">
            <MenuOutlined />
          </button>
          <div class="header-breadcrumb">
            <span class="hb-platform">LANLink Console</span>
            <span class="hb-sep">/</span>
            <span class="hb-curr">{{ currentRouteLabel }}</span>
          </div>
        </div>

        <div class="header-right">
          <a-space size="middle">
            <!-- 导航栏配色切换微胶囊 -->
            <div class="sider-theme-picker">
              <span class="stp-label">导航配色:</span>
              <a-tooltip title="极简纯白（与顶栏及内容区 100% 一致 #FFFFFF）">
                <button
                  class="stp-dot dot-light"
                  :class="{ active: siderTheme === 'light' }"
                  @click="setSiderTheme('light')"
                ></button>
              </a-tooltip>
              <a-tooltip title="经典海军蓝（品牌基准主色 #1F3864）">
                <button
                  class="stp-dot dot-navy"
                  :class="{ active: siderTheme === 'navy' }"
                  @click="setSiderTheme('navy')"
                ></button>
              </a-tooltip>
            </div>

            <LanguageSwitcher variant="light" />

            <div class="operator-profile-capsule">
              <UserAvatar
                :src="user.user?.avatar || ''"
                :name="user.user?.nickname || '管理员'"
                :gender="user.user?.gender"
                role="admin"
                :size="26"
                :ring="false"
              />
              <div class="op-info">
                <span class="op-name">{{ user.user?.nickname || '平台运营' }}</span>
                <span class="op-role">
                  <span class="online-indicator"></span> 运营管理组
                </span>
              </div>
            </div>

            <a-button size="small" class="btn-front-desk" @click="backFront">
              <ExportOutlined /> 前往商城
            </a-button>

            <a-button size="small" danger ghost class="btn-logout" @click="logout">
              <LogoutOutlined /> 退出
            </a-button>
          </a-space>
        </div>
      </a-layout-header>

      <!-- 核心页面视图区域 -->
      <a-layout-content class="admin-content-area">
        <main class="admin-main-viewport">
          <router-view />
        </main>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  DashboardOutlined,
  ShopOutlined,
  AppstoreOutlined,
  GiftOutlined,
  DollarCircleOutlined,
  SafetyCertificateOutlined,
  ApiOutlined,
  FileProtectOutlined,
  CommentOutlined,
  LockOutlined,
  ExportOutlined,
  LogoutOutlined,
  MenuOutlined
} from '@ant-design/icons-vue'
import { useUserStore } from '../store/user'
import UserAvatar from '../components/ui/UserAvatar.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const selectedKeys = ref(['admin-dash'])
const openKeys = ref([])
const mobileNavOpen = ref(false)
const communicationMenuKey = 'admin-customer-communication'

// 导航栏主题色：light (极简浅白 #FFFFFF) / navy (经典海军蓝 #1F3864，默认)
// muted (#1B2434 低饱和深灰蓝) 已移除；历史保存值自动回退到 navy。
const savedSiderTheme = localStorage.getItem('ll_admin_sider_theme')
const siderTheme = ref(savedSiderTheme === 'light' ? 'light' : 'navy')

function setSiderTheme(theme) {
  siderTheme.value = theme
  localStorage.setItem('ll_admin_sider_theme', theme)
}

const communicationRouteToMenu = {
  'admin-communications': 'admin-communications',
  'admin-communications-inbox': 'admin-communications-inbox',
  'admin-communication-conversation': 'admin-communications-inbox',
  'admin-communication-message-detail': 'admin-communications-inbox',
  'admin-communications-learning': 'admin-communications-learning',
  'admin-communications-learning-detail': 'admin-communications-learning',
  'admin-communications-learning-review': 'admin-communications-learning',
  'admin-support-learning': 'admin-support-learning',
  'admin-communications-ai-config': 'admin-communications-ai-config'
}

const routeNameMap = {
  'admin-dash': '数据概览',
  'admin-merchants': '商户管理',
  'admin-merchant-detail': '商户档案详情',
  'admin-products': '商品管理',
  'admin-campaigns': '活动管理',
  'admin-payments': '交易管理',
  'admin-credit-terms': '账期审核',
  'admin-ai-models': '外部大模型',
  'admin-audit': '审计日志',
  'admin-communications': '沟通概览',
  'admin-communications-inbox': '会话工作台',
  'admin-communications-ai-config': 'AI 与密钥配置'
}

const currentRouteLabel = computed(() => {
  return routeNameMap[route.name] || '工作台'
})

watch(() => route.name, (name) => {
  if (!name) return
  mobileNavOpen.value = false
  const parentMenu = communicationRouteToMenu[name]
  if (parentMenu) {
    selectedKeys.value = [parentMenu]
    openKeys.value = [communicationMenuKey]
    return
  }
  selectedKeys.value = [name]
  openKeys.value = []
}, { immediate: true })

function onNav({ key }) { router.push({ name: key }) }
function backFront() { router.push('/') }
async function logout() { await user.logout(); router.push('/login') }
</script>

<style scoped>
.admin-root {
  min-height: 100vh;
  background: #f8fafc;
}

/* 侧边栏通用容器 */
.admin-sider {
  display: flex;
  flex-direction: column;
  box-shadow: 2px 0 10px rgba(15, 23, 42, 0.06);
  transition: all 0.25s ease;
  z-index: 20;
}
:deep(.admin-sider .ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
}

/* ========================================================
   主题风格 1：极简纯白 (Light #FFFFFF)
   特点：与后台白底内容区浑然一体
   ======================================================== */
.sider-light {
  background: #FFFFFF !important;
  border-right: 1px solid #E2E8F0;
}
.sider-light .brand-panel {
  border-bottom: 1px solid #E2E8F0;
}
.sider-light :deep(.ant-menu-light) {
  background: transparent !important;
}
.sider-light :deep(.ant-menu-light.ant-menu-inline .ant-menu-item) {
  color: #334155 !important;
  font-weight: 500;
}
.sider-light :deep(.ant-menu-light.ant-menu-inline .ant-menu-item:hover) {
  color: #1E6EB8 !important;
  background: #F1F5F9 !important;
}
.sider-light :deep(.ant-menu-light.ant-menu-inline .ant-menu-item-selected) {
  background: #EFF6FF !important;
  color: #1D4ED8 !important;
  font-weight: 600;
  border-right: 3px solid #2563EB !important;
}
.sider-light :deep(.ant-menu-light .ant-menu-submenu-title) {
  color: #334155 !important;
}
.sider-light :deep(.ant-menu-light .ant-menu-submenu-title:hover) {
  color: #1E6EB8 !important;
  background: #F1F5F9 !important;
}
.sider-light :deep(.ant-menu-light .ant-menu-sub) {
  background: #F8FAFC !important;
}
.sider-light .sider-footer {
  border-top: 1px solid #E2E8F0;
}
.sider-light .crypto-seal {
  background: #F1F5F9;
  color: #64748B;
}
.sider-light .seal-icon {
  color: #0284C7;
}

/* ========================================================
   主题风格 2：经典海军蓝 (Navy #1F3864)
   ======================================================== */
.sider-navy {
  background: #1F3864 !important;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}
.sider-navy .brand-panel {
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.sider-navy :deep(.ant-menu-dark) {
  background: transparent !important;
}
.sider-navy :deep(.ant-menu-dark.ant-menu-inline .ant-menu-item) {
  color: #CBD5E1 !important;
}
.sider-navy :deep(.ant-menu-dark.ant-menu-inline .ant-menu-item:hover) {
  color: #FFFFFF !important;
  background: rgba(255, 255, 255, 0.08) !important;
}
.sider-navy :deep(.ant-menu-dark.ant-menu-inline .ant-menu-item-selected) {
  background: #25527E !important;
  color: #FFFFFF !important;
  font-weight: 600;
  border-left: 3px solid #38BDF8 !important;
}
.sider-navy :deep(.ant-menu-dark .ant-menu-sub) {
  background: #162744 !important;
}
.sider-navy .sider-footer {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.sider-navy .crypto-seal {
  background: rgba(255, 255, 255, 0.06);
  color: #CBD5E1;
}

/* 品牌 Logo 区域与 shopping 显色强化 */
.brand-panel {
  padding: 18px 20px 14px;
}
.brand-logo-row {
  margin-bottom: 8px;
}
.brand-txt {
  font-size: 21px;
  line-height: 1.2;
}

/* 核心修复：确保深色侧栏下 shopping 100% 纯白可见，彻底根除黑色遮盖问题 */
.brand-txt.ll-wordmark .ll-lan {
  color: #FFFFFF !important;
}
.brand-txt.ll-wordmark .ll-link {
  color: #F59E0B !important;
  font-weight: 700;
}
.brand-txt.ll-wordmark .ll-shopping {
  color: #FFFFFF !important;
  font-weight: 700;
  opacity: 1 !important;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

/* 浅色侧栏时的品牌 Logo 文字适配 */
.brand-txt.ll-wordmark-light-mode .ll-lan {
  color: #0F172A !important;
}
.brand-txt.ll-wordmark-light-mode .ll-link {
  color: #B45309 !important;
}
.brand-txt.ll-wordmark-light-mode .ll-shopping {
  color: #0F172A !important;
  opacity: 1 !important;
  text-shadow: none;
}

.brand-badge-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.admin-chip {
  background: rgba(148, 163, 184, 0.16);
  color: #94A3B8;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.sider-light .admin-chip {
  background: #E0F2FE;
  color: #0369A1;
}
.live-dot {
  width: 6px;
  height: 6px;
  background: #38BDF8;
  border-radius: 50%;
}
.sider-light .live-dot {
  background: #0284C7;
}
.version-chip {
  color: #64748B;
  font-size: 11px;
  font-family: monospace;
}

/* 菜单项尺寸与微交互 */
.custom-admin-menu {
  margin-top: 8px;
  flex: 1;
}
:deep(.custom-admin-menu .ant-menu-item) {
  height: 38px;
  line-height: 38px;
  margin: 3px 8px;
  width: calc(100% - 16px);
  border-radius: 6px;
  font-size: 13px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
:deep(.custom-admin-menu .ant-menu-submenu-title) {
  height: 38px;
  line-height: 38px;
  margin: 3px 8px;
  width: calc(100% - 16px);
  border-radius: 6px;
  font-size: 13px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.sider-footer {
  padding: 12px 14px;
}
.crypto-seal {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  padding: 6px 10px;
  border-radius: 6px;
}

/* 顶栏与切换器 */
.admin-main-layout {
  background: #F8FAFC;
}
.admin-glass-header {
  background: rgba(255, 255, 255, 0.9) !important;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(226, 232, 240, 0.8);
  height: 56px;
  line-height: 56px;
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.header-breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.hb-platform {
  color: #64748B;
  font-weight: 500;
}
.hb-sep {
  color: #CBD5E1;
}
.hb-curr {
  color: #0F172A;
  font-weight: 600;
}

/* 导航栏主题色微切换器 */
.sider-theme-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #F1F5F9;
  padding: 3px 8px;
  border-radius: 16px;
  border: 1px solid #E2E8F0;
}
.stp-label {
  font-size: 11px;
  color: #64748B;
}
.stp-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  padding: 0;
  transition: all 0.2s;
}
.stp-dot.dot-light {
  background: #FFFFFF;
  border: 1px solid #CBD5E1;
}
.stp-dot.dot-navy {
  background: #1F3864;
}
.stp-dot:hover {
  transform: scale(1.15);
}
.stp-dot.active {
  border-color: #0284C7;
  box-shadow: 0 0 0 2px rgba(2, 132, 199, 0.35);
}

/* 操作员卡片胶囊 */
.operator-profile-capsule {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #F1F5F9;
  padding: 4px 10px 4px 6px;
  border-radius: 20px;
  border: 1px solid #E2E8F0;
}
.op-info {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}
.op-name {
  font-size: 12px;
  font-weight: 600;
  color: #1E293B;
}
.op-role {
  font-size: 10px;
  color: #64748B;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}
.online-indicator {
  width: 5px;
  height: 5px;
  background: #10B981;
  border-radius: 50%;
}
.btn-front-desk, .btn-logout {
  border-radius: 6px;
}

.admin-content-area {
  background: #F8FAFC;
  min-height: calc(100vh - 56px);
}
.admin-main-viewport {
  max-width: 1440px;
  margin: 0 auto;
}

/* 移动端导航开关（桌面隐藏） */
.admin-nav-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  margin-right: 8px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  background: #F8FAFC;
  color: #0F172A;
  font-size: 16px;
  cursor: pointer;
}
.admin-mobile-backdrop { display: none; }

/* 手机竖屏：侧栏改为抽屉式，顶栏只保留必要操作 */
@media (max-width: 767px) {
  .admin-sider {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    height: 100vh;
    transform: translateX(-100%);
    transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    z-index: 1001;
  }
  .admin-sider.mobile-open { transform: translateX(0); }
  .admin-mobile-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.45);
    z-index: 1000;
    -webkit-tap-highlight-color: transparent;
  }
  .admin-nav-toggle { display: inline-flex; }
  .admin-glass-header { height: 54px; line-height: 54px; padding: 0 10px; }
  .header-left { display: flex; align-items: center; min-width: 0; flex: 1; }
  .header-breadcrumb {
    min-width: 0;
    display: block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12px;
  }
  .hb-platform, .hb-sep { display: none; }
  .sider-theme-picker, .op-info, .btn-front-desk { display: none !important; }
  .operator-profile-capsule { padding: 0; border: 0; background: transparent; }
  .header-right { flex: none; }
  .header-right :deep(.ant-space) { gap: 6px !important; }
  .btn-logout { padding-inline: 8px; }
  .admin-content-area { min-height: calc(100vh - 54px); }
}
</style>
