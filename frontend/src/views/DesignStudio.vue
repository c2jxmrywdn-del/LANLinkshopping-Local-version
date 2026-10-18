<template>
  <div class="studio">
    <!-- 顶部：工具选择 + OAuth 授权状态 -->
    <a-card :bordered="false" class="head-card">
      <div class="head">
        <div>
          <h1>设计工作台</h1>
          <p>设计工具对接中台：OAuth 授权 · 组件资源 · 设计令牌 · 实时预览 · 增量同步 · 代码导出</p>
        </div>
        <a-radio-group v-model:value="tool" button-style="solid" @change="onToolChange">
          <a-radio-button v-for="t in tools" :key="t.toolId" :value="t.toolId">{{ t.displayName }}</a-radio-button>
        </a-radio-group>
      </div>
      <!-- 能力矩阵 -->
      <div class="caps" v-if="currentTool">
        <span class="caps-label">能力矩阵</span>
        <a-tag v-for="c in CAP_LABELS" :key="c.k"
               :color="currentTool.capabilities.includes(c.k) ? 'blue' : 'default'"
               :style="{ opacity: currentTool.capabilities.includes(c.k) ? 1 : .45 }">
          {{ currentTool.capabilities.includes(c.k) ? '✓ ' : '× ' }}{{ c.label }}
        </a-tag>
      </div>
    </a-card>

    <a-row :gutter="16">
      <!-- 左：OAuth + 设计文件/组件 -->
      <a-col :span="8">
        <a-card title="① OAuth 2.0 授权" class="block">
          <a-space direction="vertical" style="width:100%">
            <a-button type="primary" block :loading="authLoading" @click="startAuth">发起授权</a-button>
            <div v-if="authUrl" class="auth-url">
              <a-typography-paragraph copyable :content="authUrl" class="url-text" />
            </div>
            <a-input v-model:value="authCode" placeholder="粘贴授权码（或使用演示码）" />
            <a-button block :disabled="!authCode" :loading="tokenLoading" @click="doExchange">换取访问令牌</a-button>
            <a-alert v-if="token" type="success" show-icon
                     :message="'令牌: ' + token.slice(0, 16) + '…（已缓存，用于后续调用）'" />
          </a-space>
        </a-card>

        <a-card title="② 设计文件与组件资源" class="block">
          <a-space direction="vertical" style="width:100%">
            <a-input v-model:value="fileKey" placeholder="fileKey（演示: demo-001）" />
            <a-button block :disabled="!token" :loading="fileLoading" @click="loadFile">读取设计文件</a-button>
            <a-button block :disabled="!token" :loading="compLoading" @click="loadComponents">批量获取组件</a-button>
            <a-button block :disabled="!token" :loading="tokenSetLoading" @click="loadTokens">解析设计令牌</a-button>
            <a-button block :disabled="!token" @click="runSync">增量同步（≤3s）</a-button>
          </a-space>
          <!-- 文件树 -->
          <div v-if="file" class="file-tree">
            <div class="file-name">{{ file.name }}</div>
            <div v-for="p in file.pages" :key="p.nodeId" class="page">
              <div class="page-name">📄 {{ p.name }}</div>
              <div v-for="f in p.frames" :key="f.nodeId" class="frame" @click="loadPreview(f.nodeId)">
                <span>🖼 {{ f.name }}</span>
                <small>{{ f.width }}×{{ f.height }}</small>
              </div>
            </div>
          </div>
        </a-card>
      </a-col>

      <!-- 中：实时预览 + 增量同步 + 设计令牌 -->
      <a-col :span="8">
        <a-card title="③ 实时预览 / 增量同步" class="block">
          <a-button block :disabled="!token" :loading="previewLoading" @click="loadPreview()">刷新预览</a-button>
          <img v-if="preview?.imageUrl" :src="preview.imageUrl" alt="设计稿预览" class="preview-img" />
          <a-empty v-else description="选择画板节点查看预览" />
          <a-alert v-if="syncResult" :type="syncResult.hasChanges ? 'info' : 'success'" show-icon
                   class="sync-alert"
                   :message="`增量同步 v${syncResult.previousVersion} → v${syncResult.currentVersion}`"
                   :description="`耗时 ${syncResult.elapsedMs}ms（目标 ≤3000ms）· 变更节点 ${syncResult.changedNodeIds?.length || 0} 个`" />
        </a-card>

        <a-card title="④ 设计令牌（样式变量自动映射）" class="block">
          <a-empty v-if="!tokenSet" description="点击左侧「解析设计令牌」" />
          <template v-else>
            <div class="token-group" v-for="(map, kind) in tokenDisplay" :key="kind">
              <div class="token-kind">{{ kind }}</div>
              <div v-for="(v, k) in map" :key="k" class="token-row">
                <span class="token-key">{{ k }}</span>
                <span class="token-val">
                  <span v-if="kind === '颜色'" class="swatch" :style="{ background: v }"></span>{{ v?.fontFamily ? (v.fontSize + 'px / ' + v.fontWeight) : v }}
                </span>
              </div>
            </div>
          </template>
        </a-card>
      </a-col>

      <!-- 右：多格式代码导出 + 模板/嵌入/回传 -->
      <a-col :span="8">
        <a-card title="⑤ 多格式代码导出" class="block">
          <a-space direction="vertical" style="width:100%">
            <a-select v-model:value="exportFormat" style="width:100%">
              <a-select-option value="REACT">React (JSX)</a-select-option>
              <a-select-option value="VUE">Vue (SFC)</a-select-option>
              <a-select-option value="HTML">HTML</a-select-option>
              <a-select-option value="CSS">CSS（设计令牌）</a-select-option>
            </a-select>
            <a-select v-model:value="exportComponent" style="width:100%" placeholder="选择组件">
              <a-select-option v-for="c in components" :key="c.componentId" :value="c.componentId">{{ c.name }}</a-select-option>
            </a-select>
            <a-button type="primary" block :disabled="!token || !exportComponent" :loading="exportLoading" @click="doExport">
              一键导出 {{ exportFormat }}
            </a-button>
          </a-space>
          <div v-if="exportResult" class="export-result">
            <div class="export-meta">
              <a-tag color="green">✓ {{ exportResult.fileName }}</a-tag>
              <a-button size="small" @click="copyCode">复制代码</a-button>
            </div>
            <pre class="code-block"><code>{{ exportResult.code }}</code></pre>
          </div>
        </a-card>

        <a-card title="⑥ 模板库 / 编辑器嵌入 / 成果回传（Canva 闭环）" class="block">
          <a-space direction="vertical" style="width:100%">
            <a-button block :disabled="!token" @click="loadTemplates">检索模板库</a-button>
            <a-button block :disabled="!token" @click="doEmbed">唤起在线编辑器</a-button>
            <a-input v-model:value="callbackUrl" placeholder="编辑完成的成果 URL" />
            <a-button block :disabled="!token || !callbackUrl" :loading="callbackLoading" @click="doCallback">回传设计成果</a-button>
          </a-space>
          <a-alert v-if="embedSpec" type="info" show-icon class="embed-alert"
                   :message="'编辑器已唤起: ' + embedSpec.mode" :description="embedSpec.embedUrl" />
          <a-alert v-if="callbackResult?.success" type="success" show-icon class="embed-alert"
                   message="成果回传成功（设计流程闭环）" :description="callbackResult.message" />
          <div class="tpl-grid" v-if="templates.length">
            <div v-for="t in templates" :key="t.templateId" class="tpl-card">
              <img :src="t.thumbnailUrl" :alt="t.title" />
              <div class="tpl-title">{{ t.title }}</div>
            </div>
          </div>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { designApi } from '../api'

const CAP_LABELS = [
  { k: 'OAUTH', label: 'OAuth 授权' },
  { k: 'FILE_READ', label: '文件读取' },
  { k: 'COMPONENT_EXPORT', label: '组件导出' },
  { k: 'TOKEN_PARSE', label: '令牌解析' },
  { k: 'PREVIEW', label: '实时预览' },
  { k: 'INCREMENTAL_SYNC', label: '增量同步' },
  { k: 'TEMPLATE_LIBRARY', label: '模板库' },
  { k: 'EMBED_EDITOR', label: '编辑器嵌入' },
  { k: 'DESIGN_CALLBACK', label: '成果回传' }
]

const tools = ref([])
const tool = ref('mock')
const currentTool = computed(() => tools.value.find(t => t.toolId === tool.value))

// ① OAuth
const authLoading = ref(false)
const tokenLoading = ref(false)
const authUrl = ref('')
const authCode = ref('')
const token = ref('')

// ② 文件/组件/令牌
const fileKey = ref('demo-001')
const fileLoading = ref(false)
const compLoading = ref(false)
const tokenSetLoading = ref(false)
const file = ref(null)
const components = ref([])
const tokenSet = ref(null)

// ③ 预览/同步
const previewLoading = ref(false)
const preview = ref(null)
const syncResult = ref(null)

// ⑤ 导出
const exportFormat = ref('REACT')
const exportComponent = ref(null)
const exportLoading = ref(false)
const exportResult = ref(null)

// ⑥ 模板/嵌入/回传
const templates = ref([])
const embedSpec = ref(null)
const callbackUrl = ref('')
const callbackLoading = ref(false)
const callbackResult = ref(null)

const tokenDisplay = computed(() => {
  if (!tokenSet.value) return {}
  const out = {}
  if (tokenSet.value.colors) out['颜色'] = tokenSet.value.colors
  if (tokenSet.value.typography) out['字体'] = tokenSet.value.typography
  if (tokenSet.value.spacing) out['间距'] = tokenSet.value.spacing
  if (tokenSet.value.radii) out['圆角'] = tokenSet.value.radii
  if (tokenSet.value.shadows) out['阴影'] = tokenSet.value.shadows
  return out
})

onMounted(async () => {
  try {
    tools.value = await designApi.tools() || []
  } catch (e) {
    message.error('能力矩阵加载失败')
  }
})

function onToolChange() {
  token.value = ''
  file.value = null
  components.value = []
  tokenSet.value = null
  preview.value = null
  syncResult.value = null
  message.info(`已切换到 ${currentTool.value?.displayName || tool.value}，请重新授权`)
}

async function startAuth() {
  authLoading.value = true
  try {
    const vo = await designApi.authUrl(tool.value, 'studio-' + Date.now())
    authUrl.value = vo?.authUrl || ''
    authCode.value = 'demo-code' // 演示环境预填，真实 OAuth 回调后带 code
    message.success('授权地址已生成，可跳转或直接使用演示码')
  } catch (e) {
    message.error('生成授权地址失败')
  } finally {
    authLoading.value = false
  }
}

async function doExchange() {
  tokenLoading.value = true
  try {
    const vo = await designApi.exchangeToken(tool.value, authCode.value)
    token.value = vo?.accessToken || ''
    message.success('访问令牌已获取，可调用设计能力')
  } catch (e) {
    message.error('令牌交换失败')
  } finally {
    tokenLoading.value = false
  }
}

async function loadFile() {
  fileLoading.value = true
  try {
    file.value = await designApi.designFile(tool.value, fileKey.value, token.value)
  } catch (e) {
    message.error('设计文件读取失败')
  } finally {
    fileLoading.value = false
  }
}

async function loadComponents() {
  compLoading.value = true
  try {
    components.value = (await designApi.components(tool.value, fileKey.value, token.value, [])) || []
    if (components.value.length && !exportComponent.value) exportComponent.value = components.value[0].componentId
    message.success(`获取到 ${components.value.length} 个组件`)
  } catch (e) {
    message.error('组件批量获取失败')
  } finally {
    compLoading.value = false
  }
}

async function loadTokens() {
  tokenSetLoading.value = true
  try {
    tokenSet.value = await designApi.tokens(tool.value, fileKey.value, token.value)
    message.success('设计令牌解析完成')
  } catch (e) {
    message.error('令牌解析失败')
  } finally {
    tokenSetLoading.value = false
  }
}

async function loadPreview(nodeId) {
  previewLoading.value = true
  try {
    preview.value = await designApi.preview(tool.value, fileKey.value, token.value, nodeId)
  } catch (e) {
    message.error('预览渲染失败')
  } finally {
    previewLoading.value = false
  }
}

async function runSync() {
  try {
    syncResult.value = await designApi.sync(tool.value, fileKey.value, token.value, syncResult.value?.currentVersion)
    message.success(`同步完成（${syncResult.value.elapsedMs}ms）`)
  } catch (e) {
    message.error('增量同步失败')
  }
}

async function doExport() {
  exportLoading.value = true
  try {
    exportResult.value = await designApi.exportCode(tool.value, fileKey.value, exportComponent.value, token.value, exportFormat.value)
    message.success(`已导出 ${exportResult.value.fileName}`)
  } catch (e) {
    message.error('代码导出失败')
  } finally {
    exportLoading.value = false
  }
}

function copyCode() {
  if (!exportResult.value?.code) return
  navigator.clipboard?.writeText(exportResult.value.code)
  message.success('代码已复制到剪贴板')
}

async function loadTemplates() {
  try {
    templates.value = (await designApi.templates(tool.value, token.value, {})) || []
    message.success(`检索到 ${templates.value.length} 个模板`)
  } catch (e) {
    message.error('模板库检索失败（该工具可能不支持模板库能力）')
  }
}

async function doEmbed() {
  try {
    embedSpec.value = await designApi.embed(tool.value, token.value, 'design-001')
    message.success('在线编辑器已唤起')
  } catch (e) {
    message.error('编辑器唤起失败（该工具可能不支持嵌入能力）')
  }
}

async function doCallback() {
  callbackLoading.value = true
  try {
    callbackResult.value = await designApi.callback(tool.value, token.value, 'design-001', callbackUrl.value)
  } catch (e) {
    message.error('成果回传失败')
  } finally {
    callbackLoading.value = false
  }
}
</script>

<style scoped>
.studio { max-width: 1440px; margin: 0 auto; }
.head-card { border-radius: 16px; margin-bottom: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.head h1 { margin: 0 0 4px; font-size: 24px; }
.head p { margin: 0; color: var(--ll-muted, #64748b); }
.caps { margin-top: 14px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.caps-label { font-size: 12px; color: var(--ll-muted, #64748b); margin-right: 4px; }
.block { border-radius: 14px; margin-bottom: 16px; }
.auth-url { background: #f5f7fa; border-radius: 8px; padding: 6px 10px; }
.url-text { margin: 0; word-break: break-all; }
.file-tree { margin-top: 12px; }
.file-name { font-weight: 600; margin-bottom: 8px; }
.page { margin-bottom: 10px; }
.page-name { font-size: 13px; color: var(--ll-muted, #64748b); margin-bottom: 4px; }
.frame { display: flex; justify-content: space-between; padding: 6px 10px; border-radius: 8px; cursor: pointer;
         background: #f8fafc; margin-bottom: 4px; font-size: 13px; }
.frame:hover { background: #eef4fb; }
.preview-img { width: 100%; margin-top: 12px; border-radius: 10px; border: 1px solid #eef0f3; }
.sync-alert { margin-top: 12px; }
.token-group { margin-bottom: 12px; }
.token-kind { font-weight: 600; margin-bottom: 6px; }
.token-row { display: flex; justify-content: space-between; font-size: 12px; padding: 3px 0; }
.token-key { color: var(--ll-muted, #64748b); }
.token-val { display: flex; align-items: center; gap: 6px; }
.swatch { width: 14px; height: 14px; border-radius: 4px; border: 1px solid #e5e7eb; display: inline-block; }
.export-result { margin-top: 12px; }
.export-meta { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.code-block { background: #13233a; color: #e5eef8; padding: 12px; border-radius: 10px; font-size: 12px;
              max-height: 320px; overflow: auto; }
.embed-alert { margin-top: 12px; }
.tpl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.tpl-card img { width: 100%; border-radius: 8px; }
.tpl-title { font-size: 12px; margin-top: 4px; }
</style>
