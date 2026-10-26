<template>
  <div class="apply-container">
    <!-- 头部横幅：21st.dev 质感渐变光晕与品牌背书 -->
    <div class="apply-hero">
      <div class="hero-badge">
        <span class="pulse-dot"></span>
        <span>LANLink B2B 商户入驻通道 · 安全合规体系</span>
      </div>
      <h1 class="hero-title">携手共建聚合型工业与建材数字供应链</h1>
      <p class="hero-desc">
        依托全链路资质审核与自动初筛风控引擎，全程遵循《数据安全法》与《个人信息保护法》。商户核心财务与法人信息采用 <strong>AES-256-GCM 银行级加密</strong>，保障商户企业资产与数据隐私。
      </p>
    </div>

    <div class="apply-grid">
      <!-- 左侧：多步骤入驻向导卡片 -->
      <div class="apply-main">
        <a-card :bordered="false" class="apply-card glass-panel">
          <div class="step-nav">
            <div
              v-for="(st, idx) in steps"
              :key="idx"
              class="step-nav-item"
              :class="{ active: currentStep === idx, completed: currentStep > idx }"
              @click="goToStep(idx)"
            >
              <div class="step-num">{{ currentStep > idx ? '✓' : idx + 1 }}</div>
              <div class="step-info">
                <div class="step-title">{{ st.title }}</div>
                <div class="step-sub">{{ st.sub }}</div>
              </div>
            </div>
          </div>

          <a-form :model="form" layout="vertical" class="apply-form" @finish="submit">
            <!-- 步骤 1：企业工商主体基础信息 -->
            <div v-show="currentStep === 0" class="step-pane">
              <div class="pane-header">
                <h3>企业工商主体基础信息</h3>
                <p>请准确填写企业登记在国家企业信用信息公示系统上的真实信息</p>
              </div>

              <a-row :gutter="16">
                <a-col :span="14">
                  <a-form-item label="企业名称" required>
                    <a-input v-model:value="form.entName" size="large" placeholder="例如：华晨建材集团有限公司" allow-clear />
                  </a-form-item>
                </a-col>
                <a-col :span="10">
                  <a-form-item label="主体注册类型" required>
                    <a-select v-model:value="form.regType" size="large">
                      <a-select-option value="公司">公司 (有限责任/股份等)</a-select-option>
                      <a-select-option value="个体工商户">个体工商户 (需注册资本>10万)</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="16">
                <a-col :span="14">
                  <a-form-item label="统一社会信用代码" required extra="标准18位国家统一社会信用代码（数字与大写英文字母）">
                    <a-input
                      v-model:value="form.creditCode"
                      size="large"
                      placeholder="18位英文字母与数字组合，如 91500000MA5U0000XA"
                      :maxlength="18"
                      @input="onCreditCodeInput"
                      allow-clear
                    />
                  </a-form-item>
                </a-col>
                <a-col :span="10">
                  <a-form-item label="注册资本（人民币/元）" required extra="有限责任公司或注册资本高于10万元个体工商户">
                    <a-input-number v-model:value="form.regCapital" :min="0" :step="100000" size="large" style="width:100%" placeholder="例如 5000000" />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="入驻方式" required>
                    <a-select v-model:value="form.joinType" size="large" @change="onJoinTypeChange">
                      <a-select-option value="入驻">自主入驻 (自营品牌)</a-select-option>
                      <a-select-option value="加盟">特约加盟 (渠道代理)</a-select-option>
                      <a-select-option value="邀约">平台战略邀约</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="是否有近半年稳定纳税记录" required>
                    <a-radio-group v-model:value="form.taxStatus" size="large">
                      <a-radio :value="1">有稳定纳税记录（优先过审）</a-radio>
                      <a-radio :value="0">暂无或申报期未到</a-radio>
                    </a-radio-group>
                  </a-form-item>
                </a-col>
              </a-row>

              <!-- 平台战略邀约：必须输入平台邀约码 -->
              <a-row :gutter="16" v-if="form.joinType === '邀约'">
                <a-col :span="24">
                  <div class="code-input-card invite-card">
                    <a-form-item label="平台战略邀约码" required extra="平台战略邀约入驻需由平台专员定向发放专属邀约码，凭码享受直连绿色通道">
                      <a-input
                        v-model:value="form.joinCode"
                        size="large"
                        placeholder="请输入平台战略邀约码（如：INV-2026888 或专属定向邀约码）"
                        allow-clear
                      />
                    </a-form-item>
                  </div>
                </a-col>
              </a-row>

              <!-- 特约加盟：必须输入特约加盟码 -->
              <a-row :gutter="16" v-if="form.joinType === '加盟'">
                <a-col :span="24">
                  <div class="code-input-card franchise-card">
                    <a-form-item label="特约渠道加盟码" required extra="特约渠道加盟需提供区域运营商或总部发放的合规加盟授权码">
                      <a-input
                        v-model:value="form.joinCode"
                        size="large"
                        placeholder="请输入特约渠道加盟码（如：JM-888888 或服务商加盟授权码）"
                        allow-clear
                      />
                    </a-form-item>
                  </div>
                </a-col>
              </a-row>

              <div class="step-footer">
                <a-button type="primary" size="large" @click="nextStep">下一步：法人与财务信息 →</a-button>
              </div>
            </div>

            <!-- 步骤 2：商户法人与重要财务信息（已删除原划线警示框） -->
            <div v-show="currentStep === 1" class="step-pane">
              <div class="pane-header">
                <h3>法人代表与财务结算信息</h3>
                <p>用于平台对公转账结算、订单履约资金监管及商户真实身份核验</p>
              </div>

              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="法定代表人真实姓名" required>
                    <a-input v-model:value="form.legalPerson" size="large" placeholder="如：张建国" allow-clear />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="法定代表人身份证号" required extra="18位二代居民身份证号（末位可为 X）">
                    <a-input v-model:value="form.legalIdCard" size="large" placeholder="如：110101198503151234" :maxlength="18" allow-clear />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="对公银行账号 (企业结算卡)" required extra="8-32位纯数字对公银行账户">
                    <a-input v-model:value="form.bankAccount" size="large" placeholder="如：6222021001123456789" allow-clear />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="开户银行及具体支行名称" required extra="包含具体总行及支行名称">
                    <a-input v-model:value="form.bankName" size="large" placeholder="如：招商银行股份有限公司北京建国门支行" allow-clear />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="业务负责人联系手机" required extra="11位大陆有效手机号，用于接收审核及履约重要短信">
                    <a-input v-model:value="form.contactPhone" size="large" placeholder="如：13700000002" :maxlength="11" allow-clear />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="官方对公业务邮箱" extra="用于接收电子协议、账单月结单与重要通知">
                    <a-input v-model:value="form.contactEmail" size="large" placeholder="如：merchant.apply@lanlink.com" allow-clear />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-form-item label="企业经营场所与注册地址" required extra="需与营业执照登记住所一致或为实际办公仓库地址">
                <a-input v-model:value="form.businessAddress" size="large" placeholder="例如：北京市海淀区中关村南大街1号科技大厦8层801" allow-clear />
              </a-form-item>

              <div class="step-footer between">
                <a-button size="large" @click="currentStep = 0">← 上一步</a-button>
                <a-button type="primary" size="large" @click="nextStep">下一步：资质证照与凭证材料 →</a-button>
              </div>
            </div>

            <!-- 步骤 3：资质证照与纳税证明上传（必须完整上传信息才可进行下一步） -->
            <div v-show="currentStep === 2" class="step-pane">
              <div class="pane-header">
                <h3>资质证照与税务凭证核验</h3>
                <p>必须上传真实、完整、清晰的证照材料，核验通过后方可进入下一步</p>
              </div>

              <!-- 营业执照上传（强制必填） -->
              <div class="cert-upload-card" :class="{ 'card-valid': license.status === 'done' }">
                <div class="cert-header">
                  <div>
                    <span class="required-star">*</span> <strong>营业执照正副本扫描件/高清实拍图（必传）</strong>
                    <div class="cert-sub">JPG / PNG 格式，单份 ≤ 10MB，需文字清晰、四角完整、无反光遮挡</div>
                  </div>
                  <a-tag v-if="license.status === 'done'" color="success"><CheckCircleOutlined /> 营业执照已就绪</a-tag>
                  <a-tag v-else color="warning">待上传</a-tag>
                </div>

                <div class="up-box" :class="{ done: license.status === 'done' }">
                  <template v-if="!license.name">
                    <a-upload accept="image/jpeg,image/png" :show-upload-list="false" :before-upload="pickLicense">
                      <div class="up-trigger" :class="{ uploading: license.status === 'uploading' }">
                        <template v-if="license.status === 'uploading'">
                          <a-spin size="small" />
                          <div class="up-pct">{{ license.percent }}% 营业执照上传中...</div>
                        </template>
                        <template v-else>
                          <div class="up-icon"><FolderUp :size="32" class="text-slate-400 inline-block" /></div>
                          <div class="up-text">点击或拖拽上传营业执照图片</div>
                          <div class="up-hint-text">支持高分辨率证件照片（需包含统一社会信用代码与公章）</div>
                        </template>
                      </div>
                    </a-upload>
                  </template>
                  <template v-else>
                    <div class="up-preview">
                      <CertImage
                        :src="license.url || license.previewUrl"
                        kind="license"
                        size="medium"
                        alt="营业执照"
                        empty-text="原件未上传"
                        error-text="营业执照图片加载失败，请重新上传"
                      />
                      <div class="up-meta">
                        <div class="up-name">{{ license.name }}</div>
                        <template v-if="license.status === 'uploading'">
                          <a-progress :percent="license.percent" size="small" style="width:160px" />
                        </template>
                        <template v-else-if="license.status === 'done'">
                          <a-tag color="success"><CheckCircleOutlined /> 校验合规</a-tag>
                        </template>
                        <template v-else-if="license.status === 'error'">
                          <a-tag color="error"><CloseCircleOutlined /> 上传失败</a-tag>
                          <a size="small" @click="retryLicense">重试</a>
                        </template>
                      </div>
                      <a-button v-if="license.status === 'done'" type="text" danger size="small" @click="removeLicense">重新上传</a-button>
                    </div>
                  </template>
                </div>
                <!-- 空白态展示示例照片，减少因拍摄不合格导致的反复驳回 -->
                <CertSampleHint v-if="!license.name" kind="license" />
              </div>

              <!-- 税务登记号与完税证明材料（若勾选有稳定纳税记录则为必填） -->
              <div class="cert-upload-card" style="margin-top:16px" :class="{ 'card-valid': isTaxProofValid }">
                <div class="cert-header">
                  <div>
                    <span v-if="form.taxStatus === 1" class="required-star">*</span>
                    <strong>税务登记号及本季度完税记录</strong>
                    <span v-if="form.taxStatus === 1" class="tax-status-hint">（已申报稳定纳税，需按季度提交近 3 个月共 3 份方可进入下一步）</span>
                    <span v-else class="text-muted">（未声明纳税记录，选填）</span>
                    <div class="cert-sub">按季度提交：{{ taxPeriodLabels.join('、') }} 各 1 份《企业所得税》完税凭证（PDF/JPG/PNG，共 3 份）</div>
                  </div>
                  <a-upload accept=".pdf,.jpg,.jpeg,.png" :show-upload-list="false" :before-upload="pickTax">
                    <a-button size="small" :loading="taxBusy"><UploadOutlined /> 添加完税凭证</a-button>
                  </a-upload>
                </div>

                <!-- 季度覆盖情况：3 个月各 1 份，缺哪个月一眼可见 -->
                <div v-if="form.taxStatus === 1" class="tax-period-track">
                  <span class="tpt-label">本季度应报月份：</span>
                  <span
                    v-for="(p, i) in taxPeriods"
                    :key="p"
                    class="tpt-chip"
                    :class="{ ok: coveredTaxPeriods.has(p) }"
                  >
                    {{ taxPeriodLabels[i] }} · {{ coveredTaxPeriods.has(p) ? '已上传' : '待上传' }}
                  </span>
                </div>

                <a-form-item :label="form.taxStatus === 1 ? '税务登记号 (必填)' : '税务登记号 (选填)'" :required="form.taxStatus === 1">
                  <a-input
                    v-model:value="form.taxRegNo"
                    placeholder="请输入15/18/20位税务登记号进行稳定度核验"
                    allow-clear
                  />
                </a-form-item>

                <div v-if="taxFiles.length" class="tax-list">
                  <div v-for="(t, i) in taxFiles" :key="t.uid" class="tax-item">
                    <FileTextOutlined v-if="t.isPdf" class="tax-ico" />
                    <!-- 图片型凭证显示缩略图；上传中/加载失败自动回退为占位块 -->
                    <CertImage v-else :src="t.url" kind="tax" size="thumb" alt="完税凭证" empty-text="上传中" error-text="加载失败" />
                    <span class="tax-name">{{ t.name }}</span>
                    <span v-if="t.period" class="tax-period-tag">{{ monthLabel(t.period) }}</span>
                    <template v-if="t.status === 'uploading'">
                      <a-progress :percent="t.percent" size="small" style="width:100px" />
                    </template>
                    <template v-else-if="t.status === 'done'">
                      <a-tag color="success">已上传</a-tag>
                    </template>
                    <a class="tax-del" @click="removeTax(i)">移除</a>
                  </div>
                </div>
                <div v-else class="empty-tax">
                  <div class="empty-tax-tip">
                    <span v-if="form.taxStatus === 1" style="color: #ea580c;">⚠️ 您勾选了有稳定纳税记录：本季度需上传近 3 个月共 3 份企业所得税完税凭证（还差 {{ missingTaxPeriods.length }} 份）</span>
                    <span v-else>暂未添加税务证明附件（未申报纳税可跳过）</span>
                  </div>
                  <CertSampleHint kind="tax" />
                </div>
              </div>

              <div class="step-footer between">
                <a-button size="large" @click="currentStep = 1">← 上一步</a-button>
                <a-button type="primary" size="large" @click="nextStep">下一步：特殊经营许可与承诺 →</a-button>
              </div>
            </div>

            <!-- 步骤 4：特殊经营许可证核验与确认提交 -->
            <div v-show="currentStep === 3" class="step-pane">
              <div class="pane-header">
                <h3>行业特许资质与特殊经营许可证核验</h3>
                <p>请如实说明企业是否持有国家特许经营许可，并仔细核对入驻信息</p>
              </div>

              <!-- 提问：是否有特殊经营许可证 -->
              <div class="special-permit-question-box">
                <div class="question-title-row">
                  <span class="q-icon">❓</span>
                  <div>
                    <h4 class="q-title">是否有特殊经营许可证 / 行业特许准入资质？</h4>
                    <p class="q-desc">如经营危险化学品、民用爆炸物、食品工业流通、特种劳动防护装备、压力管道、医疗器械、建筑消防工程等国家法定特许经营门类，必须上传特许许可证件才能入驻。</p>
                  </div>
                </div>

                <a-radio-group v-model:value="form.hasSpecialPermit" size="large" class="special-permit-radios" @change="onSpecialPermitChange">
                  <a-radio :value="false">
                    <span class="radio-label"><strong>否</strong>（普通建材与工业品销售，无前置/后置特殊审批资质要求）</span>
                  </a-radio>
                  <a-radio :value="true">
                    <span class="radio-label"><strong>是</strong>（经营范围涉及特种物资/特种行业，具备国家特许经营许可）</span>
                  </a-radio>
                </a-radio-group>
              </div>

              <!-- 当有特殊经营许可证时，展开强制上传 -->
              <div v-if="form.hasSpecialPermit" class="special-permit-details-card">
                <div class="sub-card-header">
                  <span class="required-star">*</span>
                  <strong>特殊经营许可证信息录入与附件上传（必填必传）</strong>
                </div>

                <a-form-item label="特殊经营许可证全称及证书编号" required extra="例如：《危险化学品经营许可证》（渝A危化经字[2026]089号）">
                  <a-input v-model:value="form.specialPermitName" size="large" placeholder="请输入特殊经营许可证名称及编号" allow-clear />
                </a-form-item>

                <!-- 许可证扫描件上传 -->
                <div class="cert-upload-card" :class="{ 'card-valid': specialPermit.status === 'done' }">
                  <div class="cert-header">
                    <div>
                      <span class="required-star">*</span> <strong>特殊经营许可证正本扫描件 / 高清原件照片</strong>
                      <div class="cert-sub">支持 JPG / PNG / PDF 格式，文件大小 ≤ 10MB，需包含年检及发证机关印章</div>
                    </div>
                    <a-tag v-if="specialPermit.status === 'done'" color="success"><CheckCircleOutlined /> 许可证已就绪</a-tag>
                    <a-tag v-else color="warning">待上传</a-tag>
                  </div>

                  <div class="up-box" :class="{ done: specialPermit.status === 'done' }">
                    <template v-if="!specialPermit.name">
                      <a-upload accept="image/jpeg,image/png,application/pdf,.pdf,.jpg,.png" :show-upload-list="false" :before-upload="pickSpecialPermit">
                        <div class="up-trigger" :class="{ uploading: specialPermit.status === 'uploading' }">
                          <template v-if="specialPermit.status === 'uploading'">
                            <a-spin size="small" />
                            <div class="up-pct">{{ specialPermit.percent }}% 许可证上传中...</div>
                          </template>
                          <template v-else>
                            <div class="up-icon"><FileText :size="32" class="text-slate-400 inline-block" /></div>
                            <div class="up-text">点击或拖拽上传特殊经营许可证文件</div>
                            <div class="up-hint-text">支持彩色扫描件或清晰实拍图（JPG、PNG 或 PDF）</div>
                          </template>
                        </div>
                      </a-upload>
                    </template>
                    <template v-else>
                      <div class="up-preview">
                        <CertImage
                          v-if="!specialPermit.isPdf"
                          :src="specialPermit.url || specialPermit.previewUrl"
                          kind="permit"
                          size="medium"
                          alt="特殊经营许可证"
                          empty-text="原件未上传"
                          error-text="许可证图片加载失败，请重新上传"
                        />
                        <FileTextOutlined v-else style="font-size: 42px; color: #2563eb; margin: 0 10px;" />
                        <div class="up-meta">
                          <div class="up-name">{{ specialPermit.name }}</div>
                          <template v-if="specialPermit.status === 'uploading'">
                            <a-progress :percent="specialPermit.percent" size="small" style="width:160px" />
                          </template>
                          <template v-else-if="specialPermit.status === 'done'">
                            <a-tag color="success"><CheckCircleOutlined /> 许可证上传成功</a-tag>
                          </template>
                          <template v-else-if="specialPermit.status === 'error'">
                            <a-tag color="error"><CloseCircleOutlined /> 上传失败</a-tag>
                            <a size="small" @click="retrySpecialPermit">重试</a>
                          </template>
                        </div>
                        <a-button v-if="specialPermit.status === 'done'" type="text" danger size="small" @click="removeSpecialPermit">重新上传</a-button>
                      </div>
                    </template>
                  </div>
                  <!-- 特殊经营许可证同样提供示例照片与占位回退 -->
                  <CertSampleHint v-if="!specialPermit.name" kind="permit" />
                </div>
              </div>

              <!-- 补充资质说明 -->
              <a-form-item label="其他补充行业资质 / 质量管理认证（选填，如 ISO9001、环境体系认证等，多个以逗号隔开）" style="margin-top: 16px;">
                <a-input v-model:value="form.qNames" size="large" placeholder="如：质量管理体系ISO9001, 建筑材料检验合格证" allow-clear />
              </a-form-item>

              <!-- 入驻信息核验确认看板 -->
              <div class="summary-check-card">
                <div class="summary-title">入驻申报信息综合核验确认：</div>
                <div class="summary-row"><span>企业全称：</span><strong>{{ form.entName || '—' }}</strong></div>
                <div class="summary-row"><span>统一信用代码：</span><strong>{{ form.creditCode || '—' }}</strong></div>
                <div class="summary-row">
                  <span>申报入驻模式：</span>
                  <strong>
                    <span v-if="form.joinType === '邀约'">平台战略邀约（邀约码: {{ form.joinCode || '未填写' }}）</span>
                    <span v-else-if="form.joinType === '加盟'">特约渠道加盟（加盟码: {{ form.joinCode || '未填写' }}）</span>
                    <span v-else>自主入驻 (自营品牌)</span>
                  </strong>
                </div>
                <div class="summary-row"><span>法定代表人：</span><strong>{{ form.legalPerson || '—' }}（身份证加密传输）</strong></div>
                <div class="summary-row"><span>对公结算账号：</span><strong>{{ form.bankAccount ? form.bankAccount.slice(0, 4) + ' **** ' + form.bankAccount.slice(-4) : '—' }}</strong></div>
                <div class="summary-row">
                  <span>营业执照材料：</span>
                  <strong :style="{ color: license.url ? '#16a34a' : '#dc2626' }">
                    {{ license.url ? ('已就绪 (' + (license.name || '营业执照正本') + ')') : '未上传 (不可提交)' }}
                  </strong>
                </div>
                <div class="summary-row">
                  <span>季度完税材料：</span>
                  <strong>
                    <span v-if="form.taxStatus === 1">
                      已上传 {{ coveredTaxPeriods.size }}/3 份（税号: {{ form.taxRegNo || '已填' }}）
                      <span v-if="missingTaxPeriods.length" style="color: #dc2626;">· 缺 {{ missingTaxPeriods.map(monthLabel).join('、') }}</span>
                    </span>
                    <span v-else>未申报近期纳税</span>
                  </strong>
                </div>
                <div class="summary-row">
                  <span>特殊经营许可：</span>
                  <strong :style="{ color: form.hasSpecialPermit ? (specialPermit.url ? '#16a34a' : '#dc2626') : '#475569' }">
                    <span v-if="form.hasSpecialPermit">
                      {{ specialPermit.url ? ('已上传《' + (form.specialPermitName || '特许许可证') + '》') : '已勾选有特殊许可，尚未上传扫描件' }}
                    </span>
                    <span v-else>否（无特殊行业审批要求）</span>
                  </strong>
                </div>
              </div>

              <div class="agreement-box">
                <a-checkbox v-model:checked="agreed">
                  我已仔细核对上述全部信息，阅读并同意 <a href="javascript:;">《LANLink 平台商户服务入驻协议》</a> 及 <a href="javascript:;">《商户敏感数据加密与安全声明》</a>，承诺所填材料与证件真实合法。
                </a-checkbox>
              </div>

              <div class="step-footer between">
                <a-button size="large" @click="currentStep = 2">← 上一步</a-button>
                <a-button type="primary" html-type="submit" size="large" :loading="loading" :disabled="!agreed" class="submit-btn">
                  确认并提交商户入驻申请
                </a-button>
              </div>
            </div>
          </a-form>
        </a-card>
      </div>

      <!-- 右侧：当前商户状态与资质看板 -->
      <div class="apply-sidebar">
        <a-card :bordered="false" class="status-card glass-panel" title="我的商户入驻档案">
          <template v-if="mine">
            <div class="status-banner" :class="`st-${mine.reviewStatus}`">
              <div class="status-badge-icon">
                <span v-if="mine.reviewStatus === 1">✓</span>
                <span v-else-if="mine.reviewStatus === 2">✕</span>
                <span v-else>⌛</span>
              </div>
              <div class="status-text-wrap">
                <div class="status-main">{{ statusText }}</div>
                <div class="status-tip">{{ statusSubtitle }}</div>
              </div>
            </div>

            <div class="status-progress">
              <div class="flow-title">入驻审核全周期跟踪</div>
              <a-steps :current="mine.reviewStatus === 1 ? 3 : (mine.reviewStatus === 2 ? 1 : 1)" size="small" direction="vertical">
                <a-step title="提交入驻申请" :description="mine.createTime ? mine.createTime.replace('T', ' ').slice(0, 16) : '已完成'" />
                <a-step title="初筛引擎智能比对" description="主体资格、资本阈值与纳税记录校验" />
                <a-step title="平台运营专家人工复审" :description="mine.reviewStatus === 1 ? '已通过' : (mine.reviewStatus === 2 ? '审核驳回' : '正在排队复审')" />
                <a-step title="开通商户权限" :description="mine.reviewStatus === 1 ? '已开通商品发布与经营特权' : '审核后自动激活'" />
              </a-steps>
            </div>

            <div class="security-data-card">
              <div class="sec-card-title">
                <span><ShieldCheck :size="16" class="inline-block mr-1 align-middle text-sky-500" /> 加密商户档案</span>
                <a-tag color="blue">AES-256-GCM</a-tag>
              </div>
              <div class="sec-item">
                <span class="k">法人代表：</span>
                <span class="v">{{ mine.legalPerson ? maskName(mine.legalPerson) : '已安全加密' }}</span>
              </div>
              <div class="sec-item">
                <span class="k">结算账户：</span>
                <span class="v font-mono">{{ mine.bankAccount ? maskBank(mine.bankAccount) : '已安全加密' }}</span>
              </div>
              <div class="sec-item">
                <span class="k">注册资本：</span>
                <span class="v">¥{{ mine.regCapital || '0.00' }}</span>
              </div>
              <div class="sec-item">
                <span class="k">入驻模式：</span>
                <span class="v">{{ mine.joinType || '直连入驻' }} <span v-if="mine.joinCode">({{ mine.joinCode }})</span></span>
              </div>
              <div class="sec-item">
                <span class="k">营业执照：</span>
                <span class="v">
                  <a v-if="mine.licenseUrl" :href="mine.licenseUrl" target="_blank">查看原件 ↗</a>
                  <span v-else class="text-muted">未上传</span>
                </span>
              </div>
              <div class="sec-item" v-if="mine.hasSpecialPermit">
                <span class="k">特殊许可：</span>
                <span class="v">
                  <a v-if="mine.specialPermitUrl" :href="mine.specialPermitUrl" target="_blank">{{ mine.specialPermitName || '查看许可证' }} ↗</a>
                  <span v-else>{{ mine.specialPermitName || '已备案' }}</span>
                </span>
              </div>
            </div>

            <!-- 入驻后：月度完税记录上报（首季按季度 3 份，之后每月 1 份，次月 15 日前完成） -->
            <div v-if="mine.reviewStatus === 1" class="tax-compliance-card">
              <div class="taxc-title">
                <span><FileSpreadsheet :size="16" class="inline-block mr-1 align-middle text-amber-500" /> 完税记录月度上报</span>
                <a-tag :color="currentTaxStatus.color">{{ currentTaxStatus.short }}</a-tag>
              </div>
              <p class="taxc-policy">入驻首季按季度提交近 3 个月（共 3 份）；之后每月上报上一自然月记录，次月 15 日前完成，最多延期 15 天。</p>
              <div class="taxc-current" :class="`is-${currentTaxStatus.status}`">
                <div class="taxc-current-main">
                  <div class="taxc-period">本期所属：{{ currentTaxStatus.periodLabel }}（企业所得税）</div>
                  <div class="taxc-deadline">
                    截止 {{ deadlineText(currentTaxStatus.deadline) }}
                    <span v-if="currentTaxStatus.hint"> · {{ currentTaxStatus.hint }}</span>
                  </div>
                </div>
                <a-upload accept=".pdf,.jpg,.jpeg,.png" :show-upload-list="false" :before-upload="pickMonthlyTax">
                  <a-button size="small" type="primary" ghost :loading="taxProofBusy">
                    {{ currentTaxStatus.status === 'overdue' ? '补报'
                      : (currentTaxStatus.status === 'on_time' || currentTaxStatus.status === 'late') ? '重报' : '上报' }}
                  </a-button>
                </a-upload>
              </div>
              <div class="taxc-timeline">
                <div v-for="s in recentTaxStatuses" :key="s.period" class="taxc-chip" :class="`is-${s.status}`">
                  <span class="taxc-chip-period">{{ s.periodLabel }}</span>
                  <span class="taxc-chip-state">{{ s.label }}</span>
                </div>
              </div>
              <div class="taxc-legend">完税记录上报情况 · 逾期未报将影响商铺经营评级</div>
            </div>

            <div v-if="mine.reviewStatus === 2 && mine.rejectReason" class="reject-alert">
              <div class="reject-title">驳回整改意见：</div>
              <div class="reject-msg">{{ mine.rejectReason }}</div>
              <a-button type="dashed" danger block style="margin-top:10px" @click="resetFormForReapply">
                重新修改并重新提交申请
              </a-button>
            </div>
          </template>

          <template v-else>
            <div class="empty-status">
              <div class="empty-icon"><Building2 :size="48" class="text-slate-300 inline-block" /></div>
              <h4>您当前尚未入驻商户</h4>
              <p>完成左侧四步入驻流程，即可开通商户专属管理控制台，上架发布工业商品。</p>
            </div>
          </template>
        </a-card>

        <a-card :bordered="false" class="policy-card glass-panel" style="margin-top:16px" title="平台入驻准入标准">
          <ul class="policy-list">
            <li><strong>公司主体优先</strong>：有限责任公司或注册资本高于 10 万元的个体工商户。</li>
            <li><strong>渠道准入校验</strong>：平台战略邀约需核验专属邀约码，特约加盟需核验渠道授权码。</li>
            <li><strong>真实证件核查</strong>：营业执照统一社会信用代码需与国家公示系统一致。</li>
            <li><strong>特许合规管理</strong>：涉及特殊行业范围必须上传有效行政许可证书。</li>
          </ul>
        </a-card>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  FolderUp,
  FileText,
  ShieldCheck,
  FileSpreadsheet,
  Building2
} from 'lucide-vue-next'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  FileTextOutlined,
  UploadOutlined
} from '@ant-design/icons-vue'
import { merchantApi } from '../api'
import CertImage from '../components/ui/CertImage.vue'
import CertSampleHint from '../components/ui/CertSampleHint.vue'
import {
  completedMonths,
  monthKey,
  addMonths,
  monthLabel,
  lastCompletedMonth,
  monthlyStatus,
  splitTaxProofUrls,
  deadlineText
} from '../utils/taxCompliance'

const loading = ref(false)
const mine = ref(null)
const currentStep = ref(0)
const agreed = ref(true)

const steps = [
  { title: '工商主体', sub: '主体类型与模式' },
  { title: '财务与法人', sub: '法人与结算卡' },
  { title: '资质证照', sub: '执照与纳税证明' },
  { title: '诚信与特许', sub: '特许经营与承诺' }
]

const form = reactive({
  entName: '',
  creditCode: '',
  regType: '公司',
  regCapital: 5000000,
  taxStatus: 1,
  joinType: '入驻',
  joinCode: '',
  legalPerson: '张建国',
  legalIdCard: '110101198503151234',
  bankAccount: '6222021001123456789',
  bankName: '招商银行股份有限公司北京建国门支行',
  contactPhone: '13700000002',
  contactEmail: 'merchant.apply@lanlink.com',
  businessAddress: '北京市海淀区中关村南大街1号科技大厦8层801',
  taxRegNo: '',
  hasSpecialPermit: false,
  specialPermitName: '',
  qNames: ''
})

const statusText = computed(() => {
  if (!mine.value) return ''
  return { 0: '入驻申请审核中', 1: '入驻成功 · 商户权限已生效', 2: '入驻申请被驳回' }[mine.value.reviewStatus] || '处理中'
})

const statusSubtitle = computed(() => {
  if (!mine.value) return ''
  return {
    0: '材料已进入平台初筛与运营复审队列，请耐心等待',
    1: '您已具备商户发布与管理权限，可前往商户中心管理商品',
    2: '材料不符合准入规范，请查看整改意见后重新提交'
  }[mine.value.reviewStatus] || ''
})

// 材料上传状态
const MAX_MB = 10
const license = reactive({ name: '', file: null, status: '', percent: 0, url: '', previewUrl: '' })
const taxFiles = ref([])
const taxBusy = ref(false)

// 完税记录口径：首季按季度提交近 3 个月（3 份）；入驻后每月上报，次月 15 日前完成（最多延期 15 天）
const NOW = new Date()
const taxPeriods = computed(() => completedMonths(NOW, 3))
const taxPeriodLabels = computed(() => taxPeriods.value.map(monthLabel))
const taxProofUrls = computed(() => splitTaxProofUrls(mine.value?.taxProofUrls))
const taxProofBusy = ref(false)

/** 入驻月份：月度上报只从入驻当月开始考核，避免把入驻前的月份误判为逾期 */
const onboardMonth = computed(() => {
  const t = mine.value?.createTime
  if (!t) return null
  const d = new Date(String(t).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : monthKey(d)
})

/** 当前应上报的所属期：取「上一自然月」与「入驻月份」中较晚者 */
const currentTaxPeriod = computed(() => {
  const last = lastCompletedMonth(new Date())
  const start = onboardMonth.value
  return start && start > last ? start : last
})

const currentTaxStatus = computed(() => monthlyStatus(currentTaxPeriod.value, taxProofUrls.value, new Date()))
const recentTaxStatuses = computed(() => {
  const cur = currentTaxPeriod.value
  return [cur, addMonths(cur, -1), addMonths(cur, -2)]
    .filter(p => !onboardMonth.value || p >= onboardMonth.value)
    .map(p => monthlyStatus(p, taxProofUrls.value, new Date()))
})

/** 季度内已覆盖的所属月份（仅统计上传完成且带月份的凭证） */
const coveredTaxPeriods = computed(() => {
  const used = new Set(taxFiles.value.filter(t => t.status === 'done' && t.period).map(t => t.period))
  return new Set(used)
})
const missingTaxPeriods = computed(() => taxPeriods.value.filter(p => !coveredTaxPeriods.value.has(p)))

const specialPermit = reactive({
  name: '',
  file: null,
  status: '',
  percent: 0,
  url: '',
  previewUrl: '',
  isPdf: false
})

const isTaxProofValid = computed(() => {
  if (form.taxStatus !== 1) return true
  const hasReg = Boolean(form.taxRegNo && /^[0-9A-Za-z]{15}$|^[0-9A-Za-z]{18}$|^[0-9A-Za-z]{20}$/.test(form.taxRegNo.trim()))
  // 季度口径：3 个月的记录必须齐全
  return hasReg && missingTaxPeriods.value.length === 0
})

function onCreditCodeInput(e) {
  const val = e.target.value || ''
  form.creditCode = val.toUpperCase().replace(/[^0-9A-Z]/g, '')
}

function onJoinTypeChange(val) {
  if (val === '入驻') {
    form.joinCode = ''
  }
}

function onSpecialPermitChange(e) {
  if (!e.target.value) {
    form.specialPermitName = ''
    removeSpecialPermit()
  }
}

// 统一校验每一阶段表单信息的准确性
function validateStep(stepIdx) {
  if (stepIdx === 0) {
    // 步骤 1：工商主体基础信息
    const name = form.entName ? form.entName.trim() : ''
    if (!name) {
      message.warning('请填写营业执照上的企业全称')
      return false
    }
    if (name.length < 2 || name.length > 60) {
      message.warning('企业名称长度需在 2 到 60 个字符之间')
      return false
    }

    const code = form.creditCode ? form.creditCode.trim().toUpperCase() : ''
    if (!code) {
      message.warning('请填写18位统一社会信用代码')
      return false
    }
    if (code.length !== 18 || !/^[0-9A-Za-z]{18}$/.test(code)) {
      message.warning('统一社会信用代码格式不正确（必须为18位数字或英文字母组合）')
      return false
    }

    if (!form.regCapital || Number(form.regCapital) <= 0) {
      message.warning('注册资本必须为大于 0 的数值')
      return false
    }
    if (form.regType === '个体工商户' && Number(form.regCapital) < 100000) {
      message.warning('根据平台准入标准，个体工商户注册资本需高于 10 万元')
      return false
    }

    // 平台战略邀约必须有邀约码，特约加盟必须有加盟码
    if (form.joinType === '邀约') {
      const jc = form.joinCode ? form.joinCode.trim() : ''
      if (!jc) {
        message.warning('平台战略邀约入驻必须填写平台邀约码')
        return false
      }
      if (jc.length < 4 || jc.length > 32) {
        message.warning('平台邀约码格式不正确（需为4-32位有效编码）')
        return false
      }
    } else if (form.joinType === '加盟') {
      const jc = form.joinCode ? form.joinCode.trim() : ''
      if (!jc) {
        message.warning('特约加盟入驻必须填写特约加盟码')
        return false
      }
      if (jc.length < 4 || jc.length > 32) {
        message.warning('特约加盟码格式不正确（需为4-32位有效编码）')
        return false
      }
    }

    if (form.taxStatus !== 0 && form.taxStatus !== 1) {
      message.warning('请选择是否有近半年稳定纳税记录')
      return false
    }
    return true
  }

  if (stepIdx === 1) {
    // 步骤 2：商户法人与重要财务信息
    const lp = form.legalPerson ? form.legalPerson.trim() : ''
    if (!lp) {
      message.warning('请填写法定代表人真实姓名')
      return false
    }
    if (lp.length < 2 || lp.length > 20) {
      message.warning('法定代表人姓名需为 2 到 20 个字符')
      return false
    }

    const idCard = form.legalIdCard ? form.legalIdCard.trim() : ''
    if (!idCard) {
      message.warning('请填写法定代表人二代身份证号')
      return false
    }
    if (!/(^\d{15}$)|(^\d{17}(\d|X|x)$)/.test(idCard)) {
      message.warning('法定代表人身份证号格式不正确（需为15位或18位二代身份证号）')
      return false
    }

    const account = form.bankAccount ? form.bankAccount.trim() : ''
    if (!account) {
      message.warning('请填写对公银行结算账号')
      return false
    }
    if (!/^\d{8,32}$/.test(account)) {
      message.warning('对公银行账号必须为 8-32 位纯数字')
      return false
    }

    const bank = form.bankName ? form.bankName.trim() : ''
    if (!bank || bank.length < 4) {
      message.warning('请填写完整的开户银行及具体支行名称（至少4个字符）')
      return false
    }

    const phone = form.contactPhone ? form.contactPhone.trim() : ''
    if (!phone) {
      message.warning('请填写业务负责人联系手机')
      return false
    }
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      message.warning('业务负责人联系手机格式不正确（需为11位大陆手机号）')
      return false
    }

    if (form.contactEmail && form.contactEmail.trim()) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.contactEmail.trim())) {
        message.warning('官方对公业务邮箱格式不正确')
        return false
      }
    }

    const addr = form.businessAddress ? form.businessAddress.trim() : ''
    if (!addr || addr.length < 5) {
      message.warning('请填写详细的企业经营场所与注册地址（至少5个字符）')
      return false
    }
    return true
  }

  if (stepIdx === 2) {
    // 步骤 3：资质证照与纳税证明上传（必须上传完整信息才能进行下一步）
    if (!license.url || license.status !== 'done') {
      message.warning('第三页必须上传完整信息：请上传清晰完整的营业执照正副本扫描件并等待上传完成')
      return false
    }

    if (form.taxStatus === 1) {
      const trn = form.taxRegNo ? form.taxRegNo.trim() : ''
      if (!trn) {
        message.warning('您在第一步声明了近半年有稳定纳税记录，请填写税务登记号')
        return false
      }
      if (!/^[0-9A-Za-z]{15}$|^[0-9A-Za-z]{18}$|^[0-9A-Za-z]{20}$/.test(trn)) {
        message.warning('税务登记号格式不正确（需为15、18或20位数字或大写英文字母）')
        return false
      }
      const missing = missingTaxPeriods.value
      if (missing.length) {
        message.warning(`本季度需提交近 3 个月共 3 份完税记录，还缺：${missing.map(monthLabel).join('、')}`)
        return false
      }
    } else if (form.taxRegNo && form.taxRegNo.trim()) {
      if (!/^[0-9A-Za-z]{15}$|^[0-9A-Za-z]{18}$|^[0-9A-Za-z]{20}$/.test(form.taxRegNo.trim())) {
        message.warning('税务登记号格式不正确（需为15、18或20位数字或大写英文字母）')
        return false
      }
    }
    return true
  }

  if (stepIdx === 3) {
    // 步骤 4：特殊经营许可证核验与承诺
    if (form.hasSpecialPermit) {
      const spName = form.specialPermitName ? form.specialPermitName.trim() : ''
      if (!spName || spName.length < 2) {
        message.warning('您声明具备特殊经营范围，请填写特殊经营许可证全称及编号')
        return false
      }
      if (!specialPermit.url || specialPermit.status !== 'done') {
        message.warning('具备特殊经营许可的商户，必须上传相关特许经营许可证扫描件或实拍图才能入驻')
        return false
      }
    }

    if (!agreed.value) {
      message.warning('请阅读并勾选同意商户服务入驻协议与安全声明')
      return false
    }
    return true
  }

  return true
}

function goToStep(idx) {
  if (idx === currentStep.value) return
  if (idx < currentStep.value) {
    currentStep.value = idx
    return
  }
  // 逐级校验当前步骤
  for (let s = currentStep.value; s < idx; s++) {
    if (!validateStep(s)) return
  }
  currentStep.value = idx
}

function nextStep() {
  if (!validateStep(currentStep.value)) return
  if (currentStep.value < 3) {
    currentStep.value++
  }
}

function checkBase(file, kinds) {
  if (!kinds.some(k => file.type === k || file.name.toLowerCase().endsWith(k))) {
    message.error('文件格式不符合要求')
    return false
  }
  if (file.size > MAX_MB * 1024 * 1024) { message.error(`文件不能超过 ${MAX_MB}MB`); return false }
  return true
}

// 营业执照上传
function pickLicense(file) {
  if (!checkBase(file, ['image/jpeg', 'image/png', '.jpg', '.png'])) return false
  license.name = file.name
  license.file = file
  license.previewUrl = URL.createObjectURL(file)
  uploadLicense()
  return false
}

async function uploadLicense() {
  license.status = 'uploading'
  license.percent = 0
  try {
    const data = await merchantApi.applyUpload('license', license.file, (p) => { license.percent = p })
    license.url = data.url
    license.status = 'done'
    message.success('营业执照上传成功')
  } catch (e) {
    license.status = 'error'
    message.error(e?.message || '营业执照上传失败，请重试')
  }
}

function retryLicense() { if (license.file) uploadLicense() }
function removeLicense() {
  Object.assign(license, { name: '', file: null, status: '', percent: 0, url: '', previewUrl: '' })
}

// 完税材料上传（首季按季度：近 3 个月各 1 份，共 3 份）
function pickTax(file) {
  if (!checkBase(file, ['application/pdf', 'image/jpeg', 'image/png', '.pdf', '.jpg', '.png'])) return false
  if (taxFiles.value.length >= 3) { message.warning('本季度最多 3 份（每月 1 份）完税记录，请先移除已有附件'); return false }
  const used = new Set(taxFiles.value.map(t => t.period).filter(Boolean))
  const period = taxPeriods.value.find(p => !used.has(p))
  if (!period) { message.warning('本季度 3 个月的完税记录已全部上传'); return false }
  const item = {
    uid: Date.now() + Math.random(),
    name: file.name,
    file,
    period,
    status: 'uploading',
    percent: 0,
    url: '',
    isPdf: file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
  }
  taxFiles.value.push(item)
  // 注意：push 进 ref 数组后，item 是原始对象，直接改 item.xxx 不会触发视图更新
  // （表现为最后一份上传进度永远 0%、状态永远"上传中"）。统一通过数组内的响应式代理回写。
  const patchTaxItem = (patch) => {
    const target = taxFiles.value.find(t => t.uid === item.uid)
    if (target) Object.assign(target, patch)
  }
  taxBusy.value = true
  merchantApi.applyUpload('taxProof', file, (p) => patchTaxItem({ percent: p }))
    .then(data => {
      patchTaxItem({ url: data.url, status: 'done' })
      message.success(`${monthLabel(period)} 完税记录已上传`)
    })
    .catch((err) => {
      patchTaxItem({ status: 'error' })
      message.error(err?.message || '纳税凭证上传失败')
    })
    .finally(() => { taxBusy.value = false })
  return false
}

function removeTax(i) { taxFiles.value.splice(i, 1) }

// 已入驻商户：月度完税记录上报（携带所属期，后端写入文件名；前端据所属期与上传时间判定按时/延期/逾期）
function pickMonthlyTax(file) {
  if (!checkBase(file, ['application/pdf', 'image/jpeg', 'image/png', '.pdf', '.jpg', '.png'])) return false
  taxProofBusy.value = true
  merchantApi.submitTaxProof(file, currentTaxPeriod.value)
    .then(async (data) => {
      if (data?.url) {
        message.success('本期完税记录已上报')
        try { mine.value = await merchantApi.my() } catch (e) { /* 刷新失败时保留本地状态提示 */ }
      }
    })
    .catch((err) => { message.error(err?.message || '完税记录上报失败，请重试') })
    .finally(() => { taxProofBusy.value = false })
  return false
}

// 特殊经营许可证上传
function pickSpecialPermit(file) {
  if (!checkBase(file, ['image/jpeg', 'image/png', 'application/pdf', '.jpg', '.png', '.pdf'])) return false
  specialPermit.name = file.name
  specialPermit.file = file
  specialPermit.isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
  if (!specialPermit.isPdf) {
    specialPermit.previewUrl = URL.createObjectURL(file)
  }
  uploadSpecialPermit()
  return false
}

async function uploadSpecialPermit() {
  specialPermit.status = 'uploading'
  specialPermit.percent = 0
  try {
    const data = await merchantApi.applyUpload('specialPermit', specialPermit.file, (p) => { specialPermit.percent = p })
    specialPermit.url = data.url
    specialPermit.status = 'done'
    message.success('特殊经营许可证上传成功')
  } catch (e) {
    specialPermit.status = 'error'
    message.error(e?.message || '特殊经营许可证上传失败，请重试')
  }
}

function retrySpecialPermit() { if (specialPermit.file) uploadSpecialPermit() }
function removeSpecialPermit() {
  Object.assign(specialPermit, { name: '', file: null, status: '', percent: 0, url: '', previewUrl: '', isPdf: false })
}

function maskName(str) {
  if (!str) return ''
  return str.length > 2 ? str.charAt(0) + '*' + str.charAt(str.length - 1) : str.charAt(0) + '*'
}

function maskBank(str) {
  if (!str || str.length < 8) return str || ''
  return str.slice(0, 4) + ' **** **** ' + str.slice(-4)
}

function resetFormForReapply() {
  currentStep.value = 0
  message.info('请根据整改意见修改表单并重新提交')
}

async function submit() {
  // 逐步严格核验全部输入
  for (let s = 0; s < 4; s++) {
    if (!validateStep(s)) {
      currentStep.value = s
      return
    }
  }

  loading.value = true
  try {
    const payload = {
      ...form,
      creditCode: form.creditCode.trim().toUpperCase(),
      joinCode: form.joinCode ? form.joinCode.trim() : null,
      inviteCode: form.joinType === '邀约' ? form.joinCode.trim() : null,
      franchiseCode: form.joinType === '加盟' ? form.joinCode.trim() : null,
      licenseUrl: license.url || null,
      taxProofUrls: taxFiles.value.filter(t => t.status === 'done').map(t => t.url),
      hasSpecialPermit: Boolean(form.hasSpecialPermit),
      specialPermitName: form.hasSpecialPermit ? form.specialPermitName.trim() : null,
      specialPermitUrl: form.hasSpecialPermit ? specialPermit.url : null
    }
    const r = await merchantApi.apply(payload)
    mine.value = r
    message.success(r.reviewStatus === 1 ? '入驻成功！初筛规则引擎自动过审，商户权限已激活' : '申请已成功提交，请关注审核结果')
  } catch (e) {
    message.error(e?.message || '提交失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const res = await merchantApi.my()
    if (res) mine.value = res
  } catch (e) {}
})
</script>

<style scoped>
.apply-container {
  max-width: 1240px; margin: 0 auto; padding: 24px 16px;
}
.apply-hero {
  margin-bottom: 24px; text-align: left;
}
.hero-badge {
  display: inline-flex; align-items: center; gap: 8px; padding: 4px 12px;
  background: rgba(37, 99, 235, 0.08); border: 1px solid rgba(37, 99, 235, 0.2);
  border-radius: 999px; font-size: 12px; font-weight: 600; color: #2563eb; margin-bottom: 12px;
}
.pulse-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.2); animation: pulse 2s infinite;
}
@keyframes pulse { 0% { opacity: 1; } 50% { opacity: .4; } 100% { opacity: 1; } }

.hero-title {
  font-size: 28px; font-weight: 700; color: #0f172a; margin: 0 0 10px;
  letter-spacing: -0.02em;
}
.hero-desc {
  font-size: 14px; color: #475569; max-width: 860px; line-height: 1.6; margin: 0;
}
.hero-desc strong { color: #1e293b; }

.apply-grid {
  display: grid; grid-template-columns: 1fr 380px; gap: 24px; align-items: start;
}
.glass-panel {
  background: #ffffff; border: 1px solid #e2e8f0; border-radius: 16px;
  box-shadow: 0 4px 20px -2px rgba(15, 23, 42, 0.05);
}

/* 步骤导航条 */
.step-nav {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
  padding: 12px 16px; background: #f8fafc; border-radius: 12px; margin-bottom: 24px;
}
.step-nav-item {
  display: flex; align-items: center; gap: 10px; cursor: pointer; padding: 6px;
  border-radius: 8px; transition: all .2s; opacity: .7;
}
.step-nav-item.active { opacity: 1; background: #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,.04); }
.step-nav-item.completed { opacity: 1; }
.step-num {
  width: 26px; height: 26px; border-radius: 50%; display: grid; place-items: center;
  font-size: 12px; font-weight: 700; background: #e2e8f0; color: #475569;
}
.step-nav-item.active .step-num { background: #2563eb; color: #fff; }
.step-nav-item.completed .step-num { background: #10b981; color: #fff; }
.step-title { font-size: 13px; font-weight: 600; color: #1e293b; }
.step-sub { font-size: 11px; color: #94a3b8; }

.pane-header { margin-bottom: 20px; }
.pane-header h3 { font-size: 17px; font-weight: 700; color: #0f172a; margin: 0 0 4px; }
.pane-header p { font-size: 13px; color: #64748b; margin: 0; }

/* 邀约码 / 加盟码高亮输入卡片 */
.code-input-card {
  padding: 14px 18px; border-radius: 12px; margin-bottom: 16px; transition: all .3s;
}
.code-input-card.invite-card {
  background: #f0fdf4; border: 1px solid #86efac;
}
.code-input-card.franchise-card {
  background: #eff6ff; border: 1px solid #93c5fd;
}
.field-hint-text {
  font-size: 12px; color: #475569; margin-top: 4px;
}

/* 资质材料上传卡片 */
.cert-upload-card {
  padding: 16px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; transition: all .2s;
}
.cert-upload-card.card-valid {
  border-color: #86efac; background: #f0fdf4;
}
.cert-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.required-star { color: #ef4444; font-weight: 700; margin-right: 4px; }
.cert-sub { font-size: 12px; color: #64748b; margin-top: 2px; }
.tax-status-hint { font-size: 12px; color: #ea580c; font-weight: 600; margin-left: 4px; }

.up-box {
  border: 2px dashed #cbd5e1; border-radius: 12px; padding: 16px; min-height: 120px;
  display: flex; align-items: center; justify-content: center; background: #fff; transition: all .2s;
}
.up-box.done { border-style: solid; border-color: #10b981; background: #f0fdf4; }
.up-trigger { cursor: pointer; text-align: center; padding: 12px 24px; color: #2563eb; }
.up-icon { font-size: 32px; margin-bottom: 6px; }
.up-text { font-size: 14px; font-weight: 600; color: #1e293b; }
.up-hint-text { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.up-preview { display: flex; align-items: center; gap: 16px; width: 100%; }
.up-meta { flex: 1; }
.up-name { font-size: 14px; font-weight: 600; color: #1e293b; margin-bottom: 4px; }

.tax-list { display: flex; flex-direction: column; gap: 8px; }
.tax-item {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px;
  background: #fff; border: 1px solid #e2e8f0; border-radius: 8px;
}
.tax-ico { font-size: 18px; color: #2563eb; }
.tax-name { font-size: 13px; font-weight: 500; flex: 1; }
.tax-del { color: #ef4444; font-size: 12px; cursor: pointer; }
.empty-tax { display: flex; flex-direction: column; gap: 4px; }
.empty-tax-tip { font-size: 12px; color: #94a3b8; text-align: center; padding: 8px; }

/* 季度完税记录：3 个月覆盖情况 */
.tax-period-track { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 2px 0 10px; }
.tpt-label { font-size: 12px; color: #64748b; }
.tpt-chip {
  padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600;
  color: #b45309; background: #fffbeb; border: 1px solid #fde68a;
}
.tpt-chip.ok { color: #15803d; background: #f0fdf4; border-color: #bbf7d0; }
.tax-period-tag {
  flex: none; padding: 1px 7px; border-radius: 999px;
  font-size: 11px; font-weight: 600; color: #1d4ed8;
  background: #eff6ff; border: 1px solid #bfdbfe;
}

/* 入驻后：月度完税记录上报卡片 */
.tax-compliance-card {
  margin-top: 14px; padding: 12px 14px;
  border: 1px solid #bfdbfe; border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff 0%, #eff6ff 100%);
}
.taxc-title {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 13px; font-weight: 700; color: #1e3a8a;
}
.taxc-policy { margin: 6px 0 10px; font-size: 11px; line-height: 1.6; color: #475569; }
.taxc-current {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 9px 10px; border-radius: 9px; background: #fff; border: 1px solid #dbeafe;
}
.taxc-current.is-overdue { border-color: #fecaca; background: #fff7f7; }
.taxc-current.is-late { border-color: #fed7aa; background: #fffaf3; }
.taxc-current-main { min-width: 0; }
.taxc-period { font-size: 12px; font-weight: 700; color: #0f172a; }
.taxc-deadline { margin-top: 2px; font-size: 11px; color: #64748b; line-height: 1.5; }
.taxc-timeline { display: flex; flex-direction: column; gap: 5px; margin-top: 10px; }
.taxc-chip {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 5px 8px; border-radius: 7px; font-size: 11px;
  background: #fff; border: 1px solid #e2e8f0; color: #475569;
}
.taxc-chip-period { font-weight: 600; }
.taxc-chip.is-on_time { border-color: #bbf7d0; background: #f0fdf4; color: #15803d; }
.taxc-chip.is-late { border-color: #fed7aa; background: #fffaf3; color: #c2410c; }
.taxc-chip.is-pending { border-color: #bfdbfe; background: #eff6ff; color: #1d4ed8; }
.taxc-chip.is-overdue { border-color: #fecaca; background: #fff5f5; color: #b91c1c; }
.taxc-legend { margin-top: 8px; font-size: 10px; color: #94a3b8; text-align: right; }

/* 步骤 4：特殊经营许可证问题与上传卡片 */
.special-permit-question-box {
  padding: 16px 18px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; margin-bottom: 18px;
}
.question-title-row { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
.q-icon { font-size: 24px; }
.q-title { font-size: 15px; font-weight: 700; color: #0f172a; margin: 0 0 4px; }
.q-desc { font-size: 12px; color: #64748b; line-height: 1.5; margin: 0; }
.special-permit-radios { display: flex; flex-direction: column; gap: 10px; margin-top: 8px; }
.radio-label { font-size: 13px; color: #1e293b; }

.special-permit-details-card {
  padding: 16px 18px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 12px; margin-bottom: 18px;
}
.sub-card-header {
  font-size: 14px; font-weight: 700; color: #1e40af; margin-bottom: 12px;
}

.summary-check-card {
  padding: 16px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px;
  margin-bottom: 16px;
}
.summary-title { font-size: 13px; font-weight: 700; color: #1e293b; margin-bottom: 10px; }
.summary-row { font-size: 13px; color: #475569; display: flex; justify-content: space-between; padding: 5px 0; }
.summary-row strong { color: #0f172a; }

.agreement-box { margin-bottom: 20px; font-size: 13px; }
.step-footer { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #f1f5f9; }
.step-footer.between { justify-content: space-between; }
.submit-btn { background: #2563eb; border-color: #2563eb; }

/* 状态侧边卡片 */
.status-banner {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 12px; margin-bottom: 18px;
}
.status-banner.st-1 { background: #f0fdf4; border: 1px solid #bbf7d0; color: #166534; }
.status-banner.st-2 { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; }
.status-banner.st-0 { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
.status-badge-icon {
  width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center;
  font-weight: 800; font-size: 16px; background: rgba(255, 255, 255, .9);
}
.status-main { font-size: 15px; font-weight: 700; }
.status-tip { font-size: 11px; opacity: .85; margin-top: 2px; }

.flow-title { font-size: 12px; font-weight: 700; color: #475569; margin-bottom: 12px; }
.status-progress { margin-bottom: 20px; }

.security-data-card {
  padding: 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; margin-bottom: 16px;
}
.sec-card-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-weight: 700; font-size: 12px; color: #334155; }
.sec-item { display: flex; justify-content: space-between; font-size: 12px; padding: 4px 0; border-bottom: 1px dashed #e2e8f0; }
.sec-item:last-child { border-bottom: none; }
.sec-item .k { color: #64748b; }
.sec-item .v { color: #0f172a; font-weight: 600; }
.font-mono { font-family: monospace; }

.reject-alert {
  padding: 12px; background: #fff1f2; border: 1px solid #fecdd3; border-radius: 10px;
}
.reject-title { font-size: 12px; font-weight: 700; color: #be123c; margin-bottom: 4px; }
.reject-msg { font-size: 12px; color: #9f1239; line-height: 1.5; }

.empty-status { text-align: center; padding: 24px 12px; color: #64748b; }
.empty-icon { font-size: 40px; margin-bottom: 10px; }
.empty-status h4 { font-size: 15px; font-weight: 700; color: #1e293b; margin: 0 0 6px; }
.empty-status p { font-size: 12px; line-height: 1.6; margin: 0; }

.policy-list { padding-left: 18px; margin: 0; font-size: 12px; color: #475569; line-height: 1.8; }

@media (max-width: 900px) {
  .apply-grid { grid-template-columns: 1fr; }
  .step-nav { grid-template-columns: repeat(2, 1fr); }
}
/* 手机竖屏：表单栅格统一单列，步骤条收紧，避免输入框被压到无法阅读 */
@media (max-width: 767px) {
  .apply-container { padding: 16px 0; }
  .hero-title { font-size: 23px; }
  .hero-desc { font-size: 13px; }
  .apply-grid { gap: 16px; }
  .step-nav { padding: 10px; gap: 8px; margin-bottom: 18px; }
  .step-nav-item { gap: 7px; padding: 4px; align-items: flex-start; }
  .step-title { font-size: 12px; }
  .step-sub { font-size: 10px; line-height: 1.4; }
  .apply-form :deep(.ant-col) { flex: 0 0 100% !important; max-width: 100% !important; }
}
</style>
