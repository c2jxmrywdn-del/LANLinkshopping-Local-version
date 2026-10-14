<template>
  <div class="page">
    <div class="head"><div><div class="eyebrow">RISK & CREDIT</div><h1>企业账期审核</h1><p>审核授信、调整额度并跟踪企业账期状态。</p></div><a-select v-model:value="status" style="width:160px" allow-clear placeholder="全部状态" @change="load"><a-select-option value="PENDING">待审核</a-select-option><a-select-option value="ACTIVE">已生效</a-select-option><a-select-option value="REJECTED">已驳回</a-select-option></a-select></div>
    <a-card :bordered="false" class="card"><a-table :data-source="list" :loading="loading" row-key="id" :scroll="{x:1000}">
      <a-table-column title="申请ID" data-index="id"/><a-table-column title="用户ID" data-index="userId"/><a-table-column title="申请额度" key="requestedLimit"><template #default="{record}">¥{{money(record.requestedLimit)}}</template></a-table-column>
      <a-table-column title="授信额度" key="creditLimit"><template #default="{record}">¥{{money(record.creditLimit)}}</template></a-table-column><a-table-column title="账期" key="termDays"><template #default="{record}">{{record.termDays}} 天</template></a-table-column>
      <a-table-column title="状态" key="status"><template #default="{record}"><a-tag :color="record.status==='ACTIVE'?'green':record.status==='PENDING'?'orange':'red'">{{statusLabel(record.status)}}</a-tag></template></a-table-column>
      <a-table-column title="申请时间" data-index="applyTime"/><a-table-column title="操作" key="op"><template #default="{record}"><a-button v-if="record.status==='PENDING'" type="primary" size="small" @click="openReview(record,'ACTIVE')">通过</a-button><a-button v-if="record.status==='PENDING'" danger size="small" @click="openReview(record,'REJECTED')">驳回</a-button><a-button v-else type="link" size="small" @click="openReview(record,'ACTIVE')">调整</a-button></template></a-table-column>
    </a-table></a-card>
    <a-modal v-model:open="open" :title="reviewStatus==='ACTIVE'?'通过 / 调整授信':'驳回授信申请'" ok-text="提交" cancel-text="取消" @ok="submitReview">
      <a-form layout="vertical"><a-form-item label="批准额度" v-if="reviewStatus==='ACTIVE'"><a-input-number v-model:value="form.approvedLimit" :min="1000" :max="500000" :precision="2" style="width:100%"/></a-form-item><a-form-item label="账期" v-if="reviewStatus==='ACTIVE'"><a-select v-model:value="form.termDays" style="width:100%"><a-select-option :value="30">30 天</a-select-option><a-select-option :value="60">60 天</a-select-option><a-select-option :value="90">90 天</a-select-option></a-select></a-form-item><a-form-item label="审核备注"><a-textarea v-model:value="form.remark" :rows="3" maxlength="255" show-count/></a-form-item></a-form>
    </a-modal>
  </div>
</template>
<script setup>
import {ref,reactive,onMounted} from 'vue'
import {message} from 'ant-design-vue'
import {creditTermApi} from '../../api'
const list=ref([]),loading=ref(false),status=ref(),open=ref(false),reviewStatus=ref('ACTIVE'),current=ref(null),form=reactive({approvedLimit:0,termDays:30,remark:''})
const money=v=>Number(v||0).toFixed(2),statusLabel=s=>s==='ACTIVE'?'已生效':s==='PENDING'?'待审核':'已驳回'
async function load(){loading.value=true;try{list.value=await creditTermApi.adminAccounts(status.value)||[]}finally{loading.value=false}}
function openReview(row,s){current.value=row;reviewStatus.value=s;form.approvedLimit=Number(row.creditLimit||row.requestedLimit||0);form.termDays=row.termDays||30;form.remark='';open.value=true}
async function submitReview(){try{await creditTermApi.review(current.value.id,{status:reviewStatus.value,approvedLimit:form.approvedLimit,termDays:form.termDays,remark:form.remark});message.success('审核完成');open.value=false;await load()}catch(e){}}
onMounted(load)
</script>
<style scoped>
.page{max-width:1180px;margin:0 auto}.head{display:flex;justify-content:space-between;align-items:flex-end;gap:20px;margin-bottom:18px}.eyebrow{font-size:11px;letter-spacing:.18em;color:#1677ff}h1{margin:5px 0}.head p{margin:0;color:#64748b}.card{border-radius:20px}.head :deep(.ant-btn-dangerous){margin-left:6px}
</style>
