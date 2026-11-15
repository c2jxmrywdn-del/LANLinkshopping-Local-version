<template>
  <div class="support-media">
    <div v-if="items.length" class="support-media-chips" role="list" aria-label="待发送附件">
      <div v-for="item in items" :key="item.uid" class="media-chip" :class="['state-' + item.state]"
        role="listitem" :title="item.error || item.summary || item.file.name">
        <img v-if="item.previewUrl" class="media-thumb" :src="item.previewUrl" alt="" />
        <span v-else class="media-type-icon" aria-hidden="true">
          <component :is="iconOf(item.kind)" />
        </span>
        <div class="media-meta">
          <span class="media-name">{{ item.file.name }}</span>
          <span class="media-sub" :class="{ 'is-error': item.state === 'failed' }">
            {{ formatSize(item.file.size) }} · {{ stateLabel(item) }}
          </span>
          <!-- 图片理解摘要：视觉降级链产出后就近展示（Apple 风 + 小清新蓝，lucide Eye） -->
          <span v-if="item.kind === 'IMAGE' && item.summary" class="media-insight"
            :title="item.summary">
            <Eye :size="11" aria-hidden="true" />
            <span class="media-insight-text">{{ item.summary }}</span>
          </span>
          <!-- 图片理解进行中占位：解析未就绪时给出明确预期 -->
          <span v-else-if="item.kind === 'IMAGE' && (item.state === 'parsing' || item.state === 'ready') && !item.summary"
            class="media-insight is-pending">
            <Eye :size="11" aria-hidden="true" />
            <span class="media-insight-text">{{ t('supportMedia.insightPending') }}…</span>
          </span>
          <!-- 文档解析预览：正文/清单摘要（lucide FileText），partial 截断用 FileWarning 明示 -->
          <span v-if="item.kind === 'DOCUMENT' && item.summary" class="media-insight"
            :title="item.summary">
            <FileText :size="11" aria-hidden="true" />
            <span class="media-insight-text">{{ item.summary }}</span>
          </span>
          <span v-if="item.partial" class="media-insight is-partial"
            :title="item.partialReason || t('supportMedia.partialTitle')">
            <FileWarning :size="11" aria-hidden="true" />
            <span class="media-insight-text">{{ t('supportMedia.partialTitle') }}{{ item.partialReason ? '：' + item.partialReason : '' }}</span>
          </span>
          <!-- 语音转写预览：解析 READY 后展示转写文本（Apple 风 + 小清新蓝），可折叠 -->
          <span v-if="item.kind === 'AUDIO' && item.transcript" class="media-transcript"
            :title="item.transcript">
            <AudioLines :size="11" aria-hidden="true" />
            <span class="media-transcript-text">{{ item.transcript }}</span>
          </span>
          <span v-else-if="item.kind === 'AUDIO' && item.state === 'parsing'" class="media-transcript is-pending">
            <AudioLines :size="11" aria-hidden="true" />
            <span class="media-transcript-text">{{ t('supportVoice.transcriptPending') }}…</span>
          </span>
          <span v-if="item.state === 'uploading'" class="media-progress" aria-hidden="true">
            <span class="media-progress-bar" :style="{ width: Math.max(2, item.progress) + '%' }"></span>
          </span>
        </div>
        <button class="media-remove" type="button" :aria-label="'移除附件 ' + item.file.name" @click="remove(item)">
          <CloseOutlined />
        </button>
      </div>
    </div>

    <div class="support-media-tools">
      <button class="media-btn" type="button" :disabled="disabled" title="添加附件：图片 / 语音 / 视频 / 文档"
        @click="pick">
        <PaperClipOutlined />
        <span>附件</span>
      </button>
      <button v-if="recorderSupported" class="media-btn" type="button"
        :class="{ 'is-recording': recording }" :disabled="disabled"
        :title="recording ? '停止录音并添加' : '录制语音（最长 60 秒）'" @click="toggleRecord">
        <AudioOutlined />
        <span>{{ recording ? `停止 ${recordSeconds}s` : '语音' }}</span>
      </button>
      <span class="media-retention" title="文件仅用于本次咨询，30 天后自动删除">30 天自动删除</span>
      <input ref="inputRef" class="media-input" type="file" multiple :accept="ACCEPT"
        :disabled="disabled" @change="onPick" />
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { AudioLines, Eye, FileText, FileWarning } from 'lucide-vue-next'
import {
  AudioOutlined,
  CloseOutlined,
  CustomerServiceOutlined,
  FileImageOutlined,
  FileTextOutlined,
  PaperClipOutlined,
  VideoCameraOutlined
} from '@ant-design/icons-vue'
import { t } from '../../i18n'
import {
  MAX_MEDIA_COUNT,
  MAX_POLL_TIMES,
  POLL_INTERVAL_MS,
  contentTypeOf,
  supportMediaApi,
  validateFile
} from '../../api/supportMedia'

const props = defineProps({
  visitorToken: { type: String, default: '' },
  // 会话号由后端按访客令牌归集；前端暂无可用值时留空，不影响预签名
  conversationNo: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
})

const ACCEPT = [
  '.jpg', '.jpeg', '.png', '.webp', '.gif',
  '.mp3', '.wav', '.m4a', '.ogg', '.webm',
  '.mp4', '.mov',
  '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.txt', '.csv', '.md', '.zip'
].join(',')

const CONSENT_KEY = 'll_support_media_consent_v1'
const MAX_RECORD_SECONDS = 60

const inputRef = ref(null)
const items = ref([])
const recording = ref(false)
const recordSeconds = ref(0)

let uidSeed = 0
let recordTimer = null
let recorder = null
let recordStream = null
let recordChunks = []
let pollTimer = null

// ---- 录音能力探测：仅支持 webm/opus 的浏览器展示麦克风按钮 ----
function detectRecorder() {
  if (typeof window === 'undefined') return false
  const Recorder = window.MediaRecorder
  if (!Recorder || !navigator.mediaDevices?.getUserMedia) return false
  if (typeof Recorder.isTypeSupported !== 'function') return true
  return Recorder.isTypeSupported('audio/webm;codecs=opus') || Recorder.isTypeSupported('audio/webm')
}
const recorderSupported = detectRecorder()

function iconOf(kind) {
  if (kind === 'IMAGE') return FileImageOutlined
  if (kind === 'AUDIO') return CustomerServiceOutlined
  if (kind === 'VIDEO') return VideoCameraOutlined
  return FileTextOutlined
}

function formatSize(bytes) {
  const size = Number(bytes || 0)
  if (size < 1024) return `${size}B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`
  return `${(size / (1024 * 1024)).toFixed(1)}MB`
}

function stateLabel(item) {
  if (item.state === 'uploading') return `上传中 ${item.progress || 0}%`
  if (item.state === 'parsing') return item.pollExhausted ? '解析中（较慢）' : '解析中'
  if (item.state === 'ready') return '就绪'
  return item.error ? `失败：${item.error}` : '失败'
}

// ---- 首次使用确认（localStorage 记住） ----
function ensureConsent() {
  try {
    if (localStorage.getItem(CONSENT_KEY) === '1') return Promise.resolve(true)
  } catch {
    // 隐私模式禁用 localStorage：退化为每次都确认
  }
  return new Promise((resolve) => {
    Modal.confirm({
      title: '上传附件前请确认',
      content: '附件仅用于本次咨询的识别与答复，超过 30 天后自动删除；你可随时在发送前移除。请勿上传身份证、银行卡、密码等敏感信息。',
      okText: '同意并继续',
      cancelText: '取消',
      onOk: () => {
        try { localStorage.setItem(CONSENT_KEY, '1') } catch {}
        resolve(true)
      },
      onCancel: () => resolve(false)
    })
  })
}

function pick() {
  if (props.disabled) return
  inputRef.value?.click()
}

function onPick(event) {
  const files = event.target?.files
  if (files && files.length) void addFiles(files)
  if (event.target) event.target.value = ''
}

/** 统一 File.type：预签名声明类型与 multipart 分片的 Content-Type 必须一致，否则策略校验失败 */
function normalizedFile(file) {
  const type = contentTypeOf(file)
  return file.type === type ? file : new File([file], file.name, { type })
}

/** 同名文件重命名，避免对象存储 key 冲突 */
function withUniqueName(file) {
  const used = new Set(items.value.map(item => item.file.name))
  if (!used.has(file.name)) return file
  const dot = file.name.lastIndexOf('.')
  const base = dot > 0 ? file.name.slice(0, dot) : file.name
  const ext = dot > 0 ? file.name.slice(dot) : ''
  let index = 2
  let name = `${base}-${index}${ext}`
  while (used.has(name)) {
    index += 1
    name = `${base}-${index}${ext}`
  }
  return new File([file], name, { type: contentTypeOf(file) })
}

function newMessageKey() {
  const rand = window.crypto?.randomUUID?.() || Math.random().toString(36).slice(2)
  return `media-${Date.now()}-${rand}`
}

/** 供父组件（输入区拖拽 / 录音）调用的统一入口 */
async function addFiles(rawFiles) {
  const files = Array.from(rawFiles || []).filter(Boolean)
  if (!files.length || props.disabled) return
  if (!(await ensureConsent())) return

  let remaining = MAX_MEDIA_COUNT - items.value.length
  if (remaining <= 0) {
    message.warning(`一条消息最多添加 ${MAX_MEDIA_COUNT} 个附件`)
    return
  }

  for (const raw of files) {
    if (remaining <= 0) {
      message.warning(`一条消息最多添加 ${MAX_MEDIA_COUNT} 个附件`)
      break
    }
    const check = validateFile(raw)
    if (!check.ok) {
      message.error(check.reason)
      continue
    }
    remaining -= 1
    const file = withUniqueName(normalizedFile(raw))
    // reactive 包装：上传进度/状态都在异步回调里改，必须走代理才能触发视图更新
    const item = reactive({
      uid: `media-${++uidSeed}`,
      file,
      kind: check.kind,
      state: 'uploading',
      progress: 0,
      mediaNo: '',
      remoteStatus: '',
      error: '',
      summary: '',
      partial: false,
      partialReason: '',
      transcript: '',
      previewUrl: check.kind === 'IMAGE' ? URL.createObjectURL(file) : '',
      pollTicks: 0,
      pollExhausted: false,
      polling: false
    })
    items.value.push(item)
    void startUpload(item)
  }
}

async function startUpload(item) {
  try {
    const presign = await supportMediaApi.presign({
      visitorToken: props.visitorToken,
      conversationNo: props.conversationNo || '',
      messageKey: newMessageKey(),
      kind: item.kind,
      fileName: item.file.name,
      contentType: contentTypeOf(item.file),
      size: item.file.size
    })
    if (!presign || !presign.mediaNo) throw new Error('服务端未返回媒体编号')
    if (presign.maxBytes && item.file.size > Number(presign.maxBytes)) {
      throw new Error('文件超过服务端允许的大小')
    }
    item.mediaNo = String(presign.mediaNo)

    await supportMediaApi.uploadToStorage({
      action: presign.action,
      fields: presign.fields,
      file: item.file,
      onProgress: (percent) => { item.progress = percent }
    })
    item.progress = 100

    const confirmed = await supportMediaApi.confirm(item.mediaNo, props.visitorToken)
    const confirmedStatus = String(confirmed?.status || 'UPLOADED').toUpperCase()
    item.remoteStatus = confirmedStatus
    // confirm 可能已同步完成解析（小文件快路径）：图片理解摘要/文档预览/语音转写就地展示
    item.summary = String(confirmed?.summary || '').trim()
    applyPartial(item, confirmed)
    if (item.kind === 'AUDIO') {
      item.transcript = String(confirmed?.asrText || '').trim()
    }
    if (confirmedStatus === 'READY') {
      item.state = 'ready'
    } else if (confirmedStatus === 'FAILED' || confirmedStatus === 'EXPIRED') {
      item.state = 'failed'
      item.error = confirmed?.errorMessage || (confirmedStatus === 'EXPIRED' ? '文件已过期，请重试' : '解析失败')
      revokePreview(item)
    } else {
      item.state = 'parsing'
    }
  } catch (err) {
    item.state = 'failed'
    // 上传失败撤销缩略图，避免占用内存
    revokePreview(item)
    item.error = err?.message || '上传失败'
    message.error(`「${item.file.name}」上传失败：${item.error}`)
  }
}

function needsPoll(item) {
  return !!item.mediaNo
    && (item.state === 'parsing' || item.state === 'uploaded')
    && !item.pollExhausted
    && !item.polling
}

async function refreshItem(item) {
  item.polling = true
  try {
    const data = await supportMediaApi.status(item.mediaNo, props.visitorToken)
    const status = String(data?.status || '').toUpperCase()
    item.remoteStatus = status
    item.summary = String(data?.summary || data?.ocrText || data?.asrText || '').trim()
    applyPartial(item, data)
    // 语音附件：转写全文（asr_text 优先于截断摘要）就近展示在附件条内
    if (item.kind === 'AUDIO') {
      const transcript = String(data?.asrText || '').trim()
      item.transcript = transcript || (status === 'READY' ? '' : item.transcript)
      if (status === 'READY' && !transcript) item.transcriptEmpty = true
    }
    if (status === 'READY') {
      item.state = 'ready'
    } else if (status === 'FAILED' || status === 'EXPIRED') {
      item.state = 'failed'
      item.error = data?.error || data?.errorMessage || (status === 'EXPIRED' ? '文件已过期，请重新上传' : '解析失败')
      revokePreview(item)
      message.error(`「${item.file.name}」${item.error}`)
    } else {
      // INIT / UPLOADED / PARSING 都视为解析未完成
      item.state = 'parsing'
    }
  } catch {
    // 轮询抖动不判失败，下一轮继续；超过上限后停在「解析中（较慢）」
  } finally {
    item.polling = false
  }
}

/** P2 超限显式化：entitiesJson.partial → 条目警示（lucide FileWarning），消灭静默丢弃 */
function applyPartial(item, data) {
  if (!data?.partial) {
    // 无 partial 标记时不清除既有值（轮询中间态可能不带该字段）
    return
  }
  item.partial = true
  item.partialReason = String(data?.partialReason || '').trim()
}

function pollStatuses() {
  for (const item of items.value) {
    if (!needsPoll(item)) continue
    if (item.pollTicks >= MAX_POLL_TIMES) {
      item.pollExhausted = true
      continue
    }
    item.pollTicks += 1
    void refreshItem(item)
  }
}

pollTimer = window.setInterval(pollStatuses, POLL_INTERVAL_MS)

function revokePreview(item) {
  if (!item?.previewUrl) return
  try { URL.revokeObjectURL(item.previewUrl) } catch {}
  item.previewUrl = ''
}

function remove(item) {
  const index = items.value.indexOf(item)
  if (index >= 0) items.value.splice(index, 1)
  if (item?.mediaNo) {
    supportMediaApi.discard(item.mediaNo, props.visitorToken).catch(() => {})
  }
  revokePreview(item)
}

/** 发送后清空已提交的附件，并释放缩略图 objectURL */
function clearSent() {
  for (const item of items.value) revokePreview(item)
  items.value = []
}

/**
 * 已确认上传、可用于本次提问的媒体编号（READY / UPLOADED）。
 * 后端 mediaNo 是形如 "SM20261108123456AB12CD" 的字符串单号，故按字符串数组提交。
 */
function readyMediaIds() {
  return items.value
    .filter(item => item.mediaNo && (item.remoteStatus === 'READY' || item.remoteStatus === 'UPLOADED'))
    .map(item => item.mediaNo)
}

function hasUploading() {
  return items.value.some(item => item.state === 'uploading')
}

function hasParsing() {
  return items.value.some(item => item.state === 'parsing')
}

/** 本次待发送语音的转写文本（按附件顺序），供父组件在消息气泡内就近展示 */
function readyTranscripts() {
  return items.value
    .filter(item => item.kind === 'AUDIO' && item.mediaNo && item.transcript)
    .map(item => ({ mediaNo: item.mediaNo, name: item.file.name, text: item.transcript }))
}

/**
 * P2 图像/文件理解产物（按附件顺序）：图片理解摘要与文档解析预览，供父组件
 * 在消息气泡内与附件就近展示——识别结果不止进 AI 理解链，用户也能直接看到。
 */
function readyMediaInsights() {
  return items.value
    .filter(item => item.mediaNo && item.summary
      && (item.kind === 'IMAGE' || item.kind === 'DOCUMENT')
      && (item.remoteStatus === 'READY' || item.remoteStatus === 'UPLOADED'))
    .map(item => ({
      mediaNo: item.mediaNo,
      kind: item.kind,
      name: item.file.name,
      text: item.summary,
      partial: item.partial,
      partialReason: item.partialReason
    }))
}

// ---- 录音 ----
async function toggleRecord() {
  if (recording.value) {
    stopRecord()
    return
  }
  if (props.disabled) return
  if (!window.isSecureContext && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
    message.error('浏览器安全限制：非本地安全上下文（HTTP局域网访问）无法启用麦克风，请使用 http://localhost:5173 访问')
    return
  }
  if (!navigator?.mediaDevices?.getUserMedia) {
    message.error('当前浏览器环境不支持麦克风采集或权限受限，请使用 Chrome/Edge 浏览器并检查麦克风权限')
    return
  }
  if (!(await ensureConsent())) return
  try {
    recordStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mimeType = window.MediaRecorder.isTypeSupported?.('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : 'audio/webm'
    recordChunks = []
    recorder = new window.MediaRecorder(recordStream, { mimeType })
    recorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) recordChunks.push(event.data)
    }
    recorder.onstop = () => {
      const blob = new Blob(recordChunks, { type: 'audio/webm' })
      recordChunks = []
      releaseRecordStream()
      if (blob.size > 0) {
        const file = new File([blob], `voice-${Date.now()}.webm`, { type: 'audio/webm' })
        void addFiles([file])
      }
    }
    recorder.start()
    recording.value = true
    recordSeconds.value = 0
    recordTimer = window.setInterval(() => {
      recordSeconds.value += 1
      if (recordSeconds.value >= MAX_RECORD_SECONDS) stopRecord()
    }, 1000)
  } catch {
    releaseRecordStream()
    message.error('无法访问麦克风，请检查浏览器权限设置')
  }
}

function stopRecord() {
  if (recordTimer) {
    window.clearInterval(recordTimer)
    recordTimer = null
  }
  recording.value = false
  try {
    if (recorder && recorder.state !== 'inactive') recorder.stop()
  } catch {
    releaseRecordStream()
  }
  recorder = null
}

function releaseRecordStream() {
  if (recordStream) {
    try { recordStream.getTracks().forEach(track => track.stop()) } catch {}
    recordStream = null
  }
}

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = null
  if (recordTimer) window.clearInterval(recordTimer)
  recordTimer = null
  try {
    if (recorder && recorder.state !== 'inactive') recorder.stop()
  } catch {}
  releaseRecordStream()
  for (const item of items.value) revokePreview(item)
})

defineExpose({ addFiles, readyMediaIds, hasUploading, hasParsing, readyTranscripts, readyMediaInsights, clearSent })
</script>

<style scoped>
.support-media {
  --sm-border: #e4e7ec;
  --sm-chip-bg: #f8f9fb;
  --sm-ink: #182230;
  --sm-muted: #667085;
  --sm-accent: #1e6eb8;
  --sm-danger: #d92d20;
  --sm-ok: #059669;
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 8px;
}

.support-media-chips { display: flex; flex-direction: column; gap: 6px; }
.media-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--sm-border);
  border-radius: 10px;
  background: var(--sm-chip-bg);
  color: var(--sm-ink);
}
.media-chip.state-failed { border-color: rgba(217, 45, 32, 0.35); background: rgba(217, 45, 32, 0.05); }
.media-chip.state-ready { border-color: rgba(5, 150, 105, 0.28); }

.media-thumb {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  object-fit: cover;
  background: #eef2f7;
}
.media-type-icon {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: rgba(30, 110, 184, 0.08);
  color: var(--sm-accent);
  font-size: 16px;
}

.media-meta { display: flex; flex: 1 1 auto; flex-direction: column; gap: 2px; min-width: 0; }
.media-name {
  overflow: hidden;
  color: var(--sm-ink);
  font-size: 11.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.media-sub {
  overflow: hidden;
  color: var(--sm-muted);
  font-size: 10px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.media-sub.is-error { color: var(--sm-danger); }
.state-ready .media-sub { color: var(--sm-ok); }

/* 语音转写预览：小清新蓝底、圆角胶囊，Apple 风轻质感（无 emoji，lucide AudioLines） */
.media-transcript {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 2px;
  padding: 4px 7px;
  border-radius: 8px;
  background: rgba(30, 110, 184, 0.07);
  color: var(--sm-accent);
  font-size: 10.5px;
  line-height: 1.45;
}
.media-transcript.is-pending { color: var(--sm-muted); font-style: normal; opacity: 0.85; }
.media-transcript-text {
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-word;
  color: var(--sm-ink);
  opacity: 0.85;
}

/* 图片理解摘要 / 文档解析预览（P2）：与语音转写同款轻蓝衬底，lucide Eye/FileText/FileWarning */
.media-insight {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 2px;
  padding: 4px 7px;
  border-radius: 8px;
  background: rgba(30, 110, 184, 0.07);
  color: var(--sm-accent);
  font-size: 10.5px;
  line-height: 1.45;
}
.media-insight.is-pending { color: var(--sm-muted); opacity: 0.85; }
.media-insight.is-partial { background: rgba(217, 45, 32, 0.06); color: var(--sm-danger); }
.media-insight-text {
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-word;
  color: var(--sm-ink);
  opacity: 0.85;
}

.media-progress {
  position: relative;
  display: block;
  height: 3px;
  margin-top: 2px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(30, 110, 184, 0.14);
}
.media-progress-bar { display: block; height: 100%; border-radius: inherit; background: var(--sm-accent); transition: width 0.2s ease; }

.media-remove {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--sm-muted);
  font-size: 11px;
  cursor: pointer;
}
.media-remove:hover { background: rgba(19, 35, 58, 0.08); color: var(--sm-ink); }

.support-media-tools { display: flex; align-items: center; gap: 6px; }
.media-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--sm-border);
  border-radius: 999px;
  background: #fff;
  color: var(--sm-muted);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}
.media-btn:hover:not(:disabled) { border-color: #b8c6d8; color: var(--sm-ink); background: #f5f8fc; }
.media-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.media-btn.is-recording { border-color: rgba(217, 45, 32, 0.4); color: var(--sm-danger); background: rgba(217, 45, 32, 0.06); }
.media-btn.is-recording :deep(svg) { animation: media-pulse 1s ease-in-out infinite; }
@keyframes media-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }

.media-retention { margin-left: auto; color: var(--sm-muted); font-size: 9.5px; white-space: nowrap; }
.media-input { display: none; }

:global([data-theme='dark']) .support-media {
  --sm-border: #31405a;
  --sm-chip-bg: #1a2536;
  --sm-ink: #e8edf5;
  --sm-muted: #8fa0b5;
  --sm-accent: #7fb6e8;
  --sm-danger: #ff8a7a;
  --sm-ok: #34d399;
}
:global([data-theme='dark']) .media-btn { background: #16202e; }
:global([data-theme='dark']) .media-btn:hover:not(:disabled) { background: #1f2c40; }
:global([data-theme='dark']) .media-remove:hover { background: rgba(255, 255, 255, 0.1); }
:global([data-theme='dark']) .media-thumb { background: #263349; }

@media (prefers-reduced-motion: reduce) {
  .media-progress-bar { transition: none; }
  .media-btn.is-recording :deep(svg) { animation: none; }
}
</style>
