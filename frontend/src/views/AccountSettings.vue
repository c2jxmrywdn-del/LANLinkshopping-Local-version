<template>
  <div class="page">
    <a-page-header title="账户设置" sub-title="密码、两步验证、通知、隐私与界面偏好" @back="$router.push('/me')" />
    <SettingsPanel />
    <!--
      邀请好友：买家与商户都可用，被邀请人注册后统一获得普通用户身份。
      不再套一层 a-card —— InviteLinkCard 自带「纯色底 + 细边 + 标题」，
      双层卡片会出现两条边框/两层底色，视觉上像"卡片套卡片"。
    -->
    <InviteLinkCard class="invite-block" />
  </div>
</template>

<script setup>
import SettingsPanel from '../components/account/SettingsPanel.vue'
import InviteLinkCard from '../components/invite/InviteLinkCard.vue'
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.invite-block { margin-top: 16px; }
</style>
