<template>
  <div class="detail" v-if="bill">
    <div class="top"><div><div class="eyebrow">BILL DETAIL</div><h1>账期账单 #{{bill.id}}</h1><p>关联订单 {{bill.orderNo}}</p></div><a-tag :color="billColor">{{billLabel}}</a-tag></div>
    <a-card :bordered="false" class="summary">
      <div><span>待还金额</span><strong>¥{{money(bill.outstandingAmount)}}</strong></div>
      <div><span>账单金额</span><b>¥{{money(bill.amount)}}</b></div><div><span>已还</span><b>¥{{money(bill.paidAmount)}}</b></div><div><span>到期日</span><b>{{bill.dueDate}}</b></div>
    </a-card>
    <div class="cols">
      <a-card title="还款" :bordered="false">
        <a-alert v-if="bill.status==='PAID'" type="success" show-icon message="账单已结清" description="本账单对应授信已全部释放。"/>
        <a-form v-else layout="vertical" @finish="repay">
          <a-form-item label="还款金额"><a-input-number v-model:value="amount" :min="0.01" :max="Number(bill.outstandingAmount)" :precision="2" style="width:100%" addon-before="¥"/></a-form-item>
          <a-form-item label="还款方式"><a-radio-group v-model:value="method"><a-radio value="wallet">企业钱包</a-radio><a-radio value="transfer">对公转账</a-radio></a-radio-group></a-form-item>
          <a-button type="primary" block :loading="loading" html-type="submit">确认还款</a-button>
          <p class="hint">演示环境会记录还款流水并同步恢复可用授信额度。</p>
        </a-form>
      </a-card>
      <a-card title="还款记录" :bordered="false">
        <a-empty v-if="!repayments.length" description="暂无还款记录"/>
        <div v-for="r in repayments" :key="r.id" class="repay-row"><div><b>¥{{money(r.amount)}}</b><small>{{r.method==='wallet'?'企业钱包':'对公转账'}} · {{r.createTime?.replace('T',' ')}}</small></div><span>{{r.referenceNo}}</span></div>
      </a-card>
    </div>
  </div>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue'
import {message} from 'ant-design-vue'
import {useRoute} from 'vue-router'
import {creditTermApi} from '../api'
const route=useRoute(),bill=ref(null),repayments=ref([]),amount=ref(0),method=ref('wallet'),loading=ref(false)
const money=v=>Number(v||0).toFixed(2)
const billLabel=computed(()=>bill.value?.status==='PAID'?'已结清':bill.value?.status==='CANCELLED'?'已取消':bill.value?.status==='OVERDUE'||(bill.value?.dueDate&&bill.value.dueDate<new Date().toISOString().slice(0,10))?'已逾期':'待还')
const billColor=computed(()=>billLabel.value==='已结清'?'green':billLabel.value==='已逾期'?'red':'orange')
async function load(){const d=await creditTermApi.detail(route.params.id);bill.value=d;amount.value=Number(d?.outstandingAmount||0);repayments.value=await creditTermApi.repayments(route.params.id)||[]}
async function repay(){loading.value=true;try{await creditTermApi.repay(route.params.id,amount.value,method.value);message.success('还款成功');await load()}finally{loading.value=false}}
onMounted(load)
</script>
<style scoped>
.detail{max-width:1000px;margin:0 auto}.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}h1{margin:5px 0}.top p{margin:0;color:var(--ll-muted)}.summary{display:grid;grid-template-columns:1.6fr repeat(3,1fr);gap:14px;border-radius:20px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px}.summary span{display:block;opacity:.65;font-size:12px}.summary strong{display:block;font-size:40px;margin-top:4px}.summary b{display:block;margin-top:8px}.cols{display:grid;grid-template-columns:1fr 1fr;gap:16px}.hint{font-size:12px;color:var(--ll-muted);margin:10px 0 0}.repay-row{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #eef0f3;gap:10px}.repay-row small{display:block;color:var(--ll-muted);margin-top:4px}.repay-row span{font-size:11px;color:#98a2b3}@media(max-width:700px){.summary,.cols{grid-template-columns:1fr}.summary strong{font-size:32px}}
</style>
