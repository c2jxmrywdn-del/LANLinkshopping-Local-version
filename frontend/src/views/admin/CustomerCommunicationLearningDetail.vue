<template>
  <div class="candidate-detail-page">
    <a-page-header title="候选知识详情" sub-title="三级界面 · 来源、关键词与回答均可追溯">
      <template #extra>
        <a-button @click="goList">返回候选列表</a-button>
        <a-button v-if="candidate?.status==='pending'" type="primary" @click="goReview">进入四级审核</a-button>
      </template>
    </a-page-header>
    <a-spin :spinning="loading">
      <a-result v-if="!loading&&!candidate" status="404" title="候选知识不存在" sub-title="记录可能已移除，或当前账号没有访问权限。"><template #extra><a-button type="primary" @click="goList">返回候选列表</a-button></template></a-result>
      <template v-else-if="candidate">
        <a-alert :type="candidate.status==='published'?'success':candidate.status==='rejected'?'error':'warning'" show-icon class="state-alert"
          :message="'当前状态：'+stateLabel(candidate.status)"
          :description="candidate.status==='published'?'该候选已由管理员发布，可以参与线上知识库关键词匹配；并非模型训练。':candidate.status==='rejected'?'该候选未进入线上知识匹配。':'该候选尚未发布，对线上客服回答没有影响。'" />
        <a-row :gutter="[16,16]">
          <a-col :xs="24" :xl="15">
            <a-card :bordered="false" class="content-card">
              <template #title>候选回答内容</template>
              <a-descriptions bordered size="small" :column="1">
                <a-descriptions-item label="条目 ID"><code>{{ candidate.entryId }}</code></a-descriptions-item>
                <a-descriptions-item label="创建时间">{{ candidate.createdAt || '—' }}</a-descriptions-item>
                <a-descriptions-item label="审核人">{{ candidate.reviewerName || '尚未审核' }}</a-descriptions-item>
                <a-descriptions-item label="审核时间">{{ candidate.reviewedAt || '—' }}</a-descriptions-item>
                <a-descriptions-item label="审核备注">{{ candidate.reviewNote || '暂无' }}</a-descriptions-item>
              </a-descriptions>
              <div class="block">
                <div class="block-label">候选关键词</div>
                <a-space wrap><a-tag v-for="word in keywords" :key="word" color="blue">{{ word }}</a-tag><span v-if="!keywords.length" class="muted">无关键词</span></a-space>
              </div>
              <div class="block answer-block">
                <div class="block-label">待发布回答</div>
                <p>{{ candidate.answerText }}</p>
              </div>
              <a-alert type="info" show-icon message="线上生效规则" description="正式回答仍经过范围与安全拦截；实时价格、库存、个人订单、钱包信息以及提示注入等未接入数据源的问题不会因增加候选而开放回答。" />
            </a-card>
          </a-col>
          <a-col :xs="24" :xl="9">
            <a-card :bordered="false" class="source-card">
              <template #title>原始对话来源（已脱敏）</template>
              <div class="source-entry">
                <div class="source-label">客户问题</div>
                <p>{{ sourceQuestion?.content || candidate.questionText || '来源问题不可用' }}</p>
                <div class="muted tiny">{{ sourceQuestion?.createdAt || '—' }}</div>
              </div>
              <div class="source-entry agent">
                <div class="source-label">人工客服回答</div>
                <p>{{ sourceAnswer?.content || candidate.answerText }}</p>
                <div class="muted tiny">{{ sourceAnswer?.createdAt || '—' }}</div>
              </div>
              <a-button block @click="goConversation">查看完整会话时间线</a-button>
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
const route=useRoute(),router=useRouter(),loading=ref(false),candidate=ref(null),sourceQuestion=ref(null),sourceAnswer=ref(null),sourceConversationId=ref(null)
const keywords=computed(()=>{try{const parsed=JSON.parse(candidate.value?.keywordsJson||'[]');return Array.isArray(parsed)?parsed:[]}catch{return []}})
function stateLabel(v){return ({pending:'待审核',published:'已发布',rejected:'已驳回'})[v]||'未知'}
async function load(){loading.value=true;candidate.value=null;try{const result=await adminApi.communicationLearningCandidate(route.params.candidateId);candidate.value=result?.candidate||null;sourceQuestion.value=result?.sourceQuestion||null;sourceAnswer.value=result?.sourceAnswer||null;sourceConversationId.value=result?.sourceConversationId||candidate.value?.conversationId||null}catch{candidate.value=null}finally{loading.value=false}}
function goList(){router.push({name:'admin-communications-learning'})}
function goReview(){router.push({name:'admin-communications-learning-review',params:{candidateId:route.params.candidateId}})}
function goConversation(){if(sourceConversationId.value)router.push({name:'admin-communication-conversation',params:{conversationId:sourceConversationId.value}})}
onMounted(load);watch(()=>route.params.candidateId,load)
</script>
<style scoped>
.candidate-detail-page{max-width:1500px;margin:0 auto}.state-alert{margin-bottom:16px}.content-card,.source-card{border:1px solid #e7eaf0;border-radius:14px}.block{margin-top:22px}.block-label{margin-bottom:10px;color:#667085;font-size:12px;font-weight:700;letter-spacing:.03em;text-transform:uppercase}.answer-block{padding:16px;border-radius:10px;background:#f8fafc;border:1px solid #e4e7ec}.answer-block p{margin:0;white-space:pre-wrap;color:#344054;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.source-entry{border:1px solid #e4e7ec;border-radius:10px;padding:14px;margin-bottom:12px;background:#fcfcfd}.source-entry.agent{background:#f7fcf8;border-color:#d1fadf}.source-label{font-size:11px;color:#667085;font-weight:700}.source-entry p{margin:9px 0;color:#344054;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.muted{color:#98a2b3}.tiny{font-size:11px}.content-card :deep(.ant-descriptions-item-label){width:105px}
</style>
