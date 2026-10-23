<template>
  <div class="ai-model-page">
    <a-page-header
      title="外部大模型接入"
      sub-title="配置兼容 OpenAI 协议的外部大模型渠道，写入 API Key 后即可在平台内发起真实调用">
      <template #extra>
        <a-space>
          <a-button :loading="loading" @click="load"><template #icon><ReloadOutlined /></template>刷新</a-button>
          <a-button type="primary" @click="openCreate()"><template #icon><PlusOutlined /></template>接入新渠道</a-button>
        </a-space>
      </template>
    </a-page-header>

    <a-alert
      class="security-banner"
      type="info"
      show-icon
      message="密钥安全与调用说明"
      description="API Key 使用 AES-256-GCM 加密存储，管理端只回显脱敏前缀，任何接口都不会返回明文；留空表示保留已保存的密钥。开启「外部模型意图增强」后，客服长尾问题会优先走外部模型，异常时自动回退本地规则。">
    </a-alert>

    <a-card :bordered="false" class="switch-card">
      <div class="switch-row">
        <div class="switch-copy">
          <strong>外部模型意图增强</strong>
          <p>客服遇到未知或长尾问题时请求已启用的外部大模型做意图判定；关闭或调用异常时自动回退本地启发式规则。</p>
        </div>
        <a-switch
          :checked="intentExternalEnabled"
          :loading="toggleLoading"
          checked-children="开启"
          un-checked-children="关闭"
          @change="onToggleIntent" />
      </div>
    </a-card>

    <a-row :gutter="[16, 16]" class="channel-grid">
      <a-col v-for="ch in channels" :key="ch.channel" :xs="24" :md="12" :xl="8">
        <a-card class="channel-card" :bordered="false">
          <div class="channel-head">
            <div>
              <a-tag :color="ch.enabled ? 'green' : 'default'">{{ ch.enabled ? '已启用' : '已停用' }}</a-tag>
              <strong>{{ channelLabel(ch.channel) }}</strong>
              <code class="channel-code">{{ ch.channel }}</code>
            </div>
            <CloudServerOutlined class="channel-icon" />
          </div>

          <dl class="channel-meta">
            <div><dt>模型</dt><dd>{{ ch.modelName || '—' }}</dd></div>
            <div><dt>端点</dt><dd class="mono" :title="ch.baseUrl">{{ ch.baseUrl || '—' }}</dd></div>
            <div><dt>密钥</dt><dd><span v-if="ch.apiKeyPrefix" class="key-prefix">{{ ch.apiKeyPrefix }}</span><a-tag v-else>未配置</a-tag></dd></div>
          </dl>

          <div class="channel-actions">
            <a-button size="small" :loading="testingChannel === ch.channel" @click="testChannel(ch)">
              <template #icon><ThunderboltOutlined /></template>测试连接
            </a-button>
            <a-button size="small" type="primary" ghost @click="openInvoke(ch)">
              <template #icon><ExperimentOutlined /></template>调用测试
            </a-button>
            <a-button size="small" @click="openEdit(ch)">
              <template #icon><EditOutlined /></template>配置
            </a-button>
          </div>
        </a-card>
      </a-col>

      <a-col v-if="!loading && !channels.length" :span="24">
        <a-card :bordered="false">
          <a-empty description="尚未接入外部大模型，点击右上角「接入新渠道」开始">
            <a-button type="primary" @click="openCreate()">接入第一个渠道</a-button>
          </a-empty>
        </a-card>
      </a-col>
    </a-row>

    <a-card :bordered="false" class="preset-card" title="可接入的渠道">
      <p class="muted small">选择渠道后会自动填入官方端点与推荐模型，也可以使用自定义兼容服务。</p>
      <a-row :gutter="[12, 12]">
        <a-col v-for="item in availablePresets" :key="item.key" :xs="24" :sm="12" :lg="8" :xl="6">
          <div class="preset-item">
            <div>
              <b>{{ item.label }}</b>
              <small>{{ item.baseUrl || '需手动填写端点' }}</small>
            </div>
            <a-button size="small" @click="openCreate(item.key)">接入</a-button>
          </div>
        </a-col>
        <a-col v-if="!availablePresets.length" :span="24">
          <span class="muted small">全部渠道均已接入，可在上方卡片中编辑或调用测试。</span>
        </a-col>
      </a-row>
    </a-card>

    <!-- 渠道配置 -->
    <a-modal
      v-model:open="formVisible"
      :title="editing ? '配置渠道' : '接入外部大模型渠道'"
      :confirm-loading="saving"
      ok-text="保存配置"
      cancel-text="取消"
      width="620px"
      @ok="save">
      <a-form layout="vertical">
        <a-form-item label="渠道" required>
          <a-select v-model:value="form.channel" :disabled="editing" @change="onChannelChange">
            <a-select-option v-for="item in presets" :key="item.key" :value="item.key">{{ item.label }}</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="Base URL" required extra="支持 OpenAI Chat Completions 协议；填服务根地址或带 /v1 的地址都可以，系统会自动补全 /chat/completions">
          <a-input v-model:value="form.baseUrl" placeholder="https://api.deepseek.com 或 https://api.deepseek.com/v1" />
        </a-form-item>

        <a-form-item label="模型名称 Model" extra="调用时传入的 model ID，留空则使用渠道默认模型">
          <a-input v-model:value="form.modelName" placeholder="例如 deepseek-flash、gpt-4o-mini" />
        </a-form-item>

        <a-form-item
          label="API Key"
          :required="!editing"
          :extra="editing ? '留空表示保留现有密钥；输入新密钥会覆盖并重新加密存储' : '首次接入必须填写，保存后仅回显脱敏前缀'">
          <a-input-password v-model:value="form.apiKey" placeholder="sk-..." />
        </a-form-item>

        <a-form-item label="启用此渠道">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
        </a-form-item>

        <div class="inline-test">
          <a-button size="small" :loading="testingInline" @click="testInline">测试当前输入</a-button>
          <span v-if="inlineResult" class="inline-test-result" :class="inlineResult.success ? 'ok' : 'err'">
            {{ inlineResult.success
              ? `连接正常（${inlineResult.latencyMs} ms）${inlineResult.note ? ' · ' + inlineResult.note : ''}`
              : (inlineResult.error || '连接失败') }}
          </span>
        </div>
      </a-form>
    </a-modal>

    <!-- 调用测试 -->
    <a-modal
      v-model:open="invokeVisible"
      title="调用外部大模型"
      :confirm-loading="invoking"
      ok-text="发起调用"
      cancel-text="关闭"
      width="680px"
      @ok="runInvoke">
      <a-form layout="vertical">
        <a-form-item label="渠道">
          <a-tag color="blue">{{ channelLabel(invokeTarget?.channel) }}</a-tag>
          <span class="muted small">{{ invokeTarget?.modelName }} · {{ invokeTarget?.baseUrl }}</span>
        </a-form-item>
        <a-form-item label="Prompt" required>
          <a-textarea v-model:value="invokePrompt" :rows="4" show-count :maxlength="4000" placeholder="输入要发送给模型的文本" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="Temperature">
              <a-input-number v-model:value="invokeTemperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Max Tokens" extra="推理类模型（reasoning）建议 ≥500，过小会只返回推理内容">
              <a-input-number v-model:value="invokeMaxTokens" :min="1" :max="4000" :step="50" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>

      <a-spin :spinning="invoking">
        <div v-if="invokeResult" class="invoke-result">
          <a-tag :color="invokeResult.success ? 'success' : 'error'">
            {{ invokeResult.success ? `调用成功 · ${invokeResult.latencyMs} ms · ${invokeResult.model}` : '调用失败' }}
          </a-tag>
          <div v-if="invokeResult.note" class="invoke-note">{{ invokeResult.note }}</div>
          <pre>{{ invokeResult.success ? invokeResult.content : (invokeResult.error || '未知错误') }}</pre>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  ReloadOutlined,
  PlusOutlined,
  CloudServerOutlined,
  ThunderboltOutlined,
  ExperimentOutlined,
  EditOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../../api'

const presets = [
  { key: 'deepseek', label: 'DeepSeek（深度求索）', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash' },
  { key: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { key: 'qwen', label: '通义千问（DashScope）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { key: 'chatglm', label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  { key: 'mimo', label: '小米 MiMo', baseUrl: 'https://api.xiaomimimo.com/v1', model: 'mimo-v2.6-flash' },
  { key: 'bailian', label: '阿里百炼（DashScope）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { key: 'mimosa', label: 'Mimosa 内部网关', baseUrl: '', model: 'mimosa' },
  { key: 'ollama', label: 'Ollama 本地服务', baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5:7b' },
  { key: 'custom', label: '自定义兼容服务', baseUrl: '', model: '' }
]

const loading = ref(false)
const channels = ref([])
const intentExternalEnabled = ref(false)
const toggleLoading = ref(false)

const testingChannel = ref('')
const testingInline = ref(false)
const inlineResult = ref(null)

const formVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({ channel: 'deepseek', baseUrl: '', modelName: '', apiKey: '', enabled: true })

const invokeVisible = ref(false)
const invokeTarget = ref(null)
const invokePrompt = ref('请用一句话介绍 LANLinkshopping 聚合型 B2B 电商平台。')
const invokeTemperature = ref(0.3)
const invokeMaxTokens = ref(600)
const invoking = ref(false)
const invokeResult = ref(null)

const availablePresets = computed(() => {
  const configured = new Set(channels.value.map(c => c.channel))
  return presets.filter(p => !configured.has(p.key))
})

function channelLabel(key) {
  return presets.find(p => p.key === key)?.label || key || '—'
}

async function load() {
  loading.value = true
  try {
    const data = await adminApi.outboundModelConfigList()
    channels.value = Array.isArray(data?.channels) ? data.channels : []
    intentExternalEnabled.value = !!data?.intentExternalEnabled
  } catch (e) {
    message.error(e?.message || '加载外部模型配置失败')
  } finally {
    loading.value = false
  }
}

async function onToggleIntent(checked) {
  toggleLoading.value = true
  try {
    await adminApi.outboundModelConfigToggle('intent_external_enabled', checked)
    intentExternalEnabled.value = checked
    message.success(checked ? '外部模型意图增强已开启' : '外部模型意图增强已关闭')
  } catch (e) {
    message.error(e?.message || '开关更新失败')
  } finally {
    toggleLoading.value = false
  }
}

function openCreate(presetKey) {
  editing.value = false
  const preset = presets.find(p => p.key === (presetKey || 'deepseek')) || presets[0]
  Object.assign(form, {
    channel: preset.key,
    baseUrl: preset.baseUrl,
    modelName: preset.model,
    apiKey: '',
    enabled: true
  })
  inlineResult.value = null
  formVisible.value = true
}

function openEdit(record) {
  editing.value = true
  Object.assign(form, {
    channel: record.channel,
    baseUrl: record.baseUrl || '',
    modelName: record.modelName || '',
    apiKey: '',
    enabled: !!record.enabled
  })
  inlineResult.value = null
  formVisible.value = true
}

function onChannelChange(key) {
  const preset = presets.find(p => p.key === key)
  if (!preset) return
  if (!form.baseUrl) form.baseUrl = preset.baseUrl
  if (!form.modelName) form.modelName = preset.model
}

async function save() {
  if (!form.channel) return message.warning('请选择渠道')
  if (!/^https?:\/\//.test(form.baseUrl.trim())) return message.warning('Base URL 必须以 http(s) 开头')
  if (!editing.value && !form.apiKey.trim()) return message.warning('首次接入必须填写 API Key')

  saving.value = true
  try {
    await adminApi.outboundModelConfigUpsert({
      channel: form.channel,
      baseUrl: form.baseUrl.trim(),
      modelName: form.modelName.trim() || undefined,
      apiKey: form.apiKey.trim() || undefined,
      enabled: form.enabled
    })
    message.success('渠道配置已保存')
    formVisible.value = false
    await load()
  } catch (e) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function testInline() {
  testingInline.value = true
  inlineResult.value = null
  try {
    inlineResult.value = await adminApi.outboundModelConfigTest({
      channel: form.channel,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim(),
      modelName: form.modelName.trim()
    })
  } catch (e) {
    inlineResult.value = { success: false, error: e?.message || '测试失败' }
  } finally {
    testingInline.value = false
  }
}

async function testChannel(record) {
  testingChannel.value = record.channel
  try {
    const result = await adminApi.outboundModelConfigTest({
      channel: record.channel,
      baseUrl: record.baseUrl,
      apiKey: '',
      modelName: record.modelName
    })
    if (result?.success) message.success(`连接正常（${result.latencyMs} ms）`)
    else message.error(result?.error || '连接失败')
  } catch (e) {
    message.error(e?.message || '连接测试失败')
  } finally {
    testingChannel.value = ''
  }
}

function openInvoke(record) {
  invokeTarget.value = record
  invokeResult.value = null
  invokeVisible.value = true
}

async function runInvoke() {
  if (!invokePrompt.value.trim()) return message.warning('请输入 prompt')
  invoking.value = true
  invokeResult.value = null
  try {
    invokeResult.value = await adminApi.outboundModelInvoke({
      channel: invokeTarget.value?.channel,
      prompt: invokePrompt.value.trim(),
      temperature: invokeTemperature.value,
      maxTokens: invokeMaxTokens.value
    })
  } catch (e) {
    invokeResult.value = { success: false, error: e?.message || '调用失败' }
  } finally {
    invoking.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ai-model-page { display: flex; flex-direction: column; gap: 16px; }
.security-banner { border-radius: 12px; }
.switch-card, .preset-card, .channel-card { border-radius: 14px; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.switch-copy strong { display: block; color: #0f172a; font-size: 15px; margin-bottom: 6px; }
.switch-copy p { margin: 0; color: #667085; font-size: 12px; line-height: 1.7; max-width: 760px; }
.channel-grid { margin: 0; }
.channel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.channel-head strong { display: block; margin-top: 8px; color: #0f172a; font-size: 15px; }
.channel-code { display: inline-block; margin-top: 4px; color: #98a2b3; font-size: 11px; }
.channel-icon { color: #c8a45c; font-size: 20px; }
.channel-meta { margin: 14px 0 16px; display: grid; gap: 8px; }
.channel-meta div { display: flex; gap: 10px; align-items: baseline; }
.channel-meta dt { flex: 0 0 40px; color: #98a2b3; font-size: 12px; }
.channel-meta dd { margin: 0; min-width: 0; color: #1f2b3d; font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.channel-meta dd.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-weight: 500; }
.key-prefix { padding: 2px 6px; border-radius: 5px; background: rgba(16,185,129,.1); color: #067647; font-family: ui-monospace, Consolas, monospace; font-size: 11px; }
.channel-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.preset-card .muted { margin: 0 0 12px; }
.preset-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border: 1px solid #ece8df; border-radius: 12px; background: #fffdf8; }
.preset-item b { display: block; color: #1f2b3d; font-size: 13px; }
.preset-item small { display: block; margin-top: 3px; color: #98a2b3; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 240px; }
.inline-test { display: flex; align-items: flex-start; gap: 10px; }
.inline-test-result { flex: 1; min-width: 0; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.inline-test-result.ok { color: #067647; }
.inline-test-result.err { color: #b42318; }
.invoke-result { margin-top: 4px; padding: 12px 14px; border: 1px solid #ece8df; border-radius: 12px; background: #fbfaf6; }
.invoke-result pre { margin: 10px 0 0; max-height: 260px; overflow: auto; white-space: pre-wrap; word-break: break-word; color: #1f2b3d; font-size: 12px; line-height: 1.7; font-family: inherit; }
.invoke-note { margin-top: 8px; color: #b54708; font-size: 12px; line-height: 1.6; }
.muted { color: #98a2b3; }
.small { font-size: 12px; }
</style>
