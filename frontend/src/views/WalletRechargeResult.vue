<template>
  <div class="result"><a-card :bordered="false" class="card"><div class="check">✓</div><div class="eyebrow">WALLET / COMPLETE</div><h1>充值已完成</h1><p>资金已进入钱包余额，可用于后续订单支付。</p><div class="actions"><a-button type="primary" size="large" @click="$router.push('/wallet')">查看钱包</a-button><a-button size="large" @click="$router.push('/wallet/transactions')">查看流水</a-button></div><a-divider/><div class="meta">流水参考：{{ $route.params.id }}</div></a-card></div>
</template>
<script setup></script>
<style scoped>
.result{max-width:600px;margin:70px auto}.card{text-align:center;border-radius:24px;padding:34px}.check{width:64px;height:64px;border-radius:50%;margin:0 auto 18px;display:grid;place-items:center;background:rgba(200,164,92,.16);color:#9b2d20;font-size:32px;font-weight:800}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h1{margin:7px 0}.actions{display:flex;justify-content:center;gap:10px;margin-top:24px}.meta{color:var(--ll-muted);font-size:12px}
</style>