<template>
  <div class="mm-rag">
    <!-- 服务不可用时用常驻告警，而不是一次性 toast：用户可能过几分钟才回来操作 -->
    <a-alert
      v-if="!healthy"
      type="warning"
      show-icon
      class="mm-rag__alert"
      :message="healthMessage"
    >
      <template #action>
        <a-button size="small" @click="goTextKb">改用文本知识库</a-button>
        <a-button size="small" type="link" @click="checkHealth">重新探测</a-button>
      </template>
    </a-alert>

    <a-row :gutter="16">
      <!-- 左：写入 -->
      <a-col :xs="24" :lg="10">
        <a-card title="写入多模态索引" size="small">
          <a-form layout="vertical">
            <a-form-item label="文档 ID" required>
              <a-input v-model:value="ingest.docId" placeholder="唯一标识，如 product-manual-01" allow-clear />
            </a-form-item>
            <a-form-item label="正文（空行分段，每段独立成块）">
              <a-textarea
                v-model:value="ingest.text"
                :rows="10"
                placeholder="粘贴文档正文。段落之间用空行分隔——服务端按 \n\n 切块，不切的话整篇会变成一个大块，检索精度会明显下降。"
              />
            </a-form-item>
            <a-space>
              <a-button type="primary" :loading="ingesting" :disabled="!canIngest" @click="doIngest">
                写入索引
              </a-button>
              <span class="mm-rag__hint">单篇约 10-60 秒（含实体抽取）</span>
            </a-space>
          </a-form>
        </a-card>
      </a-col>

      <!-- 右：检索 -->
      <a-col :xs="24" :lg="14">
        <a-card title="多模态检索" size="small">
          <a-form layout="vertical">
            <a-form-item label="问题">
              <a-textarea
                v-model:value="query.question"
                :rows="3"
                placeholder="例如：这款商品的保修条款是什么？"
              />
            </a-form-item>
            <a-form-item label="检索模式">
              <a-radio-group v-model:value="query.mode" button-style="solid">
                <a-radio-button value="naive">向量</a-radio-button>
                <a-radio-button value="hybrid">混合</a-radio-button>
                <a-radio-button value="local">实体邻域</a-radio-button>
                <a-radio-button value="global">关系摘要</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-space>
              <a-button type="primary" :loading="querying" :disabled="!query.question.trim()" @click="doQuery">
                检索
              </a-button>
              <a-input-number v-model:value="query.topK" :min="1" :max="20" addon-before="TopK" />
            </a-space>
          </a-form>

          <a-divider />
          <a-spin :spinning="querying">
            <div v-if="answer" class="mm-rag__answer">{{ answer }}</div>
            <a-empty v-else description="尚无检索结果" :image="false" />
          </a-spin>
        </a-card>
      </a-col>
    </a-row>

    <!-- 最近一次写入结果 -->
    <a-card v-if="lastIngest" title="最近一次写入" size="small" class="mm-rag__last">
      <a-descriptions :column="3" size="small">
        <a-descriptions-item label="文档 ID">{{ lastIngest.docId }}</a-descriptions-item>
        <a-descriptions-item label="切块数">{{ lastIngest.chunks }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ lastIngest.status }}</a-descriptions-item>
      </a-descriptions>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { kbApi } from '../../api'

/**
 * 多模态知识库管理页（RAG-Anything）。
 *
 * <p>与现有文本知识库的关系：**并存、不替代**。文本库走 Redis HNSW（`aikb-idx`），
 * 这个走 rag-service 的独立存储，用于图片/表格/公式等富文档。
 *
 * <p>三个必须知道的约定（都来自实测）：
 *  1. 服务不可用时后端返回 **HTTP 200 + body.code=503**，
 *     不能只看 HTTP 状态；`request.js` 已把业务码挂到 `err.bizCode`。
 *  2. health 端点是 silent 的，探测失败不弹 toast——这个页面挂载就探测一次，
 *     服务没启用是常态，不该打扰用户。
 *  3. 正文要按空行分段：服务端用 `split_by_character="\n\n"` 切块，
 *     整篇塞进去会变成单个巨型 chunk，检索精度会掉。
 */
const router = useRouter()

const healthy = ref(true)
const healthMessage = ref('')
const ingesting = ref(false)
const querying = ref(false)
const answer = ref('')
const lastIngest = ref(null)

const ingest = reactive({ docId: '', text: '' })
const query = reactive({ question: '', mode: 'naive', topK: 5 })

const canIngest = computed(() => ingest.docId.trim() && ingest.text.trim() && !ingesting.value)

async function checkHealth() {
  try {
    const data = await kbApi.multimodalHealth()
    // 服务不可用时后端返回 data:null（code 仍是 200），必须判 data 本身
    healthy.value = !!(data && data.ok)
    healthMessage.value = healthy.value
      ? ''
      : '多模态知识库未启用（rag-service 未就绪或未配置密钥）。你仍可使用文本知识库问答。'
  } catch (e) {
    healthy.value = false
    // bizCode=503 表示依赖服务不可用；其余按通用错误处理
    healthMessage.value = e?.bizCode === 503
      ? '多模态知识库服务不可用。你仍可使用文本知识库问答。'
      : '无法连接多模态知识库，请稍后重试。'
  }
}

async function doIngest() {
  ingesting.value = true
  try {
    // 按空行切成段落数组，交给后端拼 content_list——
    // 比让前端拼原生结构更不容易出错，也让后端能统一做清洗
    const paragraphs = ingest.text
      .split(/\n\s*\n/)
      .map((s) => s.trim())
      .filter(Boolean)
    const data = await kbApi.multimodalIngest({ docId: ingest.docId.trim(), paragraphs })
    lastIngest.value = data
    message.success(`已写入 ${data?.chunks ?? 0} 个切块`)
  } catch (e) {
    if (e?.bizCode === 503) {
      healthy.value = false
      healthMessage.value = '多模态知识库服务不可用。你仍可使用文本知识库问答。'
    } else {
      message.error(e?.message || '写入失败')
    }
  } finally {
    ingesting.value = false
  }
}

async function doQuery() {
  querying.value = true
  answer.value = ''
  try {
    const data = await kbApi.multimodalQuery({
      question: query.question.trim(),
      mode: query.mode,
      topK: query.topK
    })
    answer.value = data?.answer || ''
    if (!answer.value) message.info('检索完成，但没有命中内容')
  } catch (e) {
    if (e?.bizCode === 503) {
      healthy.value = false
      healthMessage.value = '多模态知识库服务不可用。你仍可使用文本知识库问答。'
    } else {
      message.error(e?.message || '检索失败')
    }
  } finally {
    querying.value = false
  }
}

function goTextKb() {
  router.push({ name: 'admin-dash' })
}

onMounted(checkHealth)
</script>

<style scoped>
.mm-rag { display: flex; flex-direction: column; gap: 16px; }
.mm-rag__alert { margin-bottom: 4px; }
.mm-rag__hint { font-size: 12px; color: #94a3b8; }
.mm-rag__answer {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 14px;
  color: #0f172a;
}
.mm-rag__last { margin-top: 4px; }
</style>
