<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { attachmentApi } from '../api'
import { locale } from '../i18n'

const props = defineProps({
  bizType: { type: String, required: true },
  bizId: { type: [Number, String], default: null },
  title: { type: String, default: '业务材料' },
  canUpload: { type: Boolean, default: true },
  canBind: { type: Boolean, default: false },
  canDelete: { type: Boolean, default: true },
  // 运营台看不到挂起件（按业务对象只列已绑定），所以上传即绑定，否则运营传的材料永远不可见
  bindOnUpload: { type: Boolean, default: false }
})

const items = ref([])
const MAX_FILE_BYTES = 25 * 1024 * 1024
const loading = ref(false)
const uploading = ref(false)
const binding = ref(false)
const percent = ref(0)
const fileInput = ref(null)

// 列表最多返回 50 条（后端硬上限），提示语整体成句避免被插值拆散
const hint = computed(() => (locale.value === 'en-US'
  ? 'Up to the 50 most recent files are listed.'
  : '最多显示最近 50 份材料'))

const pendingCount = computed(() => items.value.filter(i => i.status === 'PENDING').length)

function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

// bizId 到位后会再加载一次，两个请求的返回顺序不保证：只认最后一次发起的
let loadSeq = 0
// 换了业务域/业务对象就立刻清空：抽屉复用上一个商户的清单，会让审核人把别人的材料当成本单的
let lastKey = null

// 缩略图在消费侧异步生成：列表里还有在途原件时自动再拉一次，
// 否则审核人要手动刷新页面才看得到刚上传材料的预览。轮数封顶，避免长驻定时器。
const THUMB_POLL_MS = 5000
const THUMB_POLL_MAX_ROUNDS = 8
let thumbTimer = null
let thumbRounds = 0

function thumbInFlight() {
  return items.value.some(i => i.thumbStatus === 'PENDING' || i.thumbStatus === 'PROCESSING')
}

function scheduleThumbPoll() {
  clearTimeout(thumbTimer)
  if (!thumbInFlight() || thumbRounds >= THUMB_POLL_MAX_ROUNDS) return
  thumbRounds += 1
  thumbTimer = setTimeout(load, THUMB_POLL_MS)
}

async function load() {
  const key = `${props.bizType}#${props.bizId ?? ''}`
  const seq = ++loadSeq
  if (key !== lastKey) {
    items.value = []
    lastKey = key
    thumbRounds = 0
  }
  loading.value = true
  try {
    const list = await attachmentApi.list({ bizType: props.bizType, bizId: props.bizId ?? undefined })
    if (seq !== loadSeq) return
    // EXPIRED 是墓碑行（回收或运营下线后的留痕），既不能绑定也不能下载，列出来只会让人误点
    items.value = (list || []).filter(i => i.status !== 'EXPIRED')
  } catch (e) {
    // 拦截器已提示；失败时清空，不留下上一个对象的清单冒充当前对象的
    if (seq === loadSeq) items.value = []
  } finally {
    if (seq === loadSeq) {
      loading.value = false
      scheduleThumbPoll()
    }
  }
}

async function bindTo(attachmentNo) {
  await attachmentApi.bind({
    attachmentNo,
    bizType: props.bizType,
    bizId: Number(props.bizId)
  })
}

function pick() {
  fileInput.value?.click()
}

async function onFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  // 与后端 spring.servlet.multipart.max-file-size 同档：超了先挡在前端，省一次必然失败的上传
  if (file.size > MAX_FILE_BYTES) {
    message.error(locale.value === 'en-US' ? 'The file is larger than 25 MB' : '材料不能超过 25MB')
    return
  }
  uploading.value = true
  percent.value = 0
  try {
    const view = await attachmentApi.upload(props.bizType, file, p => { percent.value = p })
    if (props.bindOnUpload && props.bizId != null && view?.no) {
      await bindTo(view.no)
    }
    message.success(locale.value === 'en-US' ? 'Uploaded' : '上传成功')
    await load()
  } catch (e) {
    // 错误提示由 request.js 统一 toast，这里只需结束 loading 状态
  } finally {
    uploading.value = false
  }
}

async function bindAll() {
  const pending = items.value.filter(i => i.status === 'PENDING')
  if (!pending.length || !props.bizId) return
  binding.value = true
  try {
    // 逐条串行会在第一个 429/403 处中断，后面的材料停在待提交却看不到原因；并发跑完再统一重取
    const results = await Promise.allSettled(pending.map(i => bindTo(i.no)))
    const bound = results.filter(r => r.status === 'fulfilled').length
    if (bound === pending.length) {
      message.success(locale.value === 'en-US' ? 'Materials bound' : '材料已绑定')
    } else {
      message.warning(locale.value === 'en-US'
        ? `${bound} of ${pending.length} files bound; the rest failed`
        : `${bound}/${pending.length} 份已绑定，其余未成功`)
    }
  } finally {
    await load()
    binding.value = false
  }
}

async function remove(item) {
  try {
    await attachmentApi.remove(item.no)
    message.success(locale.value === 'en-US' ? 'Material removed' : '材料已删除')
  } catch (e) {
    // 拦截器已提示（已交付材料不可自行下线等）
  }
  await load()
}

watch(() => [props.bizType, props.bizId], load, { immediate: true })

onBeforeUnmount(() => clearTimeout(thumbTimer))
</script>

<template>
  <div class="attach-panel">
    <div class="attach-head">
      <span class="attach-title">{{ title }}</span>
      <div class="attach-actions">
        <span v-if="uploading" class="attach-percent" data-no-translate>{{ percent }}%</span>
        <a-button size="small" :loading="uploading" :disabled="!canUpload" @click="pick">
          {{ uploading ? '上传中' : '上传材料' }}
        </a-button>
        <input ref="fileInput" type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" hidden @change="onFile" />
      </div>
    </div>

    <a-alert class="attach-hint" type="info" show-icon :message="hint" />

    <a-spin :spinning="loading">
      <a-empty v-if="!items.length" :description="'暂无材料'" />
      <ul v-else class="attach-list">
        <li v-for="item in items" :key="item.no">
          <!-- 文件名是用户数据，整行加 data-no-translate 防止词典把「营业执照」等词译坏 -->
          <img
            v-if="item.thumbUrl"
            class="attach-thumb"
            :src="item.thumbUrl"
            :alt="'预览'"
            loading="lazy"
          />
          <a-tag
            v-else-if="item.thumbStatus === 'PENDING' || item.thumbStatus === 'PROCESSING'"
            color="blue"
          >{{ '预览生成中' }}</a-tag>
          <span class="attach-name" data-no-translate>{{ item.fileName }}</span>
          <a-tag :color="item.status === 'BOUND' ? 'green' : 'orange'">
            {{ item.status === 'BOUND' ? '已绑定' : '待提交' }}
          </a-tag>
          <span class="attach-size" data-no-translate>{{ formatSize(item.size) }}</span>
          <a :href="item.url" target="_blank" rel="noopener">{{ '下载' }}</a>
          <a-popconfirm
            v-if="canDelete"
            :title="'删除该材料？'"
            :ok-text="'删除'"
            :cancel-text="'取消'"
            @confirm="remove(item)"
          >
            <a-button type="link" size="small" danger>{{ '删除' }}</a-button>
          </a-popconfirm>
        </li>
      </ul>
    </a-spin>

    <div v-if="canBind && pendingCount" class="attach-foot">
      <a-button size="small" type="primary" :loading="binding" @click="bindAll">
        {{ '绑定到当前对象' }}
      </a-button>
    </div>
  </div>
</template>

<style scoped>
.attach-panel { display: flex; flex-direction: column; gap: 10px; }
.attach-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.attach-title { font-weight: 600; color: var(--ll-ink, #0F172A); }
.attach-actions { display: flex; align-items: center; gap: 8px; }
.attach-percent { font-variant-numeric: tabular-nums; color: #64748b; font-size: 12px; }
.attach-hint { padding: 0; }
.attach-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.attach-list li { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.attach-name { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attach-thumb {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid rgba(19, 35, 58, .12);
  background: #fff;
}
.attach-size { color: #64748b; font-size: 12px; }
.attach-foot { display: flex; justify-content: flex-end; }
</style>
