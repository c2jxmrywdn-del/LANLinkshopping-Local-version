<template>
  <div class="reu-root">
    <!-- 上传区：虚线框 + UploadCloud，点击或拖拽 -->
    <a-upload
      multiple
      :show-upload-list="false"
      :before-upload="onBeforeUpload"
      :custom-request="onCustomRequest"
      accept=".jpg,.jpeg,.png,.webp,.gif,.mp4,.mov,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.zip,.txt,.csv"
    >
      <div class="reu-dropzone" :class="{ 'is-disabled': atMaxCount }">
        <UploadCloud :size="22" class="reu-dropzone-icon" />
        <span class="reu-dropzone-text">{{ t('点击或拖拽上传') }}</span>
        <span v-if="atMaxCount" class="reu-dropzone-hint">{{ t('最多上传 9 项凭证') }}</span>
      </div>
    </a-upload>

    <!-- 自管列表：图标 | 名称 | 体积 | 进度 | 重试 | 删除 -->
    <ul v-if="items.length" class="reu-list">
      <li v-for="it in items" :key="it.uid" class="reu-item" :class="{ 'is-error': it.status === 'error' }">
        <component :is="iconFor(it.kind)" :size="16" class="reu-item-icon" />
        <span class="reu-item-name" :title="it.name">{{ it.name }}</span>
        <span class="reu-item-size">{{ formatBytes(it.size) }}</span>
        <span v-if="it.status === 'uploading'" class="reu-item-progress">{{ it.progress }}%</span>
        <span v-else-if="it.status === 'error'" class="reu-item-error">{{ it.error }}</span>
        <span v-else class="reu-item-done-dot" aria-hidden="true"></span>
        <button
          v-if="it.status === 'error'"
          type="button"
          class="reu-item-action"
          :title="t('凭证上传失败，请重试')"
          @click="retry(it)"
        >
          <RotateCcw :size="14" />
        </button>
        <button type="button" class="reu-item-action" title="删除" @click="remove(it.uid)">
          <Trash2 :size="14" />
        </button>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { FileText, Image as ImageIcon, RotateCcw, Trash2, UploadCloud, Video } from 'lucide-vue-next'
import { chatApi } from '../../api'
import { t } from '../../i18n'
import {
  formatBytes,
  pickEvidenceKind,
  toEvidenceItem,
  validateEvidenceFile,
} from '../../utils/refundEvidence'
import { REFUND_EVIDENCE_MAX_COUNT } from '../../utils/validationRules'

const props = defineProps({
  /** 已就绪证据数组 [{type,url,name}]（v-model:evidences） */
  evidences: { type: Array, default: () => [] },
  maxCount: { type: Number, default: REFUND_EVIDENCE_MAX_COUNT },
})
const emit = defineEmits(['update:evidences', 'status'])

let uidSeed = 0
/** 自管上传条目：{ uid, kind, name, size, file, status: uploading|done|error, progress, url, error } */
const items = ref([])

const doneItems = computed(() => items.value.filter(it => it.status === 'done'))
const atMaxCount = computed(() => doneItems.value.length >= props.maxCount)
const uploadingCount = computed(() => items.value.filter(it => it.status === 'uploading').length)
const errorCount = computed(() => items.value.filter(it => it.status === 'error').length)

// 已就绪项外抛（v-model:evidences）；上传/失败计数外抛供弹窗提交门槛拦截
watch(doneItems, (list) => {
  emit('update:evidences', list.map(it => toEvidenceItem(it.kind, it.url, it.name)))
}, { deep: true })
watch([uploadingCount, errorCount], ([up, err]) => {
  emit('status', { uploading: up, error: err, done: doneItems.value.length })
}, { immediate: true })

function iconFor(kind) {
  if (kind === 'image') return ImageIcon
  if (kind === 'video') return Video
  return FileText
}

/** antd 预检：白名单/体积不过关提示后拒绝（返回 false 阻断上传；列表自管，无视觉残留） */
function onBeforeUpload(file) {
  if (atMaxCount.value) {
    message.warning(t('最多上传 9 项凭证'))
    return false
  }
  const err = validateEvidenceFile(file)
  if (err) {
    message.warning(err)
    return false
  }
  return true
}

/** customRequest 接管：走 chatApi.upload（买家侧登录端点），进度回写条目 */
function onCustomRequest({ file }) {
  const kind = pickEvidenceKind(file)
  if (!kind) {
    message.warning('不支持的证据格式（仅图片 / MP4·MOV 视频 / PDF·文档·压缩包文件）')
    return
  }
  const item = {
    uid: ++uidSeed,
    kind,
    name: String(file.name || '未命名文件'),
    size: file.size,
    file,
    status: 'uploading',
    progress: 0,
    url: '',
    error: '',
  }
  items.value.push(item)
  uploadItem(item)
}

async function uploadItem(item) {
  item.status = 'uploading'
  item.error = ''
  item.progress = 0
  try {
    const res = await chatApi.upload(item.file, item.kind, (pct) => { item.progress = pct })
    item.url = res?.url || ''
    item.name = res?.name || item.name
    if (!item.url) throw new Error('empty url')
    item.status = 'done'
  } catch (e) {
    item.status = 'error'
    item.error = t('凭证上传失败，请重试')
  }
}

function retry(item) {
  uploadItem(item)
}

function remove(uid) {
  items.value = items.value.filter(it => it.uid !== uid)
}

defineExpose({
  /** 弹窗提交门槛：存在上传中或失败项时阻止提交 */
  hasBlocking: () => uploadingCount.value > 0 || errorCount.value > 0,
  /** 弹窗重开时清空自管列表（父组件重置 evidences 后调用） */
  clear: () => { items.value = [] },
})
</script>

<style scoped>
.reu-root {
  width: 100%;
}

.reu-dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ll-space-2);
  width: 100%;
  min-height: 92px;
  padding: var(--ll-space-3);
  border: 1px dashed var(--ll-border-strong);
  border-radius: var(--ll-radius-md);
  background: var(--ll-card-soft);
  color: var(--ll-text-secondary);
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease;
}
.reu-dropzone:hover {
  border-color: var(--ll-skyblue-border);
  background: var(--ll-surface-raised);
}
.reu-dropzone.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.reu-dropzone-icon {
  color: var(--ll-skyblue-border);
}
.reu-dropzone-text {
  font-size: 12.5px;
  color: var(--ll-text-primary);
}
.reu-dropzone-hint {
  font-size: 11px;
  color: var(--ll-amber-strong);
}

.reu-list {
  list-style: none;
  margin: var(--ll-space-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-2);
}

.reu-item {
  display: flex;
  align-items: center;
  gap: var(--ll-space-2);
  padding: var(--ll-space-2) var(--ll-space-3);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  background: var(--ll-surface-raised);
  font-size: 12px;
  color: var(--ll-text-secondary);
}
.reu-item.is-error {
  border-color: var(--ll-coral);
}
.reu-item-icon {
  color: var(--ll-skyblue-border);
  flex-shrink: 0;
}
.reu-item.is-error .reu-item-icon {
  color: var(--ll-coral);
}
.reu-item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ll-text-primary);
}
.reu-item-size {
  flex-shrink: 0;
  color: var(--ll-text-tertiary);
}
.reu-item-progress {
  flex-shrink: 0;
  color: var(--ll-skyblue-border);
  font-variant-numeric: tabular-nums;
}
.reu-item-error {
  flex-shrink: 0;
  color: var(--ll-coral);
}
.reu-item-done-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ll-success);
}
.reu-item-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--ll-radius-md);
  background: transparent;
  color: var(--ll-text-tertiary);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.reu-item-action:hover {
  background: var(--ll-card-soft);
  color: var(--ll-text-primary);
}

/* 移动端断点与仓库惯例对齐：≤991 纵向堆叠，≤700 图标缩至 48px */
@media (max-width: 991px) {
  .reu-dropzone {
    min-height: 80px;
  }
}
@media (max-width: 700px) {
  .reu-dropzone {
    min-height: 48px;
    flex-direction: row;
    padding: var(--ll-space-2) var(--ll-space-3);
  }
  .reu-dropzone-icon {
    width: 20px;
    height: 20px;
  }
  .reu-item {
    flex-wrap: wrap;
  }
  .reu-item-name {
    flex-basis: 100%;
    white-space: normal;
  }
}
</style>
