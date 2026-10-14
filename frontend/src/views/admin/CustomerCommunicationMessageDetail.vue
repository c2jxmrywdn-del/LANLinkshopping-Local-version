<template>
  <div class="message-detail-page">
    <a-page-header title="消息记录详情" sub-title="四级界面 · 查看单条消息上下文">
      <template #extra><a-button @click="back">返回会话</a-button></template>
    </a-page-header>
    <a-spin :spinning="loading">
      <a-result v-if="!loading && !messageRecord" status="404" title="消息不存在" sub-title="当前会话中找不到这条消息，或你没有访问权限。">
        <template #extra><a-button type="primary" @click="back">返回会话</a-button></template>
      </a-result>
      <template v-else-if="messageRecord">
        <a-alert class="privacy" type="info" show-icon message="隐私与审计说明" description="此页仅显示服务端已保存的脱敏文本和消息元数据，不显示访客标识、IP、登录账号或订单/钱包数据。消息正文只读。" />
        <a-row :gutter="[16,16]">
          <a-col :xs="24" :lg="15">
            <a-card :bordered="false" class="main-card">
              <div class="record-label">消息正文</div>
              <div class="content">{{ messageRecord.content || '（空消息）' }}</div>
              <a-divider />
              <a-descriptions bordered size="small" :column="1">
                <a-descriptions-item label="消息 ID">{{ messageRecord.id }}</a-descriptions-item>
                <a-descriptions-item label="发送方">{{ senderLabel(messageRecord.senderType) }}</a-descriptions-item>
                <a-descriptions-item label="发送时间">{{ messageRecord.createdAt || '—' }}</a-descriptions-item>
                <a-descriptions-item label="可见性">
                  <a-tag v-if="messageRecord.senderType==='note' || Number(messageRecord.internalNote)===1" color="orange">仅运营内部可见</a-tag>
                  <a-tag v-else color="green">会话参与者可见</a-tag>
                </a-descriptions-item>
              </a-descriptions>
            </a-card>
          </a-col>
          <a-col :xs="24" :lg="9">
            <a-card title="所属会话" :bordered="false" class="side-card">
              <h3>{{ conversation?.subject || '客户咨询' }}</h3>
              <div class="meta">{{ conversation?.conversationNo || '—' }}</div>
              <a-divider />
              <a-descriptions size="small" :column="1">
                <a-descriptions-item label="业务分类">{{ conversation?.category || '其他' }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ statusLabel(conversation?.status) }}</a-descriptions-item>
                <a-descriptions-item label="客户">{{ conversation?.customerLabel || '网站访客' }}</a-descriptions-item>
              </a-descriptions>
              <a-button type="primary" block @click="back">打开完整会话时间线</a-button>
            </a-card>
          </a-col>
        </a-row>
      </template>
    </a-spin>
  </div>
</template>
<script setup>
import { computed,onMounted,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { adminApi } from '../../api'
const route=useRoute(),router=useRouter(),loading=ref(false),conversation=ref(null),messages=ref([])
const messageRecord=computed(()=>messages.value.find(m=>Number(m.id)===Number(route.params.messageId))||null)
function back(){router.push({name:'admin-communication-conversation',params:{conversationId:route.params.conversationId}})}
function senderLabel(v){return ({customer:'客户',bot:'机器人客服',agent:'人工客服',note:'内部备注'})[v]||v||'未知'}
function statusLabel(v){return ({open:'待处理',waiting_agent:'待客服回复',waiting_customer:'等待客户',resolved:'已解决'})[v]||'未知'}
async function load(){loading.value=true;try{const result=await adminApi.communicationConversation(route.params.conversationId);conversation.value=result?.conversation||null;messages.value=Array.isArray(result?.messages)?result.messages:[]}catch{conversation.value=null;messages.value=[]}finally{loading.value=false}}
onMounted(load);watch(()=>[route.params.conversationId,route.params.messageId],load)
</script>
<style scoped>
.message-detail-page{max-width:1400px;margin:0 auto}.privacy{margin-bottom:16px}.main-card,.side-card{border:1px solid #e7eaf0;border-radius:14px}.record-label{font-size:12px;font-weight:700;letter-spacing:.04em;color:#667085;text-transform:uppercase}.content{margin-top:12px;padding:20px;border-radius:10px;border:1px solid #e4e7ec;background:#f8fafc;color:#344054;line-height:1.85;white-space:pre-wrap;overflow-wrap:anywhere;min-height:140px}.side-card h3{margin:0;color:#101828;font-size:15px}.meta{margin-top:6px;color:#98a2b3;font-size:12px}.side-card :deep(.ant-descriptions-item-label){width:90px;color:#667085}
</style>
