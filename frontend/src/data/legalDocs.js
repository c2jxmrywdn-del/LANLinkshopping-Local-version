// LANLinkshopping 法律文档中心数据源（中英双语）。
// 结构：每个文档 { key, title, updated, sections: [{ h, paras: [p], bullets: [b] }] }
// 每个 h/paras/b 元素均为 { zh, en }；LegalCenter.vue 按当前语言渲染。
// 注意：内容为演示平台的合规文本骨架，正式上线前应由法务复核。

const doc = (key, titleZh, titleEn, updated, sections) => ({
  key, updated,
  title: { zh: titleZh, en: titleEn },
  sections
})

const S = (hZh, hEn, ...items) => ({
  h: { zh: hZh, en: hEn },
  paras: items.filter(i => typeof i.p === 'object').map(i => i.p),
  bullets: items.filter(i => typeof i.b === 'object').map(i => i.b)
})
const P = (zh, en) => ({ p: { zh, en } })
const B = (zh, en) => ({ b: { zh, en } })

export const LEGAL_DOCS = [
  doc('terms', '用户服务协议', 'Terms of Service', '2026-10-29', [
    S('一、协议的接受与效力', '1. Acceptance and Effect',
      P('欢迎使用 LANLinkshopping（以下简称"本平台"）。本协议是您与本平台运营方之间关于使用本平台全部服务（包括商城交易、企业账期、营销活动、直播、智能客服等）的法律约定。您注册账户、点击同意或实际使用本平台服务，即视为已阅读并同意本协议全部内容。',
        'Welcome to LANLinkshopping (the "Platform"). These Terms constitute the legal agreement between you and the Platform operator regarding all services (marketplace transactions, enterprise credit terms, campaigns, live commerce, AI assistant, etc.). Registering an account, clicking "agree", or actually using the Platform means you have read and accepted these Terms in full.'),
      P('本平台为演示性 B2B 电商平台：平台内的商品、商户、行情与部分交易数据为演示数据，支付与外部服务对接使用沙盒环境。正式商用版本上线前，本协议作为产品规则与体验约定执行。',
        'The Platform is a demonstration B2B e-commerce platform: products, merchants, market data and part of transaction data are demo data; payment and external service integrations use sandbox environments. Until a production version is released, these Terms serve as product rules and experience conventions.')),
    S('二、账户注册与企业实名', '2. Registration and Enterprise Verification',
      P('您应以真实、准确、完整的信息注册，并确保在信息变更时及时更新。采购商与商户均需完成企业实名认证后，方可使用询价、下单、账期、直播带货等企业级功能。',
        'You shall register with truthful, accurate and complete information and keep it updated. Both buyers and merchants must complete enterprise verification before using enterprise features such as quotation, ordering, credit terms and live commerce.'),
      B('每位用户仅可注册一个主账户，不得出借、转让或与他人共用账户；', 'Each user may register only one primary account; lending, transferring or sharing accounts is prohibited;'),
      B('平台有权对认证材料进行核验，并对存疑账户采取限制、暂停或注销措施；', 'The Platform may verify verification materials and restrict, suspend or cancel suspicious accounts;'),
      B('账户注销后，平台将依照《隐私政策》对个人信息进行删除或匿名化处理。', 'After account cancellation, personal information will be deleted or anonymized in accordance with the Privacy Policy.')),
    S('三、商品与交易规则', '3. Products and Transactions',
      P('平台上的商品由入驻商户发布并对其信息的真实性、合法性负责；平台提供交易撮合、订单管理、物流信息聚合与售后协调服务。您应基于企业采购需求审慎下单，确认规格、价格、账期与发票安排后再提交订单。',
        'Products on the Platform are published by merchants who are responsible for their authenticity and legality; the Platform provides matching, order management, logistics aggregation and after-sales coordination. You should place orders prudently based on enterprise procurement needs, confirming specifications, prices, credit terms and invoicing before submitting.'),
      P('订单成立、付款、发货、验收与退换货按订单页与商户店铺公示的规则执行；平台规则与商户承诺不一致时，以对您更有利者为准。',
        'Order formation, payment, delivery, acceptance and returns follow the rules published on order pages and merchant stores; where Platform rules conflict with merchant commitments, the more favorable one to you prevails.')),
    S('四、支付、企业账期与资金安全', '4. Payment, Credit Terms and Fund Safety',
      P('平台支持余额、在线支付、对公转账与企业账期等多种结算方式。申请账期授信时，您授权平台在必要范围内收集并核验企业资质与经营信息；授信额度、账期天数与还款计划以平台审批结果与账单页面展示为准。',
        'The Platform supports balance, online payment, corporate transfer and enterprise credit terms. When applying for credit, you authorize the Platform to collect and verify enterprise qualifications to the necessary extent; approved limits, term days and repayment plans follow platform approval and bill pages.'),
      P('您应按时清偿账期款项；逾期可能影响授信额度、产生违约金并记入平台信用记录。演示环境中的支付与账期操作不产生真实资金流转。',
        'You shall repay credit terms on time; overdue payments may affect limits, incur penalties and be recorded in Platform credit history. In the demo environment, payment and credit operations do not move real funds.')),
    S('五、商户经营与直播规范', '5. Merchant Operations and Live Commerce',
      B('商户应保证商品来源合法、描述真实，不得发布假冒、侵权或禁售商品；', 'Merchants shall ensure lawful sourcing and truthful descriptions; counterfeit, infringing or prohibited products are not allowed;'),
      B('直播内容应遵守法律法规与公序良俗，不得虚假宣传、诱导欺诈或展示违规内容；', 'Live streaming shall comply with laws and public order; false advertising, fraud or violations are prohibited;'),
      B('平台对违规行为有权采取下架、限流、暂停直播、冻结结算直至清退等措施，并保留追责权利。', 'For violations, the Platform may delist products, throttle traffic, suspend streams, freeze settlements or expel accounts, and reserves the right to pursue liability.')),
    S('六、AI 智能服务的使用', '6. AI-Powered Services',
      P('平台提供 AI 客服、知识库问答、价格情报解读等智能服务。AI 生成内容可能存在错误或滞后，仅供参考，不构成平台对事实的确认或任何承诺；涉及交易、资金、法律的重要决策，请以平台页面公示信息与人工客服确认为准。',
        'The Platform provides AI customer service, knowledge-base Q&A and price intelligence interpretation. AI-generated content may be inaccurate or outdated, is for reference only, and does not constitute confirmation or commitment; for decisions involving transactions, funds or law, rely on information published on Platform pages and human support.'),
      P('默认情况下，智能服务优先使用本地部署模型处理；当您主动开启联网搜索、网页解读或外部模型兜底等功能时，必要的问题上下文才会传输至对应外部服务，具体见《隐私政策》。',
        'By default, AI services are processed by locally deployed models; only when you actively enable web search, page reading or external-model fallback will necessary question context be transmitted to the corresponding external services, as detailed in the Privacy Policy.')),
    S('七、用户行为规范', '7. User Conduct',
      B('不得利用平台从事违法违规活动，不得发布、传播违法违规信息；', 'Do not use the Platform for illegal activities or publish unlawful information;'),
      B('不得以技术手段干扰平台正常运行、爬取非授权数据或攻击系统安全；', 'Do not interfere with normal operation, crawl unauthorized data or attack system security;'),
      B('不得恶意下单、刷单、套取优惠或损害其他用户与商户的合法权益。', 'No malicious ordering, fake transactions, coupon abuse or harm to other users and merchants.')),
    S('八、协议的变更与终止', '8. Amendments and Termination',
      P('平台可根据业务与法规变化修订本协议，并在本页面公告修订内容；重大变更将以站内消息等方式提示。您继续使用服务视为接受修订；如不同意，可停止使用并申请注销账户。',
        'The Platform may revise these Terms as business and regulations evolve and will announce changes on this page; material changes will be notified via in-site messages. Continued use means acceptance; otherwise you may stop using the Platform and request account cancellation.')),
    S('九、法律适用与争议解决', '9. Governing Law and Disputes',
      P('本协议适用中华人民共和国法律。因本协议产生的争议，双方应友好协商；协商不成的，任何一方可向平台运营方所在地有管辖权的人民法院提起诉讼。',
        'These Terms are governed by the laws of the People\'s Republic of China. Disputes shall be resolved through friendly negotiation first; failing that, either party may bring suit before a competent court where the Platform operator is located.')),
  ]),

  doc('privacy', '隐私政策', 'Privacy Policy', '2026-10-29', [
    S('一、我们收集的信息', '1. Information We Collect',
      B('账户信息：手机号、昵称、头像、登录凭证与登录日志；', 'Account: phone number, nickname, avatar, credentials and login logs;'),
      B('企业信息：营业执照、资质证照、税务与开户信息（仅企业认证、账期授信与合规所需）；', 'Enterprise: business licenses, qualifications, tax and bank details (only for verification, credit terms and compliance);'),
      B('交易信息：订单、收货地址、发票信息、账期账单与支付状态；', 'Transactions: orders, addresses, invoicing, credit bills and payment status;'),
      B('使用信息：设备与浏览器特征、页面访问记录、搜索与浏览偏好、客服与 AI 对话内容。', 'Usage: device/browser characteristics, page visits, search and browsing preferences, support and AI conversations.')),
    S('二、我们如何使用信息', '2. How We Use Information',
      B('完成注册认证、订单履约、账期风控与售后处理；', 'Registration, fulfillment, credit risk control and after-sales;'),
      B('保障账户与交易安全，识别欺诈与违规行为；', 'Account and transaction security, fraud and violation detection;'),
      B('改进产品体验、推荐相关商品与活动（可经 Cookie 偏好关闭分析类处理）；', 'Product improvement and relevant recommendations (analytics can be disabled via Cookie preferences);'),
      B('在获得您同意的范围内发送服务通知与营销信息，您可随时退订。', 'Service notices and marketing within your consent, unsubscribable at any time.')),
    S('三、AI 与自动化处理', '3. AI and Automated Processing',
      P('平台的智能客服、知识库与价格解读功能默认由本地部署的开源模型处理，相关对话与检索在平台基础设施内完成。当您主动启用联网搜索、网页内容解读或"外部模型兜底"时，为回答问题所必需的问题文本与平台知识库检索片段会传输至相应的外部模型服务商；传输内容不包含您的支付凭证、账户密码等敏感凭证信息。',
        'AI customer service, knowledge base and price interpretation are processed by locally deployed open-source models by default; conversations and retrieval stay within Platform infrastructure. When you actively enable web search, page reading or external-model fallback, the question text and retrieved knowledge snippets necessary for answering are transmitted to the corresponding external model providers; transmitted content excludes sensitive credentials such as payment tokens and passwords.'),
      P('您可以随时在提问时不启用上述功能（不勾选相应开关），或联系平台关闭账户级 AI 增强能力。',
        'You may simply not enable those features when asking, or contact the Platform to disable account-level AI enhancement.')),
    S('四、信息的共享与披露', '4. Sharing and Disclosure',
      B('商户履约所必需：收货人与地址信息将提供给对应商户与物流服务方；', 'Fulfillment necessity: recipient and address information is shared with the relevant merchant and logistics providers;'),
      B('法律要求：依法律法规或有权机关要求进行的披露；', 'Legal requirements: disclosure as required by laws or authorities;'),
      B('平台承诺：不会向任何第三方出售您的个人信息，不会用于与承诺目的无关的用途。', 'Commitment: we never sell your personal information or use it beyond stated purposes.')),
    S('五、存储与安全保护', '5. Storage and Security',
      P('个人信息存储于平台数据库与缓存系统，传输采用加密通道；渠道密钥等敏感配置以加密算法存储并定期轮换。平台实施访问控制、操作审计与数据备份等安全措施，安全事件的处置与通知遵循适用法律要求。',
        'Personal information is stored in Platform databases and caches, transmitted over encrypted channels; sensitive configurations such as channel keys are encrypted and rotated. Access control, audit logging and backup are in place; security incident handling and notification follow applicable laws.')),
    S('六、Cookie 与同类技术', '6. Cookies and Similar Technologies',
      P('平台使用必要 Cookie 维持登录与交易流程，并可选启用偏好与分析类 Cookie。您可以在"隐私与 Cookie"偏好中心随时查看、调整与撤回授权，详见《Cookie 政策》。',
        'The Platform uses necessary cookies to maintain login and transactions, with optional preference and analytics cookies. You may review, adjust and withdraw consent anytime in the Privacy & Cookie preference center; see the Cookie Policy.')),
    S('七、您的权利', '7. Your Rights',
      B('查询、复制与更正您的个人信息；', 'Access, copy and correct your personal information;'),
      B('删除个人信息、撤回同意与注销账户；', 'Delete information, withdraw consent and cancel your account;'),
      B('对自动化决策（如风控拦截）要求人工复核与说明。', 'Request human review of automated decisions such as risk-control blocks.')),
    S('八、政策更新与联系我们', '8. Updates and Contact',
      P('本政策可能适时更新，重大变更将通过站内公告提示。如需行使权利或咨询隐私事项，可通过"关于我们"页面的联系方式与平台取得联系。',
        'This Policy may be updated from time to time with material changes announced in-site. To exercise rights or raise privacy questions, contact the Platform via the channels on the About page.'))
  ]),

  doc('cookies', 'Cookie 政策', 'Cookie Policy', '2026-10-29', [
    S('一、什么是 Cookie', '1. What Are Cookies',
      P('Cookie 与同类技术（LocalStorage 等）是网站存储在您浏览器中的小型数据文件，用于维持会话、记住偏好与统计访问。平台不将 Cookie 用于识别您个人身份之外的用途。',
        'Cookies and similar technologies (LocalStorage, etc.) are small files stored in your browser to maintain sessions, remember preferences and analyze visits. The Platform does not use them beyond identifying your session and preferences.')),
    S('二、平台使用的 Cookie 分类', '2. Categories We Use',
      B('必要 Cookie：登录态、会话安全、购物车与核心交易流程；始终启用，无法关闭；', 'Necessary: login state, session security, cart and core transactions; always on;'),
      B('偏好 Cookie：主题、语言、字号与界面选择；', 'Preferences: theme, language, font size and interface choices;'),
      B('分析 Cookie：页面访问、商品浏览与采购流程的匿名或聚合分析；', 'Analytics: anonymous or aggregated analysis of visits, browsing and procurement flows;'),
      B('营销 Cookie：活动推广与营销效果衡量；平台当前默认不启用第三方广告追踪。', 'Marketing: campaign measurement; third-party ad tracking is disabled by default.')),
    S('三、第三方 Cookie', '3. Third-Party Cookies',
      P('当前平台仅在必要场景使用自有一类 Cookie，不接入第三方广告或社交登录追踪。若未来引入第三方组件，将在本政策与偏好中心同步更新并事先征求同意。',
        'Currently the Platform only uses first-party cookies in necessary scenarios and integrates no third-party ad or social-login tracking. Future third-party components will be reflected here and in the preference center with prior consent.')),
    S('四、如何管理 Cookie', '4. Managing Cookies',
      P('您可以在"隐私与 Cookie"偏好中心逐类开启或关闭（必要类除外），也可以通过浏览器设置清除或阻止 Cookie。清除必要 Cookie 后，需要重新登录并可能影响交易流程的正常使用。',
        'You can toggle each category (except necessary) in the Privacy & Cookie preference center, or clear/block cookies in browser settings. Clearing necessary cookies requires re-login and may affect transaction flows.'))
  ]),

  doc('platform', '平台说明', 'Platform Overview', '2026-10-29', [
    S('一、平台定位', '1. Positioning',
      P('LANLinkshopping 是聚合型 B2B 电商平台，将采购商、商户、物流合作方与资金服务连接在同一网络中，覆盖商城交易、营销活动、直播带货、企业账期与价格情报等完整业务链路。',
        'LANLinkshopping is an aggregated B2B e-commerce platform connecting buyers, merchants, logistics partners and funding services, covering marketplace transactions, campaigns, live commerce, enterprise credit terms and price intelligence.')),
    S('二、五种身份，一套协同', '2. Five Roles, One Network',
      B('采购商：询价、下单、账期申请与售后跟踪；', 'Buyers: quotation, ordering, credit applications and after-sales;'),
      B('商户：商品经营、订单履约、直播带货与流量运营；', 'Merchants: product management, fulfillment, live commerce and traffic operations;'),
      B('平台运营：入驻审核、活动治理、争议协调与内容安全；', 'Platform operations: onboarding review, campaign governance, dispute resolution and content safety;'),
      B('物流与资金服务方：运单协同、账期与结算支持；', 'Logistics and funding partners: waybill collaboration, credit and settlement support;'),
      B('平台员工（管理端）：审计、客服质检与知识库运营。', 'Staff (admin): auditing, support quality inspection and knowledge-base operations.')),
    S('三、演示环境说明', '3. Demo Environment',
      P('当前环境为演示与开发用途：商品、行情与部分用户数据为演示数据；支付通道、短信与外部模型调用使用沙盒或本地部署服务。演示环境中的交易与账期操作不产生真实资金流转，正式商用版本将另行发布公告与协议。',
        'The current environment is for demonstration and development: products, market data and some user data are demo data; payment, SMS and external model calls use sandbox or locally deployed services. Transactions and credit operations here move no real funds; the production release will come with separate announcements and terms.')),
    S('四、AI 能力说明', '4. AI Capabilities',
      P('平台内置本地部署的开源大模型（文本与视觉）与向量知识库，为客服问答、意图理解与价格情报解读提供智能支持；在您主动开启时可通过联网搜索与外部模型增强回答。相关数据处理规则见《隐私政策》。',
        'The Platform embeds locally deployed open-source models (text and vision) with a vector knowledge base powering support Q&A, intent understanding and price interpretation; web search and external models enhance answers upon your opt-in. Data handling follows the Privacy Policy.')),
    S('五、服务可用性', '5. Availability',
      P('平台按"现状"提供服务，可能因系统维护、升级或不可抗力出现中断或调整。计划内的维护将尽可能提前公告。',
        'Services are provided "as is" and may be interrupted for maintenance, upgrades or force majeure; planned maintenance will be announced in advance where possible.'))
  ]),

  doc('disclaimer', '免责声明', 'Disclaimer', '2026-10-29', [
    S('一、行情与价格信息', '1. Market and Price Information',
      P('价格情报台展示的行情走势、行业基准与资讯摘要来自公开渠道整理与平台演示数据，可能存在延迟、遗漏或误差，仅供采购决策参考，不构成任何投资建议或价格承诺。您应自行核实后再做商业决策。',
        'Market trends, industry benchmarks and news digests shown in the Price Intelligence console are compiled from public sources and demo data, and may be delayed, incomplete or erroneous. They are for procurement reference only and constitute no investment advice or price commitment; verify independently before business decisions.')),
    S('二、商品与交易主体', '2. Products and Transaction Parties',
      P('平台为交易撮合与技术服务平台，非商品销售主体或交易当事人。商品质量、交付、发票与售后责任由发布商品的商户承担；因商品或商户行为产生的争议，平台将协助协调但不承担替代赔偿责任（法律另有规定除外）。',
        'The Platform is a matching and technical service provider, not a seller or transaction party. Product quality, delivery, invoicing and after-sales responsibilities rest with the publishing merchant; for disputes arising from products or merchant conduct, the Platform assists coordination but bears no substitute liability unless required by law.')),
    S('三、AI 生成内容', '3. AI-Generated Content',
      P('平台内由 AI 生成的回答、摘要与建议可能存在错误、遗漏或滞后，不构成专业意见（法律、财务、税务、投资等）或平台承诺。重要决策请以官方页面信息与人工服务为准，并自行核实。',
        'AI-generated answers, summaries and suggestions may be wrong, incomplete or outdated, and constitute no professional advice (legal, financial, tax, investment) or Platform commitment. Verify with official pages and human service for important decisions.')),
    S('四、外部链接与第三方服务', '4. External Links and Third-Party Services',
      P('平台内容可能包含指向外部网站或服务的链接（如资讯原文、合作方页面）。外部内容不在平台控制范围内，其准确性与合规性由对应方负责，您访问外部链接时应自行注意信息安全。',
        'Platform content may contain links to external websites or services (e.g. news sources, partner pages). External content is beyond Platform control; its accuracy and compliance are the responsibility of the corresponding party. Exercise caution when visiting external links.')),
    S('五、不可抗力与责任限制', '5. Force Majeure and Limitation of Liability',
      P('因自然灾害、网络攻击、政策调整、电信故障等不可抗力或非平台过错原因导致服务中断或数据损失的，平台在法律允许范围内免于承担责任，并将尽合理努力减少影响、恢复服务。',
        'The Platform is not liable, to the extent permitted by law, for service interruption or data loss caused by natural disasters, cyberattacks, policy changes, telecom failures or other force majeure events beyond its fault, and will make reasonable efforts to mitigate and restore.')),
    S('六、知识产权', '6. Intellectual Property',
      P('平台的设计、文案、代码、商标与页面素材归平台运营方或相应权利人所有；未经许可，不得复制、爬取或用于商业用途。平台展示的商品图文归相应商户或权利人所有。',
        'The Platform\'s design, copy, code, trademarks and page assets belong to the operator or respective rights holders; copying, crawling or commercial use without permission is prohibited. Product images and descriptions belong to the respective merchants or rights holders.'))
  ]),
]

export function legalDocByKey(key) {
  return LEGAL_DOCS.find(d => d.key === key) || null
}

export function legalTitle(doc, lang) {
  const t = doc.title
  return (lang === 'zh' ? t.zh : t.en) || t.zh
}
