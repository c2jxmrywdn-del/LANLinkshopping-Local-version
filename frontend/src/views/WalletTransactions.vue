<template>
  <div class="module-page">
    <div class="page-head"><div><div class="eyebrow">WALLET / TRANSACTIONS</div><h1>钱包流水</h1><p>按收入、支出、退款查看完整资金轨迹。</p></div><a-button @click="$router.push('/wallet')">返回钱包</a-button></div>
    <a-card :bordered="false" class="glass-card">
      <div class="filters">
        <a-select v-model:value="type" allow-clear placeholder="全部类型" style="width:180px">
          <a-select-option value="recharge">充值</a-select-option><a-select-option value="pay">消费</a-select-option><a-select-option value="refund">退款</a-select-option>
        </a-select>
        <a-input v-model:value="keyword" allow-clear placeholder="搜索订单号 / 备注" style="max-width:300px"/>
      </div>
      <a-table :data-source="filtered" :columns="cols" row-key="id" :pagination="{pageSize:12,showTotal:t=>`共 ${t} 条`}">
        <template #bodyCell="{column,record}">
          <template v-if="column.key==='type'"><a-tag :color="colors[record.changeType] || 'default'">{{labels[record.changeType] || record.changeType}}</a-tag></template>
          <template v-else-if="column.key==='amount'"><span :class="Number(record.amount)>=0?'plus':'minus'">{{Number(record.amount)>=0?'+':''}}{{Number(record.amount||0).toFixed(2)}}</span></template>
          <template v-else-if="column.key==='time'">{{(record.createTime||'').replace('T',' ')}}</template>
          <template v-else-if="column.key==='detail'"><a-button type="link" size="small" @click="$router.push('/wallet/transactions/'+record.id)">查看详情</a-button></template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue'
import {walletApi} from '../api'
const logs=ref([]),type=ref(),keyword=ref('')
const labels={recharge:'充值',pay:'消费',refund:'退款入账'},colors={recharge:'blue',pay:'orange',refund:'green'}
const cols=[{title:'类型',key:'type',width:110},{title:'金额（元）',key:'amount',width:140},{title:'余额',dataIndex:'balanceAfter',width:130},{title:'关联订单',dataIndex:'refOrderNo',width:180},{title:'备注',dataIndex:'remark'},{title:'时间',key:'time',width:170},{title:'',key:'detail',width:90}]
const filtered=computed(()=>logs.value.filter(x=>(!type.value||x.changeType===type.value)&&(!keyword.value||[x.refOrderNo,x.remark].some(v=>String(v||'').toLowerCase().includes(keyword.value.toLowerCase())))))
onMounted(async()=>{try{logs.value=(await walletApi.my())?.logs||[]}catch(e){}})
</script>
<style scoped>
.module-page{max-width:1040px;margin:0 auto}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h1{margin:4px 0;font-size:28px;color:var(--ll-ink)}p{margin:0;color:var(--ll-muted)}.glass-card{border-radius:18px;background:rgba(255,255,255,.76);box-shadow:0 16px 48px rgba(19,35,58,.07)}.filters{display:flex;gap:10px;margin-bottom:16px;flex-wrap:wrap}.plus{color:#16835a;font-weight:700}.minus{color:#9b2d20;font-weight:700}
</style>