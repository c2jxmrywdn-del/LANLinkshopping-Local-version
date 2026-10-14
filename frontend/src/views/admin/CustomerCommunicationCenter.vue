<template>
  <div class="cc-page">
    <a-page-header title="客户沟通中心" sub-title="统一处理客户咨询、人工回复与受控知识沉淀">
      <template #extra>
        <a-button @click="load" :loading="loading"><template #icon><ReloadOutlined /></template>刷新数据</a-button>
        <a-button type="primary" @click="go('admin-communications-inbox')"><template #icon><MessageOutlined /></template>进入会话工作台</a-button>
      </template>
    </a-page-header>

    <section class="workspace-hero" aria-label="客户沟通工作台介绍">
      <div class="hero-copy">
        <div class="hero-kicker"><span class="hero-kicker-dot"></span> SUPPORT OPERATIONS · LANLINKSHOPPING</div>
        <h2>把每一次客户对话，变成更好的服务</h2>
        <p>统一收件箱、人工跟进与受控知识沉淀。先解决问题，再审核学习；让客服能力持续进步，同时保持业务边界清晰。</p>
        <div class="hero-actions">
          <span><i class="hero-mini-dot"></i> 人工审核后才发布</span>
          <span><i class="hero-mini-dot muted-dot"></i> 脱敏与权限控制</span>
          <span><i class="hero-mini-dot gold-dot"></i> 全流程可追溯</span>
        </div>
      </div>
      <div class="hero-mark" aria-hidden="true">
        <div class="hero-ring ring-one"></div>
        <div class="hero-ring ring-two"></div>
        <div class="hero-core"><span>LL</span><small>SUPPORT</small></div>
        <div class="hero-chip chip-top">Conversation</div>
        <div class="hero-chip chip-bottom">Reviewed learning</div>
      </div>
    </section>

    <a-alert class="privacy-alert" type="info" show-icon
      message="沟通数据保护与学习边界"
      description="网站客服会话保存前进行常见个人信息与凭据脱敏。对话只能生成待审核候选，必须人工核验后才能发布为知识条目；不会自动训练模型，也不读取客户订单、钱包或账户资料。" />

    <a-row :gutter="[16,16]" class="stat-grid">
      <a-col :xs="12" :lg="6">
        <a-card class="metric-card"><a-statistic title="待处理会话" :value="stats.needsReply || 0" :value-style="{color:'#b54708'}" /><div class="metric-note">需运营人员关注</div></a-card>
      </a-col>
      <a-col :xs="12" :lg="6">
        <a-card class="metric-card"><a-statistic title="未分配会话" :value="stats.unassigned || 0" :value-style="{color:'#175cd3'}" /><div class="metric-note">等待认领</div></a-card>
      </a-col>
      <a-col :xs="12" :lg="6">
        <a-card class="metric-card"><a-statistic title="今日会话" :value="stats.todayConversations || 0" /><div class="metric-note">按服务器业务日期统计</div></a-card>
      </a-col>
      <a-col :xs="12" :lg="6">
        <a-card class="metric-card"><a-statistic title="待审核知识" :value="stats.pendingLearning || 0" :value-style="{color:'#b42318'}" /><div class="metric-note">尚未影响线上客服</div></a-card>
      </a-col>
    </a-row>

    <a-row :gutter="[16,16]" class="action-grid">
      <a-col :xs="24" :lg="8">
        <a-card class="workflow-card" hoverable @click="go('admin-communications-inbox')">
          <div class="workflow-icon navy"><MessageOutlined /></div>
          <h3>会话工作台</h3>
          <p>筛选未分配、待回复和已解决会话；在单个会话中回复、添加内部备注、认领与调整优先级。</p>
          <a-button type="link" @click.stop="go('admin-communications-inbox')">打开工作台 <RightOutlined /></a-button>
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="8">
        <a-card class="workflow-card" hoverable @click="go('admin-communications-learning')">
          <div class="workflow-icon amber"><BulbOutlined /></div>
          <h3>对话学习审核</h3>
          <p>从客户问题和人工回答中提取脱敏候选，检查关键词、回答事实、重复意图和安全范围，再决定发布或驳回。</p>
          <a-button type="link" @click.stop="go('admin-communications-learning')">查看候选 <RightOutlined /></a-button>
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="8">
        <a-card class="workflow-card" hoverable @click="go('admin-support-learning')">
          <div class="workflow-icon pale"><ExperimentOutlined /></div>
          <h3>学习沙盒</h3>
          <p>对新问法和候选回答进行隔离试跑。沙盒预检通过不等于自动发布，正式知识依然需要人工审核。</p>
          <a-button type="link" @click.stop="go('admin-support-learning')">进入沙盒 <RightOutlined /></a-button>
        </a-card>
      </a-col>
    </a-row>

    <a-card class="panel-card" :bordered="false">
      <template #title><span class="panel-title">最近更新的会话</span></template>
      <template #extra><a-button type="link" @click="go('admin-communications-inbox')">查看全部</a-button></template>
      <a-table :columns="columns" :data-source="recent" row-key="id" :loading="loading" :pagination="false" :scroll="{x:900}">
        <template #bodyCell="{column,record}">
          <template v-if="column.key==='subject'">
            <a-button type="link" class="subject-link" @click="openConversation(record)">{{ record.subject || '专属客服咨询' }}</a-button>
            <div class="muted small">{{ record.conversationNo }}</div>
          </template>
          <template v-else-if="column.key==='status'"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template>
          <template v-else-if="column.key==='priority'"><a-tag :color="priorityColor(record.priority)">{{ priorityLabel(record.priority) }}</a-tag></template>
          <template v-else-if="column.key==='last'"><span class="last-preview">{{ record.lastMessagePreview || '暂无摘要' }}</span><div class="muted small">{{ record.lastMessageAt || record.createdAt }}</div></template>
          <template v-else-if="column.key==='action'"><a-button size="small" @click="openConversation(record)">处理会话</a-button></template>
        </template>
        <template #emptyText><a-empty description="暂时没有客户会话"><template #description><span>网站客服收到客户咨询后，会自动在这里建立会话。</span></template></a-empty></template>
      </a-table>
    </a-card>
    <div class="page-foot"><span class="status-dot"></span> 客户沟通服务连接正常性以接口响应为准；此处仅展示当前账号有权访问的数据。</div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ReloadOutlined, MessageOutlined, RightOutlined, BulbOutlined, ExperimentOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../../api'

const router = useRouter()
const loading = ref(false)
const stats = reactive({ needsReply:0, waitingCustomer:0, resolved:0, todayConversations:0, unassigned:0, pendingLearning:0, publishedLearning:0 })
const recent = ref([])
const columns = [
  {title:'客户会话',key:'subject',width:230},
  {title:'业务分类',dataIndex:'category',width:130},
  {title:'状态',key:'status',width:120},
  {title:'优先级',key:'priority',width:100},
  {title:'最近消息',key:'last',width:250},
  {title:'操作',key:'action',width:100,fixed:'right'}
]
function go(name) { router.push({name}) }
function openConversation(record) { router.push({name:'admin-communication-conversation',params:{conversationId:record.id}}) }
function statusLabel(v) { return ({open:'待处理',waiting_agent:'待客服回复',waiting_customer:'等待客户',resolved:'已解决'})[v] || '未知' }
function statusColor(v) { return ({open:'orange',waiting_agent:'red',waiting_customer:'blue',resolved:'green'})[v] || 'default' }
function priorityLabel(v) { return ({low:'低',normal:'普通',high:'高',urgent:'紧急'})[v] || '普通' }
function priorityColor(v) { return ({low:'default',normal:'blue',high:'orange',urgent:'red'})[v] || 'blue' }
async function load() {
  loading.value = true
  try {
    const [overview,page] = await Promise.all([
      adminApi.communicationOverview(),
      adminApi.communicationConversations({queue:'all',page:1,size:8})
    ])
    Object.assign(stats,overview || {})
    recent.value = page?.records || []
  } catch {
    message.error('沟通中心数据加载失败，请检查管理员权限或后端服务。')
  } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.cc-page{max-width:1600px;margin:0 auto}
.workspace-hero{position:relative;display:flex;align-items:center;justify-content:space-between;gap:32px;min-height:220px;margin:2px 0 18px;padding:30px 34px;overflow:hidden;border:1px solid rgba(19,35,58,.12);border-radius:18px;background:radial-gradient(circle at 82% 25%,rgba(200,164,92,.22),transparent 26%),radial-gradient(circle at 72% 95%,rgba(96,136,176,.20),transparent 34%),linear-gradient(120deg,#13233a 0%,#1c3656 56%,#274563 100%);box-shadow:0 14px 34px rgba(19,35,58,.10);color:#fff}
.workspace-hero::before{content:"";position:absolute;inset:0;background:linear-gradient(115deg,transparent 0%,rgba(255,255,255,.06) 44%,transparent 58%);transform:translateX(-70%);animation:hero-glint 9s ease-in-out infinite;pointer-events:none}
.hero-copy{position:relative;z-index:1;max-width:780px}
.hero-kicker{display:flex;align-items:center;gap:8px;color:rgba(255,255,255,.68);font-size:10px;font-weight:700;letter-spacing:.16em}
.hero-kicker-dot{width:6px;height:6px;border-radius:50%;background:#c8a45c;box-shadow:0 0 0 4px rgba(200,164,92,.13)}
.hero-copy h2{margin:14px 0 10px;color:#fff;font-size:clamp(22px,2.2vw,30px);line-height:1.25;font-weight:700;letter-spacing:-.025em}
.hero-copy p{max-width:740px;margin:0;color:rgba(255,255,255,.76);font-size:13px;line-height:1.85}
.hero-actions{display:flex;flex-wrap:wrap;gap:10px 18px;margin-top:20px}
.hero-actions span{display:inline-flex;align-items:center;gap:7px;color:rgba(255,255,255,.82);font-size:11px}
.hero-mini-dot{width:5px;height:5px;border-radius:50%;background:#4bd4a0}.hero-mini-dot.muted-dot{background:#8db6e4}.hero-mini-dot.gold-dot{background:#d6b66e}
.hero-mark{position:relative;flex:0 0 220px;width:220px;height:170px;display:grid;place-items:center;opacity:.95}
.hero-ring{position:absolute;width:138px;height:138px;border:1px solid rgba(255,255,255,.18);border-radius:50%;box-shadow:inset 0 0 28px rgba(255,255,255,.025)}
.ring-one{transform:scaleX(.72) rotate(38deg)}.ring-two{transform:scaleX(.72) rotate(-38deg);border-color:rgba(200,164,92,.43)}
.hero-core{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;width:76px;height:76px;border:1px solid rgba(255,255,255,.3);border-radius:22px;background:linear-gradient(145deg,rgba(255,255,255,.16),rgba(255,255,255,.04));box-shadow:0 12px 34px rgba(0,0,0,.12),inset 0 1px rgba(255,255,255,.2);backdrop-filter:blur(10px);transform:rotate(-4deg)}
.hero-core span{font-size:25px;font-weight:750;letter-spacing:-.09em;color:#fff}.hero-core small{margin-top:1px;color:rgba(255,255,255,.68);font-size:7px;letter-spacing:.18em}
.hero-chip{position:absolute;padding:6px 9px;border:1px solid rgba(255,255,255,.15);border-radius:7px;background:rgba(255,255,255,.08);backdrop-filter:blur(8px);color:rgba(255,255,255,.78);font-size:9px;box-shadow:0 4px 10px rgba(0,0,0,.06)}
.chip-top{top:5px;right:0}.chip-bottom{bottom:5px;left:-1px}
@keyframes hero-glint{0%,72%,100%{transform:translateX(-70%)}32%{transform:translateX(70%)}}
@media(max-width:760px){.workspace-hero{min-height:auto;padding:24px 20px}.hero-mark{display:none}.hero-copy h2{font-size:23px}.hero-actions{gap:8px 14px}}
@media(prefers-reduced-motion:reduce){.workspace-hero::before{animation:none}}.privacy-alert{margin:0 0 18px}.stat-grid{margin-bottom:16px}.metric-card{height:100%;border:1px solid #e7eaf0;border-radius:14px;box-shadow:0 2px 8px rgba(16,24,40,.025)}.metric-note{margin-top:8px;color:#667085;font-size:12px}.action-grid{margin-bottom:18px}.workflow-card{height:100%;min-height:216px;border:1px solid #e7eaf0;border-radius:14px;transition:transform .18s ease,box-shadow .18s ease}.workflow-card:hover{transform:translateY(-2px);box-shadow:0 12px 28px rgba(16,24,40,.08)}.workflow-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:12px;font-size:19px}.workflow-icon.navy{color:#fff;background:#13233a}.workflow-icon.amber{color:#8a4b08;background:#fff0d7}.workflow-icon.pale{color:#344054;background:#f2f4f7}.workflow-card h3{margin:14px 0 8px;color:#101828;font-size:15px;font-weight:650}.workflow-card p{min-height:56px;margin:0;color:#667085;font-size:13px;line-height:1.7}.panel-card{border:1px solid #e7eaf0;border-radius:14px}.panel-title{font-weight:650;color:#101828}.subject-link{padding:0;height:auto;font-weight:600;text-align:left}.muted{color:#98a2b3}.small{font-size:11px}.last-preview{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#344054;font-size:12px}.page-foot{display:flex;align-items:center;gap:8px;margin-top:16px;color:#667085;font-size:12px}.status-dot{width:7px;height:7px;border-radius:50%;background:#12b76a;box-shadow:0 0 0 3px #dcfae6}
</style>
