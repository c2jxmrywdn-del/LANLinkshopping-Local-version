<template>
  <div class="admin-tech">
    <a-card :bordered="false" class="card">
      <div class="page-head">
        <div>
          <div class="eyebrow">TECH SERVICE · OPERATIONS</div>
          <h2>技术服务运营台</h2>
          <p class="lead">专家定向邀请与资质审核、需求受理与匹配、陪跑项目里程碑推进、成果评价与托管受理。</p>
        </div>
        <a-button :loading="loading" @click="loadAll">刷新数据</a-button>
      </div>

      <div class="stat-grid">
        <div v-for="s in stats" :key="s.key" class="stat" :class="s.tone">
          <span>{{ s.label }}</span>
          <b>{{ s.value }}</b>
        </div>
      </div>

      <a-tabs v-model:activeKey="activeTab" size="small" @change="onTabChange">
        <a-tab-pane key="experts" tab="专家资源池">
          <div class="toolbar">
            <a-radio-group v-model:value="expertStatus" button-style="solid" size="small" @change="loadExperts(1)">
              <a-radio-button :value="''">全部</a-radio-button>
              <a-radio-button :value="0">待审核</a-radio-button>
              <a-radio-button :value="1">已上架</a-radio-button>
              <a-radio-button :value="-1">已驳回</a-radio-button>
            </a-radio-group>
            <a-input-search v-model:value="expertKeyword" placeholder="搜索姓名 / 单位 / 领域" style="width: 240px" @search="loadExperts(1)" />
            <a-button type="primary" @click="openInvite">定向邀请入驻</a-button>
          </div>
          <a-table :data-source="experts" :columns="expertCols" row-key="expertId" size="middle" :loading="loading" :pagination="expertPageProps" @change="onExpertPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'type'">
                <a-tag :color="record.expertType === 'BROKER' ? 'purple' : 'blue'">
                  {{ record.expertType === 'BROKER' ? '技术经纪人' : '技术专家' }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="expertStatusColor(record.status)">
                  {{ expertStatusLabel(record.status) }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'domains'">
                <div class="cell-main">{{ record.domains || '—' }}</div>
                <div class="cell-sub">{{ record.industries || '—' }}</div>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-space>
                  <a-button v-if="record.status === 0" size="small" type="primary" ghost @click="reviewExpert(record, 'approve')">通过</a-button>
                  <a-button v-if="record.status === 0" size="small" danger ghost @click="reviewExpert(record, 'reject')">驳回</a-button>
                  <a-button size="small" @click="showExpert(record)">详情</a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="demands" tab="需求受理">
          <div class="toolbar">
            <a-radio-group v-model:value="demandStatus" button-style="solid" size="small" @change="loadDemands(1)">
              <a-radio-button :value="''">全部</a-radio-button>
              <a-radio-button value="MATCHING">匹配中</a-radio-button>
              <a-radio-button value="IN_PROGRESS">对接中</a-radio-button>
              <a-radio-button value="CLOSED">已关闭</a-radio-button>
            </a-radio-group>
            <a-input-search v-model:value="demandKeyword" placeholder="搜索需求标题 / 行业" style="width: 240px" @search="loadDemands(1)" />
          </div>
          <a-table :data-source="demands" :columns="demandCols" row-key="demandId" size="middle" :loading="loading" :pagination="demandPageProps" @change="onDemandPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'budget'">
                <span class="tech-num">{{ budgetText(record) }}</span>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="demandStatusColor(record.status)">{{ demandStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-select :value="record.status" size="small" style="width: 110px" :options="demandStatusOptions" @change="(v) => changeDemandStatus(record, v)" />
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="projects" tab="陪跑项目">
          <div class="toolbar">
            <a-radio-group v-model:value="projectStatus" button-style="solid" size="small" @change="loadProjects(1)">
              <a-radio-button :value="''">全部</a-radio-button>
              <a-radio-button value="ACTIVE">进行中</a-radio-button>
              <a-radio-button value="COMPLETED">已完成</a-radio-button>
            </a-radio-group>
            <a-input-search v-model:value="projectKeyword" placeholder="搜索项目编号 / 标题" style="width: 240px" @search="loadProjects(1)" />
          </div>
          <a-table :data-source="projects" :columns="projectCols" row-key="projectId" size="middle" :loading="loading" :pagination="projectPageProps" @change="onProjectPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'progress'">
                <div class="progress-cell">
                  <a-progress :percent="Number(record.progress || 0)" :show-info="false" size="small" />
                  <span class="tech-num">{{ record.progress || 0 }}%</span>
                </div>
                <div class="cell-sub">{{ record.nextAction || '—' }}</div>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="record.status === 'COMPLETED' ? 'green' : 'blue'">
                  {{ record.status === 'COMPLETED' ? '已完成' : '进行中' }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-space>
                  <a-button size="small" type="primary" ghost :disabled="record.status === 'COMPLETED'" @click="advanceProject(record)">推进里程碑</a-button>
                  <a-button size="small" @click="openAssign(record)">指派团队</a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="appraisals" tab="成果评价">
          <a-table :data-source="appraisals" :columns="appraisalCols" row-key="appraisalId" size="middle" :loading="loading" :pagination="appraisalPageProps" @change="onAppraisalPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="appraisalStatusColor(record.status)">{{ appraisalStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-button size="small" type="primary" ghost @click="openAppraisal(record)">更新进度</a-button>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="hostings" tab="托管受理">
          <a-table :data-source="hostings" :columns="hostingCols" row-key="hostingId" size="middle" :loading="loading" :pagination="hostingPageProps" @change="onHostingPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="hostingStatusColor(record.status)">{{ hostingStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-select :value="record.status" size="small" style="width: 120px" :options="hostingStatusOptions" @change="(v) => changeHostingStatus(record, v)" />
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="contracts" tab="合同台账">
          <div class="toolbar">
            <a-input-search v-model:value="contractKeyword" placeholder="搜索合同号 / 标题 / 甲方" style="width: 260px" @search="loadContracts(1)" />
            <a-select v-model:value="contractStatus" allow-clear placeholder="合同状态" style="width: 140px" :options="contractStatusOptions" @change="loadContracts(1)" />
          </div>
          <a-table :data-source="contracts" :columns="contractCols" row-key="contractNo" size="middle" :loading="loading" :pagination="contractPageProps" @change="onContractPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="contractStatusColor(record.status)">{{ contractStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'amount'">
                <span class="tech-num">¥{{ Number(record.amount || 0).toFixed(2) }}</span>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="serviceFees" tab="服务费">
          <div class="toolbar">
            <a-input-search v-model:value="feeKeyword" placeholder="搜索费用号 / 合同号" style="width: 260px" @search="loadServiceFees(1)" />
            <a-select v-model:value="feeStatus" allow-clear placeholder="费用状态" style="width: 140px" :options="feeStatusOptions" @change="loadServiceFees(1)" />
          </div>
          <a-table :data-source="serviceFees" :columns="feeCols" row-key="feeNo" size="middle" :loading="loading" :pagination="feePageProps" @change="onFeePage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="record.status === 'PAID' ? 'green' : 'gold'">{{ record.status === 'PAID' ? '已到账' : '待支付' }}</a-tag>
              </template>
              <template v-else-if="column.key === 'amount'"><span class="tech-num">¥{{ Number(record.amount || 0).toFixed(2) }}</span></template>
              <template v-else-if="column.key === 'op'">
                <a-button v-if="record.status === 'PENDING'" size="small" type="primary" ghost @click="openMarkPaid(record)">确认到账</a-button>
                <span v-else class="cell-sub">{{ record.payChannel || '—' }}</span>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="escrow" tab="资金监管">
          <div class="toolbar">
            <a-button type="primary" @click="openEscrow">登记监管流水</a-button>
            <span class="cell-sub">平台只登记外部存管/分账/交易所凭证，不划转交易本金。</span>
          </div>
          <a-table :data-source="escrowRows" :columns="escrowCols" row-key="escrowNo" size="middle" :loading="loading" :pagination="escrowPageProps" @change="onEscrowPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'direction'">
                <a-tag :color="record.direction === 'IN' ? 'blue' : 'green'">{{ record.direction === 'IN' ? '买方入金' : '卖方结算' }}</a-tag>
              </template>
              <template v-else-if="column.key === 'amount'"><span class="tech-num">¥{{ Number(record.amount || 0).toFixed(2) }}</span></template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="orgTasks" tab="机构任务">
          <div class="toolbar">
            <a-select v-model:value="orgTaskStatus" allow-clear placeholder="任务状态" style="width: 150px" :options="orgTaskStatusOptions" @change="loadOrgTasks(1)" />
            <a-input-search v-model:value="orgTaskKeyword" placeholder="搜索任务号 / 标题" style="width: 240px" @search="loadOrgTasks(1)" />
            <a-button type="primary" @click="openOrgTask">派发机构任务</a-button>
          </div>
          <a-table :data-source="orgTasks" :columns="orgTaskCols" row-key="taskNo" size="middle" :loading="loading" :pagination="orgTaskPageProps" @change="onOrgTaskPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'taskType'">
                <a-tag>{{ taskTypeLabel(record.taskType) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="orgTaskStatusColor(record.status)">{{ orgTaskStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-button v-if="record.status === 'SUBMITTED'" size="small" type="primary" ghost @click="openOrgReview(record)">验收</a-button>
                <span v-else class="cell-sub">{{ record.reviewNote || '—' }}</span>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="reviews" tab="履约评价">
          <div class="toolbar">
            <a-select v-model:value="reviewTargetType" allow-clear placeholder="评价对象" style="width: 150px" :options="reviewTargetOptions" @change="loadReviews(1)" />
            <span class="cell-sub">评分用于派单权重与机构分级，不对外发布排名。</span>
          </div>
          <a-table :data-source="reviews" :columns="reviewCols" row-key="reviewNo" size="middle" :loading="loading" :pagination="reviewPageProps" @change="onReviewPage">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'target'">
                <div class="cell-main">{{ record.targetName || '—' }}</div>
                <div class="cell-sub">{{ reviewTargetLabel(record.targetType) }}</div>
              </template>
              <template v-else-if="column.key === 'scores'">
                <span class="cell-sub">{{ record.scoreResponse }}/{{ record.scoreProfessional }}/{{ record.scoreDelivery }}/{{ record.scoreCommunication }}/{{ record.scoreResult }}</span>
              </template>
              <template v-else-if="column.key === 'overall'">
                <span class="tech-num">{{ Number(record.overall || 0).toFixed(2) }}</span>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="pilotResources" tab="中试资源">
          <div class="toolbar">
            <a-button type="primary" @click="openResource()">新增资源</a-button>
            <span class="cell-sub">资源须由合作机构提交资质与设备清单，运营核验后上架。</span>
          </div>
          <a-table :data-source="pilotResources" :columns="pilotResourceCols" row-key="resourceId" size="middle" :loading="loading" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'type'">
                <a-tag>{{ resourceTypeLabel(record.resourceType) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'price'">
                <span class="tech-num">{{ resourcePriceText(record) }}</span>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="record.status === 1 ? 'green' : 'default'">{{ record.status === 1 ? '已上架' : '已下架' }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-space>
                  <a-button size="small" @click="openResource(record)">编辑</a-button>
                  <a-button size="small" :danger="record.status === 1" ghost @click="toggleResource(record)">
                    {{ record.status === 1 ? '下架' : '上架' }}
                  </a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="pilotBookings" tab="中试预约">
          <div class="toolbar">
            <a-select v-model:value="bookingStatus" allow-clear placeholder="预约状态" style="width: 150px" :options="bookingStatusOptions" @change="loadPilotBookings" />
            <a-button @click="loadPilotBookings">刷新</a-button>
          </div>
          <a-table :data-source="pilotBookings" :columns="pilotBookingCols" row-key="bookingNo" size="middle" :loading="loading" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="bookingStatusColor(record.status)">{{ bookingStatusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-select :value="record.status" size="small" style="width: 120px" :options="bookingStatusOptions" @change="(v) => changeBookingStatus(record, v)" />
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="compliance" tab="合规边界">
          <a-alert type="warning" show-icon :message="complianceNotice" style="margin-bottom: 14px" />
          <a-table :data-source="complianceItems" :columns="complianceCols" row-key="module" size="small" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'allowed'"><span class="txt-allow">{{ record.allowed }}</span></template>
              <template v-else-if="column.key === 'forbidden'"><span class="txt-deny">{{ record.forbidden }}</span></template>
              <template v-else-if="column.key === 'partner'">
                <span>{{ record.partner }}</span>
                <div class="cell-sub">{{ record.qualification }}</div>
              </template>
            </template>
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </a-card>

    <!-- 确认服务费到账 -->
    <a-modal v-model:open="markPaidOpen" title="确认服务费到账" :confirm-loading="saving" ok-text="确认到账" cancel-text="取消" @ok="submitMarkPaid">
      <a-form layout="vertical">
        <a-form-item label="费用记录">
          <a-input :value="markPaidTarget ? `${markPaidTarget.feeNo} · ¥${Number(markPaidTarget.amount || 0).toFixed(2)}` : ''" disabled />
        </a-form-item>
        <a-form-item label="收款渠道">
          <a-select v-model:value="markPaidForm.payChannel" :options="[{ label: '银行存管', value: 'bank' }, { label: '持牌分账', value: 'payment' }, { label: '交易所结算', value: 'exchange' }]" />
        </a-form-item>
        <a-form-item label="外部流水号">
          <a-input v-model:value="markPaidForm.externalRef" placeholder="存管/分账/结算机构流水号" />
        </a-form-item>
        <a-form-item label="发票号">
          <a-input v-model:value="markPaidForm.invoiceNo" placeholder="可选" />
        </a-form-item>
        <a-alert type="info" show-icon message="仅登记服务费到账；交易本金仍由外部机构存管与结算，平台不划转。" />
      </a-form>
    </a-modal>

    <!-- 登记资金监管流水 -->
    <a-modal v-model:open="escrowOpen" title="登记资金监管流水" :confirm-loading="saving" ok-text="登记" cancel-text="取消" @ok="submitEscrow">
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="关联交易 ID"><a-input-number v-model:value="escrowForm.dealId" :min="1" style="width: 100%" /></a-form-item>
          <a-form-item label="关联合同 ID"><a-input-number v-model:value="escrowForm.contractId" :min="1" style="width: 100%" /></a-form-item>
          <a-form-item label="方向">
            <a-select v-model:value="escrowForm.direction" :options="[{ label: '买方入金', value: 'IN' }, { label: '卖方结算', value: 'OUT' }]" />
          </a-form-item>
          <a-form-item label="金额（元）"><a-input-number v-model:value="escrowForm.amount" :min="1" :step="1000" style="width: 100%" /></a-form-item>
          <a-form-item label="机构">
            <a-select v-model:value="escrowForm.provider" :options="[{ label: '银行存管', value: 'bank' }, { label: '持牌分账', value: 'payment' }, { label: '交易所结算', value: 'exchange' }]" />
          </a-form-item>
          <a-form-item label="外部凭证号"><a-input v-model:value="escrowForm.externalRef" /></a-form-item>
        </div>
        <a-alert type="warning" show-icon message="平台不归集交易本金；该记录仅登记外部机构凭证，用于对账与争议处理。" />
      </a-form>
    </a-modal>

    <!-- 定向邀请 -->
    <a-modal v-model:open="inviteOpen" title="定向邀请专家入驻" :confirm-loading="saving" ok-text="确认邀请" cancel-text="取消" width="680px" @ok="submitInvite">
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="类型" required>
            <a-select v-model:value="inviteForm.expertType" :options="[{ label: '技术专家', value: 'EXPERT' }, { label: '技术经纪人', value: 'BROKER' }]" />
          </a-form-item>
          <a-form-item label="姓名" required><a-input v-model:value="inviteForm.name" :maxlength="40" /></a-form-item>
          <a-form-item label="职称 / 职位"><a-input v-model:value="inviteForm.title" :maxlength="60" /></a-form-item>
          <a-form-item label="所属单位" required><a-input v-model:value="inviteForm.organization" :maxlength="100" /></a-form-item>
          <a-form-item label="行业标签"><a-input v-model:value="inviteForm.industries" placeholder="智能制造,新材料" /></a-form-item>
          <a-form-item label="擅长领域"><a-input v-model:value="inviteForm.domains" placeholder="机器视觉,技术尽调" /></a-form-item>
          <a-form-item class="full" label="个人简介"><a-textarea v-model:value="inviteForm.bio" :rows="3" :maxlength="1000" show-count /></a-form-item>
          <a-form-item class="full" label="资质与成果"><a-textarea v-model:value="inviteForm.credentials" :rows="2" :maxlength="500" show-count /></a-form-item>
          <a-form-item label="咨询费（元）"><a-input-number v-model:value="inviteForm.consultFee" :min="0" :step="100" style="width: 100%" /></a-form-item>
        </div>
        <a-alert type="info" show-icon message="邀请入驻将直接上架并标记为已核验；主动申请的记录需在此页审核后上架。" />
      </a-form>
    </a-modal>

    <!-- 指派团队 -->
    <a-modal v-model:open="assignOpen" title="指派陪跑团队" :confirm-loading="saving" ok-text="确认指派" cancel-text="取消" @ok="submitAssign">
      <a-form layout="vertical">
        <a-form-item label="技术经纪人（全程陪跑）">
          <a-select v-model:value="assignForm.brokerId" allow-clear :options="brokerOptions" />
        </a-form-item>
        <a-form-item label="责任专家">
          <a-select v-model:value="assignForm.ownerExpertId" allow-clear :options="expertOptions" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 评价进度 -->
    <a-modal v-model:open="appraisalOpen" title="更新成果评价进度" :confirm-loading="saving" ok-text="保存" cancel-text="取消" @ok="submitAppraisal">
      <a-form layout="vertical">
        <a-form-item label="状态" required>
          <a-select v-model:value="appraisalForm.status" :options="appraisalStatusOptions" />
        </a-form-item>
        <a-form-item label="结论等级（由合作机构回填）">
          <a-input v-model:value="appraisalForm.conclusionLevel" placeholder="例如：国内先进（合作机构出具）" />
        </a-form-item>
        <a-form-item label="报告编号">
          <a-input v-model:value="appraisalForm.reportNo" placeholder="合作机构报告编号" />
        </a-form-item>
        <a-alert type="warning" show-icon message="平台只跟踪受理进度，评价结论须由合作评价机构出具，后台不得代填未经机构确认的结论。" />
      </a-form>
    </a-modal>

    <!-- 驳回原因 -->
    <a-modal v-model:open="rejectOpen" title="驳回入驻申请" :confirm-loading="saving" ok-text="确认驳回" cancel-text="取消" @ok="submitReject">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将写入审核留痕）" required>
          <a-textarea
            v-model:value="rejectForm.note"
            :rows="4"
            :maxlength="255"
            show-count
            placeholder="例如：缺少专利代理机构执业许可证扫描件，请补正后重新提交"
          />
        </a-form-item>
        <a-alert type="info" show-icon message="原因只进审核留痕，可追溯到人；当前专家独立登录端未开放，申请人暂通过运营线下反馈获取驳回理由。" />
      </a-form>
    </a-modal>

    <!-- 派发机构任务 -->
    <a-modal v-model:open="orgTaskOpen" title="派发机构任务" :confirm-loading="saving" ok-text="派发" cancel-text="取消" width="680px" @ok="submitOrgTask">
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="承接机构" required>
            <a-select v-model:value="orgTaskForm.orgId" :options="orgOptions" placeholder="选择合作机构" />
          </a-form-item>
          <a-form-item label="任务类型">
            <a-select v-model:value="orgTaskForm.taskType" :options="taskTypeOptions" />
          </a-form-item>
          <a-form-item label="关联陪跑项目 ID">
            <a-input-number v-model:value="orgTaskForm.projectId" :min="1" style="width: 100%" placeholder="可选" />
          </a-form-item>
          <a-form-item label="承接人用户 ID">
            <a-input-number v-model:value="orgTaskForm.assigneeUserId" :min="1" style="width: 100%" placeholder="可选，机构账号 ID" />
          </a-form-item>
          <a-form-item label="截止日期">
            <a-input v-model:value="orgTaskForm.dueDate" placeholder="YYYY-MM-DD，可留空" />
          </a-form-item>
          <a-form-item class="full" label="任务标题" required>
            <a-input v-model:value="orgTaskForm.title" :maxlength="200" show-count />
          </a-form-item>
          <a-form-item class="full" label="任务范围">
            <a-textarea v-model:value="orgTaskForm.scope" :rows="3" :maxlength="1000" show-count placeholder="交付边界、材料要求与验收口径" />
          </a-form-item>
        </div>
        <a-alert type="info" show-icon message="机构只提交过程材料与结论建议；评价结论须由具备资质的机构出具。" />
      </a-form>
    </a-modal>

    <!-- 机构任务验收 -->
    <a-modal v-model:open="orgReviewOpen" title="机构任务验收" :confirm-loading="saving" ok-text="提交验收" cancel-text="取消" @ok="submitOrgReview">
      <a-form layout="vertical">
        <a-form-item label="任务">
          <a-input :value="orgReviewTarget ? `${orgReviewTarget.taskNo} · ${orgReviewTarget.title}` : ''" disabled />
        </a-form-item>
        <a-form-item label="交付说明">
          <a-textarea :value="orgReviewTarget?.deliverableText || ''" :rows="3" disabled />
        </a-form-item>
        <a-form-item label="验收结论" required>
          <a-select v-model:value="orgReviewForm.decision" :options="[{ label: '通过', value: 'ACCEPTED' }, { label: '驳回', value: 'REJECTED' }]" />
        </a-form-item>
        <a-form-item label="验收意见">
          <a-textarea v-model:value="orgReviewForm.note" :rows="3" :maxlength="500" show-count placeholder="驳回时须填写原因" />
        </a-form-item>
        <a-alert type="warning" show-icon message="平台验收只确认交付完整性与过程留痕，不代表对技术结论的行政认可。" />
      </a-form>
    </a-modal>

    <!-- 中试资源上架 -->
    <a-modal v-model:open="resourceOpen" :title="resourceForm.resourceId ? '编辑中试资源' : '新增中试资源'" :confirm-loading="saving" ok-text="保存" cancel-text="取消" width="680px" @ok="submitResource">
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="资源名称" required><a-input v-model:value="resourceForm.name" :maxlength="120" /></a-form-item>
          <a-form-item label="所属机构" required><a-input v-model:value="resourceForm.orgName" :maxlength="120" /></a-form-item>
          <a-form-item label="区域"><a-input v-model:value="resourceForm.region" placeholder="上海 / 杭州 / 苏州" /></a-form-item>
          <a-form-item label="资源类型">
            <a-select v-model:value="resourceForm.resourceType" :options="resourceTypeOptions" />
          </a-form-item>
          <a-form-item label="计价方式">
            <a-select v-model:value="resourceForm.priceMode" :options="priceModeOptions" />
          </a-form-item>
          <a-form-item label="参考价格（元）"><a-input-number v-model:value="resourceForm.price" :min="0" :step="1000" style="width: 100%" /></a-form-item>
          <a-form-item label="联系人"><a-input v-model:value="resourceForm.contact" :maxlength="80" /></a-form-item>
          <a-form-item label="排序"><a-input-number v-model:value="resourceForm.sort" :min="0" style="width: 100%" /></a-form-item>
          <a-form-item class="full" label="能力范围">
            <a-textarea v-model:value="resourceForm.capabilities" :rows="2" :maxlength="500" show-count />
          </a-form-item>
          <a-form-item class="full" label="设备清单">
            <a-textarea v-model:value="resourceForm.equipment" :rows="2" :maxlength="500" show-count />
          </a-form-item>
        </div>
      </a-form>
    </a-modal>

    <!-- 专家详情 -->
    <a-drawer v-model:open="expertOpen" title="专家档案" :width="drawerWidth" placement="right">
      <div v-if="currentExpert" class="expert-detail">
        <h3>{{ currentExpert.name }} <a-tag :color="expertStatusColor(currentExpert.status)">{{ expertStatusLabel(currentExpert.status) }}</a-tag></h3>
        <p class="sub">{{ currentExpert.title }} · {{ currentExpert.organization }}</p>
        <a-descriptions :column="1" size="small" bordered class="mt">
          <a-descriptions-item label="类型">{{ currentExpert.expertType === 'BROKER' ? '技术经纪人' : '技术专家' }}</a-descriptions-item>
          <a-descriptions-item label="行业">{{ currentExpert.industries || '—' }}</a-descriptions-item>
          <a-descriptions-item label="领域">{{ currentExpert.domains || '—' }}</a-descriptions-item>
          <a-descriptions-item label="邀请状态">{{ currentExpert.inviteStatus || '—' }}</a-descriptions-item>
          <a-descriptions-item label="咨询费">¥{{ currentExpert.consultFee || 0 }}</a-descriptions-item>
          <a-descriptions-item label="简介">{{ currentExpert.bio || '—' }}</a-descriptions-item>
          <a-descriptions-item label="资质">{{ currentExpert.credentials || '—' }}</a-descriptions-item>
        </a-descriptions>
        <AttachmentPanel
          class="mt"
          biz-type="tech-expert-profile"
          :biz-id="currentExpert.expertId"
          title="专家资质材料"
          :bind-on-upload="true"
        />
      </div>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { techAdminApi, techApi } from '../../api'
import AttachmentPanel from '../../components/AttachmentPanel.vue'

const loading = ref(false)
const saving = ref(false)
const activeTab = ref('experts')
const counts = ref({})

const experts = ref([])
const expertPage = ref({ page: 1, size: 10, total: 0 })
const expertStatus = ref('')
const expertKeyword = ref('')

const demands = ref([])
const demandPage = ref({ page: 1, size: 10, total: 0 })
const demandStatus = ref('')
const demandKeyword = ref('')

const projects = ref([])
const projectPage = ref({ page: 1, size: 10, total: 0 })
const projectStatus = ref('')
const projectKeyword = ref('')

const appraisals = ref([])
const appraisalPage = ref({ page: 1, size: 10, total: 0 })

const hostings = ref([])
const hostingPage = ref({ page: 1, size: 10, total: 0 })

const contracts = ref([])
const contractPage = ref({ page: 1, size: 10, total: 0 })
const contractStatus = ref()
const contractKeyword = ref('')
const serviceFees = ref([])
const serviceFeePage = ref({ page: 1, size: 10, total: 0 })
const feeStatus = ref()
const feeKeyword = ref('')
const escrowRows = ref([])
const escrowPage = ref({ page: 1, size: 10, total: 0 })

const orgTasks = ref([])
const orgTaskPage = ref({ page: 1, size: 10, total: 0 })
const orgTaskStatus = ref()
const orgTaskKeyword = ref('')
const reviews = ref([])
const reviewPage = ref({ page: 1, size: 10, total: 0 })
const reviewTargetType = ref()
const pilotResources = ref([])
const pilotBookings = ref([])
const bookingStatus = ref()
const orgs = ref([])

const orgTaskOpen = ref(false)
const orgTaskForm = reactive({ orgId: undefined, projectId: undefined, assigneeUserId: undefined, taskType: 'EVALUATION', title: '', scope: '', dueDate: '' })
const orgReviewOpen = ref(false)
const orgReviewTarget = ref(null)
const orgReviewForm = reactive({ decision: 'ACCEPTED', note: '' })
const resourceOpen = ref(false)
const resourceForm = reactive({
  resourceId: undefined, name: '', orgName: '', region: '', resourceType: 'PILOT',
  capabilities: '', equipment: '', priceMode: 'QUOTE', price: undefined,
  contact: '', status: 1, sort: 0
})

const markPaidOpen = ref(false)
const markPaidTarget = ref(null)
const markPaidForm = reactive({ payChannel: 'bank', externalRef: '', invoiceNo: '' })
const escrowOpen = ref(false)
const escrowForm = reactive({ dealId: undefined, contractId: undefined, amount: 0, direction: 'IN', provider: 'bank', externalRef: '' })

const complianceItems = ref([])
const complianceNotice = ref('')

const inviteOpen = ref(false)
const inviteForm = reactive({ expertType: 'EXPERT', name: '', title: '', organization: '', industries: '', domains: '', bio: '', credentials: '', consultFee: 0 })
const assignOpen = ref(false)
const assignTarget = ref(null)
const assignForm = reactive({ brokerId: undefined, ownerExpertId: undefined })
const appraisalOpen = ref(false)
const appraisalTarget = ref(null)
const appraisalForm = reactive({ status: 'EXPERT_REVIEW', conclusionLevel: '', reportNo: '' })
const rejectOpen = ref(false)
const rejectTarget = ref(null)
const rejectForm = reactive({ note: '' })
const expertOpen = ref(false)
const currentExpert = ref(null)
const drawerWidth = computed(() => (window.innerWidth < 768 ? '100%' : 520))

const stats = computed(() => [
  { key: 'expertPending', label: '待审专家', value: counts.value.expertPending || 0, tone: 'warn' },
  { key: 'expertOnline', label: '在架专家', value: counts.value.expertOnline || 0, tone: 'ok' },
  { key: 'demandOpen', label: '待受理需求', value: counts.value.demandOpen || 0, tone: 'info' },
  { key: 'projectActive', label: '进行中项目', value: counts.value.projectActive || 0, tone: 'info' },
  { key: 'appraisalPending', label: '评价待办', value: counts.value.appraisalPending || 0, tone: 'warn' },
  { key: 'hostingPending', label: '托管待受理', value: counts.value.hostingPending || 0, tone: 'warn' }
])

const expertCols = [
  { title: '姓名', dataIndex: 'name', key: 'name', width: 110 },
  { title: '类型', key: 'type', width: 110 },
  { title: '单位 / 职称', key: 'org', width: 200, customRender: ({ record }) => `${record.organization || '—'}` },
  { title: '领域', key: 'domains' },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'op', width: 190 }
]
const demandCols = [
  { title: '需求标题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '行业', dataIndex: 'industry', key: 'industry', width: 110 },
  { title: '预算', key: 'budget', width: 160 },
  { title: '状态', key: 'status', width: 100 },
  { title: '变更状态', key: 'op', width: 140 }
]
const projectCols = [
  { title: '项目', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '编号', dataIndex: 'projectNo', key: 'projectNo', width: 170 },
  { title: '进度 / 下一步', key: 'progress' },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'op', width: 190 }
]
const appraisalCols = [
  { title: '申请编号', dataIndex: 'appraisalNo', key: 'appraisalNo', width: 170 },
  { title: '标准', dataIndex: 'standardCode', key: 'standardCode', width: 140 },
  { title: '状态', key: 'status', width: 120 },
  { title: '报告编号', dataIndex: 'reportNo', key: 'reportNo', width: 160 },
  { title: '操作', key: 'op', width: 110 }
]
const hostingCols = [
  { title: '托管标的', dataIndex: 'targetName', key: 'targetName', ellipsis: true },
  { title: '类型', dataIndex: 'hostingType', key: 'hostingType', width: 100 },
  { title: '编号', dataIndex: 'hostingNo', key: 'hostingNo', width: 160 },
  { title: '状态', key: 'status', width: 100 },
  { title: '变更状态', key: 'op', width: 140 }
]
const complianceCols = [
  { title: '服务线', dataIndex: 'module', key: 'module', width: 150 },
  { title: '平台可做', key: 'allowed' },
  { title: '绝不触碰', key: 'forbidden' },
  { title: '资质 / 合作方', key: 'partner', width: 260 }
]

const contractCols = [
  { title: '合同号', dataIndex: 'contractNo', key: 'contractNo', width: 170 },
  { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '类型', dataIndex: 'contractType', key: 'contractType', width: 100 },
  { title: '金额', key: 'amount', width: 130 },
  { title: '状态', key: 'status', width: 100 },
  { title: '签署时间', dataIndex: 'signedAt', key: 'signedAt', width: 160 }
]
const feeCols = [
  { title: '费用号', dataIndex: 'feeNo', key: 'feeNo', width: 170 },
  { title: '合同号', dataIndex: 'contractNo', key: 'contractNo', width: 170 },
  { title: '类型', dataIndex: 'feeType', key: 'feeType', width: 110 },
  { title: '金额', key: 'amount', width: 130 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'op', width: 140 }
]
const escrowCols = [
  { title: '流水号', dataIndex: 'escrowNo', key: 'escrowNo', width: 170 },
  { title: '方向', key: 'direction', width: 100 },
  { title: '金额', key: 'amount', width: 130 },
  { title: '机构', dataIndex: 'provider', key: 'provider', width: 140 },
  { title: '外部凭证', dataIndex: 'externalRef', key: 'externalRef', width: 160 },
  { title: '发生时间', dataIndex: 'occurredAt', key: 'occurredAt', width: 160 }
]
const orgTaskCols = [
  { title: '任务号', dataIndex: 'taskNo', key: 'taskNo', width: 170 },
  { title: '类型', key: 'taskType', width: 110 },
  { title: '任务标题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '机构', dataIndex: 'orgId', key: 'orgId', width: 90 },
  { title: '承接人', dataIndex: 'assigneeUserId', key: 'assigneeUserId', width: 100 },
  { title: '状态', key: 'status', width: 120 },
  { title: '截止', dataIndex: 'dueDate', key: 'dueDate', width: 110 },
  { title: '操作', key: 'op', width: 110 }
]
const reviewCols = [
  { title: '评价编号', dataIndex: 'reviewNo', key: 'reviewNo', width: 170 },
  { title: '评价对象', key: 'target', width: 160 },
  { title: '关联项目', dataIndex: 'projectTitle', key: 'projectTitle', ellipsis: true },
  { title: '五维评分', key: 'scores', width: 160 },
  { title: '综合', key: 'overall', width: 80 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 160 }
]
const pilotResourceCols = [
  { title: '资源名称', dataIndex: 'name', key: 'name', ellipsis: true },
  { title: '机构', dataIndex: 'orgName', key: 'orgName', width: 200 },
  { title: '类型', key: 'type', width: 110 },
  { title: '区域', dataIndex: 'region', key: 'region', width: 90 },
  { title: '价格', key: 'price', width: 150 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'op', width: 160 }
]
const pilotBookingCols = [
  { title: '预约号', dataIndex: 'bookingNo', key: 'bookingNo', width: 170 },
  { title: '资源', dataIndex: 'resourceName', key: 'resourceName', ellipsis: true },
  { title: '用户', dataIndex: 'userId', key: 'userId', width: 90 },
  { title: '期望日期', dataIndex: 'scheduledDate', key: 'scheduledDate', width: 120 },
  { title: '状态', key: 'status', width: 100 },
  { title: '处理', key: 'op', width: 140 }
]

const contractStatusOptions = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已签署', value: 'SIGNED' },
  { label: '已归档', value: 'ARCHIVED' }
]
const feeStatusOptions = [
  { label: '待支付', value: 'PENDING' },
  { label: '已到账', value: 'PAID' }
]

const orgTaskStatusOptions = [
  { label: '待接收', value: 'ASSIGNED' },
  { label: '进行中', value: 'IN_PROGRESS' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '已验收', value: 'ACCEPTED' },
  { label: '已驳回', value: 'REJECTED' }
]
const taskTypeOptions = [
  { label: '评价协作', value: 'EVALUATION' },
  { label: '交付执行', value: 'DELIVERY' },
  { label: '代理服务', value: 'AGENCY' },
  { label: '成果评价', value: 'APPRAISAL' }
]
const reviewTargetOptions = [
  { label: '技术专家', value: 'EXPERT' },
  { label: '技术经纪人', value: 'BROKER' },
  { label: '服务机构', value: 'ORG' }
]
const resourceTypeOptions = [
  { label: '中试线', value: 'PILOT' },
  { label: '概念验证', value: 'VERIFICATION' },
  { label: '共享实验室', value: 'LAB' }
]
const priceModeOptions = [
  { label: '按需报价', value: 'QUOTE' },
  { label: '固定价', value: 'FIXED' },
  { label: '面议', value: 'NEGOTIABLE' },
  { label: '免费', value: 'FREE' }
]
const bookingStatusOptions = [
  { label: '待确认', value: 'APPLIED' },
  { label: '已排期', value: 'ACCEPTED' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已拒绝', value: 'REJECTED' }
]
const orgOptions = computed(() => orgs.value.map((o) => ({ label: o.name, value: o.orgId })))

const demandStatusOptions = [
  { label: '匹配中', value: 'MATCHING' },
  { label: '对接中', value: 'IN_PROGRESS' },
  { label: '已关闭', value: 'CLOSED' }
]
const hostingStatusOptions = [
  { label: '已申请', value: 'APPLIED' },
  { label: '托管中', value: 'ACTIVE' },
  { label: '已暂停', value: 'SUSPENDED' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已取消', value: 'CANCELLED' }
]
const appraisalStatusOptions = [
  { label: '已提交', value: 'SUBMITTED' },
  { label: '材料预审', value: 'MATERIAL_REVIEW' },
  { label: '专家评审', value: 'EXPERT_REVIEW' },
  { label: '报告已出具', value: 'REPORT_ISSUED' },
  { label: '已退回', value: 'REJECTED' }
]
const brokerOptions = computed(() => experts.value.filter((e) => e.expertType === 'BROKER' && e.status === 1).map((e) => ({ label: `${e.name} · ${e.organization}`, value: e.expertId })))
const expertOptions = computed(() => experts.value.filter((e) => e.status === 1).map((e) => ({ label: `${e.name} · ${e.organization}`, value: e.expertId })))

const expertPageProps = computed(() => ({ current: expertPage.value.page, pageSize: expertPage.value.size, total: Number(expertPage.value.total), showSizeChanger: false, size: 'small' }))
const demandPageProps = computed(() => ({ current: demandPage.value.page, pageSize: demandPage.value.size, total: Number(demandPage.value.total), showSizeChanger: false, size: 'small' }))
const projectPageProps = computed(() => ({ current: projectPage.value.page, pageSize: projectPage.value.size, total: Number(projectPage.value.total), showSizeChanger: false, size: 'small' }))
const appraisalPageProps = computed(() => ({ current: appraisalPage.value.page, pageSize: appraisalPage.value.size, total: Number(appraisalPage.value.total), showSizeChanger: false, size: 'small' }))
const hostingPageProps = computed(() => ({ current: hostingPage.value.page, pageSize: hostingPage.value.size, total: Number(hostingPage.value.total), showSizeChanger: false, size: 'small' }))
const contractPageProps = computed(() => ({ current: contractPage.value.page, pageSize: contractPage.value.size, total: Number(contractPage.value.total), showSizeChanger: false, size: 'small' }))
const feePageProps = computed(() => ({ current: serviceFeePage.value.page, pageSize: serviceFeePage.value.size, total: Number(serviceFeePage.value.total), showSizeChanger: false, size: 'small' }))
const escrowPageProps = computed(() => ({ current: escrowPage.value.page, pageSize: escrowPage.value.size, total: Number(escrowPage.value.total), showSizeChanger: false, size: 'small' }))
const orgTaskPageProps = computed(() => ({ current: orgTaskPage.value.page, pageSize: orgTaskPage.value.size, total: Number(orgTaskPage.value.total), showSizeChanger: false, size: 'small' }))
const reviewPageProps = computed(() => ({ current: reviewPage.value.page, pageSize: reviewPage.value.size, total: Number(reviewPage.value.total), showSizeChanger: false, size: 'small' }))

function expertStatusLabel(s) {
  return s === 1 ? '已上架' : s === -1 ? '已驳回' : '待审核'
}
function expertStatusColor(s) {
  return s === 1 ? 'green' : s === -1 ? 'red' : 'gold'
}
function demandStatusLabel(s) {
  return { MATCHING: '匹配中', IN_PROGRESS: '对接中', CLOSED: '已关闭' }[s] || s
}
function demandStatusColor(s) {
  return { MATCHING: 'blue', IN_PROGRESS: 'purple', CLOSED: 'default' }[s] || 'default'
}
function appraisalStatusLabel(s) {
  return { SUBMITTED: '已提交', MATERIAL_REVIEW: '材料预审', EXPERT_REVIEW: '专家评审', REPORT_ISSUED: '报告已出具', REJECTED: '已退回' }[s] || s
}
function appraisalStatusColor(s) {
  return { SUBMITTED: 'blue', MATERIAL_REVIEW: 'gold', EXPERT_REVIEW: 'purple', REPORT_ISSUED: 'green', REJECTED: 'red' }[s] || 'default'
}
function hostingStatusLabel(s) {
  return { APPLIED: '已申请', ACTIVE: '托管中', SUSPENDED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }[s] || s
}
function hostingStatusColor(s) {
  return { APPLIED: 'gold', ACTIVE: 'green', SUSPENDED: 'orange', COMPLETED: 'blue', CANCELLED: 'default' }[s] || 'default'
}
function budgetText(record) {
  const min = Number(record.budgetMin || 0)
  const max = Number(record.budgetMax || 0)
  if (!min && !max) return '面议'
  return `¥${min} – ¥${max}`
}

async function loadOverview() {
  const data = await techAdminApi.overview()
  counts.value = data?.counts || {}
  complianceItems.value = data?.compliance?.items || complianceItems.value
  complianceNotice.value = data?.compliance?.notice || complianceNotice.value
}

async function loadExperts(page = 1) {
  const res = await techAdminApi.experts({ page, size: expertPage.value.size, status: expertStatus.value === '' ? undefined : expertStatus.value, keyword: expertKeyword.value || undefined })
  experts.value = res?.records || []
  expertPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadDemands(page = 1) {
  const res = await techAdminApi.demands({ page, size: demandPage.value.size, status: demandStatus.value || undefined, keyword: demandKeyword.value || undefined })
  demands.value = res?.records || []
  demandPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadProjects(page = 1) {
  const res = await techAdminApi.projects({ page, size: projectPage.value.size, status: projectStatus.value || undefined, keyword: projectKeyword.value || undefined })
  projects.value = res?.records || []
  projectPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadAppraisals(page = 1) {
  const res = await techAdminApi.appraisals({ page, size: appraisalPage.value.size })
  appraisals.value = res?.records || []
  appraisalPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadHostings(page = 1) {
  const res = await techAdminApi.hostings({ page, size: hostingPage.value.size })
  hostings.value = res?.records || []
  hostingPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadContracts(page = 1) {
  const res = await techAdminApi.contracts({
    page, size: contractPage.value.size,
    status: contractStatus.value || undefined,
    keyword: contractKeyword.value || undefined
  })
  contracts.value = res?.records || []
  contractPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadServiceFees(page = 1) {
  const res = await techAdminApi.serviceFees({
    page, size: serviceFeePage.value.size,
    status: feeStatus.value || undefined,
    keyword: feeKeyword.value || undefined
  })
  serviceFees.value = res?.records || []
  serviceFeePage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadEscrow(page = 1) {
  const res = await techAdminApi.escrowLedger({ page, size: escrowPage.value.size })
  escrowRows.value = res?.records || []
  escrowPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadOrgTasks(page = 1) {
  const res = await techAdminApi.orgTasks({
    page, size: orgTaskPage.value.size,
    status: orgTaskStatus.value || undefined,
    keyword: orgTaskKeyword.value || undefined
  })
  orgTasks.value = res?.records || []
  orgTaskPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadReviews(page = 1) {
  const res = await techAdminApi.reviews({
    page, size: reviewPage.value.size,
    targetType: reviewTargetType.value || undefined
  })
  reviews.value = res?.records || []
  reviewPage.value = { page: Number(res?.page || page), size: Number(res?.size || 10), total: Number(res?.total || 0) }
}

async function loadPilotResources() {
  pilotResources.value = (await techAdminApi.pilotResources()) || []
}

async function loadPilotBookings() {
  pilotBookings.value = (await techAdminApi.pilotBookings({
    status: bookingStatus.value || undefined
  })) || []
}

async function loadOrgs() {
  if (orgs.value.length) return
  orgs.value = (await techApi.organizations()) || []
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadExperts(1), loadDemands(1), loadProjects(1),
      loadAppraisals(1), loadHostings(1), loadContracts(1), loadServiceFees(1), loadEscrow(1),
      loadOrgTasks(1), loadReviews(1), loadPilotResources(), loadPilotBookings()])
  } catch (e) {
    message.error('技术服务数据加载失败：' + (e?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function onTabChange(key) {
  const loaders = {
    experts: loadExperts, demands: loadDemands, projects: loadProjects,
    appraisals: loadAppraisals, hostings: loadHostings,
    contracts: loadContracts, serviceFees: loadServiceFees, escrow: loadEscrow,
    orgTasks: loadOrgTasks, reviews: loadReviews,
    pilotResources: loadPilotResources, pilotBookings: loadPilotBookings
  }
  if (loaders[key]) loaders[key](1)
}
function onExpertPage(p) { loadExperts(p.current) }
function onDemandPage(p) { loadDemands(p.current) }
function onProjectPage(p) { loadProjects(p.current) }
function onAppraisalPage(p) { loadAppraisals(p.current) }
function onHostingPage(p) { loadHostings(p.current) }
function onContractPage(p) { loadContracts(p.current) }
function onFeePage(p) { loadServiceFees(p.current) }
function onEscrowPage(p) { loadEscrow(p.current) }
function onOrgTaskPage(p) { loadOrgTasks(p.current) }
function onReviewPage(p) { loadReviews(p.current) }

function contractStatusLabel(s) {
  return { DRAFT: '草稿', PENDING_SIGN: '待签署', SIGNED: '已签署', ARCHIVED: '已归档' }[s] || s
}
function contractStatusColor(s) {
  return { DRAFT: 'gold', PENDING_SIGN: 'gold', SIGNED: 'green', ARCHIVED: 'default' }[s] || 'default'
}
function taskTypeLabel(t) {
  return { EVALUATION: '评价协作', DELIVERY: '交付执行', AGENCY: '代理服务', APPRAISAL: '成果评价' }[t] || t
}
function orgTaskStatusLabel(s) {
  return { ASSIGNED: '待接收', IN_PROGRESS: '进行中', SUBMITTED: '已提交', ACCEPTED: '已验收', REJECTED: '已驳回' }[s] || s
}
function orgTaskStatusColor(s) {
  return { ASSIGNED: 'gold', IN_PROGRESS: 'blue', SUBMITTED: 'purple', ACCEPTED: 'green', REJECTED: 'red' }[s] || 'default'
}
function reviewTargetLabel(t) {
  return { EXPERT: '技术专家', BROKER: '技术经纪人', ORG: '服务机构' }[t] || t
}
function resourceTypeLabel(t) {
  return { PILOT: '中试线', VERIFICATION: '概念验证', LAB: '共享实验室' }[t] || t
}
function resourcePriceText(r) {
  const mode = { QUOTE: '按需报价', FIXED: '固定价', NEGOTIABLE: '面议', FREE: '免费' }[r.priceMode] || r.priceMode
  return r.price ? `¥${Number(r.price).toLocaleString()} · ${mode}` : mode
}
function bookingStatusLabel(s) {
  return { APPLIED: '待确认', ACCEPTED: '已排期', COMPLETED: '已完成', REJECTED: '已拒绝' }[s] || s
}
function bookingStatusColor(s) {
  return { APPLIED: 'gold', ACCEPTED: 'green', COMPLETED: 'blue', REJECTED: 'red' }[s] || 'default'
}

async function openOrgTask() {
  try {
    await loadOrgs()
  } catch (e) {
    message.error(e?.message || '机构列表加载失败')
  }
  Object.assign(orgTaskForm, {
    orgId: undefined, projectId: undefined, assigneeUserId: undefined,
    taskType: 'EVALUATION', title: '', scope: '', dueDate: ''
  })
  orgTaskOpen.value = true
}

async function submitOrgTask() {
  if (!orgTaskForm.orgId || !orgTaskForm.title.trim()) {
    message.warning('请选择承接机构并填写任务标题')
    return
  }
  saving.value = true
  try {
    await techAdminApi.createOrgTask({
      orgId: orgTaskForm.orgId,
      projectId: orgTaskForm.projectId || null,
      assigneeUserId: orgTaskForm.assigneeUserId || null,
      taskType: orgTaskForm.taskType,
      title: orgTaskForm.title.trim(),
      scope: orgTaskForm.scope || null,
      dueDate: orgTaskForm.dueDate || null
    })
    message.success('机构任务已派发')
    orgTaskOpen.value = false
    await loadOrgTasks(1)
  } catch (e) {
    message.error(e?.message || '派发失败')
  } finally {
    saving.value = false
  }
}

function openOrgReview(record) {
  orgReviewTarget.value = record
  orgReviewForm.decision = 'ACCEPTED'
  orgReviewForm.note = ''
  orgReviewOpen.value = true
}

async function submitOrgReview() {
  if (!orgReviewTarget.value) return
  if (orgReviewForm.decision === 'REJECTED' && !orgReviewForm.note.trim()) {
    message.warning('驳回必须填写验收意见')
    return
  }
  saving.value = true
  try {
    await techAdminApi.reviewOrgTask(orgReviewTarget.value.taskNo, {
      decision: orgReviewForm.decision,
      note: orgReviewForm.note.trim() || null
    })
    message.success('机构任务已验收')
    orgReviewOpen.value = false
    await loadOrgTasks(orgTaskPage.value.page)
  } catch (e) {
    message.error(e?.message || '验收失败')
  } finally {
    saving.value = false
  }
}

function openResource(row = null) {
  Object.assign(resourceForm, row ? {
    resourceId: row.resourceId,
    name: row.name,
    orgName: row.orgName,
    region: row.region || '',
    resourceType: row.resourceType || 'PILOT',
    capabilities: row.capabilities || '',
    equipment: row.equipment || '',
    priceMode: row.priceMode || 'QUOTE',
    price: row.price ?? undefined,
    contact: row.contact || '',
    status: row.status,
    sort: row.sort || 0
  } : {
    resourceId: undefined, name: '', orgName: '', region: '', resourceType: 'PILOT',
    capabilities: '', equipment: '', priceMode: 'QUOTE', price: undefined,
    contact: '', status: 1, sort: 0
  })
  resourceOpen.value = true
}

async function submitResource() {
  if (!resourceForm.name.trim() || !resourceForm.orgName.trim()) {
    message.warning('请填写资源名称与所属机构')
    return
  }
  saving.value = true
  try {
    await techAdminApi.upsertPilotResource({
      resourceId: resourceForm.resourceId || null,
      name: resourceForm.name.trim(),
      orgName: resourceForm.orgName.trim(),
      region: resourceForm.region || null,
      resourceType: resourceForm.resourceType,
      capabilities: resourceForm.capabilities || null,
      equipment: resourceForm.equipment || null,
      priceMode: resourceForm.priceMode,
      price: resourceForm.price ?? null,
      contact: resourceForm.contact || null,
      status: resourceForm.status,
      sort: resourceForm.sort
    })
    message.success('中试资源已保存')
    resourceOpen.value = false
    await loadPilotResources()
  } catch (e) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleResource(row) {
  try {
    await techAdminApi.togglePilotResource(row.resourceId, row.status === 1 ? 0 : 1)
    message.success(row.status === 1 ? '资源已下架' : '资源已上架')
    await loadPilotResources()
  } catch (e) {
    message.error(e?.message || '状态更新失败')
  }
}

async function changeBookingStatus(record, status) {
  try {
    await techAdminApi.updatePilotBooking(record.bookingNo, { status, note: record.adminNote || null })
    record.status = status
    message.success('预约状态已更新')
  } catch (e) {
    message.error(e?.message || '更新失败')
  }
}

function openMarkPaid(record) {
  markPaidTarget.value = record
  markPaidForm.payChannel = 'bank'
  markPaidForm.externalRef = ''
  markPaidForm.invoiceNo = ''
  markPaidOpen.value = true
}
async function submitMarkPaid() {
  if (!markPaidTarget.value) return
  saving.value = true
  try {
    await techAdminApi.markServiceFeePaid(markPaidTarget.value.feeId, { ...markPaidForm })
    message.success('服务费已确认到账')
    markPaidOpen.value = false
    await loadServiceFees(serviceFeePage.value.page)
  } catch (e) {
    message.error(e?.message || '确认失败')
  } finally {
    saving.value = false
  }
}

function openEscrow() {
  Object.assign(escrowForm, { dealId: undefined, contractId: undefined, amount: 0, direction: 'IN', provider: 'bank', externalRef: '' })
  escrowOpen.value = true
}
async function submitEscrow() {
  if (!escrowForm.amount || escrowForm.amount <= 0) {
    message.warning('请填写大于 0 的监管金额')
    return
  }
  saving.value = true
  try {
    await techAdminApi.recordEscrow({ ...escrowForm })
    message.success('监管流水已登记')
    escrowOpen.value = false
    await loadEscrow(1)
  } catch (e) {
    message.error(e?.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function showExpert(record) {
  currentExpert.value = record
  expertOpen.value = true
}

function reviewExpert(record, decision) {
  if (decision === 'reject') {
    rejectTarget.value = record
    rejectForm.note = ''
    rejectOpen.value = true
    return
  }
  doReview(record, 'approve', '')
}

async function submitReject() {
  const note = rejectForm.note.trim()
  if (!note) {
    message.warning('驳回必须填写原因')
    return
  }
  await doReview(rejectTarget.value, 'reject', note)
  rejectOpen.value = false
}

async function doReview(record, decision, note) {
  saving.value = true
  try {
    await techAdminApi.reviewExpert(record.expertId, decision, note)
    message.success(decision === 'approve' ? '已通过并上架' : '已驳回，原因已写入审核留痕')
    await Promise.all([loadExperts(expertPage.value.page), loadOverview()])
  } catch (e) {
    message.error(e?.message || '审核失败')
  } finally {
    saving.value = false
  }
}

function openInvite() {
  Object.assign(inviteForm, { expertType: 'EXPERT', name: '', title: '', organization: '', industries: '', domains: '', bio: '', credentials: '', consultFee: 0 })
  inviteOpen.value = true
}

async function submitInvite() {
  if (!inviteForm.name.trim() || !inviteForm.organization.trim()) {
    message.warning('请填写姓名与所属单位')
    return
  }
  saving.value = true
  try {
    await techAdminApi.inviteExpert({ ...inviteForm })
    message.success('已邀请入驻并上架')
    inviteOpen.value = false
    await Promise.all([loadExperts(1), loadOverview()])
  } catch (e) {
    message.error(e?.message || '邀请失败')
  } finally {
    saving.value = false
  }
}

async function changeDemandStatus(record, status) {
  try {
    await techAdminApi.updateDemandStatus(record.demandId, status)
    record.status = status
    message.success('需求状态已更新')
  } catch (e) {
    message.error(e?.message || '更新失败')
  }
}

async function changeHostingStatus(record, status) {
  try {
    await techAdminApi.updateHosting(record.hostingId, status)
    record.status = status
    message.success('托管状态已更新')
  } catch (e) {
    message.error(e?.message || '更新失败')
  }
}

async function advanceProject(record) {
  try {
    const data = await techAdminApi.advanceProject(record.projectId)
    Object.assign(record, data?.project || {})
    message.success('里程碑已推进')
    await loadOverview()
  } catch (e) {
    message.error(e?.message || '推进失败')
  }
}

function openAssign(record) {
  assignTarget.value = record
  assignForm.brokerId = record.brokerId || undefined
  assignForm.ownerExpertId = record.ownerExpertId || undefined
  assignOpen.value = true
}

async function submitAssign() {
  if (!assignTarget.value) return
  saving.value = true
  try {
    await techAdminApi.assignTeam(assignTarget.value.projectId, { brokerId: assignForm.brokerId, ownerExpertId: assignForm.ownerExpertId })
    message.success('陪跑团队已指派')
    assignOpen.value = false
    await loadProjects(projectPage.value.page)
  } catch (e) {
    message.error(e?.message || '指派失败')
  } finally {
    saving.value = false
  }
}

function openAppraisal(record) {
  appraisalTarget.value = record
  appraisalForm.status = record.status || 'EXPERT_REVIEW'
  appraisalForm.conclusionLevel = record.conclusionLevel || ''
  appraisalForm.reportNo = record.reportNo || ''
  appraisalOpen.value = true
}

async function submitAppraisal() {
  if (!appraisalTarget.value) return
  saving.value = true
  try {
    await techAdminApi.updateAppraisal(appraisalTarget.value.appraisalId, { ...appraisalForm })
    message.success('评价进度已更新')
    appraisalOpen.value = false
    await Promise.all([loadAppraisals(appraisalPage.value.page), loadOverview()])
  } catch (e) {
    message.error(e?.message || '更新失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.admin-tech { padding: 4px; }
.card { border-radius: 18px; box-shadow: 0 10px 34px rgba(19, 35, 58, 0.06); }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.eyebrow { color: var(--ll-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.14em; }
.page-head h2 { margin: 6px 0 6px; font-size: 24px; letter-spacing: -0.02em; }
.lead { margin: 0; max-width: 780px; color: var(--ll-muted); font-size: 13px; line-height: 1.8; }
.stat-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; margin-bottom: 8px; }
.stat { padding: 12px 14px; border-radius: 14px; background: rgba(15, 23, 42, 0.04); }
.stat span { display: block; color: var(--ll-muted); font-size: 12px; }
.stat b { display: block; margin-top: 6px; font-size: 22px; letter-spacing: -0.02em; }
.stat.warn b { color: #b45309; }
.stat.ok b { color: #047857; }
.stat.info b { color: var(--ll-primary); }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 12px; }
.cell-main { font-weight: 600; }
.cell-sub { margin-top: 4px; color: var(--ll-muted); font-size: 12px; }
.progress-cell { display: flex; align-items: center; gap: 10px; }
.progress-cell :deep(.ant-progress) { flex: 1; margin: 0; }
.txt-allow { color: #047857; }
.txt-deny { color: #9b2d20; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.form-grid .full { grid-column: 1 / -1; }
.expert-detail h3 { margin: 0; font-size: 20px; }
.expert-detail .sub { margin: 6px 0 0; color: var(--ll-muted); font-size: 13px; }
.mt { margin-top: 16px; }
@media (max-width: 1100px) { .stat-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 767px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .page-head { flex-direction: column; }
  .form-grid { grid-template-columns: 1fr; }
}
</style>
