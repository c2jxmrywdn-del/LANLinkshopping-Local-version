<template>
  <div class="result">
    <div class="card">
      <div class="mark"><Check :size="30" :stroke-width="2.6" /></div>
      <div class="eyebrow">CAMPAIGNS / PARTICIPATION</div>
      <h1>参与成功</h1>
      <p>你已加入「{{ title || '本次活动' }}」，奖励积分已进入会员积分流水。</p>
      <div class="reward">+{{ points }} <span>积分</span></div>
      <div class="actions">
        <a-button type="primary" size="large" @click="$router.push('/membership/points')">查看积分</a-button>
        <a-button size="large" @click="$router.push('/activity/my')">我的活动</a-button>
        <a-button size="large" @click="$router.push('/activity')">继续逛活动</a-button>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { campaignApi } from '../api'
import { Check } from 'lucide-vue-next'
const route = useRoute()
const title = ref('')
const points = ref(0)
onMounted(async () => {
  try {
    const a = await campaignApi.detail(route.params.id)
    title.value = a?.title || ''
    points.value = a?.rewardPoints || 0
  } catch (e) { /* 拦截器已提示 */ }
})
</script>
<style scoped>
.result { max-width: 620px; margin: 70px auto; }
.card { text-align: center; border-radius: 24px; padding: 38px; background: var(--ll-card, #fff);
  box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.mark { width: 68px; height: 68px; border-radius: 50%; display: grid; place-items: center; margin: 0 auto 18px;
  background: rgba(4, 120, 87, .12); color: #047857; font-size: 34px; font-weight: 800; }
.eyebrow { font-size: 11px; letter-spacing: .18em; color: var(--ll-primary, #1E6EB8); font-weight: 600; }
h1 { margin: 8px 0; font-size: 26px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.card p { color: var(--ll-muted, #64748B); }
.reward { font-size: 42px; font-weight: 800; color: #b45309; margin: 24px 0; }
.reward span { font-size: 15px; font-weight: 600; }
.actions { display: flex; justify-content: center; gap: 8px; flex-wrap: wrap; }
@media (max-width: 480px) { .card { padding: 28px 18px; } .reward { font-size: 34px; } }
</style>
