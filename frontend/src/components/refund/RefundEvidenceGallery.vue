<template>
  <div class="reg-root">
    <div class="reg-head">
      <FileText :size="14" class="reg-head-icon" />
      <span class="reg-head-text">{{ title }}</span>
      <span v-if="list.length" class="reg-head-count">{{ list.length }}</span>
    </div>

    <div v-if="!list.length" class="reg-empty">{{ t('未提交凭证') }}</div>

    <div v-else class="reg-grid">
      <template v-for="(it, idx) in list" :key="idx">
        <!-- 图片：缩略图点击放大 -->
        <div v-if="it.type === 'image'" class="reg-cell">
          <a-image
            :src="resolveStorageUrl(it.url)"
            :alt="it.name"
            :width="96"
            :height="72"
            fit="cover"
            class="reg-img"
          />
          <span class="reg-name" :title="it.name">{{ it.name }}</span>
        </div>

        <!-- 视频：内联预览 -->
        <div v-else-if="it.type === 'video'" class="reg-cell reg-cell-wide">
          <video :src="resolveStorageUrl(it.url)" controls preload="metadata" class="reg-video"></video>
          <span class="reg-name" :title="it.name">{{ it.name }}</span>
        </div>

        <!-- 文件/.bin：图标 + 名称 + 下载 -->
        <div v-else class="reg-cell reg-cell-file">
          <FileText :size="18" class="reg-file-icon" />
          <span class="reg-name" :title="it.name">{{ it.name }}</span>
          <a :href="resolveStorageUrl(it.url)" :download="it.name" class="reg-download" :title="t('下载附件')">
            <Download :size="14" />
            <span>{{ t('下载附件') }}</span>
          </a>
          <span v-if="isBin(it.url)" class="reg-bin-hint">{{ t('.bin 附件下载后按原格式重命名即可打开') }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Download, FileText } from 'lucide-vue-next'
import { t } from '../../i18n'
import { parseEvidences } from '../../utils/refundEvidence'
import { resolveStorageUrl } from '../../utils/storageUrl'

const props = defineProps({
  /** 证据 JSON 字符串（链A t_order.refund_evidences / 链B t_order_rma.proof_images） */
  evidences: { type: String, default: '' },
  title: { type: String, default: '' },
})

const list = computed(() => parseEvidences(props.evidences))
const title = computed(() => props.title || t('买家提交的退款凭证'))

/** 落盘形态为 .bin（Word/压缩包等 zip 容器），如实说明重命名打开方式 */
function isBin(url) {
  return /\.bin$/i.test(String(url || '').split('?')[0])
}
</script>

<style scoped>
.reg-root {
  width: 100%;
}

.reg-head {
  display: flex;
  align-items: center;
  gap: var(--ll-space-2);
  margin-bottom: var(--ll-space-2);
}
.reg-head-icon {
  color: var(--ll-skyblue-border);
}
.reg-head-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--ll-text-primary);
}
.reg-head-count {
  padding: 0 7px;
  border-radius: 9px;
  background: var(--ll-skyblue);
  color: var(--ll-skyblue-on);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
}

.reg-empty {
  padding: var(--ll-space-3);
  border: 1px dashed var(--ll-border);
  border-radius: var(--ll-radius-md);
  font-size: 12px;
  color: var(--ll-text-tertiary);
  text-align: center;
}

.reg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--ll-space-2);
}

.reg-cell {
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-1, 4px);
  padding: var(--ll-space-2);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  background: var(--ll-card-soft);
}
.reg-cell-wide {
  grid-column: 1 / -1;
}
.reg-img {
  border-radius: var(--ll-radius-md);
  overflow: hidden;
}
.reg-video {
  width: 100%;
  max-width: 420px;
  border-radius: var(--ll-radius-md);
  background: var(--ll-card);
}
.reg-cell-file {
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
}
.reg-file-icon {
  color: var(--ll-skyblue-border);
  flex-shrink: 0;
}
.reg-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ll-text-primary);
}
.reg-download {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  color: var(--ll-skyblue-border);
  font-size: 12px;
  text-decoration: none;
}
.reg-download:hover {
  color: var(--ll-skyblue-hover);
}
.reg-bin-hint {
  flex-basis: 100%;
  font-size: 11px;
  color: var(--ll-amber-strong);
}

@media (max-width: 991px) {
  .reg-grid {
    grid-template-columns: 1fr;
  }
  .reg-video {
    max-width: 100%;
  }
}
@media (max-width: 700px) {
  .reg-cell {
    padding: var(--ll-space-2);
  }
  .reg-name {
    white-space: normal;
  }
}
</style>
