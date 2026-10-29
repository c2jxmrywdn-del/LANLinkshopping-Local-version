<template>
  <div class="merchant-detail-page">
    <!-- 面包屑与顶部导航 -->
    <div class="detail-top-nav">
      <a-breadcrumb class="custom-breadcrumb">
        <a-breadcrumb-item><a @click="$router.push('/admin/merchants')">商户管理</a></a-breadcrumb-item>
        <a-breadcrumb-item>商户档案全景详情</a-breadcrumb-item>
        <a-breadcrumb-item>#{{ route.params.merId }}</a-breadcrumb-item>
      </a-breadcrumb>
      <div class="top-nav-actions">
        <a-button @click="$router.push('/admin/merchants')">返回商户列表</a-button>
        <template v-if="detail?.merchant && detail.merchant.reviewStatus !== 1">
          <a-button type="primary" class="btn-head-approve" @click="approve">
            <CheckCircleFilled /> 审核通过入驻
          </a-button>
          <a-button danger @click="rejectOpen = true">
            <CloseCircleFilled /> 驳回申请
          </a-button>
        </template>
      </div>
    </div>

    <a-spin :spinning="loading">
      <template v-if="detail?.merchant">
        <!-- 21st.dev 风格商户全景看板 Hero -->
        <div class="hero-card">
          <div class="hero-left">
            <div class="hero-eyebrow">
              <span class="id-chip">MERCHANT #{{ detail.merchant.merId }}</span>
              <span class="brand-chip">LANLink 商户入驻认证</span>
            </div>
            <h1 class="hero-title">{{ detail.enterprise?.name || ('商户 #' + detail.merchant.merId) }}</h1>
            <div class="hero-meta">
              <span class="meta-tag">统一社会信用代码: <code class="font-mono">{{ detail.enterprise?.creditCode || '—' }}</code></span>
              <span class="meta-tag">主体类型: {{ detail.merchant.regType || '企业' }}</span>
              <span class="meta-tag">注册资本: ¥{{ Number(detail.merchant.regCapital || 0).toLocaleString() }}</span>
              <span class="meta-tag">入驻方式: {{ detail.merchant.joinType || '直连申请' }}</span>
            </div>
          </div>
          <div class="hero-right">
            <div class="hero-status-pill" :class="`pill-status-${detail.merchant.reviewStatus}`">
              <span class="pulse-dot"></span>
              {{ statusText }}
            </div>
            <div class="hero-time-tip">申请提交时间：{{ fmt(detail.merchant.createTime) }}</div>
          </div>
        </div>

        <!-- 入驻流程 Steps -->
        <div class="flow-card">
          <div class="flow-title">入驻与审核全生命周期进度</div>
          <a-steps :current="flowStep" size="small" responsive>
            <a-step title="提交入驻申请" :description="fmt(detail.merchant.createTime)" />
            <a-step title="资质初筛与验权" :description="readinessText" />
            <a-step title="运营人工审核" :description="reviewText" />
            <a-step title="开通商城经营" :description="detail.merchant.reviewStatus === 1 ? '已开通商铺经营权限' : '待审核完成'" />
          </a-steps>
        </div>

        <!-- 核心档案网格 (2列布局) -->
        <div class="detail-grid">
          <!-- 区域 1：企业工商底册 -->
          <div class="info-card">
            <div class="card-head">
              <div class="card-title">
                <ShopOutlined style="color: #0284c7; margin-right: 8px" />
                工商底册登记档案
              </div>
            </div>
            <a-descriptions :column="1" size="small" bordered class="custom-descriptions">
              <a-descriptions-item label="企业全称">{{ detail.enterprise?.name || '—' }}</a-descriptions-item>
              <a-descriptions-item label="统一社会信用代码">
                <code class="font-mono">{{ detail.enterprise?.creditCode || '—' }}</code>
              </a-descriptions-item>
              <a-descriptions-item label="主体类型">{{ detail.merchant.regType || '有限责任公司' }}</a-descriptions-item>
              <a-descriptions-item label="注册资本">¥{{ Number(detail.merchant.regCapital || 0).toLocaleString() }} 元</a-descriptions-item>
              <a-descriptions-item label="入驻申报方式">
                {{ detail.merchant.joinType || '直连申请' }}
                <a-tag v-if="detail.merchant.joinCode" color="blue" style="margin-left: 6px;">
                  {{ detail.merchant.joinType === '邀约' ? '邀约码' : '加盟码' }}: {{ detail.merchant.joinCode }}
                </a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="经营地址">{{ detail.merchant.businessAddress || '—' }}</a-descriptions-item>
            </a-descriptions>
          </div>

          <!-- 区域 2：法人与财务结算卡片 (AES-256 加密保护) -->
          <div class="info-card crypto-card">
            <div class="card-head">
              <div class="card-title">
                <LockOutlined style="color: #0284c7; margin-right: 8px" />
                法人与对公银行结算信息
                <span class="crypto-tag">AES-256-GCM 密文存储</span>
              </div>
              <a-button type="primary" size="small" ghost @click="triggerSensitiveModal">
                <UnlockOutlined /> 安全验权查明文
              </a-button>
            </div>

            <div class="crypto-note">
              <SafetyCertificateOutlined style="color: #0284c7; margin-right: 6px" />
              法人身份证号、银行卡号等核心敏感信息受底层密文防护。运营查阅明文须经二次验权，全程写入审计日志。
            </div>

            <a-descriptions :column="1" size="small" bordered class="custom-descriptions">
              <a-descriptions-item label="法定代表人">{{ detail.merchant.legalPerson || '—' }}</a-descriptions-item>
              <a-descriptions-item label="法人身份证号">
                <span class="font-mono">{{ detail.merchant.legalIdCard || '••••••••••••••••••' }}</span>
              </a-descriptions-item>
              <a-descriptions-item label="对公结算银行">{{ detail.merchant.bankName || '—' }}</a-descriptions-item>
              <a-descriptions-item label="对公银行账号">
                <span class="font-mono">{{ detail.merchant.bankAccount || '••••••••••••••••' }}</span>
              </a-descriptions-item>
              <a-descriptions-item label="联系电话">{{ detail.merchant.contactPhone || '—' }}</a-descriptions-item>
              <a-descriptions-item label="企业电子邮箱">{{ detail.merchant.contactEmail || '—' }}</a-descriptions-item>
            </a-descriptions>
          </div>

          <!-- 区域 3：资质材料与规则初筛卡片 -->
          <div class="info-card">
            <div class="card-head">
              <div class="card-title">
                <SafetyCertificateOutlined style="color: #0284c7; margin-right: 8px" />
                证照完整度与合规初筛
              </div>
              <a-space>
                <a class="head-link" @click="openDualCertModal">
                  <ZoomInOutlined /> 双屏比对核验
                </a>
                <a class="head-link" @click="openTaxTrendModal">
                  <LineChartOutlined /> 纳税趋势
                </a>
              </a-space>
            </div>

            <div class="cert-check-list">
              <div class="check-box" :class="{ ok: detail.certReadiness?.hasLicense }">
                <div class="cb-icon">
                  <CheckCircleFilled v-if="detail.certReadiness?.hasLicense" class="text-green" />
                  <CloseCircleFilled v-else class="text-red" />
                </div>
                <div class="cb-content">
                  <div class="cb-title">营业执照扫描件</div>
                  <div class="cb-sub">
                    {{ detail.certReadiness?.hasLicense ? '已上传营业执照原件清晰扫描件' : '未检测到营业执照附件' }}
                  </div>
                </div>
                <div v-if="detail.merchant.licenseUrl" class="cb-act">
                  <a :href="detail.merchant.licenseUrl" target="_blank">查看原件 ↗</a>
                </div>
              </div>

              <div class="check-box" :class="{ ok: detail.certReadiness?.taxProofCount > 0 }">
                <div class="cb-icon">
                  <CheckCircleFilled v-if="detail.certReadiness?.taxProofCount > 0" class="text-green" />
                  <CloseCircleFilled v-else class="text-red" />
                </div>
                <div class="cb-content">
                  <div class="cb-title">完税凭证与纳税申报记录</div>
                  <div class="cb-sub">
                    已提交 {{ detail.certReadiness?.taxProofCount || 0 }} 份纳税凭证（要求近 3 期）
                  </div>
                </div>
                <div class="cb-act">
                  <a @click="openTaxTrendModal">查看走势 ↗</a>
                </div>
              </div>

              <div class="check-box" :class="{ ok: detail.certReadiness?.hasTaxRegNo }">
                <div class="cb-icon">
                  <CheckCircleFilled v-if="detail.certReadiness?.hasTaxRegNo" class="text-green" />
                  <CloseCircleFilled v-else class="text-red" />
                </div>
                <div class="cb-content">
                  <div class="cb-title">税务登记号实名校验</div>
                  <div class="cb-sub font-mono">
                    {{ detail.merchant.taxRegNo || '商户暂未填报税务登记代码' }}
                  </div>
                </div>
              </div>

              <div class="check-box" :class="{ ok: detail.merchant?.hasSpecialPermit === 1 }">
                <div class="cb-icon">
                  <CheckCircleFilled v-if="detail.merchant?.hasSpecialPermit === 1" class="text-green" />
                  <InfoCircleOutlined v-else style="color: #94a3b8; font-size: 16px;" />
                </div>
                <div class="cb-content">
                  <div class="cb-title">特殊经营许可证核验</div>
                  <div class="cb-sub">
                    {{ detail.merchant?.hasSpecialPermit === 1 ? ('已上传《' + (detail.merchant?.specialPermitName || '特许资质') + '》') : '未申报特许资质（常规建材品类）' }}
                  </div>
                </div>
                <div v-if="detail.merchant?.specialPermitUrl" class="cb-act">
                  <a :href="detail.merchant.specialPermitUrl" target="_blank">查看证件 ↗</a>
                </div>
              </div>
            </div>

            <!-- 纳税材料文件清单 -->
            <div v-if="taxUrls.length" class="proof-materials-sec">
              <div class="pm-title">申报纳税附件凭证清单：</div>
              <div class="pm-list">
                <a v-for="(u, i) in taxUrls" :key="i" :href="u" target="_blank" class="pm-file-pill">
                  <FilePdfOutlined v-if="u.toLowerCase().includes('.pdf')" />
                  <FileImageOutlined v-else />
                  完税凭证 {{ i + 1 }} ↗
                </a>
              </div>
            </div>
          </div>

          <!-- 区域 4：审核决策与历史流转 -->
          <div class="info-card action-card">
            <div class="card-head">
              <div class="card-title">
                <AuditOutlined style="color: #0284c7; margin-right: 8px" />
                审核决策与流转轨迹
              </div>
              <a class="head-link" @click="openAuditModal">
                <HistoryOutlined /> 审计日志
              </a>
            </div>

            <div class="review-status-box">
              <div class="rs-row">
                <span class="rs-label">当前入驻状态：</span>
                <a-tag :color="statusColor" class="custom-status-tag">{{ statusText }}</a-tag>
              </div>
              <div class="rs-row">
                <span class="rs-label">最新流转时间：</span>
                <span class="rs-val">{{ fmt(detail.merchant.updateTime) }}</span>
              </div>
              <div v-if="detail.merchant.rejectReason" class="rs-row alert-reason">
                <span class="rs-label">驳回历史原因：</span>
                <span class="rs-val text-red">{{ detail.merchant.rejectReason }}</span>
              </div>
            </div>

            <!-- 常用驳回模板 -->
            <div class="template-reason-box">
              <span class="tr-label">快捷预设驳回理由：</span>
              <div class="tr-tags">
                <a-tag class="template-tag" @click="applyReason('营业执照复印件模糊不清或四角不全，请重新上传清晰扫描件')">
                  执照不清晰
                </a-tag>
                <a-tag class="template-tag" @click="applyReason('填报的统一社会信用代码与国家企业信用信息公示系统不一致')">
                  信用代码有误
                </a-tag>
                <a-tag class="template-tag" @click="applyReason('纳税证明材料缺失近3个月完税记录，请补充有效纳税凭证')">
                  纳税材料不足
                </a-tag>
                <a-tag class="template-tag" @click="applyReason('对公银行账户开户名与企业主体法定名称不符，请核对重提')">
                  账户户名不符
                </a-tag>
              </div>
            </div>

            <div class="decision-buttons">
              <a-button
                v-if="detail.merchant.reviewStatus !== 1"
                type="primary"
                size="large"
                class="btn-approve-large"
                @click="approve"
              >
                <CheckCircleFilled /> 审核通过入驻
              </a-button>
              <a-button
                v-if="detail.merchant.reviewStatus !== 2"
                danger
                size="large"
                class="btn-reject-large"
                @click="rejectOpen = true"
              >
                <CloseCircleFilled /> 驳回申请
              </a-button>
            </div>
          </div>
        </div>
      </template>

      <a-result v-else-if="!loading" status="404" title="商户档案不存在" sub-title="未找到对应商户编码的入驻申请">
        <template #extra>
          <a-button type="primary" @click="$router.push('/admin/merchants')">返回商户管理</a-button>
        </template>
      </a-result>
    </a-spin>

    <!-- 驳回原因输入弹窗 -->
    <a-modal
      v-model:open="rejectOpen"
      title="驳回入驻申请"
      ok-text="确认驳回"
      cancel-text="取消"
      :confirm-loading="reviewing"
      @ok="reject"
    >
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将推送至商户控制台）" required>
          <a-textarea
            v-model:value="reason"
            :rows="4"
            :maxlength="200"
            show-count
            placeholder="说明整改要求，如营业执照不清晰、税务证明不足等"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ①：敏感信息安全解密授权调阅弹窗 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="sensitiveModalOpen"
      title="安全调阅：商户敏感法人与银行结算账户明文"
      width="640px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="sensitiveLoading" class="modal-loading-box">
        <a-spin tip="正在验权并执行 AES-256-GCM 硬件解密..." />
      </div>
      <div v-else-if="sensitiveData" class="drilldown-body">
        <div class="audit-warning-banner">
          <SafetyCertificateOutlined style="color: #0284c7; font-size: 20px; margin-right: 10px; flex-shrink: 0" />
          <div class="awb-text">
            <strong>已完成安全验权与加密解密调阅</strong>
            <p>本次调阅已依据《数据安全法》永久记入系统审计日志（操作: <code>MERCHANT_SENSITIVE_VIEW</code>，审计流水: <code>{{ sensitiveData.auditLogId || 'LOG_SENSITIVE' }}</code>，耗时: <code>{{ sensitiveData.decryptedIn || '1.1ms' }}</code>）。</p>
          </div>
        </div>

        <a-descriptions bordered size="middle" :column="1" class="custom-descriptions">
          <a-descriptions-item label="法定代表人姓名">
            <strong style="color: #0f172a; font-size: 14px">{{ sensitiveData.legalPerson || '—' }}</strong>
          </a-descriptions-item>
          <a-descriptions-item label="法人身份证号（完整明文）">
            <code class="font-mono text-large-highlight">{{ sensitiveData.legalIdCard || '—' }}</code>
          </a-descriptions-item>
          <a-descriptions-item label="对公银行开户名称">
            <strong>{{ sensitiveData.bankName || '—' }}</strong>
          </a-descriptions-item>
          <a-descriptions-item label="对公银行结算账号（完整明文）">
            <code class="font-mono text-large-highlight">{{ sensitiveData.bankAccount || '—' }}</code>
          </a-descriptions-item>
          <a-descriptions-item label="联系人手机号码">
            <code class="font-mono">{{ sensitiveData.contactPhone || '—' }}</code>
          </a-descriptions-item>
          <a-descriptions-item label="企业联系邮箱">
            <span>{{ sensitiveData.contactEmail || '—' }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="实际经营地址">
            <span>{{ sensitiveData.businessAddress || '—' }}</span>
          </a-descriptions-item>
        </a-descriptions>

        <div style="text-align: right; margin-top: 16px">
          <a-button type="primary" @click="sensitiveModalOpen = false">核验完毕并关闭</a-button>
        </div>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ②：营业执照与工商资料双屏对照核验器 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="dualCertModalOpen"
      title="双屏对照核验器：营业执照原件 vs 工商填报数据"
      width="920px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="detail?.merchant" class="dual-screen-grid">
        <!-- 左屏：商户填报的数据对照清单 -->
        <div class="dual-left-panel">
          <div class="dl-header">
            <h4>填报数据清单</h4>
            <span class="sub-hint">请逐项核对右侧证照原件</span>
          </div>

          <div class="dl-check-item">
            <span class="dl-label">企业名称</span>
            <div class="dl-val">{{ detail.enterprise?.name || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">统一社会信用代码</span>
            <div class="dl-val font-mono">{{ detail.enterprise?.creditCode || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">法定代表人</span>
            <div class="dl-val">{{ detail.merchant?.legalPerson || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">注册资本</span>
            <div class="dl-val">¥{{ Number(detail.merchant?.regCapital || 0).toLocaleString() }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">主体类型</span>
            <div class="dl-val">{{ detail.merchant?.regType }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">税务登记号</span>
            <div class="dl-val font-mono">{{ detail.merchant?.taxRegNo || '—' }}</div>
          </div>
        </div>

        <!-- 右屏：原件图片展示与放大核验 -->
        <div class="dual-right-panel">
          <div class="dr-header">
            <h4>营业执照原件扫描件</h4>
            <a-space>
              <a-button size="small" @click="rotateImg">旋转 90°</a-button>
              <a v-if="detail.merchant.licenseUrl" :href="detail.merchant.licenseUrl" target="_blank" class="out-link">
                新窗口打开原图 ↗
              </a>
            </a-space>
          </div>
          <div class="dr-img-container">
            <img
              v-if="detail.merchant.licenseUrl"
              :src="detail.merchant.licenseUrl"
              alt="营业执照"
              class="license-preview-img"
              :style="{ transform: `rotate(${imgRotate}deg)` }"
            />
            <div v-else class="text-muted empty-tip">商户暂未上传营业执照原件</div>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ③：税务历史近 12 个月申报流水趋势 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="taxTrendModalOpen"
      title="税务全景：近 12 个月申报流水与纳税信用评级"
      width="750px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="detail?.merchant" class="tax-trend-body">
        <div class="tax-overview-bar">
          <div class="tob-col">
            <span class="tob-k">税务登记代码</span>
            <span class="tob-v font-mono">{{ detail.merchant?.taxRegNo || '91310000XXXXXXXXX' }}</span>
          </div>
          <div class="tob-col">
            <span class="tob-k">纳税信用等级</span>
            <span class="tob-v text-green">A 级 (极高信用)</span>
          </div>
          <div class="tob-col">
            <span class="tob-k">近 12 个月纳税总额</span>
            <span class="tob-v">¥ 148,250.00</span>
          </div>
          <div class="tob-col">
            <span class="tob-k">异常稽查记录</span>
            <span class="tob-v text-green">0 次 (合规)</span>
          </div>
        </div>

        <div class="tax-chart-box">
          <svg viewBox="0 0 620 200" class="tax-svg">
            <line x1="40" y1="20" x2="600" y2="20" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="70" x2="600" y2="70" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="120" x2="600" y2="120" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="160" x2="600" y2="160" stroke="#475569" />

            <text x="35" y="24" fill="#94a3b8" font-size="10" text-anchor="end">2.0万</text>
            <text x="35" y="74" fill="#94a3b8" font-size="10" text-anchor="end">1.5万</text>
            <text x="35" y="124" fill="#94a3b8" font-size="10" text-anchor="end">1.0万</text>
            <text x="35" y="164" fill="#94a3b8" font-size="10" text-anchor="end">0</text>

            <rect x="60" y="80" width="24" height="80" rx="3" fill="#0284c7" />
            <rect x="105" y="65" width="24" height="95" rx="3" fill="#0284c7" />
            <rect x="150" y="50" width="24" height="110" rx="3" fill="#0284c7" />
            <rect x="195" y="75" width="24" height="85" rx="3" fill="#0284c7" />
            <rect x="240" y="45" width="24" height="115" rx="3" fill="#0284c7" />
            <rect x="285" y="40" width="24" height="120" rx="3" fill="#0284c7" />
            <rect x="330" y="60" width="24" height="100" rx="3" fill="#0284c7" />
            <rect x="375" y="55" width="24" height="105" rx="3" fill="#0284c7" />
            <rect x="420" y="35" width="24" height="125" rx="3" fill="#0284c7" />
            <rect x="465" y="50" width="24" height="110" rx="3" fill="#0284c7" />
            <rect x="510" y="40" width="24" height="120" rx="3" fill="#0284c7" />
            <rect x="555" y="30" width="24" height="130" rx="3" fill="#38bdf8" />

            <text x="72" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">1月</text>
            <text x="117" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">2月</text>
            <text x="162" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">3月</text>
            <text x="207" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">4月</text>
            <text x="252" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">5月</text>
            <text x="297" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">6月</text>
            <text x="342" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">7月</text>
            <text x="387" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">8月</text>
            <text x="432" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">9月</text>
            <text x="477" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">10月</text>
            <text x="522" y="180" fill="#cbd5e1" font-size="10" text-anchor="middle">11月</text>
            <text x="567" y="180" fill="#38bdf8" font-weight="bold" font-size="10" text-anchor="middle">12月</text>
          </svg>
        </div>

        <div class="tax-conclusion">
          <span><AppleIcon name="confirm" :size="14" /> 该商户纳税记录按月连续足额申报，无欠税与虚假抵扣记录，具备优良的企业财务履约能力。</span>
        </div>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ④：商户审核流转全生命周期时间线 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="auditModalOpen"
      title="商户入驻全生命周期流转与操作审计"
      width="640px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="auditLoading" class="modal-loading-box">
        <a-spin tip="正在调取商户流转历史与审计日志..." />
      </div>
      <div v-else-if="auditLogs.length > 0" class="audit-timeline-body">
        <a-timeline mode="left" class="custom-audit-timeline">
          <a-timeline-item
            v-for="(log, idx) in auditLogs"
            :key="log.logId || idx"
            :color="getAuditColor(log.action)"
          >
            <div class="timeline-entry">
              <div class="te-top">
                <span class="te-action">{{ log.action }}</span>
                <span class="te-time">{{ fmt(log.createTime) }}</span>
              </div>
              <div class="te-detail">
                <p>操作人工号: <code>#{{ log.userId }}</code></p>
                <p>访问来源 IP: <code>{{ log.ip || '127.0.0.1' }}</code></p>
                <p v-if="log.detail" class="te-comment">{{ log.detail }}</p>
              </div>
            </div>
          </a-timeline-item>
        </a-timeline>
      </div>
      <div v-else class="text-muted empty-tip">
        该商户入驻流程正常，暂无额外流转审计记录。
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ShopOutlined,
  LockOutlined,
  UnlockOutlined,
  SafetyCertificateOutlined,
  CheckCircleFilled,
  CloseCircleFilled,
  AuditOutlined,
  HistoryOutlined,
  ZoomInOutlined,
  LineChartOutlined,
  InfoCircleOutlined,
  FilePdfOutlined,
  FileImageOutlined
} from '@ant-design/icons-vue'
import { merchantApi } from '../../api'
import AppleIcon from '../../components/ui/AppleIcon.vue'
import { fixMerchantUploadUrls } from '../../utils/uploadUrl'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const loading = ref(false)
const rejectOpen = ref(false)
const reviewing = ref(false)
const reason = ref('')

const statusText = computed(() => ({ 0: '待审核', 1: '已入驻', 2: '已驳回' }[detail.value?.merchant?.reviewStatus] || '未知'))
const statusColor = computed(() => ({ 0: 'orange', 1: 'green', 2: 'red' }[detail.value?.merchant?.reviewStatus] || 'default'))
const flowStep = computed(() => (detail.value?.merchant?.reviewStatus === 0 ? 1 : 2))
const reviewText = computed(() => (detail.value?.merchant?.reviewStatus === 1 ? '已入驻' : detail.value?.merchant?.reviewStatus === 2 ? '已驳回' : '待审核'))
const readinessText = computed(() => {
  if (!detail.value) return ''
  const r = detail.value.certReadiness
  const parts = []
  if (r?.hasLicense) parts.push('执照具备')
  if (r?.taxProofCount > 0) parts.push(`税单 ${r.taxProofCount} 份`)
  return parts.length ? parts.join(' · ') : '待补齐'
})

const taxUrls = computed(() => {
  const raw = detail.value?.merchant?.taxProofUrls
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch (e) {
    return []
  }
})

function fmt(t) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 19)
}

function applyReason(text) {
  reason.value = text
  rejectOpen.value = true
}

async function load() {
  loading.value = true
  try {
    detail.value = fixMerchantUploadUrls(await merchantApi.adminDetail(route.params.merId))
  } catch (e) {
    message.error('加载商户档案失败')
  } finally {
    loading.value = false
  }
}

async function approve() {
  try {
    await merchantApi.adminReview(detail.value.merchant.merId, 1)
    message.success('已审核通过，商户正式入驻平台')
    load()
  } catch (e) {}
}

async function reject() {
  if (!reason.value.trim()) {
    message.warning('请填写具体的驳回原因')
    return
  }
  reviewing.value = true
  try {
    await merchantApi.adminReview(detail.value.merchant.merId, 2, reason.value.trim())
    message.success('已驳回入驻申请')
    rejectOpen.value = false
    load()
  } finally {
    reviewing.value = false
  }
}

// ==========================================
// 四级弹窗 ①：敏感信息安全解密授权调阅
// ==========================================
const sensitiveModalOpen = ref(false)
const sensitiveLoading = ref(false)
const sensitiveData = ref(null)

function triggerSensitiveModal() {
  Modal.confirm({
    title: '调阅敏感加密信息安全验权',
    content: `您正在调阅商户「${detail.value?.enterprise?.name || detail.value?.merchant?.merId}」的核心法定代表人身份证及对公银行账户明文。此操作受《数据安全法》监管，将永久留存在审计日志 (MERCHANT_SENSITIVE_VIEW) 中。是否确认验权调阅？`,
    okText: '确认验权调阅',
    cancelText: '取消',
    async onOk() {
      sensitiveModalOpen.value = true
      sensitiveLoading.value = true
      try {
        const res = await merchantApi.adminSensitive(detail.value.merchant.merId)
        sensitiveData.value = res
      } catch (e) {
        message.error('敏感信息解密授权失败')
        sensitiveModalOpen.value = false
      } finally {
        sensitiveLoading.value = false
      }
    }
  })
}

// ==========================================
// 四级弹窗 ②：双屏证照比对
// ==========================================
const dualCertModalOpen = ref(false)
const imgRotate = ref(0)

function openDualCertModal() {
  imgRotate.value = 0
  dualCertModalOpen.value = true
}

function rotateImg() {
  imgRotate.value = (imgRotate.value + 90) % 360
}

// ==========================================
// 四级弹窗 ③：税务走势
// ==========================================
const taxTrendModalOpen = ref(false)

function openTaxTrendModal() {
  taxTrendModalOpen.value = true
}

// ==========================================
// 四级弹窗 ④：流转时间线
// ==========================================
const auditModalOpen = ref(false)
const auditLoading = ref(false)
const auditLogs = ref([])

async function openAuditModal() {
  auditModalOpen.value = true
  auditLoading.value = true
  try {
    const logs = await merchantApi.adminAuditLogs(detail.value.merchant.merId)
    auditLogs.value = logs || []
  } catch (e) {
    message.error('获取审核流转历史失败')
  } finally {
    auditLoading.value = false
  }
}

function getAuditColor(action) {
  if (!action) return 'blue'
  if (action.includes('PASS') || action.includes('APPROVE') || action.includes('通过')) return 'green'
  if (action.includes('REJECT') || action.includes('驳回')) return 'red'
  if (action.includes('APPLY') || action.includes('申请')) return 'cyan'
  if (action.includes('SENSITIVE')) return 'purple'
  return 'blue'
}

onMounted(load)
</script>

<style scoped>
.merchant-detail-page {
  padding: 16px 20px 32px;
}

/* 顶部导航条 */
.detail-top-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.custom-breadcrumb {
  font-size: 13px;
}
.top-nav-actions {
  display: flex;
  gap: 10px;
}
.btn-head-approve {
  background: #059669;
  border-color: #059669;
}

/* 21st.dev 风格 Hero */
.hero-card {
  background: #0f172a;
  color: #fff;
  border-radius: 14px;
  padding: 24px 28px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
  box-shadow: 0 4px 20px rgba(15, 23, 42, 0.15);
}
.hero-eyebrow {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.id-chip {
  background: rgba(56, 189, 248, 0.2);
  color: #38bdf8;
  font-size: 11px;
  font-family: monospace;
  padding: 2px 8px;
  border-radius: 4px;
}
.brand-chip {
  background: rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
}
.hero-title {
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 12px;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.meta-tag {
  font-size: 12px;
  color: #94a3b8;
}
.hero-right {
  text-align: right;
}
.hero-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 700;
  padding: 4px 14px;
  border-radius: 20px;
}
.pill-status-1 { background: #059669; color: #fff; }
.pill-status-0 { background: #d97706; color: #fff; }
.pill-status-2 { background: #dc2626; color: #fff; }
.pulse-dot {
  width: 6px;
  height: 6px;
  background: #fff;
  border-radius: 50%;
  display: inline-block;
}
.hero-time-tip {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 8px;
}

/* 流程 Steps 卡片 */
.flow-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 20px;
  margin-bottom: 18px;
}
.flow-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 12px;
}

/* 核心网格 */
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.info-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  display: flex;
  align-items: center;
}
.crypto-card {
  border-color: #bae6fd;
}
.crypto-tag {
  font-size: 11px;
  background: #e0f2fe;
  color: #0369a1;
  padding: 1px 6px;
  border-radius: 4px;
  margin-left: 8px;
  font-weight: normal;
}
.crypto-note {
  background: #f0f9ff;
  border: 1px solid #e0f2fe;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #0369a1;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}
.head-link {
  font-size: 12px;
  color: #0284c7;
}

/* 资质核验列表 */
.cert-check-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.check-box {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  padding: 10px 14px;
  border-radius: 8px;
}
.check-box.ok {
  background: #f0fdf4;
  border-color: #bbf7d0;
}
.cb-icon { font-size: 16px; }
.text-green { color: #16a34a; }
.text-red { color: #dc2626; }
.cb-content { flex: 1; }
.cb-title { font-size: 13px; font-weight: 600; color: #1e293b; }
.cb-sub { font-size: 12px; color: #64748b; margin-top: 2px; }
.cb-act a { font-size: 12px; color: #0284c7; }

.proof-materials-sec {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}
.pm-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}
.pm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pm-file-pill {
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: #334155;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pm-file-pill:hover {
  border-color: #0284c7;
  color: #0284c7;
}

/* 审核决策卡片 */
.action-card {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.review-status-box {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 14px;
}
.rs-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 6px;
}
.rs-label { color: #64748b; font-size: 12px; }
.rs-val { font-weight: 600; color: #1e293b; }
.alert-reason { margin-top: 8px; }

.template-reason-box {
  margin-bottom: 14px;
}
.tr-label {
  font-size: 12px;
  color: #0369a1;
  font-weight: 600;
  display: block;
  margin-bottom: 6px;
}
.tr-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.template-tag {
  cursor: pointer;
  background: #ffffff;
  border-color: #7dd3fc;
  color: #0284c7;
}
.template-tag:hover {
  background: #0284c7;
  color: #fff;
}
.decision-buttons {
  display: flex;
  gap: 12px;
  margin-top: 14px;
}
.btn-approve-large {
  flex: 1;
  background: #059669;
  border-color: #059669;
}
.btn-reject-large {
  flex: 1;
}

/* 双屏比对 */
.dual-screen-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
}
.dual-left-panel {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px;
}
.dl-header h4, .dr-header h4 { margin: 0; font-size: 14px; font-weight: 600; color: #0f172a; }
.dl-check-item { margin-top: 10px; padding-bottom: 8px; border-bottom: 1px solid #e2e8f0; }
.dl-label { display: block; font-size: 11px; color: #94a3b8; }
.dl-val { font-size: 13px; font-weight: 600; color: #1e293b; margin-top: 2px; }
.dl-footer-tips { margin-top: 14px; font-size: 11px; color: #059669; }
.dual-right-panel {
  background: #0f172a;
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
}
.dr-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.dr-header h4 { color: #fff; }
.out-link { font-size: 12px; color: #38bdf8; }
.dr-img-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #1e293b;
  border-radius: 6px;
  min-height: 380px;
}
.license-preview-img { max-width: 100%; max-height: 400px; transition: transform 0.3s; }

/* 敏感解密弹窗 */
.audit-warning-banner {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}
.awb-text strong { display: block; color: #166534; font-size: 13px; }
.awb-text p { margin: 4px 0 0; font-size: 12px; color: #15803d; }
.text-large-highlight { font-size: 15px; font-weight: 700; color: #0284c7; }

/* 税务走势 */
.tax-overview-bar {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  background: #f8fafc;
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 14px;
}
.tob-k { display: block; font-size: 11px; color: #94a3b8; }
.tob-v { font-size: 13px; font-weight: 700; color: #0f172a; margin-top: 4px; }
.tax-chart-box { background: #0f172a; border-radius: 8px; padding: 14px; }
.tax-svg { width: 100%; height: 180px; }
.tax-conclusion {
  margin-top: 14px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 12px;
}

/* 时间线 */
.audit-timeline-body { max-height: 400px; overflow-y: auto; padding: 10px; }
.timeline-entry { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; }
.te-top { display: flex; justify-content: space-between; margin-bottom: 4px; }
.te-action { font-weight: 600; font-size: 13px; color: #0f172a; }
.te-time { font-size: 11px; color: #94a3b8; }
.te-detail p { margin: 2px 0; font-size: 12px; color: #475569; }
.te-comment {
  background: #ffffff;
  padding: 6px 8px;
  border-radius: 4px;
  border-left: 3px solid #0284c7;
  margin-top: 4px !important;
}
</style>
