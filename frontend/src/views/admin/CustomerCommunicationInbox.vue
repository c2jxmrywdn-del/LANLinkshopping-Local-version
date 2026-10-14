<template>
  <div class="inbox-page">
    <a-page-header title="会话工作台" sub-title="按队列整理客户咨询；点击会话进入完整处理流程">
      <template #extra><a-button @click="load" :loading="loading"><template #icon><ReloadOutlined /></template>刷新</a-button></template>
    </a-page-header>
    <a-row :gutter="[10,10]" class="queue-row">
      <a-col v-for="item in queues" :key="item.value" :xs="12" :md="4">
        <button class="queue-card" :class="{active:queue===item.value}" @click="changeQueue(item.value)">
          <span>{{ item.label }}</span><small>{{ item.hint }}</small>
        </button>
      </a-col>
    </a-row>
    <a-card :bordered="false" class="inbox-card">
      <div class="toolbar">
        <a-input-search v-model:value="query" allow-clear placeholder="搜索会话编号、主题或最近消息" style="max-width:380px" @search="search" />
        <a-space wrap>
          <a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:150px" :options="statusOptions" @change="search" />
          <a-button @click="clearFilters">清空筛选</a-button>
        </a-space>
      </div>
      <a-table :data-source="records" :columns="columns" row-key="id" :loading="loading" :pagination="pagination" :scroll="{x:1050}" @change="onTableChange">
        <template #bodyCell="{column,record}">
          <template v-if="column.key==='conversation'">
            <a-button type="link" class="subject" @click="open(record)">{{ record.subject || '专属客服咨询' }}</a-button>
            <div class="meta">{{ record.conversationNo }} · {{ record.customerLabel || '网站访客' }}</div>
          </template>
          <template v-else-if="column.key==='status'"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template>
          <template v-else-if="column.key==='priority'"><a-tag :color="priorityColor(record.priority)">{{ priorityLabel(record.priority) }}</a-tag></template>
          <template v-else-if="column.key==='preview'"><div class="preview">{{ record.lastMessagePreview || '尚无消息摘要' }}</div><div class="meta">{{ record.lastMessageAt || record.createdAt || '—' }}</div></template>
          <template v-else-if="column.key==='unread'"><a-badge :count="record.unreadCount || 0" :show-zero="false" /></template>
          <template v-else-if="column.key==='action'"><a-button type="primary" ghost size="small" @click="open(record)">打开会话</a-button></template>
        </template>
        <template #emptyText><a-empty description="此队列暂无会话"><template #description><span>客户从网站专属客服提交问题后，会在这里生成对话记录。</span></template></a-empty></template>
      </a-table>
    </a-card>
    <div class="footer-note">沟通记录经过常见个人信息脱敏；该列表不展示账号、订单或钱包明细。优先级与状态调整均会留下审计记录。</div>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../../api'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const query = ref('')
const status = ref(undefined)
const queue = ref('all')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const queues = [
  {label:'全部会话',value:'all',hint:'整体视图'},
  {label:'未分配',value:'unassigned',hint:'等待认领'},
  {label:'我的会话',value:'mine',hint:'分配给我'},
  {label:'待处理',value:'open',hint:'需要跟进'},
  {label:'等待客户',value:'waiting',hint:'已发送回复'},
  {label:'已解决',value:'resolved',hint:'已结束'}
]
const statusOptions = [
  {label:'待处理',value:'open'},
  {label:'待客服回复',value:'waiting_agent'},
  {label:'等待客户',value:'waiting_customer'},
  {label:'已解决',value:'resolved'}
]
const columns = [
  {title:'客户会话',key:'conversation',width:255},
  {title:'业务分类',dataIndex:'category',width:125},
  {title:'状态',key:'status',width:120},
  {title:'优先级',key:'priority',width:95},
  {title:'负责人',dataIndex:'assignedAdminName',width:110},
  {title:'最新消息',key:'preview',width:250},
  {title:'未读',key:'unread',width:65,align:'center'},
  {title:'操作',key:'action',width:115,fixed:'right'}
]
const pagination = computed(()=>({current:page.value,pageSize:size.value,total:total.value,showSizeChanger:true,pageSizeOptions:['10','20','50'],showTotal:t=>'共 '+t+' 条会话'}))
function statusLabel(v) { return ({open:'待处理',waiting_agent:'待客服回复',waiting_customer:'等待客户',resolved:'已解决'})[v] || '未知' }
function statusColor(v) { return ({open:'orange',waiting_agent:'red',waiting_customer:'blue',resolved:'green'})[v] || 'default' }
function priorityLabel(v) { return ({low:'低',normal:'普通',high:'高',urgent:'紧急'})[v] || '普通' }
function priorityColor(v) { return ({low:'default',normal:'blue',high:'orange',urgent:'red'})[v] || 'blue' }
async function load() {
  loading.value = true
  try {
    const result = await adminApi.communicationConversations({queue:queue.value,q:query.value.trim() || undefined,status:status.value,page:page.value,size:size.value})
    records.value = result?.records || []
    total.value = Number(result?.total || 0)
  } catch { message.error('会话列表加载失败，请稍后重试。') }
  finally { loading.value = false }
}
function changeQueue(v) { queue.value=v; page.value=1; load() }
function search() { page.value=1; load() }
function clearFilters() { query.value=''; status.value=undefined; queue.value='all'; page.value=1; load() }
function onTableChange(p) { page.value=p.current; size.value=p.pageSize; load() }
function open(record) { router.push({name:'admin-communication-conversation',params:{conversationId:record.id}}) }
onMounted(load)
</script>
<style scoped>
.inbox-page{max-width:1600px;margin:0 auto}.queue-row{margin:0 0 16px}.queue-card{display:flex;flex-direction:column;gap:6px;width:100%;min-height:78px;padding:15px 16px;text-align:left;border:1px solid #e4e7ec;border-radius:12px;background:#fff;cursor:pointer;transition:border-color .18s,background .18s,box-shadow .18s}.queue-card span{color:#344054;font-weight:650;font-size:13px}.queue-card small{color:#98a2b3;font-size:11px}.queue-card:hover{border-color:#9eb2ca}.queue-card.active{border-color:#13233a;background:#f1f5f9;box-shadow:inset 0 0 0 1px #13233a}.inbox-card{border:1px solid #e7eaf0;border-radius:14px}.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:18px}.subject{padding:0;height:auto;text-align:left;font-weight:650}.meta{margin-top:3px;color:#98a2b3;font-size:11px}.preview{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#344054;font-size:12px;line-height:1.6}.footer-note{margin-top:14px;color:#667085;font-size:12px;line-height:1.7}
</style>
