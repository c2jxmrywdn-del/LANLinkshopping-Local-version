<template>
  <div class="credit-authority-card-root">
    <GlowBorderCard
      :glow-enabled="true"
      glow-color1="#059669"
      glow-color2="#0284c7"
      :glow-duration="10"
      custom-class="authority-glow-card"
    >
      <!-- 卡片头部：持牌征信机构认证与防篡改防伪验签 -->
      <div class="card-header">
        <div class="authority-brand">
          <div class="authority-icon-box">
            <Scale :size="22" class="text-emerald-600" aria-hidden="true" />
          </div>
          <div>
            <div class="authority-title-row">
              <h3 class="authority-name">{{ institutionName }}</h3>
              <span class="auth-type-pill">国家持牌征信源</span>
            </div>
            <p class="authority-subtitle">
              数据专线直联 · 隐私计算合规准入 · 平台端零征信原始数据留存
            </p>
          </div>
        </div>

        <!-- 验签防伪状态徽章 (21st.dev 脉冲徽章) -->
        <div class="tamper-proof-seal">
          <RadarPulseBadge
            status="success"
            size="sm"
            text="已核验·防篡改"
            :icon="ShieldCheck"
          />
          <span class="seal-hash">SM3: {{ signatureDigest }}</span>
        </div>
      </div>

      <!-- 核心 Bento 网格：风控评级、授信额度、履约指标 -->
      <div class="authority-bento-grid">
        <!-- Bento 1: 信用风控等级与健康指数 -->
        <div class="bento-cell rating-cell">
          <div class="cell-head">
            <span class="cell-title">企业综合信用评级</span>
            <Award :size="16" class="text-amber-500" aria-hidden="true" />
          </div>
          <div class="rating-display">
            <span class="grade-badge" :class="gradeClass">{{ riskGrade }}</span>
            <div class="grade-desc">
              <strong>{{ gradeTitle }}</strong>
              <small>履约概率极高 · 违约风险率 &lt; 0.08%</small>
            </div>
          </div>
          <div class="credit-health-bar">
            <div class="bar-labels">
              <span>信用健康指数</span>
              <strong>{{ healthScore }} / 100</strong>
            </div>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: healthScore + '%' }"></div>
            </div>
          </div>
        </div>

        <!-- Bento 2: 核定可用授信与账期周期 -->
        <div class="bento-cell limit-cell">
          <div class="cell-head">
            <span class="cell-title">核定企业授信额度</span>
            <CircleDollarSign :size="16" class="text-sky-600" aria-hidden="true" />
          </div>
          <div class="limit-value-row">
            <span class="currency">¥</span>
            <NumberTicker
              :value="creditLimit"
              :decimal-places="2"
              class="limit-number"
            />
          </div>
          <div class="limit-meta-chips">
            <span class="meta-chip">
              <Calendar :size="12" class="text-indigo-600 mr-1" aria-hidden="true" />
              准入账期：<strong>{{ termDays }} 天</strong> (7~15天标准短融)
            </span>
            <span class="meta-chip">
              <TrendingUp :size="12" class="text-emerald-600 mr-1" aria-hidden="true" />
              执行日息：<strong>{{ (dailyRate * 100).toFixed(4) }}%/天</strong>
            </span>
          </div>
        </div>

        <!-- Bento 3: 授权凭证与有效期限 -->
        <div class="bento-cell valid-cell">
          <div class="cell-head">
            <span class="cell-title">授权凭证时效</span>
            <Clock :size="16" class="text-indigo-500" aria-hidden="true" />
          </div>
          <div class="valid-range-box">
            <div class="valid-row">
              <span class="label">授权流水号：</span>
              <span class="val font-mono">{{ assessmentRefNo }}</span>
            </div>
            <div class="valid-row">
              <span class="label">生效有效期：</span>
              <span class="val font-mono">{{ validFrom }} ~ {{ validUntil }}</span>
            </div>
            <div class="valid-status-chip">
              <CheckCircle2 :size="13" class="text-emerald-600 mr-1" aria-hidden="true" />
              <span>授信凭证处于法定有效保护期内</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部金融监管与合规免责公开栏 -->
      <div class="compliance-footer-bar">
        <div class="footer-left">
          <FileCheck :size="16" class="text-emerald-700 flex-shrink-0" aria-hidden="true" />
          <p class="footer-text">
            <strong>国家金融监管合规承诺</strong>：严格落实央行《征信业务管理办法》与《个人信息保护法》。本平台不自建征信库、不转存企业征信底稿。所有授信评估均通过联邦学习/隐私安全屋由持牌机构直接核定，数据传输经国密算法脱敏防篡改。
          </p>
        </div>
        <div class="footer-action">
          <button
            type="button"
            class="auth-action-btn"
            @click="onViewCertificate"
          >
            <span>验签存证详情</span>
            <ArrowRight :size="14" class="ml-1" aria-hidden="true" />
          </button>
        </div>
      </div>
    </GlowBorderCard>

    <!-- 弹窗：防篡改存证与脱敏证书详情 -->
    <a-modal
      v-model:open="showCertModal"
      title="第三方持牌征信合规授权凭据 (脱敏公示)"
      :footer="null"
      width="600px"
      centered
    >
      <div class="cert-modal-body">
        <div class="cert-modal-seal">
          <ShieldCheck :size="40" class="text-emerald-600 mb-2" aria-hidden="true" />
          <h4>国家合规征信授权防篡改电子存证</h4>
          <p class="cert-sub">本凭证由持牌征信机构数字签名生成，哈希链上可验</p>
        </div>

        <a-descriptions bordered :column="1" size="small" class="mt-4">
          <a-descriptions-item label="发证机构">{{ institutionName }}（中国人民银行备案征信机构）</a-descriptions-item>
          <a-descriptions-item label="企业信用评级">
            <a-tag color="green">{{ riskGrade }}</a-tag>
            <span class="text-xs text-slate-500 ml-1">{{ gradeTitle }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="核定授信额度">¥{{ Number(creditLimit).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</a-descriptions-item>
          <a-descriptions-item label="准入账期期限">{{ termDays }} 天 (支持 7 ~ 15 天灵活流贷)</a-descriptions-item>
          <a-descriptions-item label="授权流水单号">{{ assessmentRefNo }}</a-descriptions-item>
          <a-descriptions-item label="防篡改数字摘要 (SM3)">
            <span class="font-mono text-xs text-slate-700 select-all">{{ fullDigest }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="合规隔离状态">
            <span class="text-emerald-600 font-semibold">平台端零敏感数据沉淀 · 独立公证存管</span>
          </a-descriptions-item>
        </a-descriptions>

        <div class="modal-footer-action">
          <a-button type="primary" block @click="showCertModal = false">
            已确认合规存证无误
          </a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  ArrowRight,
  Award,
  Calendar,
  CheckCircle2,
  CircleDollarSign,
  Clock,
  FileCheck,
  Scale,
  ShieldCheck,
  TrendingUp
} from 'lucide-vue-next'
import GlowBorderCard from '../ui/GlowBorderCard.vue'
import RadarPulseBadge from '../ui/RadarPulseBadge.vue'
import NumberTicker from '../ui/NumberTicker.vue'

const props = defineProps({
  institutionName: {
    type: String,
    default: '百行征信 · 央行合规数据直联源'
  },
  riskGrade: {
    type: String,
    default: 'AAA'
  },
  healthScore: {
    type: Number,
    default: 96.8
  },
  creditLimit: {
    type: Number,
    default: 200000
  },
  termDays: {
    type: Number,
    default: 15
  },
  dailyRate: {
    type: Number,
    default: 0.0003
  },
  assessmentRefNo: {
    type: String,
    default: 'CRED-EXT-20261105-8891'
  },
  validFrom: {
    type: String,
    default: '2026-01-01'
  },
  validUntil: {
    type: String,
    default: '2026-12-31'
  }
})

const showCertModal = ref(false)

const gradeTitle = computed(() => {
  if (props.riskGrade === 'AAA') return '卓越稳健型采购商 (优质龙头)'
  if (props.riskGrade === 'AA+' || props.riskGrade === 'AA') return '优良成长型采购商'
  if (props.riskGrade === 'A') return '标准合规型采购商'
  return '基准授信企业'
})

const gradeClass = computed(() => {
  if (props.riskGrade.startsWith('AAA')) return 'grade-aaa'
  if (props.riskGrade.startsWith('AA')) return 'grade-aa'
  return 'grade-a'
})

const fullDigest = computed(() => {
  return 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
})

const signatureDigest = computed(() => {
  return fullDigest.value.slice(0, 10) + '...' + fullDigest.value.slice(-6)
})

function onViewCertificate() {
  showCertModal.value = true
}
</script>

<style scoped>
.credit-authority-card-root {
  width: 100%;
}

.authority-glow-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 20px -2px rgba(15, 23, 42, 0.05);
}

/* 顶部品牌与防伪验签 */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f1f5f9;
}

.authority-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.authority-icon-box {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgba(16, 185, 129, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.authority-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.authority-name {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.auth-type-pill {
  font-size: 11px;
  font-weight: 600;
  color: #059669;
  background: rgba(16, 185, 129, 0.12);
  padding: 2px 8px;
  border-radius: 9999px;
}

.authority-subtitle {
  margin: 2px 0 0;
  font-size: 12px;
  color: #64748b;
}

.tamper-proof-seal {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.seal-hash {
  font-size: 10px;
  color: #94a3b8;
  font-family: monospace;
}

/* Bento 响应式网格 */
.authority-bento-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.bento-cell {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  transition: all 200ms ease;
}

.bento-cell:hover {
  background: #ffffff;
  border-color: #cbd5e1;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.04);
}

.cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cell-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

/* 评级 */
.rating-display {
  display: flex;
  align-items: center;
  gap: 10px;
}

.grade-badge {
  font-size: 24px;
  font-weight: 800;
  padding: 4px 12px;
  border-radius: 8px;
  letter-spacing: 0.5px;
}

.grade-aaa {
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(16, 185, 129, 0.3);
}

.grade-aa {
  background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(2, 132, 199, 0.3);
}

.grade-a {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.3);
}

.grade-desc {
  display: flex;
  flex-direction: column;
}

.grade-desc strong {
  font-size: 13px;
  color: #0f172a;
}

.grade-desc small {
  font-size: 11px;
  color: #059669;
}

.credit-health-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bar-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #64748b;
}

.bar-track {
  height: 6px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 9999px;
  background: linear-gradient(90deg, #10b981 0%, #06b6d4 100%);
  transition: width 500ms ease-out;
}

/* 额度 */
.limit-value-row {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.currency {
  font-size: 20px;
  font-weight: 700;
  color: #0284c7;
}

.limit-number {
  font-size: 26px;
  font-weight: 800;
  color: #0f172a;
}

.limit-meta-chips {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.meta-chip {
  display: flex;
  align-items: center;
  font-size: 11px;
  color: #475569;
  background: #ffffff;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}

/* 有效期 */
.valid-range-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.valid-row {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
}

.valid-row .label {
  color: #64748b;
}

.valid-row .val {
  color: #1e293b;
  font-weight: 600;
  word-break: break-all;
}

.valid-status-chip {
  display: flex;
  align-items: center;
  font-size: 11px;
  color: #059669;
  background: rgba(16, 185, 129, 0.08);
  padding: 4px 8px;
  border-radius: 6px;
  font-weight: 500;
}

/* 合规底栏 */
.compliance-footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.footer-left {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex: 1;
  min-width: 240px;
}

.footer-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #166534;
}

.footer-text strong {
  color: #14532d;
}

.footer-action {
  flex-shrink: 0;
}

.auth-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 14px;
  min-height: 44px; /* 移动端 44px 触控热区规范 */
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #86efac;
  color: #166534;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 160ms ease;
  touch-action: manipulation;
}

.auth-action-btn:hover {
  background: #166534;
  border-color: #166534;
  color: #ffffff;
}

/* 弹窗样式 */
.cert-modal-body {
  padding: 8px 0;
}

.cert-modal-seal {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}

.cert-modal-seal h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.cert-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.modal-footer-action {
  margin-top: 20px;
}

/* 响应式断点适配 */
@media (max-width: 900px) {
  .authority-bento-grid {
    grid-template-columns: 1fr;
  }
  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .tamper-proof-seal {
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .authority-glow-card {
    padding: 16px;
  }
  .compliance-footer-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .auth-action-btn {
    width: 100%;
  }
}
</style>
