<template>
  <div class="sys">
    <div class="hero"><div><div class="eyebrow">CREDIT BILLS</div><h1>账单中心</h1><p>按到期日管理应付账单与还款计划。</p></div><a-button @click="$router.push('/credit-term')">返回账期中心</a-button></div>
    <a-card :bordered="false" class="card">
      <a-table :data-source="bills" :loading="loading" row-key="id" :scroll="{x:900}">
        <a-table-column title="账单号" data-index="id" key="id"/>
        <a-table-column title="订单号" data-index="orderNo" key="orderNo"/>
        <a-table-column title="账单金额" key="amount"><template #default="{record}">¥{{ money(record.amount) }}</template></a-table-column>
        <a-table-column title="已还" key="paidAmount"><template #default="{record}">¥{{ money(record.paidAmount) }}</template></a-table-column>
        <a-table-column title="待还" key="outstandingAmount"><template #default="{record}"><b>¥{{ money(record.outstandingAmount) }}</b></template></a-table-column>
        <a-table-column title="到期日" data-index="dueDate" key="dueDate"/>
        <a-table-column title="状态" key="status"><template #default="{record}"><a-tag :color="billColor(record)">{{billLabel(record)}}</a-tag></template></a-table-column>
        <a-table-column title="操作" key="op"><template #default="{record}"><a-button type="link" @click="$router.push('/credit-term/bills/'+record.id)">查看 / 还款</a-button></template></a-table-column>
      </a-table>
      <a-empty v-if="!loading&&!bills.length" description="暂无账单"/>
    </a-card>
  </div>
</template>
<script setup>
import {ref,onMounted} from 'vue'
import {creditTermApi} from '../api'
const bills=ref([]),loading=ref(true)
const money=v=>Number(v||0).toFixed(2)
const billLabel=b=>b.status==='PAID'?'已结清':b.status==='CANCELLED'?'已取消':b.status==='OVERDUE'||(b.dueDate&&b.dueDate<new Date().toISOString().slice(0,10))?'已逾期':'待还'
const billColor=b=>billLabel(b)==='已结清'?'green':billLabel(b)==='已逾期'?'red':'orange'
onMounted(async()=>{try{bills.value=await creditTermApi.bills()||[]}finally{loading.value=false}})
</script>
<style scoped>
.sys{max-width:1100px;margin:0 auto}.hero{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}h1{margin:5px 0}.hero p{margin:0;color:var(--ll-muted)}.card{border-radius:20px}
</style>
