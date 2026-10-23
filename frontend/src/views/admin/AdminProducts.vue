<template>
  <div class="admin-products-page">
    <!-- 二级页面：21st.dev 风格数据概览指标卡 -->
    <div class="metrics-grid">
      <div class="metric-card" :class="{ active: rvFilter === 0 }" @click="setRvFilter(0)">
        <div class="metric-top">
          <span class="metric-label">待审核商品</span>
          <span class="pulse-beacon amber"></span>
        </div>
        <div class="metric-value text-amber">{{ stats.pending }}</div>
        <div class="metric-sub">
          <span>待人工介入核验</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: rvFilter === 1 }" @click="setRvFilter(1)">
        <div class="metric-top">
          <span class="metric-label">正常在售商品</span>
          <span class="pulse-beacon green"></span>
        </div>
        <div class="metric-value text-green">{{ stats.approved }}</div>
        <div class="metric-sub">
          <span>全平台合规上架中</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: rvFilter === 2 }" @click="setRvFilter(2)">
        <div class="metric-top">
          <span class="metric-label">审核驳回</span>
          <span class="pulse-beacon red"></span>
        </div>
        <div class="metric-value text-red">{{ stats.rejected }}</div>
        <div class="metric-sub">
          <span>等待商户整改重提</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card" :class="{ active: statusFilter === 0 }" @click="setStatusFilter(0)">
        <div class="metric-top">
          <span class="metric-label">已下架商品</span>
          <span class="pulse-beacon gray"></span>
        </div>
        <div class="metric-value text-muted">{{ stats.offShelf }}</div>
        <div class="metric-sub">
          <span>下架自查或暂售</span>
          <span class="action-hint">点击筛选 →</span>
        </div>
      </div>

      <div class="metric-card highlight-card" @click="resetFilters">
        <div class="metric-top">
          <span class="metric-label">综合过审率</span>
          <span class="badge-chip">全生命周期</span>
        </div>
        <div class="metric-value text-gradient">{{ stats.passRate || '100%' }}</div>
        <div class="metric-sub">
          <span>商品总数 {{ stats.total }} 件</span>
          <span class="action-hint">重置全部 ↺</span>
        </div>
      </div>
    </div>

    <!-- 工作台筛选与操作栏 -->
    <div class="toolbar-card">
      <div class="toolbar-left">
        <a-radio-group v-model:value="activeTab" button-style="solid" class="custom-segmented" @change="onTabChange">
          <a-radio-button value="all">全部商品</a-radio-button>
          <a-radio-button value="pending">待审核 ({{ stats.pending }})</a-radio-button>
          <a-radio-button value="approved">已通过</a-radio-button>
          <a-radio-button value="rejected">已驳回</a-radio-button>
          <a-radio-button value="off">已下架</a-radio-button>
        </a-radio-group>
      </div>

      <div class="toolbar-right">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索商品标题 / 品牌 / ID"
          style="width: 260px"
          allow-clear
          @search="search"
        />
        <a-button type="default" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <!-- 批量操作悬浮工具条 (当有多选时出现) -->
    <transition name="slide-fade">
      <div v-if="selectedRowKeys.length > 0" class="batch-bar">
        <div class="batch-bar-content">
          <span class="batch-info">
            <CheckCircleFilled style="color: #38bdf8; margin-right: 6px" />
            已选择 <strong style="color: #38bdf8">{{ selectedRowKeys.length }}</strong> 件商品
          </span>
          <div class="batch-actions">
            <a-button type="primary" size="small" class="btn-batch-approve" @click="handleBatchApprove">
              批量通过并上架
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

    <!-- 二级数据列表：高密度 21st.dev 表格 -->
    <div class="table-container">
      <a-table
        :data-source="rows"
        :columns="cols"
        row-key="prodId"
        :loading="loading"
        :row-selection="{ selectedRowKeys: selectedRowKeys, onChange: onSelectChange }"
        :pagination="{
          current,
          pageSize,
          total,
          showTotal: t => `共 ${t} 件商品`,
          showSizeChanger: true,
          onChange: (p, s) => { current = p; pageSize = s; load() }
        }"
      >
        <template #bodyCell="{ column, record }">
          <!-- 商品主体信息 -->
          <template v-if="column.key === 'prodInfo'">
            <div class="product-cell">
              <div class="prod-thumb-wrap" @click.stop="openDrawer(record)">
                <img :src="record.mainImg || '/placeholder-prod.png'" alt="img" class="prod-thumb" />
                <div class="thumb-hover-mask"><EyeOutlined /></div>
              </div>
              <div class="prod-meta">
                <div class="prod-title-line">
                  <span class="prod-title" :title="record.title" @click="openDrawer(record)">
                    {{ record.title }}
                  </span>
                </div>
                <div class="prod-tags">
                  <span class="id-badge">ID: {{ record.prodId }}</span>
                  <span v-if="record.brand" class="brand-badge">{{ record.brand }}</span>
                  <span class="merchant-link" @click.stop="openMerchantModal(record)">
                    商户 #{{ record.merId }} ↗
                  </span>
                </div>
              </div>
            </div>
          </template>

          <!-- 价格与起订量 -->
          <template v-else-if="column.key === 'pricing'">
            <div class="price-cell">
              <div class="main-price">¥{{ Number(record.price || 0).toFixed(2) }}</div>
              <div v-if="record.origPrice" class="orig-price">¥{{ Number(record.origPrice).toFixed(2) }}</div>
              <a class="curve-link" @click.stop="openPricingModal(record)">
                <LineChartOutlined /> 阶梯价测算
              </a>
            </div>
          </template>

          <!-- 库存与销量 -->
          <template v-else-if="column.key === 'stockAndSales'">
            <div class="stock-cell">
              <div class="stock-line">
                <span class="stock-label">库存</span>
                <span class="stock-val" :class="{ 'stock-low': record.stock < 10 }">{{ record.stock ?? 0 }}</span>
              </div>
              <div class="sales-line">
                <span class="sales-label">销量</span>
                <span class="sales-val">{{ record.sales ?? 0 }}</span>
              </div>
            </div>
          </template>

          <!-- 智能初筛合规评估 -->
          <template v-else-if="column.key === 'compliance'">
            <div class="compliance-badge-box">
              <span v-if="hasSensitiveWord(record.title)" class="compliance-pill danger" @click.stop="openViolationModal(record)">
                <WarningOutlined /> 检出极限词 ⓘ
              </span>
              <span v-else class="compliance-pill success">
                <CheckCircleOutlined /> 合规初筛正常
              </span>
            </div>
          </template>

          <!-- 审核状态 -->
          <template v-else-if="column.key === 'reviewStatus'">
            <div class="status-cell">
              <a-tag v-if="record.reviewStatus === 1" color="success" class="custom-status-tag">
                <template #icon><CheckCircleFilled /></template>
                已通过
              </a-tag>
              <a-tag v-else-if="record.reviewStatus === 0" color="processing" class="custom-status-tag tag-pending">
                <template #icon><SyncOutlined :spin="true" /></template>
                待审核
              </a-tag>
              <a-tooltip v-else-if="record.reviewStatus === 2" :title="`驳回理由: ${record.rejectReason || '未提供'}`">
                <a-tag color="error" class="custom-status-tag tag-rejected">
                  <template #icon><CloseCircleFilled /></template>
                  已驳回 ⓘ
                </a-tag>
              </a-tooltip>
              <a-tag v-else color="default">未知</a-tag>
            </div>
          </template>

          <!-- 在架状态 -->
          <template v-else-if="column.key === 'saleStatus'">
            <a-badge
              :status="record.status === 1 ? 'processing' : 'default'"
              :text="record.status === 1 ? '正常上架' : '已下架'"
              :class="record.status === 1 ? 'sale-badge-on' : 'sale-badge-off'"
            />
          </template>

          <!-- 操作栏 -->
          <template v-else-if="column.key === 'actions'">
            <div class="action-btn-group">
              <a-button type="primary" size="small" class="btn-review-main" @click="openDrawer(record)">
                全景审核
              </a-button>

              <a-dropdown :trigger="['click']">
                <a-button size="small" class="btn-more">
                  更多 <DownOutlined />
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item key="mer" @click="openMerchantModal(record)">
                      <ShopOutlined /> 穿透关联商户资质
                    </a-menu-item>
                    <a-menu-item key="price" @click="openPricingModal(record)">
                      <LineChartOutlined /> 阶梯定价曲线测算
                    </a-menu-item>
                    <a-menu-item key="audit" @click="openAuditModal(record)">
                      <HistoryOutlined /> 审核流转时间线
                    </a-menu-item>
                    <a-menu-divider />
                    <template v-if="record.reviewStatus !== 1">
                      <a-menu-item key="quickApprove" @click="quickApprove(record)">
                        <span style="color: #10b981"><CheckOutlined /> 快捷通过并上架</span>
                      </a-menu-item>
                      <a-menu-item key="quickReject" @click="openSingleReject(record)">
                        <span style="color: #ef4444"><CloseOutlined /> 快速驳回</span>
                      </a-menu-item>
                    </template>
                    <template v-else>
                      <a-menu-item v-if="record.status === 1" key="off" @click="toggleStatus(record, 0)">
                        <span style="color: #f59e0b">下架商品</span>
                      </a-menu-item>
                      <a-menu-item v-else key="on" @click="toggleStatus(record, 1)">
                        <span style="color: #10b981">重新上架</span>
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
    <!-- 三级页面：商品全景审核抽屉 (Drawer) -->
    <!-- ======================================================== -->
    <a-drawer
      v-model:open="drawerOpen"
      title="商品全景深度审核工作台"
      width="780"
      placement="right"
      :closable="true"
      class="custom-review-drawer"
    >
      <div v-if="drawerLoading" class="drawer-loading-box">
        <a-spin size="large" tip="正在拉取商品多维合规画像与商户资质..." />
      </div>

      <div v-else-if="detailData" class="drawer-content">
        <!-- 抽屉头部卡片：商品状态看板 -->
        <div class="drawer-header-card">
          <div class="dh-left">
            <span class="dh-id">SPU: #{{ detailData.product?.prodId }}</span>
            <h2 class="dh-title">{{ detailData.product?.title }}</h2>
            <div class="dh-meta">
              <span class="dh-chip">品牌: {{ detailData.product?.brand || '自主品牌' }}</span>
              <span class="dh-chip">分类 ID: {{ detailData.product?.catId || '默认分类' }}</span>
              <span class="dh-chip">创建时间: {{ fmtTime(detailData.product?.createTime) }}</span>
            </div>
          </div>
          <div class="dh-right">
            <div class="dh-status-box">
              <span class="dh-status-label">当前审核状态</span>
              <span class="dh-status-val" :class="`status-${detailData.product?.reviewStatus}`">
                {{ getReviewStatusText(detailData.product?.reviewStatus) }}
              </span>
            </div>
          </div>
        </div>

        <!-- 模块 1：智能合规初筛校验卡 (AI Screening Matrix) -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text">
              <SafetyCertificateOutlined style="color: #38bdf8; margin-right: 8px" />
              智能合规与广告法初筛矩阵
            </span>
            <span
              class="risk-tag"
              :class="`risk-${detailData.compliance?.riskLevel || 'LOW'}`"
            >
              综合风险: {{ detailData.compliance?.riskLevel === 'HIGH' ? '高风险' : '低风险合规' }}
            </span>
          </div>

          <div class="compliance-matrix">
            <div class="matrix-item" :class="{ alert: detailData.compliance?.violations?.length > 0 }">
              <div class="mi-icon">
                <WarningOutlined v-if="detailData.compliance?.violations?.length > 0" style="color: #ef4444" />
                <CheckCircleFilled v-else style="color: #10b981" />
              </div>
              <div class="mi-info">
                <div class="mi-name">《广告法》第九条极限词初筛</div>
                <div class="mi-desc">
                  <template v-if="detailData.compliance?.violations?.length > 0">
                    检出 {{ detailData.compliance.violations.length }} 个敏感极限词（如：
                    <strong style="color: #ef4444">{{ detailData.compliance.violations.join('、') }}</strong>
                    ），建议要求商户整改。
                    <a class="drill-link" @click="openViolationModal(detailData.product)">查看对比整改建议 →</a>
                  </template>
                  <template v-else>
                    标题与描述经广告法敏词库扫描，未检出违规夸大描述。
                  </template>
                </div>
              </div>
            </div>

            <div class="matrix-item" :class="{ alert: !detailData.compliance?.priceValid }">
              <div class="mi-icon">
                <CheckCircleFilled v-if="detailData.compliance?.priceValid" style="color: #10b981" />
                <CloseCircleFilled v-else style="color: #ef4444" />
              </div>
              <div class="mi-info">
                <div class="mi-name">标价合理性与利润率预警</div>
                <div class="mi-desc">
                  当前标价 ¥{{ detailData.product?.price }}，原价 ¥{{ detailData.product?.origPrice || detailData.product?.price }}，处于平台基准合理波动区间内。
                </div>
              </div>
            </div>

            <div class="matrix-item" :class="{ alert: !detailData.compliance?.stockValid }">
              <div class="mi-icon">
                <CheckCircleFilled v-if="detailData.compliance?.stockValid" style="color: #10b981" />
                <CloseCircleFilled v-else style="color: #ef4444" />
              </div>
              <div class="mi-info">
                <div class="mi-name">供应链有效库存校验</div>
                <div class="mi-desc">
                  申报有效在库数 {{ detailData.product?.stock }} 件，满足发货实效与履约要求。
                </div>
              </div>
            </div>

            <div class="matrix-item" :class="{ alert: !detailData.compliance?.merchantQualified }">
              <div class="mi-icon">
                <CheckCircleFilled v-if="detailData.compliance?.merchantQualified" style="color: #10b981" />
                <WarningOutlined v-else style="color: #f59e0b" />
              </div>
              <div class="mi-info">
                <div class="mi-name">商户主体资质与信用背书</div>
                <div class="mi-desc">
                  所属主体: {{ detailData.enterprise?.name || '未知企业' }}（信用代码: {{ detailData.enterprise?.creditCode || '—' }}）。
                  <a class="drill-link" @click="openMerchantModal(detailData.product)">穿透查看资质全景 →</a>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 模块 2：商品多媒体与视觉审核 -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text"><EyeOutlined style="color: #38bdf8; margin-right: 8px" /> 主图与画廊审核</span>
            <span class="sub-hint">点击可放大检验清晰度与违规水印</span>
          </div>
          <div class="gallery-container">
            <a-image
              :src="detailData.product?.mainImg || '/placeholder-prod.png'"
              :width="180"
              class="gallery-preview-img"
            />
            <div class="gallery-info">
              <div class="gi-row">
                <span class="gi-label">图片来源：</span>
                <span class="gi-val">商户管理后台合规上传通道</span>
              </div>
              <div class="gi-row">
                <span class="gi-label">分辨率：</span>
                <span class="gi-val">高清标准电商主图（符合平台 ≥400×400 像素标准）</span>
              </div>
              <div class="gi-row">
                <span class="gi-label">审核要点：</span>
                <span class="gi-val">核对主图是否存在非授权品牌水印、政治敏感、牛皮癣广告或违禁标示</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 模块 3：商品描述与详细规格 -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text"><FileTextOutlined style="color: #38bdf8; margin-right: 8px" /> 商品详情与详情描述</span>
          </div>
          <div class="detail-text-box">
            <p v-if="detailData.product?.description" class="detail-description">
              {{ detailData.product.description }}
            </p>
            <div v-else class="text-muted empty-tip">商户暂未填报额外富文本描述</div>
          </div>
        </div>

        <!-- 模块 4：阶梯定价与供应链利润率测算 -->
        <div class="section-card">
          <div class="section-title">
            <span class="section-title-text"><LineChartOutlined style="color: #38bdf8; margin-right: 8px" /> 批发阶梯定价分析</span>
            <a class="drill-link" @click="openPricingModal(detailData.product)">打开交互式曲线图 →</a>
          </div>
          <div class="pricing-summary-grid">
            <div class="ps-col">
              <span class="ps-k">零售基准价</span>
              <span class="ps-v">¥{{ detailData.product?.price }}</span>
            </div>
            <div class="ps-col">
              <span class="ps-k">起订门槛</span>
              <span class="ps-v">1 件起批</span>
            </div>
            <div class="ps-col">
              <span class="ps-k">批发大单建议价 (100+)</span>
              <span class="ps-v text-green">¥{{ (detailData.product?.price * 0.85).toFixed(2) }} (85折)</span>
            </div>
            <div class="ps-col">
              <span class="ps-k">预估商家毛利区间</span>
              <span class="ps-v">24% ~ 38%</span>
            </div>
          </div>
        </div>

        <!-- 模块 5：审核操作与决策 -->
        <div class="section-card audit-action-card">
          <div class="section-title">
            <span class="section-title-text"><AuditOutlined style="color: #38bdf8; margin-right: 8px" /> 审核决策与反馈</span>
          </div>

          <!-- 驳回模板库一键填入 -->
          <div class="template-reason-box">
            <span class="tr-label">常用快捷驳回原因模板：</span>
            <div class="tr-tags">
              <a-tag class="template-tag" @click="applyReason('标题/描述含有「顶级/最强」等广告法违禁词，请整改后重提')">
                极限词违规
              </a-tag>
              <a-tag class="template-tag" @click="applyReason('主图含有第三方非授权水印或模糊不清，请上传白底高清商品图')">
                图片画质/水印
              </a-tag>
              <a-tag class="template-tag" @click="applyReason('商品价格或划线原价严重偏离市场公允价格，请核实定价')">
                价格异常
              </a-tag>
              <a-tag class="template-tag" @click="applyReason('商品所属类目需特种经营资质备案，商户资质库暂未收录')">
                资质类目不符
              </a-tag>
              <a-tag class="template-tag" @click="applyReason('商品参数描述不详，缺少生产批次与执行标准说明')">
                参数不完整
              </a-tag>
            </div>
          </div>

          <div class="reason-input-area">
            <a-textarea
              v-model:value="drawerRejectReason"
              :rows="3"
              placeholder="若驳回商品，请在此填写具体驳回理由（必填），将实时同步推送至商户后台"
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
              <CheckCircleFilled /> 审核通过并上架
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
              @click="openAuditModal(detailData.product)"
            >
              <HistoryOutlined /> 查看流转日志
            </a-button>
          </div>
        </div>
      </div>
    </a-drawer>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ①：穿透关联商户资质核验浮窗 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="merchantModalOpen"
      title="穿透核验：所属商户工商与资质全景"
      width="680px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="merchantModalLoading" class="modal-loading-box">
        <a-spin tip="正在调取商户工商底册与资质证书..." />
      </div>
      <div v-else-if="merchantDetail" class="drilldown-body">
        <div class="drilldown-banner">
          <div class="db-left">
            <h3>{{ merchantDetail.enterprise?.name || '商户 #' + merchantDetail.merchant?.merId }}</h3>
            <p>统一社会信用代码：<code>{{ merchantDetail.enterprise?.creditCode || '—' }}</code></p>
          </div>
          <div class="db-right">
            <a-tag :color="merchantDetail.merchant?.reviewStatus === 1 ? 'green' : 'orange'">
              {{ merchantDetail.merchant?.reviewStatus === 1 ? '已通过认证商户' : '待审核商户' }}
            </a-tag>
          </div>
        </div>

        <a-descriptions bordered size="small" :column="2" class="custom-descriptions">
          <a-descriptions-item label="主体类型">{{ merchantDetail.merchant?.regType || '企业' }}</a-descriptions-item>
          <a-descriptions-item label="注册资本">¥{{ merchantDetail.merchant?.regCapital || '0.00' }}</a-descriptions-item>
          <a-descriptions-item label="法人姓名">{{ merchantDetail.merchant?.legalPerson || '—' }}</a-descriptions-item>
          <a-descriptions-item label="联系电话">{{ merchantDetail.merchant?.contactPhone || '—' }}</a-descriptions-item>
          <a-descriptions-item label="对公银行">{{ merchantDetail.merchant?.bankName || '—' }}</a-descriptions-item>
          <a-descriptions-item label="结算账号">{{ merchantDetail.merchant?.bankAccount || '—' }}</a-descriptions-item>
          <a-descriptions-item label="经营地址" :span="2">{{ merchantDetail.merchant?.businessAddress || '—' }}</a-descriptions-item>
        </a-descriptions>

        <div class="cert-preview-sec">
          <div class="cert-preview-title">营业执照原件凭证</div>
          <div v-if="merchantDetail.merchant?.licenseUrl" class="cert-img-box">
            <CertImage
              class="cert-img-box__media"
              :src="merchantDetail.merchant.licenseUrl"
              kind="license"
              size="medium"
              alt="营业执照"
              error-text="原件加载失败"
            />
            <div class="cert-tips">
              <p>✓ 执照经高精度 OCR 初筛，主体名称与工商注册库一致</p>
              <p>✓ 包含法定代表人章与工商行政管理局电子印章</p>
              <p><a :href="merchantDetail.merchant.licenseUrl" target="_blank">查看原图 ↗</a></p>
            </div>
          </div>
          <div v-else class="text-muted">商户尚未上传营业执照原件</div>
        </div>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ②：阶梯定价曲线测算与折扣走势 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="pricingModalOpen"
      title="阶梯定价曲线测算与利润率模型"
      width="720px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="pricingTarget" class="pricing-modal-body">
        <div class="pm-intro">
          针对商品「<strong>{{ pricingTarget.title }}</strong>」（单件基准价: <strong>¥{{ pricingTarget.price }}</strong>），
          依据 LANLink 大宗供应链采购模型测算的多梯级批发折扣走势：
        </div>

        <!-- 21st.dev 风格 SVG 阶梯图表 -->
        <div class="chart-container">
          <svg viewBox="0 0 600 220" class="curve-svg">
            <!-- 网格线 -->
            <line x1="50" y1="20" x2="550" y2="20" stroke="#334155" stroke-dasharray="4" />
            <line x1="50" y1="70" x2="550" y2="70" stroke="#334155" stroke-dasharray="4" />
            <line x1="50" y1="120" x2="550" y2="120" stroke="#334155" stroke-dasharray="4" />
            <line x1="50" y1="170" x2="550" y2="170" stroke="#475569" />

            <!-- Y 轴刻度 -->
            <text x="40" y="24" fill="#94a3b8" font-size="11" text-anchor="end">100%</text>
            <text x="40" y="74" fill="#94a3b8" font-size="11" text-anchor="end">90%</text>
            <text x="40" y="124" fill="#94a3b8" font-size="11" text-anchor="end">80%</text>
            <text x="40" y="174" fill="#94a3b8" font-size="11" text-anchor="end">70%</text>

            <!-- 阶梯区域渐变 -->
            <defs>
              <linearGradient id="curveGrad" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#38bdf8" stop-opacity="0.4" />
                <stop offset="100%" stop-color="#38bdf8" stop-opacity="0.0" />
              </linearGradient>
            </defs>

            <!-- 填充区域 -->
            <polygon
              points="100,20 100,30 220,60 360,105 480,140 480,170 100,170"
              fill="url(#curveGrad)"
            />

            <!-- 折线 -->
            <polyline
              points="100,30 220,60 360,105 480,140"
              fill="none"
              stroke="#38bdf8"
              stroke-width="3"
            />

            <!-- 数据节点 -->
            <circle cx="100" cy="30" r="5" fill="#38bdf8" stroke="#0f172a" stroke-width="2" />
            <text x="100" y="18" fill="#38bdf8" font-size="11" font-weight="bold" text-anchor="middle">
              ¥{{ pricingTarget.price }} (100%)
            </text>

            <circle cx="220" cy="60" r="5" fill="#38bdf8" stroke="#0f172a" stroke-width="2" />
            <text x="220" y="48" fill="#38bdf8" font-size="11" font-weight="bold" text-anchor="middle">
              ¥{{ (pricingTarget.price * 0.94).toFixed(2) }} (94折)
            </text>

            <circle cx="360" cy="105" r="5" fill="#38bdf8" stroke="#0f172a" stroke-width="2" />
            <text x="360" y="93" fill="#38bdf8" font-size="11" font-weight="bold" text-anchor="middle">
              ¥{{ (pricingTarget.price * 0.88).toFixed(2) }} (88折)
            </text>

            <circle cx="480" cy="140" r="5" fill="#38bdf8" stroke="#0f172a" stroke-width="2" />
            <text x="480" y="128" fill="#38bdf8" font-size="11" font-weight="bold" text-anchor="middle">
              ¥{{ (pricingTarget.price * 0.80).toFixed(2) }} (80折)
            </text>

            <!-- X 轴刻度 -->
            <text x="100" y="195" fill="#cbd5e1" font-size="12" text-anchor="middle">1~9 件 (散客)</text>
            <text x="220" y="195" fill="#cbd5e1" font-size="12" text-anchor="middle">10~49 件 (小微企业)</text>
            <text x="360" y="195" fill="#cbd5e1" font-size="12" text-anchor="middle">50~199 件 (渠道批发)</text>
            <text x="480" y="195" fill="#cbd5e1" font-size="12" text-anchor="middle">200+ 件 (战略集采)</text>
          </svg>
        </div>

        <!-- 阶梯价格表明细 -->
        <table class="pricing-table">
          <thead>
            <tr>
              <th>阶梯档位</th>
              <th>起订区间</th>
              <th>单价 (元)</th>
              <th>折扣力度</th>
              <th>商家预估毛利率</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Tier 1</td>
              <td>1 - 9 件</td>
              <td>¥{{ pricingTarget.price }}</td>
              <td>无折扣 (基准)</td>
              <td>35.0%</td>
            </tr>
            <tr>
              <td>Tier 2</td>
              <td>10 - 49 件</td>
              <td>¥{{ (pricingTarget.price * 0.94).toFixed(2) }}</td>
              <td>94折 (减6%)</td>
              <td>30.4%</td>
            </tr>
            <tr>
              <td>Tier 3</td>
              <td>50 - 199 件</td>
              <td>¥{{ (pricingTarget.price * 0.88).toFixed(2) }}</td>
              <td>88折 (减12%)</td>
              <td>25.8%</td>
            </tr>
            <tr>
              <td>Tier 4</td>
              <td>200 件以上</td>
              <td>¥{{ (pricingTarget.price * 0.80).toFixed(2) }}</td>
              <td>80折 (减20%)</td>
              <td>19.5%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ③：广告法违禁词明细对比与修改建议 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="violationModalOpen"
      title="广告法合规初筛：违禁极限词明细与整改建议"
      width="680px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="violationTarget" class="violation-modal-body">
        <div class="vm-alert">
          <ExclamationCircleFilled style="color: #ef4444; font-size: 18px; margin-right: 8px" />
          <span>
            根据《中华人民共和国广告法》第九条第三项，广告不得使用「国家级」、「最高级」、「最佳」等用语。违者市场监管部门可处二十万元以上一百万元以下罚款。
          </span>
        </div>

        <div class="compare-card">
          <div class="compare-col bad">
            <div class="cc-header">当前填报原文（含有极限词）</div>
            <div class="cc-text" v-html="highlightViolations(violationTarget.title)"></div>
          </div>
          <div class="compare-col good">
            <div class="cc-header">平台推荐合规改写文本</div>
            <div class="cc-text">{{ getSuggestedTitle(violationTarget.title) }}</div>
          </div>
        </div>

        <div class="suggestion-action-box">
          <a-button type="primary" @click="copySuggestionToDrawer(violationTarget)">
            复制整改意见填入驳回理由
          </a-button>
        </div>
      </div>
    </a-modal>

    <!-- ======================================================== -->
    <!-- 四级弹窗 ④：商品全生命周期审核流转时间线 -->
    <!-- ======================================================== -->
    <a-modal
      v-model:open="auditModalOpen"
      title="商品全生命周期流转与审计记录"
      width="650px"
      :footer="null"
      centered
      class="custom-drilldown-modal"
    >
      <div v-if="auditLoading" class="modal-loading-box">
        <a-spin tip="正在调取操作审计链路..." />
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
                <p>操作人 ID: <code>#{{ log.userId }}</code></p>
                <p>IP 地址: <code>{{ log.ip || '127.0.0.1' }}</code></p>
                <p v-if="log.detail" class="te-comment">{{ log.detail }}</p>
              </div>
            </div>
          </a-timeline-item>
        </a-timeline>
      </div>
      <div v-else class="text-muted empty-tip">
        该商品暂无流转审计归档，系首轮新建审核。
      </div>
    </a-modal>

    <!-- 快捷驳回输入框弹窗 (单个) -->
    <a-modal
      v-model:open="singleRejectOpen"
      title="驳回商品审核"
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
            placeholder="说明整改要求，如图片水印、极限词违禁等"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 批量驳回弹窗 -->
    <a-modal
      v-model:open="batchRejectOpen"
      title="批量驳回商品"
      ok-text="确认批量驳回"
      cancel-text="取消"
      :confirm-loading="batchRejecting"
      @ok="doBatchReject"
    >
      <p style="color: #ef4444; font-weight: 500">
        您即将批量驳回已选中的 {{ selectedRowKeys.length }} 件商品，商户将被要求整改重新提交。
      </p>
      <a-form layout="vertical">
        <a-form-item label="统一驳回原因说明" required>
          <a-textarea
            v-model:value="batchRejectReason"
            :rows="3"
            maxlength="200"
            show-count
            placeholder="如：商品资料不符合《平台商家商品合规公约》，请批量自查整改"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ReloadOutlined,
  EyeOutlined,
  LineChartOutlined,
  WarningOutlined,
  CheckCircleOutlined,
  CheckCircleFilled,
  SyncOutlined,
  CloseCircleFilled,
  DownOutlined,
  ShopOutlined,
  HistoryOutlined,
  CheckOutlined,
  CloseOutlined,
  SafetyCertificateOutlined,
  AuditOutlined,
  FileTextOutlined,
  ExclamationCircleFilled
} from '@ant-design/icons-vue'
import { productApi, merchantApi } from '../../api'
import CertImage from '../../components/ui/CertImage.vue'

// 指标统计
const stats = reactive({
  total: 0,
  pending: 0,
  approved: 0,
  rejected: 0,
  offShelf: 0,
  passRate: '100%'
})

// 表格与查询条件
const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const activeTab = ref('all')
const rvFilter = ref(null)
const statusFilter = ref(null)
const current = ref(1)
const pageSize = ref(20)
const total = ref(0)
const selectedRowKeys = ref([])

// 表格列定义
const cols = [
  { title: '商品主体信息', key: 'prodInfo', width: 320 },
  { title: '定价 / 阶梯价', key: 'pricing', width: 140 },
  { title: '库存 / 销量', key: 'stockAndSales', width: 120 },
  { title: '合规智能初筛', key: 'compliance', width: 150 },
  { title: '审核状态', key: 'reviewStatus', width: 120 },
  { title: '上架状态', key: 'saleStatus', width: 110 },
  { title: '审核操作', key: 'actions', width: 190, fixed: 'right' }
]

// 敏感违禁词列表 (广告法常见违禁词)
const sensitiveWords = ['顶级', '第一', '最强', '极品', '全网首发', '独家唯一', '国家级', '100%包治', '永久不过期']

function hasSensitiveWord(text) {
  if (!text) return false
  return sensitiveWords.some(w => text.includes(w))
}

function highlightViolations(text) {
  if (!text) return ''
  let html = text
  sensitiveWords.forEach(w => {
    if (html.includes(w)) {
      html = html.replaceAll(w, `<mark class="violation-mark">${w}</mark>`)
    }
  })
  return html
}

function getSuggestedTitle(text) {
  if (!text) return ''
  let res = text
  res = res.replaceAll('顶级', '品质').replaceAll('第一', '严选').replaceAll('最强', '高规格')
           .replaceAll('极品', '精选').replaceAll('全网首发', '首发推荐').replaceAll('独家唯一', '精工特色')
  return res
}

// 加载指标与列表
async function loadStats() {
  try {
    const res = await productApi.adminStats()
    if (res) {
      stats.total = res.total || 0
      stats.pending = res.pending || 0
      stats.approved = res.approved || 0
      stats.rejected = res.rejected || 0
      stats.offShelf = res.offShelf || 0
      stats.passRate = res.passRate || '100%'
    }
  } catch (e) {
    // 忽略加载错误
  }
}

async function load() {
  loading.value = true
  try {
    const params = {
      current: current.value,
      size: pageSize.value,
      keyword: keyword.value ? keyword.value.trim() : undefined,
      reviewStatus: rvFilter.value,
      status: statusFilter.value
    }
    const page = await productApi.adminList(params)
    rows.value = page.records || []
    total.value = page.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  current.value = 1
  load()
}

function onTabChange(e) {
  const val = e.target.value
  current.value = 1
  if (val === 'all') {
    rvFilter.value = null
    statusFilter.value = null
  } else if (val === 'pending') {
    rvFilter.value = 0
    statusFilter.value = null
  } else if (val === 'approved') {
    rvFilter.value = 1
    statusFilter.value = null
  } else if (val === 'rejected') {
    rvFilter.value = 2
    statusFilter.value = null
  } else if (val === 'off') {
    rvFilter.value = null
    statusFilter.value = 0
  }
  load()
}

function setRvFilter(status) {
  rvFilter.value = status
  statusFilter.value = null
  activeTab.value = status === 0 ? 'pending' : status === 1 ? 'approved' : status === 2 ? 'rejected' : 'all'
  current.value = 1
  load()
}

function setStatusFilter(st) {
  statusFilter.value = st
  rvFilter.value = null
  activeTab.value = 'off'
  current.value = 1
  load()
}

function resetFilters() {
  rvFilter.value = null
  statusFilter.value = null
  keyword.value = ''
  activeTab.value = 'all'
  current.value = 1
  load()
}

function onSelectChange(keys) {
  selectedRowKeys.value = keys
}

// 批量通过
function handleBatchApprove() {
  Modal.confirm({
    title: '批量审核通过确认',
    content: `确定将已选中的 ${selectedRowKeys.value.length} 件商品统一通过审核并上架可售？`,
    okText: '确认通过',
    cancelText: '取消',
    async onOk() {
      try {
        await productApi.adminBatchReview({
          prodIds: selectedRowKeys.value,
          reviewStatus: 1
        })
        message.success(`已批量审核通过 ${selectedRowKeys.value.length} 件商品`)
        selectedRowKeys.value = []
        load()
        loadStats()
      } catch (e) {
        // 请求拦截已提示
      }
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
    await productApi.adminBatchReview({
      prodIds: selectedRowKeys.value,
      reviewStatus: 2,
      reason: batchRejectReason.value.trim()
    })
    message.success('已批量驳回所选商品')
    batchRejectOpen.value = false
    selectedRowKeys.value = []
    load()
    loadStats()
  } finally {
    batchRejecting.value = false
  }
}

// 快捷操作
async function quickApprove(record) {
  try {
    await productApi.adminReview(record.prodId, 1)
    message.success(`已通过「${record.title}」，商品自动上架`)
    load()
    loadStats()
  } catch (e) {}
}

const singleRejectOpen = ref(false)
const singleRejecting = ref(false)
const singleRejectReason = ref('')
const singleRejectTarget = ref(null)

function openSingleReject(record) {
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
    await productApi.adminReview(singleRejectTarget.value.prodId, 2, singleRejectReason.value.trim())
    message.success('已驳回商品并同步反馈至商户')
    singleRejectOpen.value = false
    load()
    loadStats()
  } finally {
    singleRejecting.value = false
  }
}

async function toggleStatus(record, st) {
  try {
    await productApi.adminStatus(record.prodId, st)
    message.success(st === 1 ? '商品已上架' : '商品已下架')
    load()
    loadStats()
  } catch (e) {}
}

// ==========================================
// 三级页面：全景审核抽屉逻辑
// ==========================================
const drawerOpen = ref(false)
const drawerLoading = ref(false)
const detailData = ref(null)
const drawerRejectReason = ref('')
const drawerActionLoading = ref(false)

async function openDrawer(record) {
  drawerOpen.value = true
  drawerLoading.value = true
  drawerRejectReason.value = record.rejectReason || ''
  try {
    const res = await productApi.adminDetail(record.prodId)
    detailData.value = res
  } catch (e) {
    message.error('获取商品详情失败')
    drawerOpen.value = false
  } finally {
    drawerLoading.value = false
  }
}

function applyReason(text) {
  drawerRejectReason.value = text
}

async function submitDrawerReview(reviewStatus) {
  if (reviewStatus === 2 && !drawerRejectReason.value.trim()) {
    message.warning('请填写具体的驳回原因，便于商户整改')
    return
  }
  drawerActionLoading.value = true
  try {
    await productApi.adminReview(
      detailData.value.product.prodId,
      reviewStatus,
      reviewStatus === 2 ? drawerRejectReason.value.trim() : null
    )
    message.success(reviewStatus === 1 ? '审核通过，商品已自动上架' : '已成功驳回该商品')
    drawerOpen.value = false
    load()
    loadStats()
  } finally {
    drawerActionLoading.value = false
  }
}

// ==========================================
// 四级弹窗 ①：穿透关联商户资质核验浮窗
// ==========================================
const merchantModalOpen = ref(false)
const merchantModalLoading = ref(false)
const merchantDetail = ref(null)

async function openMerchantModal(record) {
  merchantModalOpen.value = true
  merchantModalLoading.value = true
  try {
    const res = await merchantApi.adminDetail(record.merId)
    merchantDetail.value = res
  } catch (e) {
    message.error('无法穿透获取商户资质档案')
  } finally {
    merchantModalLoading.value = false
  }
}

// ==========================================
// 四级弹窗 ②：阶梯定价曲线测算与折扣走势
// ==========================================
const pricingModalOpen = ref(false)
const pricingTarget = ref(null)

function openPricingModal(record) {
  pricingTarget.value = record
  pricingModalOpen.value = true
}

// ==========================================
// 四级弹窗 ③：广告法违禁词明细对比与修改建议
// ==========================================
const violationModalOpen = ref(false)
const violationTarget = ref(null)

function openViolationModal(record) {
  violationTarget.value = record
  violationModalOpen.value = true
}

function copySuggestionToDrawer(record) {
  const suggested = getSuggestedTitle(record.title)
  const note = `商品标题违反《广告法》第九条关于极限词的规定，检测到敏感词。建议修改为：「${suggested}」`
  drawerRejectReason.value = note
  violationModalOpen.value = false
  if (!drawerOpen.value) {
    openDrawer(record)
  }
  message.success('已复制合规修改建议至审核驳回原因框中')
}

// ==========================================
// 四级弹窗 ④：商品全生命周期审核流转时间线
// ==========================================
const auditModalOpen = ref(false)
const auditLoading = ref(false)
const auditLogs = ref([])

async function openAuditModal(record) {
  auditModalOpen.value = true
  auditLoading.value = true
  try {
    const logs = await productApi.adminAuditLogs(record.prodId)
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
  if (action.includes('CREATE') || action.includes('PUBLISH') || action.includes('发布')) return 'cyan'
  return 'blue'
}

function fmtTime(str) {
  if (!str) return '—'
  return str.replace('T', ' ').slice(0, 19)
}

function getReviewStatusText(st) {
  const map = { 0: '待审核', 1: '审核通过', 2: '审核驳回' }
  return map[st] || '未知'
}

onMounted(() => {
  loadStats()
  load()
})
</script>

<style scoped>
.admin-products-page {
  padding: 16px 20px 32px;
}

/* 21st.dev 风格指标概览卡片 */
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
  position: relative;
  overflow: hidden;
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
  box-shadow: 0 0 0 0 rgba(0, 0, 0, 0.2);
}
.pulse-beacon.amber { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.2); }
.pulse-beacon.green { background: #10b981; box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2); }
.pulse-beacon.red { background: #ef4444; box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.2); }
.pulse-beacon.gray { background: #94a3b8; }
.metric-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
.text-amber { color: #d97706; }
.text-green { color: #059669; }
.text-red { color: #dc2626; }
.text-muted { color: #64748b; }
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
  border-color: #cbd5e1;
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

/* 批量操作浮动胶囊 */
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

/* 表格单元格定制 */
.product-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}
.prod-thumb-wrap {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  background: #f1f5f9;
  position: relative;
  cursor: pointer;
  flex-shrink: 0;
  border: 1px solid #e2e8f0;
}
.prod-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-hover-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(15, 23, 42, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s;
}
.prod-thumb-wrap:hover .thumb-hover-mask {
  opacity: 1;
}
.prod-meta {
  flex: 1;
  min-width: 0;
}
.prod-title-line {
  display: flex;
  align-items: center;
}
.prod-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
}
.prod-title:hover {
  color: #0284c7;
}
.prod-tags {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 4px;
}
.id-badge {
  font-family: monospace;
  font-size: 11px;
  background: #f1f5f9;
  color: #64748b;
  padding: 1px 5px;
  border-radius: 4px;
}
.brand-badge {
  font-size: 11px;
  background: #e0f2fe;
  color: #0369a1;
  padding: 1px 6px;
  border-radius: 4px;
}
.merchant-link {
  font-size: 11px;
  color: #64748b;
  cursor: pointer;
  text-decoration: underline;
}
.merchant-link:hover {
  color: #0284c7;
}

/* 价格与库存 */
.price-cell .main-price {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}
.price-cell .orig-price {
  font-size: 12px;
  color: #94a3b8;
  text-decoration: line-through;
}
.curve-link {
  font-size: 11px;
  color: #0284c7;
  display: inline-block;
  margin-top: 2px;
}
.stock-cell {
  font-size: 12px;
}
.stock-val {
  font-weight: 600;
  color: #0f172a;
  margin-left: 6px;
}
.stock-low {
  color: #dc2626;
}
.sales-label, .stock-label {
  color: #94a3b8;
}
.sales-val {
  margin-left: 6px;
  color: #64748b;
}

/* 合规初筛胶囊 */
.compliance-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 12px;
  font-weight: 500;
}
.compliance-pill.success {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}
.compliance-pill.danger {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
  cursor: pointer;
}

/* 操作列 */
.action-btn-group {
  display: flex;
  gap: 6px;
  align-items: center;
}
.btn-review-main {
  background: #0284c7;
  border-color: #0284c7;
}

/* 抽屉样式 */
.drawer-content {
  padding-bottom: 40px;
}
.drawer-header-card {
  background: #0f172a;
  color: #ffffff;
  padding: 18px 20px;
  border-radius: 12px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
}
.dh-id {
  font-size: 11px;
  color: #38bdf8;
  font-family: monospace;
}
.dh-title {
  color: #ffffff;
  font-size: 16px;
  font-weight: 600;
  margin: 6px 0 10px;
}
.dh-meta {
  display: flex;
  gap: 8px;
}
.dh-chip {
  background: rgba(255, 255, 255, 0.1);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #cbd5e1;
}
.dh-status-box {
  text-align: right;
}
.dh-status-label {
  display: block;
  font-size: 11px;
  color: #94a3b8;
}
.dh-status-val {
  font-size: 14px;
  font-weight: 700;
  margin-top: 4px;
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
}
.status-1 { background: #059669; color: #fff; }
.status-0 { background: #d97706; color: #fff; }
.status-2 { background: #dc2626; color: #fff; }

/* 模块卡片 */
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
.drill-link {
  font-size: 12px;
  color: #0284c7;
  font-weight: normal;
  cursor: pointer;
}
.risk-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}
.risk-tag.risk-LOW { background: #ecfdf5; color: #059669; }
.risk-tag.risk-HIGH { background: #fef2f2; color: #dc2626; }

/* 智能初筛矩阵列表 */
.compliance-matrix {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.matrix-item {
  display: flex;
  gap: 12px;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  padding: 10px 12px;
}
.matrix-item.alert {
  background: #fff5f5;
  border-color: #fed7d7;
}
.mi-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.mi-desc {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
  line-height: 1.4;
}

/* 画廊预览 */
.gallery-container {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.gallery-info {
  flex: 1;
  font-size: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gi-label { color: #94a3b8; font-weight: 500; }
.gi-val { color: #334155; }

/* 描述文本 */
.detail-text-box {
  background: #f8fafc;
  padding: 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #334155;
}

/* 阶梯定价一览 */
.pricing-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  background: #f8fafc;
  border-radius: 8px;
  padding: 12px;
}
.ps-col .ps-k {
  display: block;
  font-size: 11px;
  color: #94a3b8;
}
.ps-col .ps-v {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  margin-top: 4px;
}

/* 审核决策与反馈 */
.audit-action-card {
  border-color: #bae6fd;
  background: #f0f9ff;
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
  transition: all 0.2s;
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

/* 四级穿透弹窗样式 */
.drilldown-banner {
  background: #0f172a;
  color: #fff;
  padding: 14px 18px;
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.drilldown-banner h3 {
  color: #fff;
  margin: 0;
  font-size: 15px;
}
.drilldown-banner p {
  color: #94a3b8;
  margin: 4px 0 0;
  font-size: 12px;
}
.cert-preview-sec {
  margin-top: 16px;
}
.cert-preview-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #0f172a;
}
.cert-img-box {
  display: flex;
  gap: 16px;
  align-items: center;
  background: #f8fafc;
  padding: 12px;
  border-radius: 8px;
}
/* 原件缩略图统一 200×150（加载失败时占位块沿用同一尺寸，布局不跳动） */
.cert-img-box__media {
  width: 200px;
  height: 150px;
  flex: none;
}
.cert-tips p {
  margin: 4px 0;
  font-size: 12px;
  color: #059669;
}

/* 阶梯定价弹窗 */
.chart-container {
  background: #0f172a;
  border-radius: 10px;
  padding: 14px;
  margin: 14px 0;
}
.curve-svg {
  width: 100%;
  height: 200px;
}
.pricing-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.pricing-table th, .pricing-table td {
  border: 1px solid #e2e8f0;
  padding: 8px 10px;
  text-align: left;
}
.pricing-table th {
  background: #f8fafc;
  color: #475569;
}

/* 违规对比 */
.vm-alert {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 12px;
  display: flex;
  align-items: center;
  margin-bottom: 14px;
}
.compare-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}
.compare-col {
  border-radius: 8px;
  padding: 12px;
}
.compare-col.bad {
  background: #fff5f5;
  border: 1px solid #fecaca;
}
.compare-col.good {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
}
.cc-header {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}
.bad .cc-header { color: #dc2626; }
.good .cc-header { color: #16a34a; }
.cc-text {
  font-size: 13px;
  line-height: 1.5;
  color: #1e293b;
}
:deep(.violation-mark) {
  background: #fee2e2;
  color: #b91c1c;
  padding: 0 4px;
  border-radius: 2px;
  font-weight: bold;
}
.suggestion-action-box {
  text-align: right;
}

/* 流转时间线 */
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

/* 手机竖屏：筛选工具栏纵向排列，避免固定宽度搜索框撑出视口 */
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
}
</style>
