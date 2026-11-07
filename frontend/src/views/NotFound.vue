<template>
  <div class="nf-page">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <h1>页面不存在</h1>
      <p>您访问的链接可能已失效、被移动，或地址输入有误。</p>
      <div class="nf-actions">
        <a-button type="primary" class="pill" @click="$router.push('/')">返回首页</a-button>
        <a-button class="pill" @click="$router.back()">返回上一页</a-button>
      </div>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.nf-page {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
}
.nf-card {
  text-align: center;
  max-width: 420px;
}
.nf-code {
  font-size: 64px;
  font-weight: 800;
  letter-spacing: -.04em;
  color: var(--mc-accent, #9b2d20);
  line-height: 1;
}
.nf-card h1 {
  margin: 14px 0 8px;
  font-size: 22px;
  font-weight: 750;
  color: #1d1d1f;
}
.nf-card p {
  margin: 0 0 22px;
  color: #6e6e73;
  font-size: 14px;
  line-height: 1.7;
}
.nf-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}
.pill {
  border-radius: 999px;
}
</style>
