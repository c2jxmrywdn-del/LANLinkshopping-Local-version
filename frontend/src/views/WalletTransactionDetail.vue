<template>
  <div class="detail"><a-button type="link" @click="$router.back()">← 返回</a-button><a-card :bordered="false" class="hero-card">
    <div class="eyebrow">TRANSACTION DETAIL</div><h1>{{ item ? (labels[item.changeType]||'钱包流水') : '流水详情' }}</h1>
    <div v-if="item" class="amount" :class="Number(item.amount)>=0?'plus':'minus'">{{Number(item.amount)>=0?'+':''}}¥{{Number(item.amount||0).toFixed(2)}}</div>
    <a-descriptions v-if="item" bordered :column="{xs:1,sm:2,md:2}">
      <a-descriptions-item label="流水 ID">{{item.id}}</a-descriptions-item><a-descriptions-item label="变动后余额">¥{{Number(item.balanceAfter||0).toFixed(2)}}</a-descriptions-item>
      <a-descriptions-item label="关联订单">{{item.refOrderNo||'—'}}</a-descriptions-item><a-descriptions-item label="时间">{{(item.createTime||'').replace('T',' ')}}</a-descriptions-item>
      <a-descriptions-item label="备注" :span="2">{{item.remark||'—'}}</a-descriptions-item>
    </a-descriptions>
    <a-empty v-else description="未找到该流水"/>
  </a-card></div>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {walletApi} from '../api'
const item=ref(null),labels={recharge:'充值',pay:'消费',refund:'退款入账'}
onMounted(async()=>{try{const d=await walletApi.my();item.value=(d?.logs||[]).find(x=>String(x.id)===String(location.pathname.split('/').pop()))||null}catch(e){}})
</script>
<style scoped>
.detail{max-width:860px;margin:0 auto}.hero-card{border-radius:22px;background:linear-gradient(135deg,rgba(19,35,58,.98),rgba(31,56,100,.95));color:#fff}.eyebrow{font-size:11px;letter-spacing:.18em;opacity:.65}h1{color:#fff;margin:8px 0}.amount{font-size:40px;font-weight:800;margin:20px 0}.plus{color:#f6d38a}.minus{color:#ffb4a8}
</style>