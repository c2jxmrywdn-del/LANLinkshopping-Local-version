<template>
  <div class="conversation-page" v-if="conversation">
    <a-breadcrumb class="crumb"><a-breadcrumb-item><a @click="go('admin-communications-inbox')">会话工作台</a></a-breadcrumb-item><a-breadcrumb-item>{{ conversation.conversationNo }}</a-breadcrumb-item></a-breadcrumb>
    <a-page-header :title="conversation.subject || '客户咨询'" :sub-title="conversation.conversationNo">
      <template #tags>
        <a-tag :color="statusColor(conversation.status)">{{ statusLabel(conversation.status) }}</a-tag>
        <a-tag :color="priorityColor(conversation.priority)">{{ priorityLabel(conversation.priority) }}优先级</a-tag>
      </template>
      <template #extra>
        <a-space wrap>
          <a-button @click="load" :loading="loading"><template #icon><ReloadOutlined /></template>刷新会话</a-button>
          <a-button v-if="!conversation.assignedAdminUserId" type="primary" ghost @click="assign" :loading="actionLoading">认领会话</a-button>
          <a-button v-else @click="unassign" :loading="actionLoading">取消分配</a-button>
          <a-button @click="go('admin-communications-inbox')">返回队列</a-button>
        </a-space>
      </template>
    </a-page-header>

    <a-row :gutter="[16,16]">
      <a-col :xs="24" :xl="16">
        <a-card class="thread-card" :bordered="false">
          <template #title>沟通记录 <a-tag>{{ messages.length }} 条</a-tag></template>
          <template #extra><span class="muted">客户可继续在网页客服窗口查看人工回复</span></template>
          <a-alert v-if="!messages.length" type="info" show-icon message="暂无沟通记录" description="客户再次向网站专属客服发送消息后，会在这里建立完整消息时间线。" />
          <div v-else class="thread">
            <div v-for="item in messages" :key="item.id" class="message-row" :class="messageClass(item)">
              <div class="message-head">
                <div class="sender">
                  <span class="avatar" :class="messageClass(item)">{{ avatarLabel(item) }}</span>
                  <div><strong>{{ item.senderName || senderLabel(item.senderType) }}</strong><div class="muted tiny">{{ senderLabel(item.senderType) }} · {{ item.createdAt || '时间未知' }}</div></div>
                </div>
                <a-space size="small" wrap>
                  <a-tag v-if="Number(item.internalNote)===1 || item.senderType==='note'" color="orange">仅内部可见</a-tag>
                  <a-tag v-if="item.senderType==='bot'" color="blue">知识库回复</a-tag>
                  <a-tag v-if="item.senderType==='customer'" color="purple">客户问题</a-tag>
                  <a-button type="link" size="small" @click="openMessage(item)">四级详情</a-button>
                </a-space>
              </div>
              <p class="message-content">{{ item.content }}</p>
              <div v-if="item.senderType==='customer' || item.senderType==='agent'" class="message-learning">
                <a-radio :checked="selectedQuestionId===item.id" :disabled="item.senderType!=='customer'" @click="selectQuestion(item)">选为学习问题</a-radio>
                <a-radio :checked="selectedAnswerId===item.id" :disabled="item.senderType!=='agent'" @click="selectAnswer(item)">选为人工答案</a-radio>
              </div>
            </div>
          </div>
        </a-card>

        <a-card title="回复客户" class="reply-card" :bordered="false">
          <div class="quick-replies">
            <span>快捷回复</span>
            <a-button v-for="preset in replyPresets" :key="preset.label" size="small" @click="draft=preset.text">{{ preset.label }}</a-button>
          </div>
          <a-textarea v-model:value="draft" :rows="4" :maxlength="2000" show-count placeholder="输入清晰、可执行的业务回复；不要索要密码、验证码或银行卡等敏感信息。" />
          <div class="reply-footer">
            <a-checkbox v-model:checked="internalNote">作为内部备注（客户不可见）</a-checkbox>
            <a-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="sendReply">{{ internalNote ? '添加内部备注' : '发送给客户' }}</a-button>
          </div>
          <a-alert v-if="!internalNote" type="info" show-icon message="发送后，客户打开聊天窗口时会同步看到该回复。" class="reply-hint" />
        </a-card>
      </a-col>

      <a-col :xs="24" :xl="8">
        <a-card title="会话属性" class="side-card" :bordered="false">
          <a-descriptions size="small" :column="1">
            <a-descriptions-item label="客户">{{ conversation.customerLabel || '网站访客' }}</a-descriptions-item>
            <a-descriptions-item label="渠道">{{ channelLabel(conversation.channel) }}</a-descriptions-item>
            <a-descriptions-item label="分类">{{ conversation.category || '其他' }}</a-descriptions-item>
            <a-descriptions-item label="负责人">{{ conversation.assignedAdminName || '未分配' }}</a-descriptions-item>
            <a-descriptions-item label="首次咨询">{{ conversation.createdAt || '—' }}</a-descriptions-item>
            <a-descriptions-item label="最近消息">{{ conversation.lastMessageAt || '—' }}</a-descriptions-item>
          </a-descriptions>
          <a-divider />
          <div class="field-label">会话状态</div>
          <a-select v-model:value="statusValue" style="width:100%" :options="statusOptions" />
          <a-button block style="margin-top:8px" :loading="actionLoading" @click="saveStatus">保存状态</a-button>
          <div class="field-label top-gap">优先级</div>
          <a-select v-model:value="priorityValue" style="width:100%" :options="priorityOptions" />
          <a-button block style="margin-top:8px" :loading="actionLoading" @click="savePriority">保存优先级</a-button>
        </a-card>

        <a-card title="从对话沉淀知识" class="side-card learning-card" :bordered="false">
          <a-alert type="warning" show-icon message="需要人工问答对" description="选择一条客户问题与一条人工客服回答。机器人回答不能直接作为人工知识样本；候选需再次审核。" />
          <a-form layout="vertical" class="learning-form">
            <a-form-item label="客户问题">
              <a-select v-model:value="selectedQuestionId" placeholder="选择一条客户问题" :options="customerMessages.map(m=>({label:shortLabel(m),value:m.id}))" show-search option-filter-prop="label" @change="updateCandidateDefaults" />
            </a-form-item>
            <a-form-item label="人工客服回答">
              <a-select v-model:value="selectedAnswerId" placeholder="选择一条人工客服回答" :options="agentMessages.map(m=>({label:shortLabel(m),value:m.id}))" show-search option-filter-prop="label" />
            </a-form-item>
            <a-form-item label="候选条目 ID">
              <a-input v-model:value="entryId" maxlength="64" />
            </a-form-item>
            <a-form-item label="关键词（逗号或换行分隔）">
              <a-textarea v-model:value="keywordText" :rows="2" maxlength="1200" />
            </a-form-item>
            <a-button type="primary" block :loading="learningLoading" :disabled="!selectedQuestionId || !selectedAnswerId" @click="createCandidate">创建待审核候选</a-button>
          </a-form>
        </a-card>
      </a-col>
    </a-row>
  </div>
  <a-card v-else :bordered="false"><a-spin v-if="loading" tip="正在加载会话…" /><a-result v-else status="warning" title="会话暂不可用" sub-title="会话可能已被移除，也可能是网络或权限问题。请重试，或返回会话工作台重新选择。"><template #extra><a-space><a-button @click="load">重新加载</a-button><a-button type="primary" @click="go('admin-communications-inbox')">返回会话工作台</a-button></a-space></template></a-result></a-card>
</template>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../../api'
const route=useRoute(), router=useRouter()
const loading=ref(true), actionLoading=ref(false), sending=ref(false), learningLoading=ref(false)
const conversation=ref(null), messages=ref([]), draft=ref(''), internalNote=ref(false)
const statusValue=ref('open'), priorityValue=ref('normal')
const selectedQuestionId=ref(undefined), selectedAnswerId=ref(undefined), entryId=ref('dialog-new'), keywordText=ref('')
const statusOptions=[{label:'待处理',value:'open'},{label:'待客服回复',value:'waiting_agent'},{label:'等待客户',value:'waiting_customer'},{label:'已解决',value:'resolved'}]
const priorityOptions=[{label:'低',value:'low'},{label:'普通',value:'normal'},{label:'高',value:'high'},{label:'紧急',value:'urgent'}]
const replyPresets=[
 {label:'请补充问题',text:'您好，为便于准确协助您，请补充说明您正在操作的页面以及遇到的具体情况。请勿发送密码、短信验证码或银行卡等敏感信息。'},
 {label:'已收到反馈',text:'您好，您的问题我们已收到。我们会根据平台现有功能与记录进一步核实，并在有明确结果后回复您。'},
 {label:'知识范围说明',text:'您好，当前智能客服仅回答平台知识库已收录的电商操作问题。涉及实时价格、库存、个人订单或钱包明细时，请通过平台对应业务页面核实。'}
]
const customerMessages=computed(()=>messages.value.filter(m=>m.senderType==='customer'))
const agentMessages=computed(()=>messages.value.filter(m=>m.senderType==='agent' && Number(m.internalNote)!==1))
function go(name){router.push({name})}
function statusLabel(v){return ({open:'待处理',waiting_agent:'待客服回复',waiting_customer:'等待客户',resolved:'已解决'})[v]||'未知'}
function statusColor(v){return ({open:'orange',waiting_agent:'red',waiting_customer:'blue',resolved:'green'})[v]||'default'}
function priorityLabel(v){return ({low:'低',normal:'普通',high:'高',urgent:'紧急'})[v]||'普通'}
function priorityColor(v){return ({low:'default',normal:'blue',high:'orange',urgent:'red'})[v]||'blue'}
function channelLabel(v){return v==='web'?'网站客服':(v||'其他')}
function senderLabel(v){return ({customer:'客户',bot:'机器人客服',agent:'人工客服',note:'内部备注'})[v]||v||'未知'}
function messageClass(m){return m.senderType==='customer'?'customer':m.senderType==='note'||Number(m.internalNote)===1?'note':m.senderType==='bot'?'bot':'agent'}
function avatarLabel(m){return m.senderType==='customer'?'客':m.senderType==='bot'?'AI':m.senderType==='note'||Number(m.internalNote)===1?'记':'服'}
function shortLabel(m){return '#'+m.id+' '+String(m.content||'').replace(/\s+/g,' ').slice(0,70)}
function selectQuestion(m){if(m.senderType!=='customer')return;selectedQuestionId.value=m.id;updateCandidateDefaults()}
function selectAnswer(m){if(m.senderType!=='agent'||Number(m.internalNote)===1)return;selectedAnswerId.value=m.id}
function updateCandidateDefaults(){
 const selected=customerMessages.value.find(m=>Number(m.id)===Number(selectedQuestionId.value))
 if(!selected)return
 entryId.value='dialog-'+route.params.conversationId+'-'+selected.id
 keywordText.value=String(selected.content||'').trim().slice(0,80)
}
async function load(){
 loading.value=true
 try{
  const result=await adminApi.communicationConversation(route.params.conversationId)
  conversation.value=result?.conversation||null
  messages.value=Array.isArray(result?.messages)?result.messages:[]
  if(conversation.value){statusValue.value=conversation.value.status||'open';priorityValue.value=conversation.value.priority||'normal'}
 }catch{conversation.value=null;messages.value=[]}
 finally{loading.value=false}
}
async function assign(){
 actionLoading.value=true
 try{await adminApi.communicationAssign(route.params.conversationId);message.success('会话已分配给当前管理员。');await load()}
 catch{message.error('认领失败，请检查权限或重试。')}
 finally{actionLoading.value=false}
}
async function unassign(){
 actionLoading.value=true
 try{await adminApi.communicationUnassign(route.params.conversationId);message.success('已取消分配。');await load()}
 catch{message.error('取消分配失败。')}
 finally{actionLoading.value=false}
}
async function saveStatus(){
 actionLoading.value=true
 try{await adminApi.communicationSetStatus(route.params.conversationId,statusValue.value);message.success('会话状态已更新。');await load()}
 catch{message.error('保存状态失败。')}
 finally{actionLoading.value=false}
}
async function savePriority(){
 actionLoading.value=true
 try{await adminApi.communicationSetPriority(route.params.conversationId,priorityValue.value);message.success('优先级已更新。');await load()}
 catch{message.error('保存优先级失败。')}
 finally{actionLoading.value=false}
}
async function sendReply(){
 const content=draft.value.trim()
 if(!content||sending.value)return
 sending.value=true
 try{
  await adminApi.communicationReply(route.params.conversationId,{content,internalNote:internalNote.value})
  message.success(internalNote.value?'内部备注已保存。':'回复已发送，客户下次同步时可见。')
  draft.value='';await load()
 }catch{message.error('发送失败，请检查权限、网络或重试。')}
 finally{sending.value=false}
}
async function createCandidate(){
 const keywords=[...new Set(keywordText.value.split(/[,，、\n]/).map(k=>k.trim()).filter(Boolean))]
 if(!keywords.length){message.warning('至少输入一个关键词。');return}
 learningLoading.value=true
 try{
  const result=await adminApi.communicationCreateLearningCandidate(route.params.conversationId,{
   questionMessageId:selectedQuestionId.value,answerMessageId:selectedAnswerId.value,entryId:entryId.value.trim(),keywords
  })
  const id=result?.id
  message.success('已创建待审核学习候选；线上客服尚未改变。')
  if(id)router.push({name:'admin-communications-learning-detail',params:{candidateId:id}})
 }catch(e){message.error(e?.message||'候选创建失败；请检查问题范围、关键词或条目 ID。')}
 finally{learningLoading.value=false}
}
function openMessage(item){router.push({name:'admin-communication-message-detail',params:{conversationId:route.params.conversationId,messageId:item.id}})}
onMounted(load)
watch(()=>route.params.conversationId,load)
</script>
<style scoped>
.conversation-page{max-width:1700px;margin:0 auto}.crumb{margin:4px 0 10px}.thread-card,.reply-card,.side-card{border:1px solid #e7eaf0;border-radius:14px;margin-bottom:16px}.muted{color:#98a2b3}.tiny{font-size:11px;margin-top:3px}.thread{display:flex;flex-direction:column;gap:14px}.message-row{border:1px solid #e4e7ec;border-radius:12px;padding:14px 15px;background:#fff}.message-row.customer{border-left:3px solid #7f56d9;background:#fcfaff}.message-row.bot{border-left:3px solid #528bff;background:#f8fbff}.message-row.agent{border-left:3px solid #12b76a;background:#f8fdf9}.message-row.note{border-left:3px solid #f79009;background:#fffcf5}.message-head{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.sender{display:flex;align-items:center;gap:10px}.sender strong{font-size:13px;color:#101828}.avatar{display:grid;place-items:center;width:34px;height:34px;border-radius:50%;font-size:11px;font-weight:700;color:#344054;background:#f2f4f7}.avatar.customer{background:#f4ebff;color:#6941c6}.avatar.bot{background:#eaf2ff;color:#175cd3}.avatar.agent{background:#dcfae6;color:#027a48}.avatar.note{background:#fef0c7;color:#93370d}.message-content{margin:13px 0 8px;color:#344054;font-size:13px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.message-learning{display:flex;gap:14px;flex-wrap:wrap;border-top:1px solid #eaecf0;padding-top:8px}.quick-replies{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}.quick-replies>span{color:#667085;font-size:12px}.reply-footer{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;margin-top:12px}.reply-hint{margin-top:12px}.field-label{margin:6px 0 8px;color:#475467;font-size:12px;font-weight:650}.top-gap{margin-top:18px}.learning-form{margin-top:14px}.learning-card :deep(.ant-alert-description){font-size:12px;line-height:1.7}.side-card :deep(.ant-descriptions-item-label){width:94px;color:#667085}.side-card :deep(.ant-descriptions-item-content){color:#344054}
</style>
