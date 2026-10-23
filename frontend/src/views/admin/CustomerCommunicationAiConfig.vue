<template>
  <div class="ai-config-page">
    <a-page-header title="AI 模型渠道与 API 密钥管理" sub-title="配置外部大模型 API Key 与端点，分发平台 API 密钥，实时分析与测试意图理解">
      <template #extra>
        <a-button @click="refreshAll" :loading="loading"><template #icon><ReloadOutlined /></template>刷新数据</a-button>
        <a-button type="primary" @click="openCreateKeyModal"><template #icon><PlusOutlined /></template>新建平台 API 密钥</a-button>
      </template>
    </a-page-header>

    <a-alert class="security-banner" type="info" show-icon
      message="API 密钥安全策略与智能客服边界约束"
      description="外部模型密钥通过服务端 AES-256-GCM 高度加密存储，回显仅展示脱敏前缀；平台分发的 API Key 支持调用频率滑动窗口限制。外部大模型仅用于辅助意图分类及知识沉淀建议，客户线上实际问答始终严格由人工审核知识库及安全兜底策略保障。" />

    <a-tabs v-model:activeKey="activeTab" type="card" class="config-tabs">
      <!-- Tab 1: 外部大模型渠道配置 -->
      <a-tab-pane key="models" tab="外部大模型渠道配置">
        <a-card :bordered="false" class="content-card">
          <div class="toggle-box">
            <div class="toggle-info">
              <div class="toggle-title">
                <RobotOutlined class="icon-robot" />
                <span>外部大模型意图增强开关 (intent_external_enabled)</span>
                <a-tag :color="intentExternalEnabled ? 'green' : 'default'">{{ intentExternalEnabled ? '已启用' : '已关闭' }}</a-tag>
              </div>
              <p class="toggle-desc">开启后，客服遇到未知或长尾复杂问题时，将优先请求已配置的外部大模型（如 DeepSeek、OpenAI、通义千问等）进行精准意图判定；若关闭或调用异常，系统无缝自动回退到本地启发式规则引擎。</p>
            </div>
            <a-switch :checked="intentExternalEnabled" :loading="toggleLoading" @change="onToggleIntentExternal" checked-children="开启" un-checked-children="关闭" />
          </div>

          <a-divider style="margin: 18px 0;" />

          <div class="table-header">
            <div>
              <h3><CloudServerOutlined /> 外部模型渠道列表</h3>
              <span class="muted small">支持主流兼容 OpenAI 格式的大语言模型接口（DeepSeek、小米 MiMo、通义千问 Qwen 等）</span>
            </div>
            <a-space wrap>
              <a-button size="small" type="primary" ghost @click="openCreateChannelModal('mimo')">
                <template #icon><PlusOutlined /></template>加入 MIMO (小米)
              </a-button>
              <a-button size="small" type="primary" ghost @click="openCreateChannelModal('qwen')">
                <template #icon><PlusOutlined /></template>加入 Qwen (通义千问)
              </a-button>
              <a-button size="small" type="primary" @click="openCreateChannelModal('custom')">
                <template #icon><PlusOutlined /></template>配置新模型渠道
              </a-button>
            </a-space>
          </div>

          <a-table :data-source="channels" :columns="channelColumns" row-key="channel" :loading="loading" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'channel'">
                <div class="channel-name-cell">
                  <a-tag :color="channelBadgeColor(record.channel)">{{ channelDisplayName(record.channel) }}</a-tag>
                  <code class="channel-code">{{ record.channel }}</code>
                </div>
              </template>

              <template v-else-if="column.key === 'modelName'">
                <span>{{ record.modelName || defaultModelName(record.channel) }}</span>
              </template>

              <template v-else-if="column.key === 'baseUrl'">
                <span class="mono-text">{{ record.baseUrl || '—' }}</span>
              </template>

              <template v-else-if="column.key === 'apiKeyPrefix'">
                <span v-if="record.apiKeyPrefix" class="key-prefix-tag">
                  <SafetyCertificateOutlined style="color: #52c41a; margin-right: 4px;" />
                  {{ record.apiKeyPrefix }}
                </span>
                <a-tag v-else color="default">未配置密钥</a-tag>
              </template>

              <template v-else-if="column.key === 'status'">
                <a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '已启用' : '未启用'" />
              </template>

              <template v-else-if="column.key === 'testResult'">
                <span v-if="testResults[record.channel]">
                  <a-tag :color="(testResults[record.channel].success || testResults[record.channel].reachable) ? 'success' : 'error'"
                         :title="testResults[record.channel].error || ''">
                    {{ (testResults[record.channel].success || testResults[record.channel].reachable) ? ((testResults[record.channel].latencyMs || 0) + ' ms 正常') : '连接异常' }}
                  </a-tag>
                </span>
                <span v-else class="muted small">未测试</span>
              </template>

              <template v-else-if="column.key === 'action'">
                <a-space wrap>
                  <a-button size="small" type="primary" ghost :loading="testingChannels[record.channel]" @click="testChannel(record)">
                    <template #icon><ThunderboltOutlined /></template>测试连接
                  </a-button>
                  <a-button size="small" :type="record.apiKeyPrefix ? 'default' : 'primary'" @click="openEditChannel(record)">
                    <template #icon><EditOutlined /></template>{{ record.apiKeyPrefix ? '配置' : '设置密钥' }}
                  </a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <!-- Tab 2: 平台 AI API 密钥管理 -->
      <a-tab-pane key="keys" tab="平台 API 密钥管理">
        <a-card :bordered="false" class="content-card">
          <div class="table-top-bar">
            <div>
              <h3><KeyOutlined /> 开放平台 API 密钥列表</h3>
              <p class="muted small">供外部系统、移动端客服插件或第三方微服务调用智能客服意图分析接口 (<code>POST /support/intent/analyze</code>)。请求时在请求头中携带 <code>X-API-Key: llk_...</code> 或 <code>Authorization: Bearer llk_...</code>。</p>
            </div>
            <a-button type="primary" @click="openCreateKeyModal"><template #icon><PlusOutlined /></template>创建新密钥</a-button>
          </div>

          <a-table :data-source="apiKeys" :columns="apiKeyColumns" row-key="id" :loading="loading" :pagination="{ pageSize: 10 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <strong>{{ record.name }}</strong>
              </template>

              <template v-else-if="column.key === 'keyPrefix'">
                <code class="key-prefix-code">{{ record.keyPrefix }}...</code>
              </template>

              <template v-else-if="column.key === 'provider'">
                <a-tag color="blue">{{ record.provider }}</a-tag>
              </template>

              <template v-else-if="column.key === 'permissions'">
                <a-tag v-for="p in (record.permissions || '').split(',')" :key="p" color="cyan">{{ p }}</a-tag>
              </template>

              <template v-else-if="column.key === 'status'">
                <a-tag :color="record.status === 'active' ? 'green' : 'red'">
                  {{ record.status === 'active' ? '有效运行中' : '已禁用' }}
                </a-tag>
              </template>

              <template v-else-if="column.key === 'expiresAt'">
                <span>{{ record.expiresAt ? formatTime(record.expiresAt) : '永久有效' }}</span>
              </template>

              <template v-else-if="column.key === 'action'">
                <a-popconfirm
                  v-if="record.status === 'active'"
                  title="确定禁用该 API 密钥？"
                  description="禁用后所有使用此密钥的客户端调用将被立即拒绝。"
                  ok-text="确认禁用"
                  cancel-text="取消"
                  ok-button-props="{ danger: true }"
                  @confirm="disableKey(record.id)"
                >
                  <a-button size="small" danger>禁用</a-button>
                </a-popconfirm>
                <span v-else class="muted small">已废弃</span>
              </template>
            </template>
            <template #emptyText>
              <a-empty description="暂无平台 API 密钥">
                <template #description><span>点击右上角“创建新密钥”为外部业务系统接入智能客服。</span></template>
              </a-empty>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <!-- Tab 3: 意图理解分析与在线调试 -->
      <a-tab-pane key="intent" tab="意图理解与调试沙箱">
        <div class="intent-grid">
          <!-- 意图分布统计 -->
          <a-card :bordered="false" class="content-card mb-4" title="意图识别统计概览">
            <template #extra>
              <a-button size="small" @click="loadIntentStats" :loading="intentStatsLoading"><template #icon><ReloadOutlined /></template>刷新统计</a-button>
            </template>
            <a-row :gutter="16" class="stats-row">
              <a-col :xs="24" :sm="8">
                <a-statistic title="累计意图日志记录" :value="totalIntentLogs" :value-style="{ color: '#175cd3' }" />
              </a-col>
              <a-col :xs="24" :sm="8">
                <a-statistic title="启发式规则判定" :value="localRuleCount" :value-style="{ color: '#027a48' }" />
              </a-col>
              <a-col :xs="24" :sm="8">
                <a-statistic title="外部模型判定" :value="externalModelCount" :value-style="{ color: '#b54708' }" />
              </a-col>
            </a-row>

            <a-divider style="margin: 16px 0;" />
            <div class="intent-stats-tags">
              <div class="stats-tags-title">热门分类调用频次：</div>
              <a-space wrap>
                <a-tag v-for="item in intentStats" :key="item.intent + item.source" :color="intentTagColor(item.intent)">
                  {{ intentNameMap[item.intent] || item.intent }} ({{ item.source }}): {{ item.count }} 次
                </a-tag>
              </a-space>
            </div>
          </a-card>

          <!-- 在线意图理解调试 -->
          <a-row :gutter="16">
            <a-col :xs="24" :lg="12">
              <a-card :bordered="false" class="content-card" title="在线意图理解测试 (Intent Analyzer)">
                <p class="muted small">模拟真实客户提问，测试本地多意图规则库与外部大模型（如开启）的分类能力及置信度：</p>
                <a-form layout="vertical">
                  <a-form-item label="测试问题（必填，最多 300 字）">
                    <a-textarea v-model:value="testQuestion" :rows="3" placeholder="例如：我的快递单号查询怎么显示没有物流信息？" show-count :maxlength="300" />
                  </a-form-item>
                  <a-form-item label="前置轮次对话上下文（可选，每行一条）">
                    <a-textarea v-model:value="testTurnsText" :rows="2" placeholder="可输入前序上下文，辅助消除多义性指代" />
                  </a-form-item>
                  <a-space>
                    <a-button type="primary" :loading="analyzing" @click="runIntentAnalyze">
                      <template #icon><ThunderboltOutlined /></template>开始意图分析
                    </a-button>
                    <a-button @click="fillPresetQuestion('complaint')">示例：人工投诉</a-button>
                    <a-button @click="fillPresetQuestion('logistics')">示例：物流查询</a-button>
                    <a-button @click="fillPresetQuestion('refund')">示例：退款申请</a-button>
                  </a-space>
                </a-form>

                <div v-if="intentAnalysisResult" class="analysis-result-box">
                  <a-divider style="margin: 16px 0;">分析判定结果</a-divider>
                  <div class="result-item">
                    <span class="result-label">识别意图类别：</span>
                    <a-tag :color="intentTagColor(intentAnalysisResult.intent)" class="large-tag">
                      {{ intentAnalysisResult.category }} (<code>{{ intentAnalysisResult.intent }}</code>)
                    </a-tag>
                  </div>
                  <div class="result-item">
                    <span class="result-label">判定来源引擎：</span>
                    <a-tag :color="intentAnalysisResult.source === 'external' ? 'purple' : 'blue'">
                      {{ intentAnalysisResult.source === 'external' ? '外部大模型分类 (External LLM)' : '本地启发式规则引擎 (Local Rules)' }}
                    </a-tag>
                  </div>
                  <div class="result-item">
                    <span class="result-label">综合置信度：</span>
                    <a-progress :percent="Math.round((intentAnalysisResult.confidence || 0) * 100)" :stroke-color="confidenceColor(intentAnalysisResult.confidence)" style="width: 220px;" />
                  </div>
                  <div class="result-item" v-if="intentAnalysisResult.clarification">
                    <span class="result-label">追问与引导建议：</span>
                    <span class="clarification-text">{{ intentAnalysisResult.clarification }}</span>
                  </div>
                  <div class="result-item">
                    <span class="result-label">匹配说明：</span>
                    <span class="reason-text">{{ intentAnalysisResult.reason }}</span>
                  </div>
                </div>
              </a-card>
            </a-col>

            <!-- AI 草稿生成与自主学习建议 -->
            <a-col :xs="24" :lg="12">
              <a-card :bordered="false" class="content-card" title="自主学习 AI 草稿生成测试 (Draft Generator)">
                <p class="muted small">测试外部大模型如何自主为新问题提供规范的客服应答草稿及知识检索关键词：</p>
                <a-form layout="vertical">
                  <a-form-item label="待生成草稿的业务问题">
                    <a-input v-model:value="suggestQuestion" placeholder="例如：平台支持哪些支付方式？可以货到付款吗？" allow-clear />
                  </a-form-item>
                  <a-button type="primary" ghost :loading="suggesting" @click="runAiSuggest">
                    <template #icon><BulbOutlined /></template>生成 AI 回答草稿与关键词
                  </a-button>
                </a-form>

                <div v-if="suggestResult" class="suggest-result-box">
                  <a-divider style="margin: 16px 0;">生成草稿与关键词</a-divider>
                  <div class="result-item">
                    <span class="result-label">生成模型：</span>
                    <a-tag color="cyan">{{ suggestResult.model }}</a-tag>
                    <span class="muted small" v-if="suggestResult.latencyMs">耗时 {{ suggestResult.latencyMs }} ms</span>
                  </div>
                  <div class="draft-answer">
                    <div class="label">建议回答文本：</div>
                    <p>{{ suggestResult.suggestedAnswer }}</p>
                  </div>
                  <div class="draft-keywords">
                    <div class="label">提取关键词：</div>
                    <a-space wrap>
                      <a-tag v-for="kw in suggestResult.suggestedKeywords" :key="kw" color="blue">{{ kw }}</a-tag>
                    </a-space>
                  </div>
                </div>
              </a-card>
            </a-col>
          </a-row>
        </div>
      </a-tab-pane>
    </a-tabs>

    <!-- Modal 1: 编辑/配置渠道配置 -->
    <a-modal v-model:open="editChannelVisible" :title="isEditingExisting ? ('配置 ' + channelDisplayName(channelForm.channel) + ' 渠道') : '加入外部大模型渠道'" @ok="handleSaveChannel" :confirm-loading="savingChannel" ok-text="保存配置" cancel-text="取消" width="640px">
      <a-form layout="vertical" class="mt-3">
        <a-form-item label="渠道名称与服务商" required extra="选择或切换所需的大模型平台">
          <a-select v-model:value="channelForm.channel" :disabled="isEditingExisting" @change="applyPreset">
            <a-select-option value="mimo">小米 MiMo (小米开放平台)</a-select-option>
            <a-select-option value="qwen">通义千问 (DashScope)</a-select-option>
            <a-select-option value="deepseek">DeepSeek (深度求索)</a-select-option>
            <a-select-option value="bailian">阿里百炼 (DashScope)</a-select-option>
            <a-select-option value="openai">OpenAI (官方)</a-select-option>
            <a-select-option value="chatglm">智谱 GLM (BigModel)</a-select-option>
            <a-select-option value="ollama">Ollama 本地服务</a-select-option>
            <a-select-option value="mimosa">Mimosa 内部网关</a-select-option>
            <a-select-option value="custom">自定义兼容服务</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="快捷填充预设">
          <a-space wrap>
            <a-button size="small" type="dashed" @click="applyPreset('mimo')">小米 MiMo</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('qwen')">通义千问 Qwen</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('deepseek')">DeepSeek 官方</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('bailian')">阿里百炼</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('openai')">OpenAI 官方</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('chatglm')">智谱 AI</a-button>
            <a-button size="small" type="dashed" @click="applyPreset('ollama')">本地 Ollama</a-button>
          </a-space>
        </a-form-item>

        <a-form-item label="服务端点 Base URL" required extra="支持 OpenAI Chat Completions 规范协议；填服务根地址或带 /v1 均可，系统自动解析 /chat/completions">
          <a-input v-model:value="channelForm.baseUrl" :placeholder="channelBaseUrlPlaceholder(channelForm.channel)" />
        </a-form-item>

        <a-form-item label="模型名称 Model Name" extra="调用接口时使用的具体模型 ID">
          <a-input v-model:value="channelForm.modelName" :placeholder="defaultModelName(channelForm.channel)" />
        </a-form-item>

        <a-form-item label="API Key 访问密钥" :extra="channelKeyHint(channelForm.channel)">
          <a-input-password v-model:value="channelForm.apiKey" :placeholder="channelKeyPlaceholder(channelForm.channel)" />
        </a-form-item>

        <a-form-item label="启用此渠道">
          <a-switch v-model:checked="channelForm.enabled" checked-children="启用" un-checked-children="停用" />
        </a-form-item>

        <div class="test-inline-box">
          <a-button size="small" type="dashed" :loading="testingInline" @click="handleTestInline">
            <template #icon><ThunderboltOutlined /></template>测试当前输入连接
          </a-button>
          <span v-if="inlineTestResult" class="inline-test-msg" :class="(inlineTestResult.success || inlineTestResult.reachable) ? 'ok' : 'err'">
            {{ (inlineTestResult.success || inlineTestResult.reachable)
              ? `连接成功 (${inlineTestResult.latencyMs || 0} ms)`
              : `连接失败: ${inlineTestResult.error || (inlineTestResult.statusCode ? ('HTTP ' + inlineTestResult.statusCode) : '网络连接超时或上游未响应')}` }}
          </span>
        </div>
      </a-form>
    </a-modal>

    <!-- Modal 2: 创建平台 API 密钥 -->
    <a-modal v-model:open="createKeyVisible" title="创建平台 API 密钥" @ok="handleCreateKey" :confirm-loading="creatingKey" ok-text="立即创建" cancel-text="取消">
      <a-form layout="vertical">
        <a-form-item label="密钥标识名称" required>
          <a-input v-model:value="keyForm.name" placeholder="例如：外部电商助手集成端" :maxlength="80" />
        </a-form-item>
        <a-form-item label="调用方 / 提供商标识" required>
          <a-input v-model:value="keyForm.provider" placeholder="例如：mobile-app 或 partner-service" :maxlength="40" />
        </a-form-item>
        <a-form-item label="权限范围">
          <a-select v-model:value="keyForm.permissions">
            <a-select-option value="intent:analyze">仅意图识别 (intent:analyze)</a-select-option>
            <a-select-option value="intent:analyze,support:read">意图识别与客服会话只读</a-select-option>
            <a-select-option value="*">完全客服权限 (*)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="有效期">
          <a-select v-model:value="keyForm.expiryDays">
            <a-select-option :value="30">30 天</a-select-option>
            <a-select-option :value="90">90 天</a-select-option>
            <a-select-option :value="365">1 年 (365 天)</a-select-option>
            <a-select-option :value="0">永久有效</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Modal 3: 新密钥明文仅展示一次 -->
    <a-modal v-model:open="showSecretModal" title="密钥创建成功" :footer="null" :closable="false" width="560px">
      <a-alert type="warning" show-icon
        message="请立即复制并妥善保存此 API 密钥！"
        description="出于最高安全策略，该密钥完整明文只会向您完整展示一次。关闭此弹窗后系统将仅保留 SHA-256 哈希值与前缀，无法再次找回！" />
      <div class="secret-display-box">
        <div class="secret-label">生成的原始 API Key：</div>
        <div class="secret-value">
          <code>{{ newlyCreatedKey?.key }}</code>
          <a-button type="primary" size="small" @click="copyKeyToClipboard">
            <template #icon><CopyOutlined /></template>复制
          </a-button>
        </div>
      </div>
      <div class="secret-meta">
        <div><strong>名称：</strong>{{ newlyCreatedKey?.name }}</div>
        <div><strong>提供商：</strong>{{ newlyCreatedKey?.provider }}</div>
        <div><strong>权限：</strong>{{ newlyCreatedKey?.permissions }}</div>
      </div>
      <div style="text-align: right; margin-top: 18px;">
        <a-button type="primary" @click="closeSecretModal">我已复制并妥善保存</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  ReloadOutlined,
  PlusOutlined,
  RobotOutlined,
  CloudServerOutlined,
  KeyOutlined,
  ThunderboltOutlined,
  EditOutlined,
  SafetyCertificateOutlined,
  BulbOutlined,
  CopyOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../../api'

const activeTab = ref('models')
const loading = ref(false)
const toggleLoading = ref(false)
const channels = ref([])
const intentExternalEnabled = ref(false)
const testResults = reactive({})
const testingChannels = reactive({})

// 渠道表头
const channelColumns = [
  { title: '渠道名称', key: 'channel', width: 180 },
  { title: '模型名称', key: 'modelName', width: 170 },
  { title: '接口 Base URL', key: 'baseUrl', width: 260 },
  { title: 'API Key', key: 'apiKeyPrefix', width: 160 },
  { title: '状态', key: 'status', width: 110 },
  { title: '最近连接测试', key: 'testResult', width: 140 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' }
]

// 平台密钥表头
const apiKeys = ref([])
const apiKeyColumns = [
  { title: '密钥名称', key: 'name', width: 180 },
  { title: '密钥前缀', key: 'keyPrefix', width: 150 },
  { title: '调用方/提供商', key: 'provider', width: 140 },
  { title: '权限范围', key: 'permissions', width: 180 },
  { title: '状态', key: 'status', width: 120 },
  { title: '过期时间', key: 'expiresAt', width: 160 },
  { title: '操作', key: 'action', width: 100, fixed: 'right' }
]

// 意图统计与调试
const intentStats = ref([])
const intentStatsLoading = ref(false)
const testQuestion = ref('我的快递单号查询怎么显示没有物流信息？')
const testTurnsText = ref('')
const analyzing = ref(false)
const intentAnalysisResult = ref(null)

const suggestQuestion = ref('平台支持哪些支付方式？可以货到付款吗？')
const suggesting = ref(false)
const suggestResult = ref(null)

// 模态框控制
const editChannelVisible = ref(false)
const isEditingExisting = ref(false)
const savingChannel = ref(false)
const testingInline = ref(false)
const inlineTestResult = ref(null)
const channelForm = reactive({
  channel: 'deepseek',
  baseUrl: '',
  apiKey: '',
  modelName: '',
  enabled: true
})

const createKeyVisible = ref(false)
const creatingKey = ref(false)
const keyForm = reactive({
  name: '',
  provider: '',
  permissions: 'intent:analyze',
  expiryDays: 90
})

const showSecretModal = ref(false)
const newlyCreatedKey = ref(null)

const intentNameMap = {
  account: '账号与安全',
  product: '商品与库存',
  order: '订单与履约',
  merchant: '商户入驻与经营',
  wallet: '钱包与余额',
  logistics: '物流轨迹与时效',
  refund: '退款与售后',
  invoice: '发票与报销',
  complaint_human: '人工客服与投诉',
  greeting: '问候与礼仪',
  other: '其他'
}

const totalIntentLogs = computed(() => intentStats.value.reduce((acc, cur) => acc + (Number(cur.count) || 0), 0))
const localRuleCount = computed(() => intentStats.value.filter(i => i.source === 'local').reduce((acc, cur) => acc + (Number(cur.count) || 0), 0))
const externalModelCount = computed(() => intentStats.value.filter(i => i.source === 'external').reduce((acc, cur) => acc + (Number(cur.count) || 0), 0))

function channelDisplayName(c) {
  const map = {
    deepseek: 'DeepSeek (深度求索)',
    openai: 'OpenAI (官方)',
    qwen: '通义千问 (DashScope)',
    chatglm: '智谱 GLM (BigModel)',
    mimo: '小米 MiMo (小米开放平台)',
    bailian: '阿里百炼 (DashScope)',
    mimosa: 'Mimosa 内部网关',
    ollama: 'Ollama 本地服务',
    custom: '自定义兼容接口'
  }
  return map[c] || c
}

function channelBadgeColor(c) {
  const map = {
    deepseek: 'blue',
    openai: 'green',
    qwen: 'purple',
    chatglm: 'orange',
    mimo: 'magenta',
    bailian: 'gold',
    mimosa: 'cyan',
    ollama: 'geekblue',
    custom: 'default'
  }
  return map[c] || 'default'
}

function defaultModelName(c) {
  const map = {
    deepseek: 'deepseek-flash',
    openai: 'gpt-4o-mini',
    qwen: 'qwen-plus',
    chatglm: 'glm-4-flash',
    mimo: 'mimo-v2.6-flash',
    bailian: 'qwen-plus',
    mimosa: 'mimosa-chat',
    ollama: 'qwen2.5:7b',
    custom: 'gpt-3.5-turbo'
  }
  return map[c] || 'default'
}

function defaultBaseUrl(c) {
  const map = {
    mimo: 'https://api.xiaomimimo.com/v1',
    qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    bailian: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    deepseek: 'https://api.deepseek.com/v1',
    openai: 'https://api.openai.com/v1',
    chatglm: 'https://open.bigmodel.cn/api/paas/v4',
    ollama: 'http://localhost:11434/v1',
    mimosa: 'http://localhost:8090/v1'
  }
  return map[c] || ''
}

function channelBaseUrlPlaceholder(c) {
  return defaultBaseUrl(c) || 'https://...'
}

function channelKeyHint(c) {
  if (c === 'mimo') {
    return '留空表示保留现有密钥；可前往小米开放平台控制台（https://api.xiaomimimo.com）创建并获取 API Key'
  }
  if (c === 'qwen' || c === 'bailian') {
    return '留空表示保留现有密钥；可前往阿里云百炼 DashScope 控制台（https://dashscope.console.aliyun.com）获取 API-KEY'
  }
  if (c === 'deepseek') {
    return '留空表示保留现有密钥；可前往 DeepSeek 开放平台（https://platform.deepseek.com）获取 API Key'
  }
  return '留空表示保留现有已配置的密钥；输入新密钥将替换原密钥并加密存储'
}

function channelKeyPlaceholder(c) {
  if (c === 'mimo') return '请输入小米 MiMo API Key (sk-...)'
  if (c === 'qwen' || c === 'bailian') return '请输入通义千问 / DashScope API Key (sk-...)'
  return 'sk-...'
}

function intentTagColor(i) {
  const map = {
    complaint_human: 'red',
    refund: 'volcano',
    logistics: 'orange',
    invoice: 'gold',
    order: 'blue',
    product: 'cyan',
    account: 'geekblue',
    wallet: 'purple',
    greeting: 'green',
    other: 'default'
  }
  return map[i] || 'default'
}

function confidenceColor(c) {
  if (c >= 0.8) return '#52c41a'
  if (c >= 0.6) return '#faad14'
  return '#ff4d4f'
}

function formatTime(str) {
  if (!str) return '—'
  return String(str).replace('T', ' ').substring(0, 19)
}

// 刷新全页
async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([loadChannels(), loadKeys(), loadIntentStats()])
  } finally {
    loading.value = false
  }
}

// 加载渠道（合并常用标准预设渠道，确保 MIMO 与 Qwen 等开箱即见、一键配置与测试）
async function loadChannels() {
  try {
    const res = await adminApi.outboundModelConfigList()
    const rawChannels = res?.channels || []

    const standardPresets = [
      { channel: 'deepseek', baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-flash' },
      { channel: 'mimo', baseUrl: 'https://api.xiaomimimo.com/v1', modelName: 'mimo-v2.6-flash' },
      { channel: 'qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-plus' },
      { channel: 'bailian', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-plus' },
      { channel: 'openai', baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4o-mini' },
      { channel: 'chatglm', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', modelName: 'glm-4-flash' },
      { channel: 'ollama', baseUrl: 'http://localhost:11434/v1', modelName: 'qwen2.5:7b' }
    ]

    const channelMap = new Map()
    for (const item of rawChannels) {
      channelMap.set(item.channel, { ...item, isConfigured: true })
    }
    for (const preset of standardPresets) {
      if (!channelMap.has(preset.channel)) {
        channelMap.set(preset.channel, {
          channel: preset.channel,
          baseUrl: preset.baseUrl,
          modelName: preset.modelName,
          apiKeyPrefix: null,
          enabled: false,
          isConfigured: false
        })
      }
    }
    channels.value = Array.from(channelMap.values())
    intentExternalEnabled.value = Boolean(res?.intentExternalEnabled)
  } catch (e) {
    message.error('加载外部模型渠道配置失败：' + (e?.message || '未知错误'))
  }
}

// 切换外部模型意图增强开关
async function onToggleIntentExternal(checked) {
  toggleLoading.value = true
  try {
    await adminApi.outboundModelConfigToggle('intent_external_enabled', checked)
    intentExternalEnabled.value = checked
    message.success(checked ? '已开启外部模型意图增强' : '已关闭外部模型意图增强，自动使用本地规则')
  } catch (e) {
    message.error('更新开关失败：' + (e?.message || '未知错误'))
  } finally {
    toggleLoading.value = false
  }
}

// 测试特定渠道连接
async function testChannel(record) {
  testingChannels[record.channel] = true
  try {
    const res = await adminApi.outboundModelConfigTest({
      channel: record.channel,
      baseUrl: record.baseUrl,
      apiKey: '',
      modelName: record.modelName
    })
    testResults[record.channel] = res
    if (res?.success || res?.reachable) {
      message.success(`${channelDisplayName(record.channel)} 连通正常，响应耗时 ${res.latencyMs || 0} ms`)
    } else {
      const errDetail = res?.error || (res?.statusCode ? `HTTP ${res.statusCode}` : '网络连接超时或上游未响应')
      message.warning(`${channelDisplayName(record.channel)} 连接异常：${errDetail}`)
    }
  } catch (e) {
    message.error('测试请求异常：' + (e?.message || '网络错误'))
  } finally {
    testingChannels[record.channel] = false
  }
}

// 打开编辑已配置渠道
function openEditChannel(record) {
  isEditingExisting.value = true
  channelForm.channel = record.channel
  channelForm.baseUrl = record.baseUrl || defaultBaseUrl(record.channel)
  channelForm.modelName = record.modelName || defaultModelName(record.channel)
  channelForm.apiKey = ''
  channelForm.enabled = record.enabled !== false
  inlineTestResult.value = null
  editChannelVisible.value = true
}

// 打开添加/配置新渠道弹窗（可预先指定渠道，如 mimo 或 qwen）
function openCreateChannelModal(presetChannel = 'mimo') {
  isEditingExisting.value = false
  channelForm.channel = presetChannel
  channelForm.apiKey = ''
  channelForm.enabled = true
  inlineTestResult.value = null
  applyPreset(presetChannel)
  editChannelVisible.value = true
}

// 预设快速填充
function applyPreset(p) {
  channelForm.channel = p
  if (p === 'deepseek') {
    channelForm.baseUrl = 'https://api.deepseek.com/v1'
    channelForm.modelName = 'deepseek-flash'
  } else if (p === 'mimo') {
    channelForm.baseUrl = 'https://api.xiaomimimo.com/v1'
    channelForm.modelName = 'mimo-v2.6-flash'
  } else if (p === 'qwen') {
    channelForm.baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    channelForm.modelName = 'qwen-plus'
  } else if (p === 'bailian') {
    channelForm.baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    channelForm.modelName = 'qwen-plus'
  } else if (p === 'openai') {
    channelForm.baseUrl = 'https://api.openai.com/v1'
    channelForm.modelName = 'gpt-4o-mini'
  } else if (p === 'chatglm') {
    channelForm.baseUrl = 'https://open.bigmodel.cn/api/paas/v4'
    channelForm.modelName = 'glm-4-flash'
  } else if (p === 'ollama') {
    channelForm.baseUrl = 'http://localhost:11434/v1'
    channelForm.modelName = 'qwen2.5:7b'
  }
}

// 模态框内测试
async function handleTestInline() {
  testingInline.value = true
  try {
    const res = await adminApi.outboundModelConfigTest({
      channel: channelForm.channel,
      baseUrl: channelForm.baseUrl,
      apiKey: channelForm.apiKey,
      modelName: channelForm.modelName
    })
    inlineTestResult.value = res
  } catch (e) {
    inlineTestResult.value = {
      success: false,
      reachable: false,
      statusCode: e?.response?.status || 500,
      error: e?.message || '请求失败'
    }
  } finally {
    testingInline.value = false
  }
}

// 保存渠道配置
async function handleSaveChannel() {
  if (!channelForm.baseUrl?.trim()) {
    message.warning('请填写服务端点 Base URL')
    return
  }
  savingChannel.value = true
  try {
    await adminApi.outboundModelConfigUpsert({
      channel: channelForm.channel,
      baseUrl: channelForm.baseUrl.trim(),
      apiKey: channelForm.apiKey?.trim() || null,
      modelName: channelForm.modelName?.trim() || defaultModelName(channelForm.channel),
      enabled: channelForm.enabled
    })
    message.success(`${channelDisplayName(channelForm.channel)} 配置已保存`)
    editChannelVisible.value = false
    await loadChannels()
  } catch (e) {
    message.error('保存失败：' + (e?.message || '未知错误'))
  } finally {
    savingChannel.value = false
  }
}

// 加载平台 API Keys
async function loadKeys() {
  try {
    const res = await adminApi.aiApiKeysList()
    apiKeys.value = Array.isArray(res) ? res : []
  } catch (e) {
    message.error('加载平台 API 密钥失败：' + (e?.message || '未知错误'))
  }
}

// 打开创建平台密钥
function openCreateKeyModal() {
  keyForm.name = ''
  keyForm.provider = ''
  keyForm.permissions = 'intent:analyze'
  keyForm.expiryDays = 90
  createKeyVisible.value = true
}

// 创建平台密钥
async function handleCreateKey() {
  if (!keyForm.name.trim() || !keyForm.provider.trim()) {
    message.warning('请填写密钥名称与提供商标识')
    return
  }
  creatingKey.value = true
  try {
    let expiresAt = null
    if (keyForm.expiryDays > 0) {
      const d = new Date()
      d.setDate(d.getDate() + keyForm.expiryDays)
      expiresAt = d.toISOString().replace('Z', '')
    }
    const res = await adminApi.aiApiKeyCreate({
      name: keyForm.name.trim(),
      provider: keyForm.provider.trim(),
      permissions: keyForm.permissions,
      expiresAt
    })
    createKeyVisible.value = false
    newlyCreatedKey.value = res
    showSecretModal.value = true
    await loadKeys()
  } catch (e) {
    message.error('创建密钥失败：' + (e?.message || '未知错误'))
  } finally {
    creatingKey.value = false
  }
}

// 复制密钥
async function copyKeyToClipboard() {
  if (!newlyCreatedKey.value?.key) return
  try {
    await navigator.clipboard.writeText(newlyCreatedKey.value.key)
    message.success('API 密钥已复制到剪贴板！')
  } catch {
    message.info('请手动选中文本并按 Ctrl+C 复制')
  }
}

function closeSecretModal() {
  showSecretModal.value = false
  newlyCreatedKey.value = null
}

// 禁用密钥
async function disableKey(id) {
  try {
    await adminApi.aiApiKeyDisable(id)
    message.success('密钥已禁用')
    await loadKeys()
  } catch (e) {
    message.error('禁用失败：' + (e?.message || '未知错误'))
  }
}

// 加载意图统计
async function loadIntentStats() {
  intentStatsLoading.value = true
  try {
    const res = await adminApi.supportIntentStats()
    intentStats.value = Array.isArray(res) ? res : []
  } catch (e) {
    console.warn('获取意图统计失败', e)
  } finally {
    intentStatsLoading.value = false
  }
}

// 运行意图分析测试
async function runIntentAnalyze() {
  if (!testQuestion.value.trim()) {
    message.warning('请输入测试问题')
    return
  }
  analyzing.value = true
  try {
    const turns = testTurnsText.value
      .split('\n')
      .map(s => s.trim())
      .filter(Boolean)
    const res = await adminApi.supportIntentAnalyze({
      question: testQuestion.value.trim(),
      recentTurns: turns
    })
    intentAnalysisResult.value = res
    message.success('意图分析完成')
    loadIntentStats()
  } catch (e) {
    message.error('意图分析失败：' + (e?.message || '请确认后端支持'))
  } finally {
    analyzing.value = false
  }
}

function fillPresetQuestion(type) {
  if (type === 'complaint') {
    testQuestion.value = '你们客服根本不解决问题，一直推卸责任，马上给我转人工投诉！'
    testTurnsText.value = '客服: 请问有什么可以帮您？\n客户: 为什么我的退款一直没到账？'
  } else if (type === 'logistics') {
    testQuestion.value = '请问我的快件到哪了？单号查不到任何轨迹信息。'
    testTurnsText.value = ''
  } else if (type === 'refund') {
    testQuestion.value = '买的衣服尺码偏小想退换货，怎么申请退货运费险？'
    testTurnsText.value = ''
  }
}

// 运行 AI 知识草稿生成
async function runAiSuggest() {
  if (!suggestQuestion.value.trim()) {
    message.warning('请输入待生成草稿的问题')
    return
  }
  suggesting.value = true
  try {
    const res = await adminApi.outboundModelAiSuggest(suggestQuestion.value.trim())
    suggestResult.value = res
    message.success('AI 草稿与关键词生成成功')
  } catch (e) {
    message.error('草稿生成失败：' + (e?.message || '请检查模型渠道配置'))
  } finally {
    suggesting.value = false
  }
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.ai-config-page { max-width: 1600px; margin: 0 auto; }
.security-banner { margin-bottom: 16px; border-radius: 8px; }
.config-tabs { background: transparent; }
.content-card { border-radius: 12px; border: 1px solid #e7eaf0; margin-bottom: 16px; }
.toggle-box { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.toggle-title { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; color: #101828; margin-bottom: 6px; }
.icon-robot { font-size: 18px; color: #175cd3; }
.toggle-desc { margin: 0; color: #667085; font-size: 13px; line-height: 1.6; max-width: 900px; }
.table-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.table-header h3 { margin: 0; font-size: 15px; font-weight: 600; }
.table-top-bar { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 18px; }
.table-top-bar h3 { margin: 0 0 4px 0; font-size: 15px; font-weight: 600; }
.channel-name-cell { display: flex; flex-direction: column; gap: 4px; }
.channel-code { font-size: 11px; color: #667085; }
.mono-text { font-family: monospace; font-size: 12px; color: #344054; word-break: break-all; }
.key-prefix-tag { font-family: monospace; font-size: 12px; background: #f6ffed; padding: 2px 6px; border-radius: 4px; border: 1px solid #b7eb8f; }
.key-prefix-code { font-family: monospace; font-size: 12px; color: #027a48; background: #ecfdf3; padding: 2px 6px; border-radius: 4px; }
.test-inline-box { margin-top: 12px; padding: 10px; background: #f8fafc; border-radius: 6px; display: flex; align-items: flex-start; gap: 12px; }
.inline-test-msg { flex: 1; min-width: 0; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.inline-test-msg.ok { color: #067647; }
.inline-test-msg.err { color: #b42318; }
.secret-display-box { margin: 18px 0; padding: 14px; background: #f8fafc; border-radius: 8px; border: 1px dashed #d0d5dd; }
.secret-label { font-size: 12px; color: #667085; margin-bottom: 6px; }
.secret-value { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.secret-value code { font-family: monospace; font-size: 14px; font-weight: 600; color: #b54708; word-break: break-all; }
.secret-meta { font-size: 13px; color: #344054; display: flex; flex-direction: column; gap: 4px; }
.stats-row { margin-bottom: 12px; }
.intent-stats-tags { margin-top: 8px; }
.stats-tags-title { font-size: 12px; color: #667085; margin-bottom: 8px; }
.analysis-result-box, .suggest-result-box { margin-top: 18px; padding: 14px; background: #f8fafc; border-radius: 8px; border: 1px solid #eaecf0; }
.result-item { display: flex; align-items: center; margin-bottom: 10px; font-size: 13px; }
.result-label { width: 130px; color: #667085; flex-shrink: 0; font-weight: 500; }
.large-tag { font-size: 13px; padding: 2px 8px; }
.clarification-text { color: #b54708; font-weight: 500; }
.reason-text { color: #344054; }
.draft-answer { margin-bottom: 12px; }
.draft-answer .label, .draft-keywords .label { font-size: 12px; color: #667085; font-weight: 600; margin-bottom: 4px; }
.draft-answer p { margin: 0; color: #101828; line-height: 1.7; background: #fff; padding: 10px; border-radius: 6px; border: 1px solid #e4e7ec; white-space: pre-wrap; }
.muted { color: #667085; }
.small { font-size: 12px; }
.mb-4 { margin-bottom: 16px; }
</style>
