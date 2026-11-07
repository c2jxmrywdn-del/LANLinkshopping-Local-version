<template>
  <div class="detail-page">
    <a-page-header title="订单详情" sub-title="订单状态、商品、收货与支付信息" @back="$router.push('/orders')" />
    <!-- 入口 6：顶部非阻塞引导条（× 关闭 7 天冷却 + 订单号防重访 + 会话曝光上限）；整句 computed 单文本节点防插值拆散 -->
    <VipPromoEntry v-if="orderPaid && growthNote" slot-key="order" variant="bar" :dedupe-key="route.params.orderNo">
      {{ growthNote }}
    </VipPromoEntry>
    <a-spin :spinning="loading">
      <template v-if="detail?.order">
        <a-card :bordered="false" class="status-card">
          <div class="status-main">
            <div class="status-copy">
              <div class="eyebrow">ORDER / {{detail.order.orderNo}}</div>
              <h1><i class="status-dot" :class="statusTone" aria-hidden="true"></i>{{statusText(detail.order)}}</h1>
              <p>{{statusHint(detail.order)}}</p>
            </div>
            <div class="status-actions">
              <a-button v-if="canPay" type="primary" size="large" @click="openPay">立即支付</a-button>
              <a-button v-if="canConfirm" type="primary" size="large" @click="confirmReceipt">确认收货</a-button>
              <a-button v-if="canShipBack" type="primary" size="large" @click="openShipBackModal">填报退货单号</a-button>
              <a-button v-if="canRefund" size="large" @click="openRefund">申请退款</a-button>
              <a-button v-if="canCancel" size="large" @click="cancel">取消订单</a-button>
            </div>
          </div>
          <div class="order-progress">
            <div v-for="(s, i) in stepsView" :key="s.title" class="op-step" :class="s.state">
              <span class="op-dot">
                <svg v-if="s.state === 'done'" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
                <template v-else>{{ i + 1 }}
    <ReviewDialog
      v-model:open="reviewOpen"
      :order-no="detail?.order?.orderNo || ''"
      :item="reviewItem"
      @submitted="onReviewed"
    />
</template>
              </span>
              <div class="op-body"><b>{{ s.title }}</b><small>{{ s.desc }}</small></div>
            </div>
          </div>
        </a-card>

        <div class="grid">
          <a-card :bordered="false" class="card" title="商品清单">
            <div v-for="item in detail.items" :key="item.itemId" class="item">
              <div class="thumb"><img v-if="item.coverUrl" :src="resolveStorageUrl(item.coverUrl)" :alt="item.prodName" loading="lazy" decoding="async" /><span v-else>商品</span></div>
              <div class="item-main"><a @click="$router.push('/product/'+item.prodId)">{{item.prodName}}</a><small>¥{{money(item.price)}} × {{item.quantity}}</small></div>
              <a-button v-if="canReview && !reviewedIds.has(item.itemId)" size="small" class="item-review" @click="openReview(item)">评价</a-button>
              <a-tag v-else-if="reviewedIds.has(item.itemId)" color="green" class="item-reviewed">已评价</a-tag>
              <strong>¥{{money(item.subtotal)}}</strong>
            </div>
            <a-empty v-if="!detail.items?.length" description="暂无商品明细" />
            <a-divider />
            <!-- 优惠构成回显：促销/会员互斥取优快照（promoTitle/promoSource 落库快照，无标题时按「促销已减」兜底） -->
            <div v-if="promoRow" class="sum point-sum">
              <span>{{ promoRow.label }}</span>
              <strong class="deduct">-¥{{money(promoRow.amount)}}</strong>
            </div>
            <div v-if="Number(detail.order.couponDiscount) > 0" class="sum point-sum">
              <span>优惠券抵扣</span>
              <strong class="deduct">-¥{{money(detail.order.couponDiscount)}}</strong>
            </div>
            <!-- 积分抵扣明细：1000 积分 = 1 元，整元抵扣 -->
            <div v-if="Number(detail.order.pointDeductAmount) > 0" class="sum point-sum">
              <span>积分抵扣（{{ detail.order.pointDeductPoints || 0 }} 积分 · 1000 积分=1 元）</span>
              <strong class="deduct">-¥{{money(detail.order.pointDeductAmount)}}</strong>
            </div>
            <div class="sum"><span>{{ Number(detail.order.pointDeductAmount) > 0 ? '实付金额' : '订单金额' }}</span><strong>¥{{money(detail.order.totalAmount)}}</strong></div>
          </a-card>

          <a-card :bordered="false" class="card" title="收货信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="收货人">{{detail.order.receiver || '—'}}</a-descriptions-item>
              <a-descriptions-item label="联系电话">{{maskPhone(detail.order.phone)}}</a-descriptions-item>
              <a-descriptions-item label="收货地址">{{detail.order.address || '—'}}</a-descriptions-item>
              <a-descriptions-item label="订单备注">{{detail.order.remark || '—'}}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <a-card :bordered="false" class="card" title="支付与退款">
            <a-button v-if="detail.order.payType === 'term'" type="link" size="small" @click="$router.push('/credit-term/bills')">进入账期账单 →</a-button>
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="支付方式">{{payType[detail.order.payType] || detail.order.payType || '—'}}</a-descriptions-item>
              <a-descriptions-item label="支付渠道">{{channelText[detail.order.payChannel] || detail.order.payChannel || '—'}}</a-descriptions-item>
              <a-descriptions-item label="支付时间">{{fmt(detail.order.payTime)}}</a-descriptions-item>
              <a-descriptions-item label="退款状态">{{refundText(detail.order)}}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundType" label="售后类型">{{detail.order.refundType === 'return_goods' ? '退货退款 (需寄回商品)' : '仅退款 (无需退货)'}}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.returnWarehouseAddr" label="商家退货地址">{{detail.order.returnWarehouseAddr}}（收件人：{{detail.order.returnReceiverName}} {{detail.order.returnReceiverPhone}}）</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.returnWaybillNo" label="退货物流">{{detail.order.returnCarrierName || '快递'}} - {{detail.order.returnWaybillNo}}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.inspectStatus" label="仓库质检">{{detail.order.inspectStatus === 'passed' ? '质检合格已入库' : '货物异常协商中'}} ({{detail.order.inspectResult || '—'}})</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundReason" label="退款申请原因">{{ detail.order.refundReason }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundApplyTime" label="申请时间">{{ fmt(detail.order.refundApplyTime) }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundStatus === 'rejected'" label="驳回原因">{{ detail.order.refundRejectReason || '不符合退款条件' }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundAmount" label="已退金额">¥{{money(detail.order.refundAmount)}}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <a-card :bordered="false" class="card" title="订单信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="订单号">{{detail.order.orderNo}}</a-descriptions-item>
              <a-descriptions-item label="订单状态">{{statusMap[detail.order.orderStatus] || detail.order.orderStatus}}</a-descriptions-item>
              <a-descriptions-item label="创建时间">{{fmt(detail.order.createTime)}}</a-descriptions-item>
              <a-descriptions-item label="更新时间">{{fmt(detail.order.updateTime)}}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <!-- 全景工业物流与在途 GIS 动态感知看板 -->
          <a-card :bordered="false" class="card ai-logistics-card" title="工业在途感知与全景物流">
            <!-- 多包裹选择 Tab (存在拆单多包裹时高亮切换) -->
            <div v-if="logisticsAgg?.waybills?.length > 1" class="package-tab-bar">
              <span class="tab-lead">分拆包裹 (共 {{ logisticsAgg.waybills.length }} 包)：</span>
              <button
                v-for="(wb, idx) in logisticsAgg.waybills"
                :key="wb.waybill.waybillId || idx"
                class="pkg-tab-item"
                :class="{ active: activeWaybillIndex === idx, 'is-reverse': wb.waybill?.waybillDirection === 'reverse' }"
                @click="activeWaybillIndex = idx"
              >
                <span v-if="wb.waybill?.waybillDirection === 'reverse'" class="tab-rma-badge">RMA退货</span>
                <span class="tab-pkg-no">{{ wb.waybill.packageNo || ('PKG-' + (idx + 1)) }}</span>
                <span class="tab-provider">{{ wb.providerName }}</span>
                <span class="tab-status">{{ waybillStatusMap[wb.waybill.status] || wb.waybill.status }}</span>
              </button>
            </div>

            <!-- 当前运单存在时：渲染 GIS 矢量动态地图与轨迹 -->
            <template v-if="currentWaybill">
              <!-- GIS 动态地图组件 -->
              <LogisticsDynamicMap
                :map-route="currentWaybill.mapRoute"
                :waybill-info="currentWaybill.waybill"
                :package-items="currentWaybill.items"
              />

              <!-- 运单基础信息栏 -->
              <div class="waybill-meta-bar">
                <div class="meta-item">
                  <span class="meta-label">承运企业：</span>
                  <strong>{{ currentWaybill.providerName }}</strong>
                </div>
                <div class="meta-item">
                  <span class="meta-label">货运单号：</span>
                  <code>{{ currentWaybill.waybill.logisticsNo || currentWaybill.waybill.waybillNo }}</code>
                </div>
                <div class="meta-item" v-if="currentWaybill.waybill.shipTime">
                  <span class="meta-label">发运时间：</span>
                  <span>{{ fmt(currentWaybill.waybill.shipTime) }}</span>
                </div>
                <div class="meta-item" v-if="currentWaybill.waybill.deliveredTime">
                  <span class="meta-label">签收时间：</span>
                  <span style="color:#16a34a; font-weight:600">{{ fmt(currentWaybill.waybill.deliveredTime) }}</span>
                </div>
              </div>

              <!-- 移动端专属快捷装载抽屉触发按钮 -->
              <div class="mobile-cargo-bar">
                <button
                  type="button"
                  class="mobile-cargo-btn"
                  @click="mobileCargoDrawerOpen = true"
                >
                  <Package :size="14" class="inline mr-1 text-sky-500" />
                  查看装载工业品与随箱质保证书（{{ currentWaybill.items?.length || 0 }} 种）
                </button>
              </div>

              <!-- 随货同行单证 (CoA / 质保证书 / 材质证明) -->
              <div v-if="currentWaybill.coaDocs && currentWaybill.coaDocs.length" class="coa-showcase-panel">
                <div class="panel-head">
                  <span class="head-title">
                    <FileText :size="15" class="text-indigo-600 inline-block mr-1 align-middle" aria-hidden="true" />
                    随货同行质保证书（{{ currentWaybill.coaDocs.length }} 份单证已随箱核发）
                  </span>
                  <span class="head-tip">出厂检验标准已在平台存证</span>
                </div>
                <div class="coa-card-grid">
                  <div v-for="doc in currentWaybill.coaDocs" :key="doc.docId" class="coa-view-card">
                    <div class="coa-view-left">
                      <FileCheck2 :size="18" class="text-sky-500 mr-2 flex-shrink-0" />
                      <div class="coa-view-info">
                        <span class="doc-main-name" :title="doc.docName">{{ doc.docName }}</span>
                        <div class="doc-sub-tags">
                          <span class="doc-badge">{{ (doc.docType || 'CoA').toUpperCase() }}</span>
                          <span v-if="doc.batchNo" class="batch-badge">批次: {{ doc.batchNo }}</span>
                        </div>
                      </div>
                    </div>
                    <div class="coa-view-actions">
                      <a :href="doc.fileUrl" target="_blank" class="preview-doc-link" rel="noopener noreferrer">
                        <ExternalLink :size="13" class="inline mr-1" />查阅原件
                      </a>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 现场数字 e-POD 签收回单与公章验签闭环 -->
              <div class="epod-status-panel">
                <div class="panel-head">
                  <span class="head-title">
                    <Stamp :size="15" class="text-rose-600 inline-block mr-1 align-middle" aria-hidden="true" />
                    现场数字 e-POD 签收凭据与公章验签
                  </span>
                  <div class="epod-badge-wrap">
                    <span v-if="currentWaybill.waybill.podAuditStatus === 'approved'" class="badge-approved">
                      <ShieldCheck :size="13" class="inline mr-1" />公章验签合格 · 已归档
                    </span>
                    <span v-else-if="currentWaybill.waybill.podAuditStatus === 'pending'" class="badge-pending">
                      <Clock :size="13" class="inline mr-1" />已提交现场回执 · 等待验签
                    </span>
                    <span v-else-if="currentWaybill.waybill.podAuditStatus === 'rejected'" class="badge-rejected">
                      <AlertTriangle :size="13" class="inline mr-1" />验签未通过 ({{ currentWaybill.waybill.podAuditNote || '需补正' }})
                    </span>
                    <span v-else class="badge-none">未上传现场签收凭据</span>
                  </div>
                </div>

                <div v-if="currentWaybill.podInfo" class="epod-detail-body">
                  <div class="epod-signer-row">
                    <span><strong>现场签收人：</strong>{{ currentWaybill.podInfo.signerName }} ({{ currentWaybill.podInfo.signerPhone || '未留电话' }})</span>
                    <span><strong>签收打卡时间：</strong>{{ fmt(currentWaybill.podInfo.signedTime) }}</span>
                  </div>
                  <div class="epod-photos-row">
                    <div class="photo-col" v-if="currentWaybill.podInfo.stampPhotoUrl">
                      <span class="photo-label">加盖公章签收单：</span>
                      <a :href="currentWaybill.podInfo.stampPhotoUrl" target="_blank" rel="noopener noreferrer">
                        <img :src="currentWaybill.podInfo.stampPhotoUrl" alt="公章原件照片" class="stamp-thumb" />
                      </a>
                    </div>
                    <div class="photo-col" v-if="currentWaybill.podInfo.cargoPhotos && currentWaybill.podInfo.cargoPhotos.length">
                      <span class="photo-label">货物外观与开箱：</span>
                      <div class="cargo-thumbs-box">
                        <a v-for="(p, pIdx) in currentWaybill.podInfo.cargoPhotos" :key="pIdx" :href="p" target="_blank" rel="noopener noreferrer">
                          <img :src="p" alt="货物外观照片" class="cargo-thumb" />
                        </a>
                      </div>
                    </div>
                  </div>
                  <div v-if="currentWaybill.podInfo.signRemark" class="epod-remark">
                    <span>验收批注：</span>{{ currentWaybill.podInfo.signRemark }}
                  </div>
                </div>

                <div class="epod-action-bar">
                  <a-button v-if="currentWaybill.waybill.podAuditStatus !== 'approved'" type="primary" size="small" @click="openEpodModal">
                    <Camera :size="13" class="inline-block mr-1 align-middle" />
                    {{ currentWaybill.waybill.podAuditStatus === 'rejected' ? '重新上传盖章回单' : '上传现场签收凭据 (e-POD)' }}
                  </a-button>
                  <span class="epod-guide-tip">工业交付要求加盖买方单位公章/收货章，并留存货物完好照片，作为结算与防货损凭据。</span>
                </div>
              </div>

              <!-- 轨迹时间轴 -->
              <div class="trace-timeline-wrap">
                <div class="timeline-title">
                  <MapPin :size="16" class="text-rose-500 inline-block mr-1 align-middle" aria-hidden="true" />
                  实时流转轨迹（{{ currentWaybill.traces?.length || 0 }} 处节点留痕）
                </div>
                <div v-if="currentWaybill.traces && currentWaybill.traces.length" class="trace-timeline">
                  <div
                    v-for="(node, idx) in [...currentWaybill.traces].reverse()"
                    :key="node.traceId || idx"
                    class="trace-node"
                    :class="{ 'is-latest': idx === 0 }"
                  >
                    <div class="node-time">{{ fmt(node.traceTime) }}</div>
                    <div class="node-dot" :class="{ abnormal: node.isAbnormal === 1 }"></div>
                    <div class="node-desc">{{ node.description }}</div>
                  </div>
                </div>
                <div v-else class="trace-empty">运单已创建，承运车队正在调度中，首条提货轨迹即将同步...</div>
              </div>
            </template>

            <!-- 尚无发货运单（待发货状态） -->
            <div v-else class="logistics-pending-panel">
              <div class="pending-icon">
                <Factory :size="40" class="text-slate-400 mb-2 stroke-[1.5]" aria-hidden="true" />
              </div>
              <div class="pending-title">工厂仓储调度备货中</div>
              <div class="pending-desc">
                {{ detail?.order?.payStatus === 1 ? '订单已完成支付核销，商户工厂正在调拨出库、打包装箱并安排货运车辆。发货后将在此生成全景矢量动态航线。' : '完成订单支付后，商户将启动仓储履约程序。' }}
              </div>
            </div>
          </a-card>
        </div>
      </template>
      <a-result v-else-if="!loading" status="404" title="订单不存在或无权查看" sub-title="请返回我的订单重新选择">
        <template #extra><a-button type="primary" @click="$router.push('/orders')">返回订单</a-button></template>
      </a-result>
    </a-spin>
    <!-- 21st.dev 多渠道收银台弹窗 -->
    <a-modal
      v-model:open="payOpen"
      :title="`收银结算 · 订单号 ${route.params.orderNo}`"
      width="680px"
      :footer="null"
      destroy-on-close
      @cancel="stopPayPolling"
    >
      <div class="pay-modal-body">
        <div class="pay-amount-banner">
          <div class="banner-left">
            <span class="banner-label">待支付订单实付金额</span>
            <div class="banner-amt">¥<b>{{ money(detail?.order?.totalAmount) }}</b></div>
          </div>
          <div class="banner-right">
            <span class="banner-badge">
              <ShieldCheck :size="13" class="inline mr-1" /> 金融级 SSL 传输防护
            </span>
          </div>
        </div>

        <!-- 21st.dev 风格支付方式多通道选择器 -->
        <div class="selector-container">
          <div class="selector-title">选择支付方式（支持 7 种主流与国际结算）</div>
          <PaymentMethodSelector
            v-model="selectedPayMethod"
            :wallet-balance="walletBalance"
            :order-amount="Number(detail?.order?.totalAmount || 0)"
          />
        </div>

        <!-- Stripe 虚拟卡槽 -->
        <div v-if="selectedPayMethod === 'stripe'" class="extra-panel">
          <StripeCardElement />
        </div>

        <!-- 对公收款账号提示 -->
        <div v-else-if="selectedPayMethod === 'corporate'" class="extra-panel">
          <MerchantCorporateBankCard
            :bank-info="{
              bankName: '中国工商银行股份有限公司深圳科技园支行',
              bankAccountMasked: '6212 26****** 8899',
              legalPersonMasked: '深圳市蓝链工业互联股份有限公司',
              taxRegNo: '91440300MA5XXXXXX',
              verified: true
            }"
          />
        </div>

        <!-- 支付宝/微信说明 -->
        <div v-else-if="selectedPayMethod === 'alipay' || selectedPayMethod === 'wechat'" class="gateway-preview-panel">
          <QrCode :size="20" class="text-sky-500 mr-2 flex-shrink-0" />
          <div class="gateway-tip">
            <strong>{{ selectedPayMethod === 'alipay' ? '支付宝' : '微信支付' }}安全网关直连</strong>
            <p>点击确认支付后将唤起{{ selectedPayMethod === 'alipay' ? '支付宝收银台' : '微信扫码二维码' }}完成交易核验。</p>
          </div>
        </div>

        <!-- 苹果/谷歌支付说明 -->
        <div v-else-if="selectedPayMethod === 'apple_pay' || selectedPayMethod === 'google_pay'" class="gateway-preview-panel">
          <Smartphone :size="20" class="text-indigo-500 mr-2 flex-shrink-0" />
          <div class="gateway-tip">
            <strong>{{ selectedPayMethod === 'apple_pay' ? 'Apple Pay 极速支付' : 'Google Pay 极速支付' }}</strong>
            <p>由 Stripe Express Checkout 引擎驱动，支持原生生物识别（Touch ID / Face ID）秒级完成扣款。</p>
          </div>
        </div>

        <!-- 确认支付大按钮 -->
        <div class="pay-submit-bar">
          <button
            type="button"
            class="pay-confirm-btn"
            :disabled="payLoading || (selectedPayMethod === 'balance' && walletBalance < Number(detail?.order?.totalAmount || 0))"
            @click="payWith(selectedPayMethod)"
          >
            <span v-if="payLoading">正在建立安全加密支付通道...</span>
            <span v-else-if="selectedPayMethod === 'balance' && walletBalance < Number(detail?.order?.totalAmount || 0)">钱包余额不足，请更换支付方式</span>
            <span v-else>立即使用 {{ payMethodDisplayName(selectedPayMethod) }} 支付 ¥{{ money(detail?.order?.totalAmount) }}</span>
          </button>
        </div>
      </div>
    </a-modal>

    <!-- 申请退款弹窗 -->
    <a-modal v-model:open="refundOpen" :title="t('申请退款')" :ok-text="t('提交申请')" :cancel-text="t('取消')"
             :confirm-loading="refunding" @ok="doRefund">
      <a-form layout="vertical">
        <a-form-item :label="t('售后类型')" required>
          <a-radio-group v-model:value="rmaType">
            <a-radio value="return_goods" :disabled="Number(detail?.order?.orderStatus) === 0">{{ t('退货退款 (需寄回商品，验货入库后打款)') }}</a-radio>
            <a-radio value="refund_only">{{ t('仅退款 (未收到货或协商一致无需退货)') }}</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item :label="t('可退金额')"><b>¥{{ money(Number(detail?.order?.totalAmount || 0) - Number(detail?.order?.refundAmount || 0)) }}</b></a-form-item>
        <a-form-item :label="t('申请原因（必填，将提交商户初审）')" required>
          <a-textarea v-model:value="refundReason" :rows="3" maxlength="200" show-count
                      :placeholder="t('例如：商品缺货 / 与描述不符 / 沟通后协商退款')" />
        </a-form-item>
        <a-form-item :label="t('退款凭证（至少上传 1 项）')" required>
          <RefundEvidenceUpload ref="evidenceUploadRef" v-model:evidences="refundEvidences" @status="onEvidenceStatus" />
          <div class="rma-evidence-hints">
            <p>{{ t('若已与商家沟通协商，请上传聊天记录截图或协商说明文件') }}</p>
            <p>{{ t('图片 JPG/PNG/WEBP/GIF 单张 ≤8MB；文件 PDF ≤20MB、TXT/CSV ≤2MB') }}</p>
            <p>{{ t('Word/压缩包等将以 .bin 附件提交，审核端可下载查看') }}</p>
            <p>{{ t('视频 MP4/MOV 单个 ≤20MB') }}</p>
          </div>
        </a-form-item>
        <a-alert type="info" show-icon :message="t('商户初审与验货流程')"
                 :description="t('仅退款在商户初审通过后立即原路出款；退货退款在商户初审通过后，请及时寄回包裹并填报运单，仓库验货合格后自动打款。')" />
      </a-form>
    </a-modal>

    <!-- 填报退货物流单号弹窗 -->
    <a-modal v-model:open="shipBackOpen" title="填报退货物流单号" ok-text="确认提交" cancel-text="取消"
             :confirm-loading="shipBackLoading" @ok="doShipBack">
      <a-alert type="info" show-icon style="margin-bottom: 16px;"
               :message="'商家收件信息：' + (detail?.order?.returnReceiverName || '仓库收件人') + ' ' + (detail?.order?.returnReceiverPhone || '')"
               :description="'退货地址：' + (detail?.order?.returnWarehouseAddr || '请与商家核对退货地址')" />
      <a-form layout="vertical">
        <a-form-item label="物流承运商" required>
          <a-select v-model:value="shipBackCarrier" placeholder="请选择快递公司">
            <a-select-option value="顺丰速运">顺丰速运</a-select-option>
            <a-select-option value="中通快递">中通快递</a-select-option>
            <a-select-option value="圆通速递">圆通速递</a-select-option>
            <a-select-option value="京东物流">京东物流</a-select-option>
            <a-select-option value="德邦快递">德邦快递</a-select-option>
            <a-select-option value="EMS">EMS</a-select-option>
            <a-select-option value="其它快递">其它快递</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="物流运单号" required>
          <a-input v-model:value="shipBackWaybillNo" placeholder="请输入寄出快递的运单号" />
        </a-form-item>
        <a-form-item label="寄回说明 (选填)">
          <a-textarea v-model:value="shipBackRemark" :rows="2" placeholder="例如：配件与原包装完好" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 现场数字 e-POD 签收凭据上传弹窗 -->
    <a-modal
      v-model:open="epodModalOpen"
      title="上传现场签收凭据 (e-POD 数字存证)"
      ok-text="确认提交验签"
      cancel-text="取消"
      :confirm-loading="epodSubmitting"
      :width="600"
      @ok="submitEpod"
    >
      <a-form layout="vertical">
        <a-alert
          type="info"
          show-icon
          style="margin-bottom: 16px;"
          message="工业大宗交付法律存证提示"
          description="按大宗商品交收合规要求，请务必核实纸质签收单上盖有买方企业公章/收货专用章，并拍摄现场货物开箱验收照片。提交后将进入商户/平台验签流程。"
        />
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12">
            <a-form-item label="现场签收人姓名" required>
              <a-input v-model:value="epodForm.signerName" placeholder="例如：李工 / 张库管" maxlength="30" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="现场签收人电话" required>
              <a-input v-model:value="epodForm.signerPhone" placeholder="用于签收身份交叉核验" maxlength="20" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="加盖公章的纸质签收单照片 (必填)" required extra="支持 JPG/PNG/WEBP/PDF，需清晰可见红章印油及签字">
          <div class="epod-upload-area">
            <div v-if="epodForm.stampPhoto" class="preview-stamp-box">
              <img :src="epodForm.stampPhoto" alt="公章签收单预览" class="preview-stamp-img" />
              <a-button type="link" danger size="small" @click="epodForm.stampPhoto = ''">重新上传</a-button>
            </div>
            <label v-else class="upload-stamp-btn">
              <Camera :size="20" class="text-rose-600 mb-1" />
              <span class="upload-btn-text">拍照或选择纸质公章签收单</span>
              <input type="file" accept="image/*,application/pdf" style="display:none" @change="handleStampUpload" />
            </label>
          </div>
        </a-form-item>

        <a-form-item label="现场货物外观 / 开箱检验照片 (选填，支持多张)" extra="记录外包装完好、托盘标签、开箱实物，防范货损纠纷">
          <div class="epod-cargo-grid">
            <div v-for="(img, idx) in epodForm.cargoPhotos" :key="idx" class="cargo-img-item">
              <img :src="img" alt="货物照片" />
              <button type="button" class="cargo-del-btn" @click="epodForm.cargoPhotos.splice(idx, 1)" aria-label="删除图片">
                <X :size="12" />
              </button>
            </div>
            <label v-if="epodForm.cargoPhotos.length < 5" class="cargo-upload-btn">
              <Plus :size="20" class="text-slate-400" />
              <span class="cargo-upload-text">添加照片</span>
              <input type="file" accept="image/*" style="display:none" @change="handleCargoUpload" />
            </label>
          </div>
        </a-form-item>

        <a-form-item label="现场验收批注 / 异议留痕 (选填)">
          <a-textarea
            v-model:value="epodForm.signRemark"
            :rows="2"
            maxlength="200"
            show-count
            placeholder="如包装轻微挤压但货物完好、托盘数量、实物外观确认等"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 21st.dev 移动端专属抽屉：当前运单装载工业品与单证 -->
    <SmoothMobileDrawer
      v-model:open="mobileCargoDrawerOpen"
      :title="`装载明细 · ${currentWaybill?.waybill?.packageNo || '货物单证'}`"
      max-height="85vh"
    >
      <div v-if="currentWaybill" class="mobile-cargo-drawer-body">
        <div class="drawer-waybill-header">
          <div class="dwh-carrier">{{ currentWaybill.providerName }} · <code>{{ currentWaybill.waybill.logisticsNo || currentWaybill.waybill.waybillNo }}</code></div>
          <span class="dwh-status">{{ waybillStatusMap[currentWaybill.waybill.status] || currentWaybill.waybill.status }}</span>
        </div>

        <div class="drawer-sec-title">
          <Package :size="14" class="inline mr-1 text-sky-500" />
          本包裹装载货物清单 ({{ currentWaybill.items?.length || 0 }} 项)
        </div>
        <div class="drawer-items-list">
          <div v-for="item in currentWaybill.items" :key="item.itemId" class="drawer-item-card">
            <img v-if="item.coverUrl" :src="resolveStorageUrl(item.coverUrl)" class="drawer-item-thumb" alt="" />
            <div class="drawer-item-meta">
              <span class="drawer-item-name">{{ item.prodName || item.title }}</span>
              <div class="drawer-item-sub">
                <span class="qty">装车数量：×{{ item.quantity }}</span>
                <span v-if="item.spec" class="spec">{{ item.spec }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="currentWaybill.coaDocs && currentWaybill.coaDocs.length" class="drawer-sec-block">
          <div class="drawer-sec-title">
            <FileText :size="14" class="inline mr-1 text-indigo-500" />
            随箱质保单证 ({{ currentWaybill.coaDocs.length }})
          </div>
          <div class="drawer-coa-list">
            <div v-for="doc in currentWaybill.coaDocs" :key="doc.docId" class="drawer-coa-pill">
              <FileCheck2 :size="13" class="text-sky-500 mr-1.5 flex-shrink-0" />
              <span class="doc-text">{{ doc.docName }}</span>
              <a :href="doc.fileUrl" target="_blank" class="doc-link" rel="noopener noreferrer">原件</a>
            </div>
          </div>
        </div>
      </div>
    </SmoothMobileDrawer>
  </div>
</template>

<script setup>
import {ref,computed,onMounted,nextTick,watch,onBeforeUnmount} from 'vue'
import {message,Modal} from 'ant-design-vue'
import {useRoute,useRouter} from 'vue-router'
import {orderApi,paymentApi,walletApi,trafficApi,logisticsApi,rmaApi} from '../api'
import request from '../api/request'
import { t } from '../i18n'
import {buildPayQrPayload,renderQrToCanvas} from '../utils/qrCode'
import {resolveStorageUrl} from '../utils/storageUrl'
import {serializeEvidences} from '../utils/refundEvidence'
import RefundEvidenceUpload from '../components/refund/RefundEvidenceUpload.vue'
import ReviewDialog from '../components/review/ReviewDialog.vue'
import {
  Factory, MapPin, FileText, FileCheck2, ExternalLink,
  Stamp, ShieldCheck, Clock, AlertTriangle, Camera, Plus, X,
  QrCode, Smartphone, CreditCard, Package
} from 'lucide-vue-next'
import LogisticsDynamicMap from '../components/logistics/LogisticsDynamicMap.vue'
import PaymentMethodSelector from '../components/payment/PaymentMethodSelector.vue'
import MerchantCorporateBankCard from '../components/payment/MerchantCorporateBankCard.vue'
import StripeCardElement from '../components/payment/StripeCardElement.vue'
import SmoothMobileDrawer from '../components/ui/SmoothMobileDrawer.vue'
import VipPromoEntry from '../components/membership/VipPromoEntry.vue'

const route=useRoute(),router=useRouter(),detail=ref(null),loading=ref(false),payLoading=ref(false),walletBalance=ref(0),payOpen=ref(false)
const selectedPayMethod = ref('stripe')
const mobileCargoDrawerOpen = ref(false)
const refundOpen=ref(false),refunding=ref(false),refundReason=ref('')
const refundEvidences=ref([])
const evidenceUploadRef=ref(null)
const evidenceStatus=ref({uploading:0,error:0,done:0})
function onEvidenceStatus(s){evidenceStatus.value=s}
const rmaType=ref('return_goods')
const shipBackOpen=ref(false),shipBackLoading=ref(false)
const shipBackCarrier=ref('顺丰速运'),shipBackWaybillNo=ref(''),shipBackRemark=ref('')

// 物流多包裹与 GIS 在途感知状态
const logisticsAgg = ref(null)
const activeWaybillIndex = ref(0)
const loadingLogistics = ref(false)

const currentWaybill = computed(() => {
  const list = logisticsAgg.value?.waybills || []
  if (!list.length) return null
  return list[activeWaybillIndex.value] || list[0]
})

const waybillStatusMap = {
  pending_ship: '待发货',
  picked_up: '已揽收',
  in_transit: '运输中',
  out_for_delivery: '派送中',
  delivered: '已签收',
  exception: '异常',
  returned: '已退回',
  cancelled: '已取消'
}

// 扫码支付状态
const payQr=ref(''),payQrDemo=ref(false),payCanvasRef=ref(null)
let payPollTimer=0
const statusMap={0:'待发货',1:'已发货',2:'已完成',3:'已取消',4:'部分发货'}
const payType={corporate:'对公转账',balance:'企业钱包',term:'账期/尾款'}
const channelText={wallet:'钱包',mock:'模拟网关',wechat:'微信支付',alipay:'支付宝'}
const canPay=computed(()=>{const o=detail.value?.order;return o&&o.payStatus===0&&o.orderStatus===0&&o.payType!=='term'})
const canCancel=computed(()=>{const o=detail.value?.order;return o&&o.orderStatus===0&&(o.payStatus===0||o.payType==='term')})
/** 可确认收货：已支付且（已发货或部分发货） */
const canConfirm=computed(()=>{const o=detail.value?.order;return !!o&&Number(o.payStatus)===1&&(Number(o.orderStatus)===1||Number(o.orderStatus)===4)})
/** 可申请退款：已支付、订单未结束，且无进行中/已完成退款（被驳回可再次申请；部分发货允许退款） */
const canReview=computed(()=>!!detail.value?.order && Number(detail.value.order.orderStatus)===2)
const reviewOpen=ref(false)
const reviewItem=ref(null)
const reviewedIds=ref(new Set())
function openReview(item){ reviewItem.value=item; reviewOpen.value=true }
function onReviewed(itemId){ reviewedIds.value=new Set([...reviewedIds.value, itemId]) }

const canRefund=computed(()=>{const o=detail.value?.order;return !!o&&Number(o.payStatus)===1&&![2,3,5].includes(Number(o.orderStatus))&&!['applied','merchant_approved','shipping_back','received','dispute','processing','success'].includes(o.refundStatus||'none')})
const canShipBack=computed(()=>{const o=detail.value?.order;return !!o&&o.refundStatus==='merchant_approved'&&o.refundType==='return_goods'})
const stepIndex=computed(()=>{const o=detail.value?.order;if(!o)return 0;if(o.orderStatus===3||o.orderStatus===5||o.payStatus===2)return -1;if(o.orderStatus===2)return 3;if(o.orderStatus===1||o.orderStatus===4)return 2;return o.payStatus===1?2:1})
// 优惠构成行：promoSource=member → 会员等级折扣；promotion → 活动名快照（promo_title 为空时按「促销优惠」兜底）
const promoRow=computed(()=>{const o=detail.value?.order;if(!o)return null;const d=Number(o.promoDiscount)||0;if(d<=0)return null;if(o.promoSource==='member')return {label:'会员等级折扣',amount:d};return {label:o.promoTitle?('促销优惠 · '+o.promoTitle):'促销优惠',amount:d}})
const fmt=v=>v?String(v).replace('T',' '):'—'
const money=v=>Number(v||0).toFixed(2)
const maskPhone=v=>{const s=String(v||'');return s.length>=7?s.slice(0,3)+'****'+s.slice(-4):s||'—'}
const refundText=o=>{
  if(!o) return '无退款'
  const st=o.refundStatus
  if(st==='success') return '已退款'
  if(st==='partial') return `已部分退款 ¥${money(o.refundAmount)}，剩余可再次申请`
  if(st==='processing') return '退款处理中'
  if(st==='merchant_approved') return o.refundType==='return_goods'?'初审通过 · 请寄回商品并填报运单':'初审通过 · 待平台出款'
  if(st==='shipping_back') return '商品已寄回 · 待仓库收货验货'
  if(st==='received') return '仓库已收货 · 质检中'
  if(st==='dispute') return '验货异常协商中'
  if(st==='applied') return '退款申请中 · 待商家初审'
  if(st==='rejected') return '退款申请被驳回'
  return '无退款'
}
const statusText=o=>o.orderStatus===3?'订单已取消':(o.orderStatus===5||o.payStatus===2)?'订单已退款':o.orderStatus===4?'部分发货':o.payStatus===1?'订单已支付':'待支付'
const statusHint=o=>o.orderStatus===3?'订单已取消，无法继续操作':(o.orderStatus===5||o.payStatus===2)?'订单已全额退款并关闭':o.orderStatus===4?'该订单已分批拆包发货，可在下方全景地图中跟踪各包裹轨迹':o.payStatus===1?'订单已进入履约流程':'完成支付后商户才会开始履约'
// 状态色点：待支付橙、履约蓝、完成绿、取消/退款灰
const statusTone=computed(()=>{const o=detail.value?.order;if(!o)return 'gray';if(Number(o.orderStatus)===3||Number(o.orderStatus)===5||Number(o.payStatus)===2)return 'gray';if(Number(o.orderStatus)===2)return 'green';if(Number(o.payStatus)===0)return 'orange';return 'blue'})
// 苹果式进度：三态（done/current/wait）+ 时间或状态描述
const stepsView=computed(()=>{
  const o=detail.value?.order;if(!o)return []
  const cur=stepIndex.value
  const t=v=>v?String(v).replace('T',' ').slice(0,16):'—'
  const raw=[
    {title:'提交订单',desc:t(o.createTime)},
    {title:'支付完成',desc:o.payTime?t(o.payTime):'—'},
    {title:'履约中',desc:Number(o.orderStatus)===4?'已拆单部分发货':Number(o.orderStatus)>=1?'商户已全部发货':'等待商户备货发货'},
    {title:'完成',desc:Number(o.orderStatus)===2?t(o.updateTime):'订单完成'}
  ]
  return raw.map((s,i)=>({...s,state:i<cur?'done':i===cur?'current':'wait'}))
})

async function load(){
  loading.value=true
  try{
    detail.value=await orderApi.detail(route.params.orderNo)
    loadLogistics()
    loadPayStatus()
  }catch(e){
    detail.value=null
  }finally{
    loading.value=false
  }
}

// 入口 6（设计稿 §2）：支付完成回执引导条——仅 paymentApi.query 确认已支付的详情页展示一次；
// 成长值=实付金额取整（口径同 MembershipService.earnGrowth：growth=orderAmount.intValue()）
const orderPaid=ref(false)
const creditedGrowth=computed(()=>Math.max(0,Math.floor(Number(detail.value?.order?.totalAmount)||0)))
const growthNote=computed(()=>creditedGrowth.value>0?`成长值已到账 +${creditedGrowth.value.toLocaleString('zh-CN')} · 订阅解锁等级折扣`:'')
async function loadPayStatus(){
  try{
    const st=await paymentApi.query(route.params.orderNo)
    orderPaid.value=Number(st?.payStatus)===1
  }catch(e){ orderPaid.value=false }
}

async function loadLogistics(){
  if(!route.params.orderNo) return
  loadingLogistics.value=true
  try{
    logisticsAgg.value = await logisticsApi.orderWaybills(route.params.orderNo)
  }catch(e){
    logisticsAgg.value = null
  }finally{
    loadingLogistics.value=false
  }
}
function payMethodDisplayName(k) {
  const map = {
    stripe: 'Stripe 国际卡',
    apple_pay: 'Apple Pay',
    google_pay: 'Google Pay',
    alipay: '支付宝',
    wechat: '微信支付',
    balance: '企业钱包',
    corporate: '企业对公转账',
    mock: '模拟网关'
  }
  return map[k] || '推荐渠道'
}

async function openPay(){
  try{
    const w = await walletApi.my()
    walletBalance.value = Number(w?.balance || 0)
  }catch(e){
    walletBalance.value = 0
  }
  selectedPayMethod.value = detail.value?.order?.payType && detail.value?.order?.payType !== 'term' ? detail.value.order.payType : 'stripe'
  payOpen.value = true
}
/** 支付成功埋点：订单详情无商户归属信息，PAY 作为平台级事件上报（后端以 0 兜底），失败静默 */
function trackPay(){ trafficApi.trackPage({eventType:'PAY',sourceType:'order_detail'}).catch(()=>{}) }

async function payWith(channel){
  if(payLoading.value) return
  if(channel === 'balance' || channel === 'wallet'){
    if(walletBalance.value < Number(detail.value?.order?.totalAmount || 0)){
      router.push('/wallet/recharge')
      payOpen.value = false
      return
    }
  }
  payLoading.value = true
  try{
    if(channel === 'balance' || channel === 'wallet'){
      const vo = await paymentApi.create(route.params.orderNo, 'wallet')
      if(!vo?.payInfo?.paid) throw new Error('支付未确认')
      message.success('支付成功')
    } else if(['stripe', 'apple_pay', 'google_pay'].includes(channel)){
      const vo = await paymentApi.create(route.params.orderNo, 'stripe')
      if(vo?.mock){
        await paymentApi.mockConfirm(route.params.orderNo)
        message.success('Stripe 支付成功（模拟环境）')
      } else {
        message.success('Stripe 支付预授权已建立')
      }
    } else if(channel === 'alipay'){
      const vo = await paymentApi.create(route.params.orderNo, 'alipay')
      if(vo?.mock){
        await paymentApi.mockConfirm(route.params.orderNo)
        message.success('支付宝支付成功（模拟环境）')
      } else if(vo?.payInfo?.form){
        const div = document.createElement('div')
        div.innerHTML = vo.payInfo.form
        document.body.appendChild(div)
        document.forms[0]?.submit()
        return
      }
    } else if(channel === 'wechat'){
      const vo = await paymentApi.create(route.params.orderNo, 'wechat')
      if(vo?.mock){
        await paymentApi.mockConfirm(route.params.orderNo)
        message.success('微信支付成功（模拟环境）')
      } else {
        message.success('请使用微信扫码完成支付')
      }
    } else if(channel === 'corporate'){
      message.info(`对公电汇订单已就绪，请向平台对公账户转账并备注订单号 ${route.params.orderNo}`)
    } else {
      const vo = await paymentApi.create(route.params.orderNo, 'mock')
      if(vo?.mock) await paymentApi.mockConfirm(route.params.orderNo)
      message.success('支付成功')
    }
    trackPay()
    payOpen.value = false
    await load()
  }catch(e){
    /* 拦截器提示 */
  }finally{
    payLoading.value = false
  }
}
async function cancel(){try{await orderApi.cancel(route.params.orderNo);message.success('订单已取消');await load()}catch(e){}}
function confirmReceipt(){
  Modal.confirm({
    title:'确认已收到货？',
    content:`订单 ${route.params.orderNo} 将标记为「已完成」，在途运单同步签收。请确认商品已验收无误。`,
    okText:'确认收货',cancelText:'再等等',
    onOk:async()=>{try{await orderApi.confirmReceipt(route.params.orderNo);message.success('确认收货成功，交易完成');await load()}catch(e){/* 拦截器已提示 */}}
  })
}
function openRefund() {
  refundReason.value = ''
  refundEvidences.value = []
  evidenceUploadRef.value?.clear?.()
  const o = detail.value?.order
  rmaType.value = (o && Number(o.orderStatus) === 0) ? 'refund_only' : 'return_goods'
  refundOpen.value = true
}

async function doRefund() {
  if (!refundReason.value.trim()) {
    message.warning(t('请填写退款原因'))
    return
  }
  // 证据 ≥1 必填（体验层，与后端 RefundEvidenceValidator 双层拦截）
  if (!refundEvidences.value.length) {
    message.warning(t('请至少上传 1 项退款凭证'))
    return
  }
  if (evidenceStatus.value.uploading > 0) {
    message.warning(t('凭证上传中，请稍候'))
    return
  }
  if (evidenceStatus.value.error > 0) {
    message.warning(t('凭证上传失败，请重试'))
    return
  }
  refunding.value = true
  try {
    const o = detail.value?.order
    const remain = Number(o?.totalAmount || 0) - Number(o?.refundAmount || 0)
    await rmaApi.apply({
      orderNo: route.params.orderNo,
      rmaType: rmaType.value,
      applyReason: refundReason.value.trim(),
      applyAmount: remain > 0 ? remain : undefined,
      proofImages: serializeEvidences(refundEvidences.value)
    })
    message.success(t('售后申请已提交，等待商户初审'))
    refundOpen.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    refunding.value = false
  }
}

function openShipBackModal() {
  shipBackWaybillNo.value = ''
  shipBackRemark.value = ''
  shipBackOpen.value = true
}

async function doShipBack() {
  if (!shipBackWaybillNo.value.trim()) {
    message.warning('请填写退货运单号')
    return
  }
  const o = detail.value?.order
  if (!o?.currentRmaNo) {
    message.error('未找到关联的售后服务单号')
    return
  }
  shipBackLoading.value = true
  try {
    await rmaApi.shipBack(o.currentRmaNo, {
      carrierName: shipBackCarrier.value,
      waybillNo: shipBackWaybillNo.value.trim(),
      remark: shipBackRemark.value.trim()
    })
    message.success('退货物流单号已填报')
    shipBackOpen.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    shipBackLoading.value = false
  }
}

// ===== 现场数字 e-POD 签收单上传与存证 =====
const epodModalOpen = ref(false)
const epodSubmitting = ref(false)
const epodForm = ref({
  signerName: '',
  signerPhone: '',
  stampPhoto: '',
  cargoPhotos: [],
  signRemark: ''
})

function openEpodModal() {
  if (!currentWaybill.value) {
    message.warning('未选择当前运单')
    return
  }
  const pod = currentWaybill.value.podInfo
  epodForm.value = {
    signerName: pod?.signerName || detail.value?.order?.receiverName || '',
    signerPhone: pod?.signerPhone || detail.value?.order?.receiverPhone || '',
    stampPhoto: pod?.stampPhotoUrl || '',
    cargoPhotos: pod?.cargoPhotos ? [...pod.cargoPhotos] : [],
    signRemark: pod?.signRemark || ''
  }
  epodModalOpen.value = true
}

async function handleStampUpload(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 20 * 1024 * 1024) {
    message.error('签收单附件不能超过 20MB')
    return
  }
  const hide = message.loading('正在上传公章签收单...', 0)
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await request.post('/attachment/upload?bizType=logistics-epod-receipt', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const docData = res?.data || res
    epodForm.value.stampPhoto = docData.fileUrl || docData.url || `/attachment/file/${docData.attachNo || docData.attachmentId}`
    message.success('公章签收单上传成功')
  } catch (err) {
    // 拦截器处理
  } finally {
    hide()
    e.target.value = ''
  }
}

async function handleCargoUpload(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 15 * 1024 * 1024) {
    message.error('照片不能超过 15MB')
    return
  }
  const hide = message.loading('正在上传货物检验照片...', 0)
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await request.post('/attachment/upload?bizType=logistics-epod-receipt', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const docData = res?.data || res
    const url = docData.fileUrl || docData.url || `/attachment/file/${docData.attachNo || docData.attachmentId}`
    epodForm.value.cargoPhotos.push(url)
    message.success('货物检验照片上传成功')
  } catch (err) {
    // 拦截器处理
  } finally {
    hide()
    e.target.value = ''
  }
}

async function submitEpod() {
  if (!epodForm.value.signerName?.trim()) {
    message.warning('请填写现场签收人姓名')
    return
  }
  if (!epodForm.value.signerPhone?.trim()) {
    message.warning('请填写现场签收人电话')
    return
  }
  if (!epodForm.value.stampPhoto) {
    message.warning('请上传加盖公章的纸质签收单原件照片')
    return
  }
  epodSubmitting.value = true
  try {
    const payload = {
      waybillId: currentWaybill.value.waybill.id,
      orderNo: route.params.orderNo,
      packageNo: currentWaybill.value.waybill.packageNo,
      signerName: epodForm.value.signerName.trim(),
      signerPhone: epodForm.value.signerPhone.trim(),
      stampPhoto: epodForm.value.stampPhoto,
      cargoPhotos: epodForm.value.cargoPhotos,
      signRemark: epodForm.value.signRemark?.trim() || ''
    }
    await logisticsApi.uploadPod(payload)
    message.success('现场数字 e-POD 凭据已上传，等待商户/平台验签核验')
    epodModalOpen.value = false
    await loadLogistics()
    await load()
  } catch (e) {
    // 拦截器提示
  } finally {
    epodSubmitting.value = false
  }
}
// ===== 扫码支付 =====
async function drawPayQr(){
  await nextTick()
  if(payCanvasRef.value) await renderQrToCanvas(payCanvasRef.value,payQr.value,{width:200,errorCorrectionLevel:'Q'})
}
/** 生成扫码支付二维码：渠道有 codeUrl 用真实码并轮询到账；未配置则退化为可扫演示码 */
async function payWithScan(){
  if(payLoading.value)return
  payLoading.value=true
  try{
    const vo=await paymentApi.create(route.params.orderNo,'wechat')
    const codeUrl=vo?.codeUrl
    payQrDemo.value=!codeUrl
    payQr.value=buildPayQrPayload({codeUrl,orderNo:route.params.orderNo,amount:detail.value?.order?.totalAmount})
    await drawPayQr()
    if(!payQrDemo.value) startPayPolling()
  }catch(e){
    // 渠道未配置（本地 mock 模式）：退化为演示码，保证交互可验证
    payQrDemo.value=true
    payQr.value=buildPayQrPayload({orderNo:route.params.orderNo,amount:detail.value?.order?.totalAmount})
    await drawPayQr()
  }finally{payLoading.value=false}
}
function closePayQr(){ stopPayPolling(); payQr.value=''; payQrDemo.value=false }
function startPayPolling(){
  stopPayPolling()
  payPollTimer=setInterval(async()=>{
    try{
      const st=await paymentApi.query(route.params.orderNo)
      if(Number(st?.payStatus)===1){ stopPayPolling(); message.success('支付成功'); trackPay(); payOpen.value=false; await load() }
    }catch(e){ /* 静默重试 */ }
  },3000)
}
function stopPayPolling(){ if(payPollTimer){ clearInterval(payPollTimer); payPollTimer=0 } }

// 弹窗关闭即清理轮询与二维码态，避免残留
watch(payOpen,(v)=>{ if(!v) closePayQr() })
onBeforeUnmount(stopPayPolling)

onMounted(load)
</script>

<style scoped>
.detail-page{max-width:1100px;margin:0 auto}

/* ===== Apple 风卡片：玻璃质感 + 极细边 + 大圆角 + 柔和投影 ===== */
.status-card,
.card{
  border-radius:24px;
  border:1px solid rgba(0,0,0,.06);
  background:rgba(255,255,255,.82);
  backdrop-filter:blur(20px) saturate(180%);
  -webkit-backdrop-filter:blur(20px) saturate(180%);
  box-shadow:0 1px 1px rgba(0,0,0,.03),0 14px 36px -16px rgba(0,0,0,.14);
}
.card{margin-bottom:18px}
.card :deep(.ant-card-head){min-height:0;padding:20px 24px 0;border-bottom:0}
.card :deep(.ant-card-head-title){padding:0;font-size:17px;font-weight:600;letter-spacing:-.015em;color:#1D1D1F}
.card :deep(.ant-card-body){padding:14px 24px 22px}

/* ===== 状态卡：白色玻璃 + 状态色点 + 胶囊按钮 ===== */
.status-card{position:relative;margin-bottom:18px;overflow:hidden}
.status-card :deep(.ant-card-body){padding:26px 26px 24px}
.eyebrow{font-size:11px;letter-spacing:.14em;font-weight:600;color:#86868B}
.status-main{display:flex;justify-content:space-between;gap:24px;align-items:flex-start}
.status-copy{min-width:0}
.status-card h1{display:flex;align-items:center;gap:10px;margin:10px 0 6px;font-size:34px;font-weight:700;letter-spacing:-.022em;color:#1D1D1F}
.status-dot{width:10px;height:10px;border-radius:50%;flex:none}
.status-dot.orange{background:#FF9F0A;box-shadow:0 0 0 5px rgba(255,159,10,.12)}
.status-dot.blue{background:#0071E3;box-shadow:0 0 0 5px rgba(0,113,227,.1)}
.status-dot.green{background:#30D158;box-shadow:0 0 0 5px rgba(48,209,88,.12)}
.status-dot.gray{background:#8E8E93;box-shadow:0 0 0 5px rgba(142,142,147,.12)}
.status-card p{margin:0;color:#6E6E73;font-size:14px;line-height:1.5}
.status-actions{display:flex;gap:10px;flex-wrap:wrap;flex:none}
.status-actions :deep(.ant-btn){height:44px;padding:0 24px;border-radius:999px;font-size:15px;font-weight:600;box-shadow:none;transition:transform .18s ease,background .18s ease,border-color .18s ease}
.status-actions :deep(.ant-btn:active){transform:scale(.97)}
.status-actions :deep(.ant-btn-primary){background:#0071E3;border-color:#0071E3;box-shadow:0 8px 20px -10px rgba(0,113,227,.75)}
.status-actions :deep(.ant-btn-primary:hover){background:#0077ED;border-color:#0077ED}
.status-actions :deep(.ant-btn-default){background:#fff;border:1px solid #D2D2D7;color:#1D1D1F}
.status-actions :deep(.ant-btn-default:hover){border-color:#86868B;color:#1D1D1F}

/* ===== 苹果式进度：细线 + 圆点，横向四段 ===== */
.order-progress{display:flex;margin-top:30px;padding-top:22px;border-top:1px solid rgba(0,0,0,.06)}
.op-step{position:relative;flex:1;min-width:0;display:flex;flex-direction:column;gap:10px}
.op-step:not(:last-child)::after{content:'';position:absolute;left:34px;right:8px;top:12px;height:2px;border-radius:1px;background:#E8E8ED}
.op-step.done:not(:last-child)::after{background:#0071E3}
.op-dot{position:relative;z-index:1;width:26px;height:26px;border-radius:50%;display:grid;place-items:center;flex:none;font-size:12px;font-weight:600;background:#F5F5F7;border:1px solid #E5E5EA;color:#86868B}
.op-step.done .op-dot{background:#0071E3;border-color:#0071E3;color:#fff}
.op-step.current .op-dot{background:#fff;border-color:#FF9F0A;color:#C93400;box-shadow:0 0 0 4px rgba(255,159,10,.14)}
.op-body{min-width:0}
.op-body b{display:block;font-size:14px;font-weight:600;letter-spacing:-.01em;color:#1D1D1F}
.op-step.wait .op-body b{font-weight:500;color:#86868B}
.op-body small{display:block;margin-top:8px;font-size:12px;color:#6E6E73;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}

.grid{display:grid;grid-template-columns:1.5fr 1fr;gap:18px;align-items:start}

/* 商品清单 */
.item{display:flex;align-items:center;gap:14px;padding:15px 0;border-bottom:1px solid #F0F0F2}
.item:last-of-type{border-bottom:0}
.thumb{width:60px;height:60px;border-radius:14px;background:#F5F5F7;border:1px solid rgba(0,0,0,.05);display:grid;place-items:center;overflow:hidden;flex:none;color:#86868B;font-size:11px}
.thumb img{width:100%;height:100%;object-fit:cover}
.item-main{flex:1;min-width:0}
.item-main a{display:block;color:#1D1D1F;font-weight:600;font-size:14px;line-height:1.5}
.item-main a:hover{color:#0071E3}
.item-main small{display:block;color:#6E6E73;margin-top:5px;font-size:12.5px}
.item>strong{font-size:15px;color:#1D1D1F;white-space:nowrap}
.sum{display:flex;justify-content:space-between;align-items:baseline;font-size:13px;color:#6E6E73}
.sum strong{font-size:24px;font-weight:700;letter-spacing:-.02em;color:#B42318}
.point-sum{margin-bottom:6px}
.point-sum strong{font-size:16px}
.point-sum .deduct{color:#067647}
.card :deep(.ant-divider){border-color:#F0F0F2;margin:8px 0 12px}

/* 字段：Apple 深灰标签 + 近黑值 */
.card :deep(.ant-descriptions-item){padding-bottom:11px}
.card :deep(.ant-descriptions-item-label){width:84px;color:#6E6E73;font-size:13px}
.card :deep(.ant-descriptions-item-content){color:#1D1D1F;font-size:14px;line-height:1.7}

.pay-modal-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}
.pay-amount-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
  padding: 16px 20px;
  border-radius: 14px;
  color: #fff;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.12);
}
.banner-label {
  display: block;
  font-size: 11.5px;
  color: #94a3b8;
  letter-spacing: .02em;
}
.banner-amt {
  font-size: 18px;
  font-weight: 500;
  color: #38bdf8;
  margin-top: 2px;
}
.banner-amt b {
  font-size: 26px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  margin-left: 3px;
  letter-spacing: -.02em;
}
.banner-badge {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 9999px;
  background: rgba(56, 189, 248, 0.12);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.25);
}
.selector-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.selector-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.extra-panel {
  animation: fadeIn .25s ease-out;
}
.gateway-preview-panel {
  display: flex;
  align-items: center;
  padding: 12px 14px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  animation: fadeIn .25s ease-out;
}
.gateway-tip strong {
  display: block;
  font-size: 13px;
  color: #0f172a;
}
.gateway-tip p {
  margin: 3px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}
.pay-submit-bar {
  margin-top: 8px;
}
.pay-confirm-btn {
  width: 100%;
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14.5px;
  font-weight: 600;
  color: #ffffff;
  background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
  border: none;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(2, 132, 199, 0.3);
  transition: all .2s ease;
}
.pay-confirm-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #0369a1 0%, #075985 100%);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(2, 132, 199, 0.4);
}
.pay-confirm-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
  transform: none;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@media(max-width:900px){
  .grid{grid-template-columns:1fr}
}
@media(max-width:760px){
  .detail-page :deep(.ant-page-header){padding:8px 0 10px}
  .detail-page :deep(.ant-page-header-heading-title){font-size:18px;white-space:nowrap}
  .detail-page :deep(.ant-page-header-heading-sub-title){display:none}
  .status-card :deep(.ant-card-body){padding:20px 18px}
  .status-main{flex-direction:column;align-items:stretch;gap:18px}
  .status-card h1{font-size:26px}
  .status-actions{width:100%}
  .status-actions :deep(.ant-btn){flex:1;min-width:0;padding:0 16px}
  .order-progress{flex-direction:column;gap:16px;margin-top:22px;padding-top:18px}
  /* 桌面 .op-step 已改纵排（圆点上、文字下），移动端必须回到横排，否则竖线 left:12px 会从文字正中穿过 */
  .op-step{flex-direction:row;align-items:flex-start;gap:10px}
  .op-step:not(:last-child)::after{left:12px;top:34px;right:auto;bottom:-8px;width:2px;height:auto}
  .op-body small{white-space:normal}
  .card :deep(.ant-card-head){padding:18px 18px 0}
  .card :deep(.ant-card-body){padding:12px 18px 18px}
  .card :deep(.ant-card-head-title){font-size:16px}
  .card :deep(.ant-descriptions-item-label){width:76px;font-size:12.5px}
  .card :deep(.ant-descriptions-item-content){font-size:13.5px}
  .item{gap:12px;padding:13px 0}
  .thumb{width:54px;height:54px;border-radius:12px}
  .sum strong{font-size:21px}
}
.pay-qr{display:flex;flex-direction:column;align-items:center;gap:10px;padding:4px 0 2px}
.pay-qr__plate{position:relative;display:grid;place-items:center;padding:10px;border-radius:var(--ll-radius-md,10px);background:#fff;box-shadow:var(--ll-shadow-sm,0 2px 8px rgba(19,35,58,.07))}
.pay-qr__plate canvas{display:block;width:200px;height:200px}
.pay-qr__logo{position:absolute;display:grid;place-items:center;width:38px;height:38px;border-radius:9px;background:#fff;box-shadow:0 0 0 3px #fff,0 1px 6px rgba(19,35,58,.18)}
.pay-qr__logo img{width:26px;height:26px;object-fit:contain}
.pay-qr__hint{margin:0;font-size:11px;line-height:1.6;color:var(--ll-muted,#64748b);text-align:center}
.pay-qr .ant-btn{width:100%}

/* AI 智能物流履约与调度看板样式 */
.ai-logistics-card {
  grid-column: 1 / -1;
  background: linear-gradient(135deg, #ffffff 0%, #f8fafc 100%);
  border: 1px solid #e2e8f0;
}
.ai-eta-header {
  padding: 12px 16px;
  background: #f1f5f9;
  border-radius: 10px;
  margin-bottom: 16px;
}
.eta-headline {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.eta-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 600;
}
.eta-time {
  font-size: 16px;
  color: #0f172a;
  font-weight: 750;
}
.eta-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}
.eta-tag.tag-success {
  background: #dcfce7;
  color: #15803d;
}
.eta-tag.tag-normal {
  background: #e0f2fe;
  color: #0369a1;
}
.eta-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: #475569;
}
.eta-metrics code {
  background: #e2e8f0;
  padding: 1px 5px;
  border-radius: 4px;
}
.trace-timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-left: 8px;
  border-left: 2px solid #e2e8f0;
  margin-left: 6px;
}
.trace-node {
  position: relative;
  padding-left: 14px;
}
.node-dot {
  position: absolute;
  left: -13px;
  top: 5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
  border: 2px solid #fff;
}
.trace-node.is-latest .node-dot {
  background: #0284c7;
  box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.2);
}
.node-time {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 2px;
}
.node-desc {
  font-size: 12px;
  color: #1e293b;
  line-height: 1.5;
}
.trace-node.is-latest .node-desc {
  font-weight: 600;
  color: #0f172a;
}
.node-dot.abnormal {
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.25);
}

/* 多包裹切换与在途感知面板 */
.package-tab-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}
.tab-lead {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}
.pkg-tab-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid #cbd5e1;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.pkg-tab-item:hover {
  border-color: #38bdf8;
}
.pkg-tab-item.active {
  background: #0284c7;
  color: #fff;
  border-color: #0284c7;
  box-shadow: 0 2px 6px rgba(2, 132, 199, 0.3);
}
.pkg-tab-item.is-reverse {
  border-color: #f59e0b;
}
.pkg-tab-item.is-reverse.active {
  background: #d97706;
  border-color: #b45309;
  box-shadow: 0 2px 6px rgba(217, 119, 6, 0.35);
}
.tab-rma-badge {
  display: inline-block;
  padding: 1px 5px;
  border-radius: 3px;
  background: #fef3c7;
  color: #b45309;
  font-size: 10px;
  font-weight: 600;
}
.pkg-tab-item.active .tab-rma-badge {
  background: rgba(255, 255, 255, 0.25);
  color: #fff;
}
.pkg-tab-item.active .tab-status {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
}
.tab-pkg-no {
  font-weight: 600;
}
.tab-provider {
  font-size: 11.5px;
  opacity: 0.9;
}
.tab-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #64748b;
}
.waybill-meta-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  padding: 10px 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin: 12px 0 16px;
  font-size: 12.5px;
  color: #334155;
}
.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.meta-label {
  color: #64748b;
}
.meta-item code {
  background: #e2e8f0;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
}
.trace-timeline-wrap {
  margin-top: 14px;
}
.timeline-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 12px;
}
.trace-empty {
  font-size: 12px;
  color: #94a3b8;
  padding: 12px;
  background: #f8fafc;
  border-radius: 6px;
}
.logistics-pending-panel {
  text-align: center;
  padding: 36px 20px;
  background: #f8fafc;
  border-radius: 10px;
  border: 1px dashed #cbd5e1;
}
.pending-icon {
  font-size: 32px;
  margin-bottom: 8px;
}
.pending-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 6px;
}
.pending-desc {
  font-size: 12.5px;
  color: #64748b;
  max-width: 480px;
  margin: 0 auto;
  line-height: 1.6;
}

/* ===== Phase 2: 随货同行单证 (CoA) 展现卡 ===== */
.coa-showcase-panel {
  margin: 14px 0;
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.head-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  display: inline-flex;
  align-items: center;
}
.head-tip {
  font-size: 11px;
  color: #64748b;
}
.coa-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}
.coa-view-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  transition: all 0.2s ease;
}
.coa-view-card:hover {
  border-color: #0284c7;
  box-shadow: 0 2px 8px rgba(2, 132, 199, 0.08);
}
.coa-view-left {
  display: flex;
  align-items: center;
  min-width: 0;
  flex: 1;
}
.coa-view-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.doc-main-name {
  font-size: 12px;
  font-weight: 600;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 170px;
}
.doc-sub-tags {
  display: flex;
  gap: 4px;
  margin-top: 2px;
}
.doc-badge {
  font-size: 10px;
  background: #e0f2fe;
  color: #0369a1;
  padding: 1px 5px;
  border-radius: 4px;
  font-weight: 600;
}
.batch-badge {
  font-size: 10px;
  background: #f1f5f9;
  color: #64748b;
  padding: 1px 5px;
  border-radius: 4px;
}
.preview-doc-link {
  font-size: 11.5px;
  color: #0284c7;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  padding: 4px 8px;
  border-radius: 6px;
  background: #f0f9ff;
}
.preview-doc-link:hover {
  background: #e0f2fe;
  color: #0369a1;
}

/* ===== Phase 2: 现场数字 e-POD 签收与验签面板 ===== */
.epod-status-panel {
  margin: 14px 0;
  padding: 14px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}
.epod-badge-wrap {
  display: inline-flex;
}
.badge-approved {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
}
.badge-pending {
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
}
.badge-rejected {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
}
.badge-none {
  background: #f8fafc;
  color: #94a3b8;
  border: 1px solid #e2e8f0;
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 999px;
}
.epod-detail-body {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #e2e8f0;
  font-size: 12.5px;
}
.epod-signer-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: #475569;
  margin-bottom: 8px;
}
.epod-photos-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 8px;
}
.photo-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.photo-label {
  font-size: 11.5px;
  color: #64748b;
  font-weight: 500;
}
.stamp-thumb {
  width: 90px;
  height: 90px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  transition: transform 0.2s;
}
.stamp-thumb:hover {
  transform: scale(1.04);
}
.cargo-thumbs-box {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cargo-thumb {
  width: 65px;
  height: 65px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
.epod-remark {
  margin-top: 8px;
  padding: 6px 10px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: #475569;
}
.epod-remark span {
  color: #64748b;
  font-weight: 500;
}
.epod-action-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.epod-guide-tip {
  font-size: 11.5px;
  color: #94a3b8;
}

/* ===== e-POD 模态框上传区域 ===== */
.upload-stamp-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1.5px dashed #cbd5e1;
  border-radius: 8px;
  padding: 20px;
  background: #f8fafc;
  cursor: pointer;
  transition: all 0.2s;
}
.upload-stamp-btn:hover {
  border-color: #e11d48;
  background: #fff1f2;
}
.upload-btn-text {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}
.preview-stamp-box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.preview-stamp-img {
  width: 80px;
  height: 80px;
  object-fit: cover;
  border-radius: 6px;
}
.epod-cargo-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.cargo-img-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
}
.cargo-img-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cargo-del-btn {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(0,0,0,0.65);
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cargo-upload-btn {
  width: 72px;
  height: 72px;
  border: 1.5px dashed #cbd5e1;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: #f8fafc;
  transition: border-color 0.2s;
}
.cargo-upload-btn:hover {
  border-color: #0284c7;
  background: #f0f9ff;
}
.cargo-upload-text {
  font-size: 10px;
  color: #64748b;
  margin-top: 2px;
}

@media (max-width: 640px) {
  .coa-card-grid {
    grid-template-columns: 1fr;
  }
  .epod-action-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .epod-action-bar :deep(.ant-btn) {
    height: 44px;
    font-size: 14px;
  }
}

/* 移动端专属装载清单与单证抽屉样式 */
.mobile-cargo-bar {
  display: none;
  margin: 10px 0 14px;
}
.mobile-cargo-btn {
  width: 100%;
  min-height: 44px;
  background: #f0f9ff;
  border: 1px dashed #7dd3fc;
  border-radius: 10px;
  color: #0369a1;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.mobile-cargo-btn:active {
  background: #e0f2fe;
}
@media (max-width: 768px) {
  .mobile-cargo-bar {
    display: block;
  }
}
.mobile-cargo-drawer-body {
  padding: 6px 2px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.drawer-waybill-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
}
.dwh-carrier {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.dwh-carrier code {
  color: #0284c7;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.dwh-status {
  font-size: 12px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  padding: 2px 8px;
  border-radius: 999px;
}
.drawer-sec-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}
.drawer-items-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.drawer-item-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.02);
}
.drawer-item-thumb {
  width: 44px;
  height: 44px;
  border-radius: 6px;
  object-fit: cover;
  border: 1px solid #e2e8f0;
  flex-shrink: 0;
}
.drawer-item-meta {
  flex: 1;
  min-width: 0;
}
.drawer-item-name {
  display: block;
  font-size: 13px;
  color: #1e293b;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.drawer-item-sub {
  display: flex;
  gap: 8px;
  margin-top: 3px;
  font-size: 11.5px;
  color: #64748b;
}
.drawer-sec-block {
  margin-top: 6px;
}
.drawer-coa-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.drawer-coa-pill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: #f0f9ff;
  border-radius: 8px;
  border: 1px solid #e0f2fe;
}
.doc-text {
  font-size: 12px;
  color: #0369a1;
  font-weight: 500;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-link {
  font-size: 11.5px;
  color: #0284c7;
  font-weight: 600;
  margin-left: 8px;
}

/* 退款凭证说明文案（颜色走令牌，零硬编码） */
.rma-evidence-hints { margin-top: 6px; display: flex; flex-direction: column; gap: 2px; }
.rma-evidence-hints p { margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--ll-text-secondary); }
</style>
