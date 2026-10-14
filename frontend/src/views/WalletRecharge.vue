<template>
  <div class="recharge"><a-button type="link" @click="$router.push('/wallet')">← 返回钱包</a-button><a-card :bordered="false" class="card"><div class="eyebrow">WALLET / RECHARGE</div><h1>钱包充值</h1><p class="sub">演示环境即时到账；真实渠道需配置商户凭证。</p>
    <a-form layout="vertical" @finish="submit"><a-form-item label="充值金额" required><a-input-number v-model:value="amount" :min="0.01" :max="50000" :precision="2" :step="100" style="width:100%"/></a-form-item>
      <div class="quick"><a-button v-for="n in [100,500,1000,5000]" :key="n" @click="amount=n">¥{{n}}</a-button></div>
      <a-form-item label="备注"><a-input v-model:value="remark" maxlength="50" placeholder="例如：企业采购备用金"/></a-form-item>
      <a-alert type="info" show-icon message="资金安全提示" description="充值、消费、退款均产生可审计流水；请勿重复提交。"/>
      <a-button type="primary" html-type="submit" size="large" block :loading="loading">确认充值</a-button>
    </a-form>
  </a-card></div>
</template>
<script setup>
import {ref} from 'vue';import {message} from 'ant-design-vue';import {walletApi} from '../api'
const amount=ref(500),remark=ref(''),loading=ref(false)
async function submit(){if(!amount.value)return message.warning('请输入充值金额');loading.value=true;try{const d=await walletApi.recharge(amount.value,remark.value);const id=d?.logs?.[0]?.id||Date.now();message.success('充值成功');location.href='/wallet/recharge/result/'+id}finally{loading.value=false}}
</script>
<style scoped>
.recharge{max-width:620px;margin:0 auto}.card{border-radius:22px;padding:8px;box-shadow:0 18px 60px rgba(19,35,58,.08)}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h1{margin:5px 0}.sub{color:var(--ll-muted)}.quick{display:flex;gap:8px;margin:-8px 0 18px;flex-wrap:wrap}
</style>