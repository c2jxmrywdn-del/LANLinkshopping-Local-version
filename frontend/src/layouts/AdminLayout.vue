<template>
  <a-layout class="admin-root">
    <a-layout-sider :width="220" theme="dark" class="sider">
      <div class="brand">
        <div class="brand-txt ll-wordmark"><span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span></div>
      </div>
      <a-menu v-model:selectedKeys="selectedKeys" v-model:openKeys="openKeys" theme="dark" mode="inline" @click="onNav">
        <a-menu-item key="admin-dash">数据概览</a-menu-item>
        <a-menu-item key="admin-merchants">商户管理</a-menu-item>
        <a-menu-item key="admin-products">商品管理</a-menu-item>
        <a-menu-item key="admin-payments">交易管理</a-menu-item><a-menu-item key="admin-credit-terms">账期审核</a-menu-item>
        <a-menu-item key="admin-audit">审计日志</a-menu-item>
        <a-sub-menu key="admin-customer-communication">
          <template #title>客户沟通中心</template>
          <a-menu-item key="admin-communications">沟通概览</a-menu-item>
          <a-menu-item key="admin-communications-inbox">会话工作台</a-menu-item>
          <a-menu-item key="admin-communications-learning">对话学习审核</a-menu-item>
          <a-menu-item key="admin-support-learning">学习沙盒</a-menu-item>
        </a-sub-menu>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="admin-header">
        <span class="title"><span class="ll-wordmark ll-wordmark-on-light"><span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span></span> 管理后台</span>
        <a-space>
          <UserAvatar
            :src="user.user?.avatar || ''"
            :name="user.user?.nickname || '管理员'"
            :gender="user.user?.gender"
            role="admin"
            :size="28"
            :ring="false"
          />
          <span class="who">{{ user.user && user.user.nickname }}（平台运营）</span>
          <a-button size="small" @click="backFront">返回前台</a-button>
          <a-button size="small" danger @click="logout">退出</a-button>
        </a-space>
      </a-layout-header>
      <a-layout-content class="admin-content">
        <main><router-view /></main>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../store/user'
import UserAvatar from '../components/ui/UserAvatar.vue'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const selectedKeys = ref(['admin-dash'])
const openKeys = ref([])
const communicationMenuKey = 'admin-customer-communication'
const communicationRouteToMenu = {
  'admin-communications': 'admin-communications',
  'admin-communications-inbox': 'admin-communications-inbox',
  'admin-communication-conversation': 'admin-communications-inbox',
  'admin-communication-message-detail': 'admin-communications-inbox',
  'admin-communications-learning': 'admin-communications-learning',
  'admin-communications-learning-detail': 'admin-communications-learning',
  'admin-communications-learning-review': 'admin-communications-learning',
  'admin-support-learning': 'admin-support-learning'
}

watch(() => route.name, (name) => {
  if (!name) return
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
.admin-root { min-height: 100vh; }
.sider { box-shadow: 2px 0 8px rgba(0,0,0,.2); }
.brand { padding: 20px 24px 16px; }
.brand-txt { font-size: 22px; line-height: 1.2; }
:deep(.ant-menu-dark .ant-menu-item) { letter-spacing: 1px; font-size: 14px; }
:deep(.ant-menu-dark .ant-menu-item-selected) { background: var(--ll-accent-gradient); }
.admin-header { background: #fff; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 1px 4px rgba(0,21,41,.08); }
.admin-header .title { font-size: 16px; font-weight: 600; }
.admin-header .who { color: #666; }
.admin-content { padding: 24px; background: #f0f2f5; }
</style>
