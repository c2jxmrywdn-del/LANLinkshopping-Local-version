<template>
  <div class="review-page">
    <a-page-header title="候选发布审核" sub-title="四级界面 · 发布会更新线上知识匹配，请逐项核验">
      <template #extra><a-button @click="goDetail">返回候选详情</a-button><a-button @click="goList">返回审核列表</a-button></template>
    </a-page-header>
    <a-alert type="warning" show-icon class="warning"
      message="发布是线上知识库变更"
      description="确认发布后，该条目会作为已审核知识参与关键词匹配。此操作不是模型训练，也不会解锁实时价格、库存、个人订单/钱包等受限数据查询。拒绝候选不会影响已有知识库。" />
    <a-spin :spinning="loading">
      <a-result v-if="!loading&&!candidate" status="404" title="找不到待审核候选" sub-title="候选可能已被其他管理员审核，或当前账号没有访问权限。"><template #extra><a-button type="primary" @click="goList">返回审核列表</a-button></template></a-result>
      <template v-else-if="candidate">
        <a-row :gutter="[16,16]">
          <a-col :xs="24" :xl="14">
            <a-card title="内容核对" :bordered="false" class="review-card">
              <a-descriptions bordered size="small" :column="1">
                <a-descriptions-item label="候选 ID">{{ candidate.id }} <a-tag v-if="candidate.source==='auto'" color="blue">系统自动沉淀</a-tag></a-descriptions-item>
                <a-descriptions-item label="条目 ID"><code>{{ candidate.entryId }}</code></a-descriptions-item>
                <a-descriptions-item label="客户问题">{{ sourceQuestion?.content || candidate.questionText }}</a-descriptions-item>
                <a-descriptions-item label="候选关键词">
                  <div class="keyword-editor">
                    <a-space wrap>
                      <a-tag v-for="(k, idx) in editableKeywords" :key="k" closable @close="removeKeyword(idx)">{{ k }}</a-tag>
                      <a-input v-if="inputVisible" ref="inputRef" v-model:value="inputValue" size="small" style="width: 100px" @blur="handleInputConfirm" @keyup.enter="handleInputConfirm" />
                      <a-tag v-else style="background: #fff; border-style: dashed;" @click="showInput">+ 添加关键词</a-tag>
                    </a-space>
                  </div>
                </a-descriptions-item>
              </a-descriptions>
              <div class="answer">
                <div class="answer-header">
                  <div class="label">拟发布回答（支持编辑完善）</div>
                  <a-button size="small" type="link" :loading="aiSuggesting" @click="fetchAiSuggest">✨ AI 建议回答与关键词</a-button>
                </div>
                <a-textarea v-model:value="answerText" :rows="4" :maxlength="1000" show-count placeholder="请输入核验后的准确客服回答。自动沉淀的候选回答为空，须补充后方可发布。" />
              </div>
              <div class="answer source-answer"><div class="label">人工客服来源回答</div><p>{{ sourceAnswer?.content || '（系统自主沉淀候选，无人工来源消息）' }}</p></div>
              <a-checkbox v-model:checked="checks.fact">我已核验回答与 LANLinkshopping 当前页面、业务规则一致。</a-checkbox>
              <a-checkbox v-model:checked="checks.privacy">我已检查脱敏情况，未包含客户隐私、账号凭据或内部配置。</a-checkbox>
              <a-checkbox v-model:checked="checks.scope">我已检查关键词范围、重复意图与越权风险，未把受限问题加入知识库。</a-checkbox>
              <a-form-item label="审核备注（发布或驳回均会记录，最多 500 字）" class="note-field">
                <a-textarea v-model:value="note" :rows="3" :maxlength="500" show-count placeholder="记录核验依据、需修改内容或驳回原因。" />
              </a-form-item>
              <a-space wrap>
                <a-button type="primary" :disabled="!canPublish || candidate.status!=='pending'" :loading="submitting" @click="confirmPublish">确认发布到线上知识库</a-button>
                <a-button danger :disabled="candidate.status!=='pending'" :loading="submitting" @click="confirmReject">驳回候选</a-button>
                <a-button v-if="conversationId" @click="goConversation">打开来源会话</a-button>
              </a-space>
            </a-card>
          </a-col>
          <a-col :xs="24" :xl="10">
            <a-card title="审核标准" :bordered="false" class="standards-card">
              <div v-for="(item,i) in standards" :key="item.title" class="standard-row">
                <span class="number">{{ String(i+1).padStart(2,'0') }}</span>
                <div><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div>
              </div>
              <a-divider />
              <a-alert :type="candidate.status==='pending'?'info':candidate.status==='published'?'success':'error'" show-icon
                :message="'当前状态：'+stateLabel(candidate.status)"
                :description="candidate.status==='pending'?'待人工决策；当前不会影响线上答复。':candidate.status==='published'?'已发布；动态知识匹配会读取该候选。':'已驳回；不会参与线上知识匹配。'" />
            </a-card>
          </a-col>
        </a-row>
      </template>
    </a-spin>
  </div>
</template>
<script setup>
import { computed,nextTick,onMounted,reactive,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { message,Modal } from 'ant-design-vue'
import { adminApi } from '../../api'
const route=useRoute(),router=useRouter(),loading=ref(false),submitting=ref(false),aiSuggesting=ref(false),candidate=ref(null),sourceQuestion=ref(null),sourceAnswer=ref(null),conversationId=ref(null),note=ref(''),answerText=ref('')
const editableKeywords=ref([]),inputVisible=ref(false),inputValue=ref(''),inputRef=ref(null)
const checks=reactive({fact:false,privacy:false,scope:false})
const standards=[
 {title:'事实与业务一致',description:'回答只描述平台当前实际存在的页面、按钮和操作，不臆造价格、库存、承诺或政策。'},
 {title:'隐私与敏感信息',description:'不包含手机号、邮箱、身份证、银行卡、密码、验证码、令牌、私密配置或可还原的个人信息。'},
 {title:'意图与边界',description:'关键词能准确命中此类问题，不与其他业务冲突；涉及个人订单、钱包及未接入实时数据时保持拒答。'},
 {title:'可追溯与可回滚',description:'来源会话、审核人员和备注均留痕；发现错误后应另行停用或修订知识条目。'}
]
const canPublish=computed(()=>checks.fact&&checks.privacy&&checks.scope&&note.value.trim().length>0&&answerText.value.trim().length>0&&editableKeywords.value.length>0)
function stateLabel(v){return ({pending:'待审核',published:'已发布',rejected:'已驳回'})[v]||'未知'}
async function load(){loading.value=true;candidate.value=null;try{const result=await adminApi.communicationLearningCandidate(route.params.candidateId);candidate.value=result?.candidate||null;sourceQuestion.value=result?.sourceQuestion||null;sourceAnswer.value=result?.sourceAnswer||null;conversationId.value=result?.sourceConversationId||candidate.value?.conversationId||null;answerText.value=candidate.value?.answerText||'';try{const k=JSON.parse(candidate.value?.keywordsJson||'[]');editableKeywords.value=Array.isArray(k)?k:[]}catch{editableKeywords.value=[]}}catch{candidate.value=null}finally{loading.value=false}}
function removeKeyword(idx){editableKeywords.value.splice(idx,1)}
function showInput(){inputVisible.value=true;nextTick(()=>{inputRef.value?.focus()})}
function handleInputConfirm(){if(inputValue.value.trim()&&!editableKeywords.value.includes(inputValue.value.trim())){editableKeywords.value.push(inputValue.value.trim().substring(0,80))}inputVisible.value=false;inputValue.value=''}
async function fetchAiSuggest(){aiSuggesting.value=true;try{const res=await adminApi.communicationCandidateAiSuggest(route.params.candidateId);if(res?.suggestedAnswer){answerText.value=res.suggestedAnswer;if(Array.isArray(res?.suggestedKeywords)&&res.suggestedKeywords.length){for(const kw of res.suggestedKeywords){if(!editableKeywords.value.includes(kw))editableKeywords.value.push(kw)}}message.success('已自动生成 AI 建议回答与关键词，请核对。')}}catch(e){message.error('AI 建议生成失败：'+(e?.message||'请检查外部模型渠道设置'))}finally{aiSuggesting.value=false}}
function goDetail(){router.push({name:'admin-communications-learning-detail',params:{candidateId:route.params.candidateId}})}
function goList(){router.push({name:'admin-communications-learning'})}
function goConversation(){if(conversationId.value)router.push({name:'admin-communication-conversation',params:{conversationId:conversationId.value}})}
function confirmPublish(){if(!canPublish.value)return;Modal.confirm({title:'发布此候选知识？',content:'此操作会将候选加入线上客服的关键词匹配范围。请确认回答、关键词与安全边界均已核验。',okText:'确认发布',cancelText:'再检查一次',onOk:()=>review('publish')})}
function confirmReject(){if(!note.value.trim()){message.warning('驳回前请填写具体原因。');return}Modal.confirm({title:'驳回此候选知识？',content:'被驳回的候选不会参与线上知识匹配。',okText:'确认驳回',okButtonProps:{danger:true},cancelText:'取消',onOk:()=>review('reject')})}
async function review(decision){submitting.value=true;try{await adminApi.communicationReviewLearningCandidate(route.params.candidateId,{decision,note:note.value.trim(),answerText:answerText.value.trim(),keywords:editableKeywords.value});message.success(decision==='publish'?'候选已发布，线上匹配将读取该条目。':'候选已驳回。');await load()}catch(e){message.error(e?.message||'审核操作失败，候选可能已被其他管理员处理。')}finally{submitting.value=false}}
onMounted(load);watch(()=>route.params.candidateId,load)
</script>
<style scoped>
.review-page{max-width:1600px;margin:0 auto}.warning{margin-bottom:16px}.review-card,.standards-card{border:1px solid #e7eaf0;border-radius:14px}.answer{margin-top:18px;border:1px solid #e4e7ec;border-radius:10px;background:#f8fafc;padding:15px}.source-answer{background:#f7fcf8;border-color:#d1fadf}.label{color:#667085;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.answer-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}.keyword-editor{margin-top:4px}.answer p{margin:9px 0 0;color:#344054;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.review-card :deep(.ant-checkbox-wrapper){display:flex;margin:14px 0;color:#344054;line-height:1.6}.note-field{margin-top:22px}.standard-row{display:flex;gap:13px;padding:12px 0;border-bottom:1px solid #f2f4f7}.standard-row:last-child{border-bottom:0}.number{display:grid;place-items:center;flex-shrink:0;width:34px;height:34px;border-radius:9px;background:#f2f4f7;color:#475467;font-size:11px;font-weight:700}.standard-row strong{font-size:13px;color:#101828}.standard-row p{margin:5px 0 0;color:#667085;font-size:12px;line-height:1.7}
</style>
