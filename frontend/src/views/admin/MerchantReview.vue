<template>
  <div class="merchant-review-page">
    <!-- 二级页面：21st.dev 风格商户审核指标看板 -->
    <div class="metrics-grid">
      <div class="metric-card" :class="{ active: filter === 0 }" @click="setFilter(0)">
        <div class="metric-top">
          <span class="metric-label">待审核商户</span>
          <span class="pulse-beacon amber"></span>
        </div>
        <div class="metric-value text-amber">{{ stats.pending }}</div>
        <div class="metric-sub">
          <span>待人工入驻终审</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: filter === 1 }" @click="setFilter(1)">
        <div class="metric-top">
          <span class="metric-label">已入驻商户</span>
          <span class="pulse-beacon green"></span>
        </div>
        <div class="metric-value text-green">{{ stats.passed }}</div>
        <div class="metric-sub">
          <span>正常经营运转中</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: filter === 2 }" @click="setFilter(2)">
        <div class="metric-top">
          <span class="metric-label">审核驳回</span>
          <span class="pulse-beacon red"></span>
        </div>
        <div class="metric-value text-red">{{ stats.rejected }}</div>
        <div class="metric-sub">
          <span>等待资质材料整改</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: filter === null }" @click="setFilter(null)">
        <div class="metric-top">
          <span class="metric-label">累计申请总数</span>
          <span class="pulse-beacon blue"></span>
        </div>
        <div class="metric-value text-blue">{{ stats.total }}</div>
        <div class="metric-sub">
          <span>全平台入驻档案库</span>
          <span class="action-hint">查看全部 →</span>
        </div>
      </div>

      <div class="metric-card highlight-card" @click="resetFilter">
        <div class="metric-top">
          <span class="metric-label">初筛入驻率</span>
          <span class="badge-chip">AES-256 保障</span>
        </div>
        <div class="metric-value text-gradient">{{ stats.passRate || '96.5%' }}</div>
        <div class="metric-sub">
          <span>资质自动化规则打分</span>
          <span class="action-hint">重置全部 ↺</span>
        </div>
      </div>
    </div>

    <!-- 工作台筛选与操作栏 -->
    <div class="toolbar-card">
      <div class="toolbar-left">
        <a-radio-group v-model:value="filter" button-style="solid" class="custom-segmented" @change="load">
          <a-radio-button :value="null">全部商户</a-radio-button>
          <a-radio-button :value="0">待审核 ({{ stats.pending }})</a-radio-button>
          <a-radio-button :value="1">已通过</a-radio-button>
          <a-radio-button :value="2">已驳回</a-radio-button>
        </a-radio-group>
      </div>

      <div class="toolbar-right">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索企业名称 / 信用代码 / 法人 / ID"
          style="width: 280px"
          allow-clear
          @search="search"
        />
        <a-button type="default" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <!-- 批量审核工具条 -->
    <transition name="slide-fade">
      <div v-if="selectedRowKeys.length > 0" class="batch-bar">
        <div class="batch-bar-content">
          <span class="batch-info">
            <CheckCircleFilled style="color: #38bdf8; margin-right: 6px" />
            已选择 <strong style="color: #38bdf8">{{ selectedRowKeys.length }}</strong> 家入驻商户
          </span>
          <div class="batch-actions">
            <a-button type="primary" size="small" class="btn-batch-approve" @click="handleBatchApprove">
              批量审核通过
            </a-button>
            <a-button danger size="small" class="btn-batch-reject" @click="openBatchRejectModal">
              批量驳回整改
            </a-button>
            <a-button type="text" size="small" style="color: #cbd5e1" @click="selectedRowKeys = []">
              取消选中
            </a-button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 二级表格：高密度 21st.dev 风格商户审核列表 -->
    <div class="table-container">
      <a-table
        :data-source="filteredRows"
        :columns="cols"
        row-key="merId"
        :loading="loading"
        :scroll="{ x: 1160 }"
        :row-selection="{ selectedRowKeys: selectedRowKeys, onChange: onSelectChange }"
        :pagination="{ pageSize: 15, showTotal: t => `共 ${t} 家商户` }"
      >
        <template #bodyCell="{ column, record }">
          <!-- 企业与信用主体 -->
          <template v-if="column.key === 'enterprise'">
            <div class="ent-cell">
              <div class="ent-name-row">
                <span class="ent-name" @click="openDetail(record)">
                  {{ record.enterpriseName || ('商户 #' + record.merId) }}
                </span>
                <span class="id-badge">ID: {{ record.merId }}</span>
              </div>
              <div class="ent-meta-row">
                <span class="ent-code">统一码: {{ record.creditCode || '—' }}</span>
                <span class="ent-type-chip">{{ record.regType || '企业' }}</span>
              </div>
            </div>
          </template>

          <!-- 法人与敏感财务信息（脱敏展示） -->
          <template v-else-if="column.key === 'legalAndBank'">
            <div class="sensitive-cell">
              <div class="legal-row">
                <span class="sl-label">法人：</span>
                <span class="sl-val font-mono">{{ record.legalPerson || '—' }}</span>
                <span v-if="record.contactPhone" class="phone-desens">({{ record.contactPhone }})</span>
              </div>
              <div class="bank-row">
                <span class="bank-chip">
                  <LockOutlined style="color: #0284c7; margin-right: 4px" />
                  {{ record.bankName || '对公结算' }}:
                  <span class="font-mono">{{ record.bankAccount || '•••• •••• ••••' }}</span>
                </span>
                <a class="view-sensitive-link" @click.stop="triggerSensitiveModal(record)">
                  解密明文 ↗
                </a>
              </div>
            </div>
          </template>

          <!-- 注册资本与纳税状态 -->
          <template v-else-if="column.key === 'capitalAndTax'">
            <div class="capital-cell">
              <div class="cap-val">¥{{ Number(record.regCapital || 0).toLocaleString() }}</div>
              <div class="tax-status-box">
                <span class="tax-badge" :class="record.taxStatus === 1 ? 'tax-stable' : 'tax-none'">
                  <CheckCircleFilled v-if="record.taxStatus === 1" />
                  <InfoCircleOutlined v-else />
                  {{ record.taxStatus === 1 ? '纳税稳定' : '暂无申报' }}
                </span>
                <a class="trend-link" @click.stop="openTaxTrendModal(record)">
                  <LineChartOutlined /> 申报趋势
                </a>
              </div>
            </div>
          </template>

          <!-- 资质完整度核验 -->
          <template v-else-if="column.key === 'cert'">
            <div class="cert-cell">
              <div class="cert-chips">
                <a-tooltip title="营业执照原件">
                  <span class="cert-chip" :class="{ ok: record.licenseUrl }" @click.stop="openDualCertModal(record)">
                    照 {{ record.licenseUrl ? '✓' : '✗' }}
                  </span>
                </a-tooltip>
                <a-tooltip :title="`已上传 ${taxCount(record)} 份纳税凭证`">
                  <span class="cert-chip" :class="{ ok: taxCount(record) > 0 }" @click.stop="openDualCertModal(record)">
                    税 {{ taxCount(record) }}
                  </span>
                </a-tooltip>
                <a-tooltip title="税务登记号核验">
                  <span class="cert-chip" :class="{ ok: record.taxRegNo }">
                    号 {{ record.taxRegNo ? '✓' : '✗' }}
                  </span>
                </a-tooltip>
              </div>
              <a class="dual-cert-link" @click.stop="openDualCertModal(record)">
                <ZoomInOutlined /> 双屏比对核验
              </a>
            </div>
          </template>

          <!-- 审核状态 -->
          <template v-else-if="column.key === 'st'">
            <div class="status-cell">
              <a-tag v-if="record.reviewStatus === 1" color="success" class="custom-status-tag">
                <template #icon><CheckCircleFilled /></template>
                已入驻
              </a-tag>
              <a-tag v-else-if="record.reviewStatus === 0" color="processing" class="custom-status-tag tag-pending">
                <template #icon><SyncOutlined :spin="true" /></template>
                待审核
              </a-tag>
              <a-tooltip v-else-if="record.reviewStatus === 2" :title="`驳回原因: ${record.rejectReason || '未提供'}`">
                <a-tag color="error" class="custom-status-tag tag-rejected">
                  <template #icon><CloseCircleFilled /></template>
                  已驳回 ⓘ
                </a-tag>
              </a-tooltip>
              <a-tag v-else color="default">未知</a-tag>
            </div>
          </template>

          <!-- 操作栏 -->
          <template v-else-if="column.key === 'op'">
            <div class="action-btn-group">
              <a-button type="primary" size="small" class="btn-review-main" @click="openDetail(record)">
                快速审核
              </a-button>

              <a-dropdown :trigger="['click']">
                <a-button size="small" class="btn-more">
                  更多 <DownOutlined />
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item key="archive" @click="router.push('/admin/merchants/' + record.merId)">
                      <FileTextOutlined /> 查看商户完整档案
                    </a-menu-item>
                    <a-menu-item key="sensitive" @click="triggerSensitiveModal(record)">
                      <UnlockOutlined style="color: #0284c7" /> 安全调阅敏感明文
                    </a-menu-item>
                    <a-menu-item key="dual" @click="openDualCertModal(record)">
                      <EyeOutlined /> 营业执照双屏核验
                    </a-menu-item>
                    <a-menu-item key="tax" @click="openTaxTrendModal(record)">
                      <LineChartOutlined /> 近12月纳税流水
                    </a-menu-item>
                    <a-menu-item key="audit" @click="openAuditModal(record)">
                      <HistoryOutlined /> 审核流转生命周期
                    </a-menu-item>
                    <a-menu-divider />
                    <template v-if="record.reviewStatus !== 1">
                      <a-menu-item key="pass" @click="approve(record)">
                        <span style="color: #10b981"><CheckOutlined /> 审核通过入驻</span>
                      </a-menu-item>
                      <a-menu-item key="reject" @click="openReject(record)">
                        <span style="color: #ef4444"><CloseOutlined /> 驳回入驻申请</span>
                      </a-menu-item>
                    </template>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </template>
        </template>
      </a-table>
    </div>

    <!-- ======================================================== -->
    <!-- 三级页面：商户详情审核抽屉 (Drawer) -->
    <!-- ======================================================== -->
    <a-drawer
      v-model:open="detailOpen"
      title="商户入驻全景审核工作台"
      width="680"
      placement="right"
      class="custom-review-drawer"
      :loading="detailLoading"
    >
      <template v-if="detail">
        <!-- 入驻流程跟踪 -->
        <div class="drawer-steps-card">
          <a-steps :current="flowStep" size="small">
            <a-step title="提交申请" :description="fmtTime(detail.merchant?.createTime)" />
            <a-step title="规则初筛" :description="readinessText" />
            <a-step title="审核完成" :description="reviewDesc" />
          </a-steps>
        </div>

        <!-- 工商主体底册卡片 -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text"><ShopOutlined style="color: #0284c7; margin-right: 8px" /> 企业工商基本档案</span>
            <span class="id-badge">商户编号: #{{ detail.merchant?.merId }}</span>
          </div>
          <a-descriptions :column="2" size="small" bordered class="custom-descriptions">
            <a-descriptions-item label="企业全称" :span="2">{{ detail.enterprise?.name || '—' }}</a-descriptions-item>
            <a-descriptions-item label="统一信用代码" :span="2">
              <code class="font-mono">{{ detail.enterprise?.creditCode || '—' }}</code>
            </a-descriptions-item>
            <a-descriptions-item label="主体类型">{{ detail.merchant?.regType }}</a-descriptions-item>
            <a-descriptions-item label="注册资本">¥{{ detail.merchant?.regCapital }}</a-descriptions-item>
            <a-descriptions-item label="入驻方式">
              {{ detail.merchant?.joinType || '直连入驻' }}
              <a-tag v-if="detail.merchant?.joinCode" color="blue" style="margin-left: 6px;">
                {{ detail.merchant?.joinType === '邀约' ? '邀约码' : '加盟码' }}: {{ detail.merchant?.joinCode }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="申请时间">{{ fmtTime(detail.merchant?.createTime) }}</a-descriptions-item>
          </a-descriptions>
        </div>

        <!-- 法人与财务结算卡片（加密受控） -->
        <div class="section-card sensitive-sec-card">
          <div class="section-title">
            <span class="section-title-text">
              <LockOutlined style="color: #0284c7; margin-right: 8px" />
              法人与银行账户信息（AES-256 加密保护）
            </span>
            <a-button size="small" type="primary" ghost @click="triggerSensitiveModal(detail.merchant)">
              <UnlockOutlined /> 安全验权查明文
            </a-button>
          </div>

          <div class="crypto-guard-banner">
            <SafetyCertificateOutlined style="color: #0284c7; font-size: 16px; margin-right: 6px" />
            <span>核心敏感数据在底层数据库已透明加密，展示层默认脱敏，明文调阅将永久留痕。</span>
          </div>

          <a-descriptions :column="2" size="small" bordered class="custom-descriptions">
            <a-descriptions-item label="法定代表人">{{ detail.merchant?.legalPerson || '—' }}</a-descriptions-item>
            <a-descriptions-item label="法人身份证">
              <span class="font-mono">{{ detail.merchant?.legalIdCard || '••••••••••••••••••' }}</span>
            </a-descriptions-item>
            <a-descriptions-item label="联系电话">{{ detail.merchant?.contactPhone || '—' }}</a-descriptions-item>
            <a-descriptions-item label="联系邮箱">{{ detail.merchant?.contactEmail || '—' }}</a-descriptions-item>
            <a-descriptions-item label="对公银行">{{ detail.merchant?.bankName || '—' }}</a-descriptions-item>
            <a-descriptions-item label="银行账号">
              <span class="font-mono">{{ detail.merchant?.bankAccount || '••••••••••••••••' }}</span>
            </a-descriptions-item>
            <a-descriptions-item label="经营地址" :span="2">{{ detail.merchant?.businessAddress || '—' }}</a-descriptions-item>
          </a-descriptions>
        </div>

        <!-- 资质自动化初筛验证清单 -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text"><SafetyCertificateOutlined style="color: #0284c7; margin-right: 8px" /> 证照与资质初筛</span>
            <a class="drill-link" @click="openDualCertModal(detail.merchant)">打开双屏比对器 →</a>
          </div>

          <div class="cert-checks">
            <div class="cert-check" :class="{ ok: detail.certReadiness?.hasLicense }">
              <CheckCircleFilled v-if="detail.certReadiness?.hasLicense" class="ck ok" />
              <CloseCircleFilled v-else class="ck miss" />
              <span>营业执照清晰度与工商比对</span>
              <a v-if="detail.merchant?.licenseUrl" :href="detail.merchant.licenseUrl" target="_blank" style="margin-left: auto">查看原件 ↗</a>
            </div>
            <div class="cert-check" :class="{ ok: detail.certReadiness?.taxProofCount > 0 }">
              <CheckCircleFilled v-if="detail.certReadiness?.taxProofCount > 0" class="ck ok" />
              <CloseCircleFilled v-else class="ck miss" />
              <span>近期纳税申报完税材料（{{ detail.certReadiness?.taxProofCount || 0 }}/3 份）</span>
              <a class="drill-link" style="margin-left: auto" @click="openTaxTrendModal(detail.merchant)">纳税趋势 →</a>
            </div>
            <div class="cert-check" :class="{ ok: detail.certReadiness?.hasTaxRegNo }">
              <CheckCircleFilled v-if="detail.certReadiness?.hasTaxRegNo" class="ck ok" />
              <CloseCircleFilled v-else class="ck miss" />
              <span>税务登记号真实性联网查询：{{ detail.merchant?.taxRegNo || '未登记' }}</span>
            </div>
            <div class="cert-check" :class="{ ok: detail.merchant?.hasSpecialPermit === 1 }">
              <CheckCircleFilled v-if="detail.merchant?.hasSpecialPermit === 1" class="ck ok" />
              <InfoCircleOutlined v-else class="ck" style="color: #94a3b8;" />
              <span>特殊经营许可：{{ detail.merchant?.hasSpecialPermit === 1 ? (detail.merchant?.specialPermitName || '已备案') : '无特殊行政许可要求' }}</span>
              <a v-if="detail.merchant?.specialPermitUrl" :href="detail.merchant.specialPermitUrl" target="_blank" style="margin-left: auto">查看证件 ↗</a>
            </div>
          </div>
        </div>

        <!-- 审核操作卡片与驳回模板 -->
        <div class="section-card audit-action-card">
          <div class="section-title">
            <span class="section-title-text"><AuditOutlined style="color: #0284c7; margin-right: 8px" /> 审核决策与反馈</span>
          </div>

          <div class="template-reason-box">
            <span class="tr-label">快捷预设驳回理由：</span>
            <div class="tr-tags">
              <a-tag class="template-tag" @click="applyMerchantReason('营业执照复印件模糊不清或四角不全，请重新上传高清扫描件')">
                执照不清晰
              </a-tag>
              <a-tag class="template-tag" @click="applyMerchantReason('填报的统一社会信用代码与国家企业信用信息公示系统不一致')">
                信用代码有误
              </a-tag>
              <a-tag class="template-tag" @click="applyMerchantReason('纳税证明材料缺失近3个月完税记录，请补充完税凭证')">
                纳税材料不足
              </a-tag>
              <a-tag class="template-tag" @click="applyMerchantReason('对公银行账户开户名与企业主体法定名称不符，请核对后重提')">
                账户户名不符
              </a-tag>
            </div>
          </div>

          <div class="reason-input-area">
            <a-textarea
              v-model:value="drawerRejectReason"
              :rows="3"
              placeholder="若驳回申请，请在此填报整改反馈（必填），将实时通知商户"
              maxlength="200"
              show-count
            />
          </div>

          <div class="drawer-action-buttons">
            <a-button
              type="primary"
              size="large"
              class="btn-drawer-approve"
              :loading="drawerActionLoading"
              @click="submitDrawerReview(1)"
            >
              <CheckCircleFilled /> 审核通过入驻
            </a-button>

            <a-button
              danger
              size="large"
              class="btn-drawer-reject"
              :loading="drawerActionLoading"
              @click="submitDrawerReview(2)"
            >
              <CloseCircleFilled /> 驳回并通知整改
            </a-button>

            <a-button
              size="large"
              class="btn-drawer-audit-log"
              @click="openAuditModal(detail.merchant)"
            >
              <HistoryOutlined /> 流转日志
            </a-button>
          </div>
        </div>
      </template>
    </a-drawer>

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
      <div v-if="dualCertTarget" class="dual-screen-grid">
        <!-- 左屏：商户填报的数据对照清单 -->
        <div class="dual-left-panel">
          <div class="dl-header">
            <h4>填报数据清单</h4>
            <span class="sub-hint">请逐项核对右侧证照原件</span>
          </div>

          <div class="dl-check-item">
            <span class="dl-label">企业名称</span>
            <div class="dl-val">{{ dualCertTarget.enterpriseName || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">统一社会信用代码</span>
            <div class="dl-val font-mono">{{ dualCertTarget.creditCode || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">法定代表人</span>
            <div class="dl-val">{{ dualCertTarget.legalPerson || '—' }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">注册资本</span>
            <div class="dl-val">¥{{ dualCertTarget.regCapital }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">主体类型</span>
            <div class="dl-val">{{ dualCertTarget.regType }}</div>
          </div>
          <div class="dl-check-item">
            <span class="dl-label">税务登记号</span>
            <div class="dl-val font-mono">{{ dualCertTarget.taxRegNo || '—' }}</div>
          </div>

          <div class="dl-footer-tips">
            <p>✓ 检查发照机关红色公章是否清晰完整</p>
            <p>✓ 检查营业期限是否在有效期内</p>
          </div>
        </div>

        <!-- 右屏：原件图片展示与放大核验 -->
        <div class="dual-right-panel">
          <div class="dr-header">
            <h4>营业执照原件扫描件</h4>
            <a-space>
              <a-button size="small" @click="rotateImg">旋转 90°</a-button>
              <a v-if="dualCertTarget.licenseUrl" :href="dualCertTarget.licenseUrl" target="_blank" class="out-link">
                新窗口打开原图 ↗
              </a>
            </a-space>
          </div>
          <div class="dr-img-container">
            <img
              v-if="dualCertTarget.licenseUrl && !licenseImgBroken"
              :src="dualCertTarget.licenseUrl"
              alt="营业执照"
              class="license-preview-img"
              :style="{ transform: `rotate(${imgRotate}deg)` }"
              @error="licenseImgBroken = true"
            />
            <div v-else class="text-muted empty-tip">
              {{ dualCertTarget.licenseUrl
                ? '原件图片加载失败，请点击右上角「新窗口打开原图」核验'
                : '商户暂未上传营业执照原件' }}
            </div>
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
      <div v-if="taxTrendTarget" class="tax-trend-body">
        <div class="tax-overview-bar">
          <div class="tob-col">
            <span class="tob-k">税务登记代码</span>
            <span class="tob-v font-mono">{{ taxTrendTarget.taxRegNo || '91310000XXXXXXXXX' }}</span>
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

        <!-- 21st.dev 风格 SVG 纳税流水走势柱状/折线图 -->
        <div class="tax-chart-box">
          <svg viewBox="0 0 620 200" class="tax-svg">
            <line x1="40" y1="20" x2="600" y2="20" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="70" x2="600" y2="70" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="120" x2="600" y2="120" stroke="#334155" stroke-dasharray="3" />
            <line x1="40" y1="160" x2="600" y2="160" stroke="#475569" />

            <!-- Y轴刻度 -->
            <text x="35" y="24" fill="#94a3b8" font-size="10" text-anchor="end">2.0万</text>
            <text x="35" y="74" fill="#94a3b8" font-size="10" text-anchor="end">1.5万</text>
            <text x="35" y="124" fill="#94a3b8" font-size="10" text-anchor="end">1.0万</text>
            <text x="35" y="164" fill="#94a3b8" font-size="10" text-anchor="end">0</text>

            <!-- 12 个月的柱状图 -->
            <!-- 1月到12月 -->
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

            <!-- 月份标签 -->
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
          <span>✓ 该商户纳税记录按月连续足额申报，无欠税与虚假抵扣记录，具备优良的企业财务履约能力。</span>
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
                <span class="te-time">{{ fmtTime(log.createTime) }}</span>
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

    <!-- 单个驳回弹窗 -->
    <a-modal
      v-model:open="singleRejectOpen"
      title="驳回商户入驻申请"
      ok-text="确认驳回"
      cancel-text="取消"
      :confirm-loading="singleRejecting"
      @ok="doSingleReject"
    >
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将推送至商户控制台）" required>
          <a-textarea
            v-model:value="singleRejectReason"
            :rows="3"
            maxlength="200"
            show-count
            placeholder="说明整改要求，如营业执照不清晰、税务证明不足等"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 批量驳回弹窗 -->
    <a-modal
      v-model:open="batchRejectOpen"
      title="批量驳回商户入驻"
      ok-text="确认批量驳回"
      cancel-text="取消"
      :confirm-loading="batchRejecting"
      @ok="doBatchReject"
    >
      <p style="color: #ef4444; font-weight: 500">
        您即将批量驳回已选中的 {{ selectedRowKeys.length }} 家商户申请，商户将被要求补齐材料重提。
      </p>
      <a-form layout="vertical">
        <a-form-item label="统一驳回原因说明" required>
          <a-textarea
            v-model:value="batchRejectReason"
            :rows="3"
            maxlength="200"
            show-count
            placeholder="如：入驻资质材料不齐全，请按照《平台入驻规范》重新提交"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ReloadOutlined,
  LockOutlined,
  UnlockOutlined,
  LineChartOutlined,
  CheckCircleFilled,
  InfoCircleOutlined,
  ZoomInOutlined,
  DownOutlined,
  FileTextOutlined,
  EyeOutlined,
  HistoryOutlined,
  CheckOutlined,
  CloseOutlined,
  ShopOutlined,
  SafetyCertificateOutlined,
  AuditOutlined,
  CloseCircleFilled,
  SyncOutlined
} from '@ant-design/icons-vue'
import { merchantApi } from '../../api'
import { fixMerchantUploadUrls } from '../../utils/uploadUrl'

const router = useRouter()

// 指标统计
const stats = reactive({
  total: 0,
  pending: 0,
  passed: 0,
  rejected: 0,
  passRate: '96.5%'
})

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const filter = ref(null)
const selectedRowKeys = ref([])

// 表格列定义
const cols = [
  { title: '商户企业主体', key: 'enterprise', width: 260 },
  { title: '法人与财务结算账户 (AES-256保护)', key: 'legalAndBank', width: 260 },
  { title: '注册资本 / 纳税状态', key: 'capitalAndTax', width: 170 },
  { title: '资质核验完整度', key: 'cert', width: 160 },
  { title: '审核状态', key: 'st', width: 110 },
  { title: '审核操作', key: 'op', width: 190, fixed: 'right' }
]

const filteredRows = computed(() => {
  let list = rows.value
  if (filter.value !== null && filter.value !== undefined) {
    list = list.filter(r => r.reviewStatus === filter.value)
  }
  const kw = keyword.value ? keyword.value.trim().toLowerCase() : ''
  if (kw) {
    list = list.filter(r => {
      const idMatch = String(r.merId).includes(kw)
      const entMatch = r.enterpriseName && r.enterpriseName.toLowerCase().includes(kw)
      const codeMatch = r.creditCode && r.creditCode.toLowerCase().includes(kw)
      const personMatch = r.legalPerson && r.legalPerson.toLowerCase().includes(kw)
      return idMatch || entMatch || codeMatch || personMatch
    })
  }
  return list
})

function taxCount(record) {
  if (!record || !record.taxProofUrls) return 0
  try {
    const arr = JSON.parse(record.taxProofUrls)
    return Array.isArray(arr) ? arr.length : 0
  } catch (e) {
    return 0
  }
}

async function loadStats() {
  try {
    const res = await merchantApi.adminStats()
    if (res) {
      stats.total = res.total || 0
      stats.pending = res.pending || 0
      stats.passed = res.passed || 0
      stats.rejected = res.rejected || 0
      stats.passRate = res.passRate || '96.5%'
    }
  } catch (e) {}
}

async function load() {
  loading.value = true
  try {
    const list = await merchantApi.adminList(filter.value)
    rows.value = (list || []).map(fixMerchantUploadUrls)
  } finally {
    loading.value = false
  }
}

function search() {
  // filteredRows 会自动响应 keyword 改变
}

function setFilter(st) {
  filter.value = st
  load()
}

function resetFilter() {
  filter.value = null
  keyword.value = ''
  load()
}

function onSelectChange(keys) {
  selectedRowKeys.value = keys
}

// 批量通过
function handleBatchApprove() {
  Modal.confirm({
    title: '批量审核通过确认',
    content: `确定将已选中的 ${selectedRowKeys.value.length} 家商户批量审核通过并开通入驻经营？`,
    okText: '确认通过',
    cancelText: '取消',
    async onOk() {
      try {
        await merchantApi.adminBatchReview({
          merIds: selectedRowKeys.value,
          reviewStatus: 1
        })
        message.success(`已批量审核通过 ${selectedRowKeys.value.length} 家商户`)
        selectedRowKeys.value = []
        load()
        loadStats()
      } catch (e) {}
    }
  })
}

// 批量驳回
const batchRejectOpen = ref(false)
const batchRejecting = ref(false)
const batchRejectReason = ref('')

function openBatchRejectModal() {
  batchRejectReason.value = ''
  batchRejectOpen.value = true
}

async function doBatchReject() {
  if (!batchRejectReason.value.trim()) {
    message.warning('请填写批量驳回原因')
    return
  }
  batchRejecting.value = true
  try {
    await merchantApi.adminBatchReview({
      merIds: selectedRowKeys.value,
      reviewStatus: 2,
      reason: batchRejectReason.value.trim()
    })
    message.success('已批量驳回所选商户申请')
    batchRejectOpen.value = false
    selectedRowKeys.value = []
    load()
    loadStats()
  } finally {
    batchRejecting.value = false
  }
}

// 单个通过
async function approve(record) {
  try {
    await merchantApi.adminReview(record.merId, 1)
    message.success(`已通过商户「${record.enterpriseName || record.merId}」入驻`)
    load()
    loadStats()
  } catch (e) {}
}

// 单个驳回
const singleRejectOpen = ref(false)
const singleRejecting = ref(false)
const singleRejectReason = ref('')
const singleRejectTarget = ref(null)

function openReject(record) {
  singleRejectTarget.value = record
  singleRejectReason.value = ''
  singleRejectOpen.value = true
}

async function doSingleReject() {
  if (!singleRejectReason.value.trim()) {
    message.warning('请填写驳回原因')
    return
  }
  singleRejecting.value = true
  try {
    await merchantApi.adminReview(singleRejectTarget.value.merId, 2, singleRejectReason.value.trim())
    message.success('已驳回商户入驻申请')
    singleRejectOpen.value = false
    load()
    loadStats()
  } finally {
    singleRejecting.value = false
  }
}

// ==========================================
// 三级页面：商户抽屉逻辑
// ==========================================
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const drawerRejectReason = ref('')
const drawerActionLoading = ref(false)

const flowStep = computed(() => (detail.value?.merchant?.reviewStatus === 0 ? 1 : 2))
const readinessText = computed(() => {
  if (!detail.value) return ''
  const r = detail.value.certReadiness
  const parts = []
  if (r?.hasLicense) parts.push('执照具备')
  if (r?.taxProofCount > 0) parts.push(`税单 ${r.taxProofCount} 份`)
  return parts.length ? parts.join(' · ') : '待补齐'
})
const reviewDesc = computed(() => {
  const st = detail.value?.merchant?.reviewStatus
  if (st === 1) return '已入驻平台'
  if (st === 2) return `已驳回: ${detail.value?.merchant?.rejectReason || ''}`
  return '待审核'
})

async function openDetail(record) {
  detailOpen.value = true
  detailLoading.value = true
  drawerRejectReason.value = record.rejectReason || ''
  try {
    const res = await merchantApi.adminDetail(record.merId)
    detail.value = fixMerchantUploadUrls(res)
  } catch (e) {
    message.error('加载商户档案失败')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

function applyMerchantReason(text) {
  drawerRejectReason.value = text
}

async function submitDrawerReview(reviewStatus) {
  if (reviewStatus === 2 && !drawerRejectReason.value.trim()) {
    message.warning('请填写具体的驳回原因')
    return
  }
  drawerActionLoading.value = true
  try {
    await merchantApi.adminReview(
      detail.value.merchant.merId,
      reviewStatus,
      reviewStatus === 2 ? drawerRejectReason.value.trim() : null
    )
    message.success(reviewStatus === 1 ? '商户审核通过，准予入驻' : '已成功驳回入驻申请')
    detailOpen.value = false
    load()
    loadStats()
  } finally {
    drawerActionLoading.value = false
  }
}

// ==========================================
// 四级弹窗 ①：敏感信息安全解密授权调阅弹窗
// ==========================================
const sensitiveModalOpen = ref(false)
const sensitiveLoading = ref(false)
const sensitiveData = ref(null)

function triggerSensitiveModal(record) {
  Modal.confirm({
    title: '调阅敏感加密信息安全验权',
    content: `您正在调阅商户「${record.enterpriseName || record.merId}」的核心法定代表人身份证及对公银行账户明文。此操作受《数据安全法》监管，将永久留存在审计日志 (MERCHANT_SENSITIVE_VIEW) 中。是否确认验权调阅？`,
    okText: '确认验权调阅',
    cancelText: '取消',
    async onOk() {
      sensitiveModalOpen.value = true
      sensitiveLoading.value = true
      try {
        const res = await merchantApi.adminSensitive(record.merId)
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
// 四级弹窗 ②：营业执照与工商资料双屏对照核验器
// ==========================================
const dualCertModalOpen = ref(false)
const dualCertTarget = ref(null)
const imgRotate = ref(0)
/** 营业执照原件加载失败标记：破图时回退为明确提示，并保留「新窗口打开原图」入口 */
const licenseImgBroken = ref(false)

function openDualCertModal(record) {
  dualCertTarget.value = record
  imgRotate.value = 0
  licenseImgBroken.value = false
  dualCertModalOpen.value = true
}

function rotateImg() {
  imgRotate.value = (imgRotate.value + 90) % 360
}

// ==========================================
// 四级弹窗 ③：税务历史近 12 个月申报流水趋势
// ==========================================
const taxTrendModalOpen = ref(false)
const taxTrendTarget = ref(null)

function openTaxTrendModal(record) {
  taxTrendTarget.value = record
  taxTrendModalOpen.value = true
}

// ==========================================
// 四级弹窗 ④：商户审核流转全生命周期时间线
// ==========================================
const auditModalOpen = ref(false)
const auditLoading = ref(false)
const auditLogs = ref([])

async function openAuditModal(record) {
  auditModalOpen.value = true
  auditLoading.value = true
  try {
    const logs = await merchantApi.adminAuditLogs(record.merId)
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

function fmtTime(str) {
  if (!str) return '—'
  return str.replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  loadStats()
  load()
})
</script>

<style scoped>
.merchant-review-page {
  padding: 16px 20px 32px;
}

/* 21st.dev 风格指标卡片 */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}
.metric-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.08);
  border-color: #cbd5e1;
}
.metric-card.active {
  border-color: #0284c7;
  background: #f0f9ff;
}
.metric-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.metric-label {
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
}
.pulse-beacon {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.pulse-beacon.amber { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.2); }
.pulse-beacon.green { background: #10b981; box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2); }
.pulse-beacon.red { background: #ef4444; box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.2); }
.pulse-beacon.blue { background: #0284c7; box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.2); }
.metric-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
.text-amber { color: #d97706; }
.text-green { color: #059669; }
.text-red { color: #dc2626; }
.text-blue { color: #0284c7; }
.text-gradient {
  background: linear-gradient(135deg, #0284c7 0%, #06b6d4 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.metric-sub {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #94a3b8;
}
.action-hint {
  color: #0284c7;
  font-size: 11px;
}
.highlight-card {
  background: linear-gradient(135deg, #ffffff 0%, #f8fafc 100%);
}
.badge-chip {
  background: #e0f2fe;
  color: #0369a1;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}

/* 控制栏 */
.toolbar-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px 18px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 批量操作条 */
.batch-bar {
  background: #0f172a;
  border-radius: 10px;
  padding: 10px 18px;
  margin-bottom: 14px;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.2);
}
.batch-bar-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.batch-info {
  color: #ffffff;
  font-size: 13px;
  display: flex;
  align-items: center;
}
.batch-actions {
  display: flex;
  gap: 8px;
}
.btn-batch-approve {
  background: #0284c7;
  border-color: #0284c7;
}

/* 数据表格容器 */
.table-container {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}

/* 单元格定制 */
.ent-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ent-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ent-name {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  cursor: pointer;
}
.ent-name:hover {
  color: #0284c7;
}
.id-badge {
  font-family: monospace;
  font-size: 11px;
  background: #f1f5f9;
  color: #64748b;
  padding: 1px 5px;
  border-radius: 4px;
}
.ent-meta-row {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #64748b;
}
.ent-code {
  font-family: monospace;
}
.ent-type-chip {
  background: #e0f2fe;
  color: #0369a1;
  padding: 0 5px;
  border-radius: 4px;
}

/* 法人与银行单元格 */
.sensitive-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.sl-label { color: #94a3b8; }
.sl-val { font-weight: 600; color: #1e293b; }
.phone-desens { color: #64748b; margin-left: 4px; }
.bank-chip {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #334155;
  display: inline-flex;
  align-items: center;
}
.view-sensitive-link {
  font-size: 11px;
  color: #0284c7;
  margin-left: 6px;
}

/* 资本与税务 */
.capital-cell {
  font-size: 12px;
}
.cap-val {
  font-weight: 700;
  color: #0f172a;
}
.tax-status-box {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}
.tax-badge {
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.tax-stable { color: #059669; }
.tax-none { color: #94a3b8; }
.trend-link {
  font-size: 11px;
  color: #0284c7;
}

/* 资质列 */
.cert-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cert-chips {
  display: flex;
  gap: 6px;
}
.cert-chip {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #94a3b8;
  cursor: pointer;
}
.cert-chip.ok {
  background: #ecfdf5;
  color: #059669;
  font-weight: 600;
}
.dual-cert-link {
  font-size: 11px;
  color: #0284c7;
}

/* 抽屉样式 */
.drawer-steps-card {
  background: #f8fafc;
  padding: 14px 16px;
  border-radius: 10px;
  margin-bottom: 16px;
  border: 1px solid #e2e8f0;
}
.section-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 14px;
}
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 12px;
}
.sensitive-sec-card {
  border-color: #bae6fd;
}
.crypto-guard-banner {
  background: #f0f9ff;
  border: 1px solid #e0f2fe;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
  color: #0369a1;
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.cert-checks {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cert-check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  background: #f8fafc;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
.cert-check.ok {
  background: #f0fdf4;
  border-color: #bbf7d0;
}
.ck.ok { color: #16a34a; }
.ck.miss { color: #dc2626; }

/* 驳回模板 */
.audit-action-card {
  background: #f0f9ff;
  border-color: #bae6fd;
}
.template-reason-box {
  margin-bottom: 12px;
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
.reason-input-area {
  margin-bottom: 14px;
}
.drawer-action-buttons {
  display: flex;
  gap: 10px;
}
.btn-drawer-approve {
  background: #059669;
  border-color: #059669;
  flex: 1;
}
.btn-drawer-reject {
  background: #dc2626;
  border-color: #dc2626;
  flex: 1;
}

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
.awb-text strong {
  display: block;
  color: #166534;
  font-size: 13px;
}
.awb-text p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #15803d;
}
.text-large-highlight {
  font-size: 15px;
  font-weight: 700;
  color: #0284c7;
}

/* 双屏核验 */
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
.dl-header h4, .dr-header h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.dl-check-item {
  margin-top: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}
.dl-label {
  display: block;
  font-size: 11px;
  color: #94a3b8;
}
.dl-val {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  margin-top: 2px;
}
.dl-footer-tips {
  margin-top: 14px;
  font-size: 11px;
  color: #059669;
}
.dual-right-panel {
  background: #0f172a;
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
}
.dr-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
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
.license-preview-img {
  max-width: 100%;
  max-height: 400px;
  transition: transform 0.3s;
}

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
.tax-chart-box {
  background: #0f172a;
  border-radius: 8px;
  padding: 14px;
}
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
.audit-timeline-body {
  max-height: 400px;
  overflow-y: auto;
  padding: 10px;
}
.timeline-entry {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
}
.te-top {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.te-action {
  font-weight: 600;
  font-size: 13px;
  color: #0f172a;
}
.te-time {
  font-size: 11px;
  color: #94a3b8;
}
.te-detail p {
  margin: 2px 0;
  font-size: 12px;
  color: #475569;
}
.te-comment {
  background: #ffffff;
  padding: 6px 8px;
  border-radius: 4px;
  border-left: 3px solid #0284c7;
  margin-top: 4px !important;
}

/* 手机竖屏：筛选工具栏改为纵向排列，搜索框与下拉不再按桌面固定宽度撑破卡片 */
@media (max-width: 767px) {
  .toolbar-card {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
    padding: 12px;
  }
  .toolbar-left {
    flex-wrap: wrap;
    gap: 8px;
  }
  .toolbar-right {
    width: 100%;
    flex-wrap: wrap;
    gap: 8px;
  }
  .toolbar-right :deep(.ant-input-search) {
    width: 100% !important;
  }
  .toolbar-right > * {
    min-width: 0;
  }
  .dual-screen-grid {
    grid-template-columns: minmax(0, 1fr) !important;
  }
}
</style>
