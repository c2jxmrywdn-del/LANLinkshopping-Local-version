<template>
  <div class="learning-page">
    <a-page-header title="对话学习审核" sub-title="把真实业务对话转化为可追溯、可审核的候选知识">
      <template #extra>
        <a-button @click="load" :loading="loading"><template #icon><ReloadOutlined /></template>刷新列表</a-button>
        <a-button type="primary" @click="goSandbox">打开学习沙盒</a-button>
      </template>
    </a-page-header>
    <a-alert type="warning" show-icon class="guardrail"
      message="发布约束"
      description="只有管理员审核通过的候选才会进入线上关键词匹配。原始内容在保存时会做常见个人信息/凭据脱敏；审核人员仍须核对回答准确性、适用范围、重复意图和隐私风险。" />
    <a-card :bordered="false" class="list-card">
      <div class="toolbar">
        <a-segmented v-model:value="status" :options="statusOptions" @change="changeStatus" />
        <a-space>
          <a-input-search v-model:value="query" placeholder="搜索条目 ID、问题或回答" allow-clear style="width:320px" @search="search" />
          <a-button @click="clear">重置</a-button>
        </a-space>
      </div>
      <a-table :data-source="records" :columns="columns" row-key="id" :loading="loading" :pagination="pagination" :scroll="{x:950}" @change="onTableChange">
        <template #bodyCell="{column,record}">
          <template v-if="column.key==='entry'">
            <a-button type="link" class="entry-link" @click="open(record)">{{ record.entryId }}</a-button>
            <div class="meta">来源会话 #{{ record.conversationId }}</div>
          </template>
          <template v-else-if="column.key==='question'"><div class="question">{{ record.questionText }}</div></template>
          <template v-else-if="column.key==='status'"><a-tag :color="stateColor(record.status)">{{ stateLabel(record.status) }}</a-tag></template>
          <template v-else-if="column.key==='reviewer'">{{ record.reviewerName || '尚未审核' }}<div class="meta">{{ record.reviewedAt || '—' }}</div></template>
          <template v-else-if="column.key==='action'"><a-button size="small" type="primary" ghost @click="open(record)">{{ record.status==='pending'?'检查候选':'查看记录' }}</a-button></template>
        </template>
        <template #emptyText><a-empty description="暂无学习候选"><template #description><span>请先在会话中选择一条客户问题和一条人工客服回答，再创建候选知识。</span></template><a-button type="primary" @click="goInbox">前往会话工作台</a-button></a-empty></template>
      </a-table>
    </a-card>
  </div>
</template>
<script setup>
import { computed,onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../../api'
const router=useRouter(),loading=ref(false),records=ref([]),status=ref('pending'),query=ref(''),page=ref(1),size=ref(20),total=ref(0)
const statusOptions=[{label:'待审核',value:'pending'},{label:'已发布',value:'published'},{label:'已驳回',value:'rejected'},{label:'全部',value:'all'}]
const columns=[{title:'候选条目',key:'entry',width:200},{title:'客户问题（脱敏）',key:'question',width:300},{title:'状态',key:'status',width:100},{title:'审核人',key:'reviewer',width:140},{title:'创建时间',dataIndex:'createdAt',width:155},{title:'操作',key:'action',width:120,fixed:'right'}]
const pagination=computed(()=>({current:page.value,pageSize:size.value,total:total.value,showSizeChanger:true,pageSizeOptions:['10','20','50'],showTotal:t=>'共 '+t+' 条候选'}))
function stateLabel(v){return ({pending:'待审核',published:'已发布',rejected:'已驳回'})[v]||'未知'}
function stateColor(v){return ({pending:'orange',published:'green',rejected:'red'})[v]||'default'}
async function load(){loading.value=true;try{const r=await adminApi.communicationLearningCandidates({status:status.value==='all'?undefined:status.value,q:query.value.trim()||undefined,page:page.value,size:size.value});records.value=r?.records||[];total.value=Number(r?.total||0)}catch{message.error('学习候选加载失败，请确认管理员权限。')}finally{loading.value=false}}
function changeStatus(){page.value=1;load()}
function search(){page.value=1;load()}
function clear(){status.value='pending';query.value='';page.value=1;load()}
function onTableChange(p){page.value=p.current;size.value=p.pageSize;load()}
function open(record){router.push({name:'admin-communications-learning-detail',params:{candidateId:record.id}})}
function goSandbox(){router.push({name:'admin-support-learning'})}
function goInbox(){router.push({name:'admin-communications-inbox'})}
onMounted(load)
</script>
<style scoped>
.learning-page{max-width:1600px;margin:0 auto}.guardrail{margin-bottom:16px}.list-card{border:1px solid #e7eaf0;border-radius:14px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:18px}.entry-link{padding:0;height:auto;font-weight:650}.meta{margin-top:3px;color:#98a2b3;font-size:11px}.question{color:#344054;line-height:1.7;overflow-wrap:anywhere}
</style>
