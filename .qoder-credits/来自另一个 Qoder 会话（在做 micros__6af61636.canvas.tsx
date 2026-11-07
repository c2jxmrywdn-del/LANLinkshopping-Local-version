import {
  BarChart,
  Callout,
  ChartComparisonGrid,
  ChartContainer,
  H1,
  LineChart,
  MetricsGrid,
  PieChart,
  ReportSection,
  ReportShell,
  Stack,
  Table,
  Text,
  type MetricItem,
} from "qoder/canvas";

// —— 报告数据契约（由 scripts/lib/breakdown.mjs 产出，plugin 注入）——
interface CatRow {
  key: string;
  label: string;
  tokens: number;
  share: number;
}
interface ToolRow {
  tool: string;
  tokens: number;
  calls: number;
  /** 平均每次调用带来的重发 token；无调用记录时 null（不给 0） */
  perCall?: number | null;
  share: number;
}
interface FileRow {
  attr: string;
  label: string;
  kind: string;
  tool: string | null;
  selfTokens: number;
  reads: number;
  trips: number;
  billed: number;
  /** 单次读取的总代价（含此后每一程重发）；reads=0 时 null */
  perRead?: number | null;
  share: number;
}
interface ReqRow {
  index: number;
  time: string | null;
  ratio: number;
  inputTokens: number;
  /** 代理或转录任一有真值即非 null；两者都没则 null（不写 0） */
  outputTokens?: number | null;
  /** proxy=代理实测 / transcript-tokens=转录 input_tokens / transcript-ratio=转录 ratio×window */
  usageSource?: "proxy" | "transcript-tokens" | "transcript-ratio" | "transcript";
  credits: number;
  originalCredits: number;
  afterCompact: boolean;
}
interface CatChild {
  tool: string | null;
  label: string;
  kind: string | null;
  tokens: number;
  share: number;
  catShare: number;
  trips: number;
}
interface CatDetail {
  key: string;
  label: string;
  tokens: number;
  share: number;
  children: CatChild[];
}
interface SubagentRow {
  agentId: string;
  agentType: string | null;
  description: string | null;
  toolUseId: string | null;
  roundTrips: number;
  billedInputTokens: number;
  peakContextRatio: number;
  credits: number;
  originalCredits: number;
  error: string | null;
}
interface SubagentTotals {
  roundTrips: number;
  billedInputTokens: number;
  credits: number;
  originalCredits: number;
}
interface Subagents {
  scanned: boolean;
  dir: string | null;
  /** 试过的候选目录；用于区分「真没子代理」与「路径没找对」。 */
  probedPaths?: string[];
  /** ok=已汇总 / no-dir=无子代理目录 / dir-empty=目录在但无 agent 文件 / no-usage=有文件但转录无 usage / error=读取抛错 */
  reason?: string;
  count: number;
  items: SubagentRow[];
  totals: SubagentTotals;
  combined: SubagentTotals;
}
/** 逐项可用性：报告里每个数字到底是模型上报的真值、按真值推导、回退默认、手工录入，还是本地根本没有。
 *  v2 旧报告没这个对象，故全部字段可选，读取时一律走默认值（旧报告只可能来自桌面端富转录）。 */
type Avail = "measured" | "derived" | "fallback" | "manual" | "unavailable";
interface Availability {
  credits?: Avail;
  roundTrips?: Avail;
  contextRatio?: Avail;
  tokens?: Avail;
  /** 输出 token：代理或转录 output_tokens 任一有真值即 measured */
  outputTokens?: Avail;
  /** 缓存命中 token：代理或转录 cache_read_input_tokens 任一有真值即 measured */
  cachedTokens?: Avail;
  categoryShare?: Avail;
  toolShare?: Avail;
  fileShare?: Avail;
  systemPrompt?: Avail;
  contextWindow?: Avail;
  compactions?: Avail;
  compactionCost?: Avail;
  model?: Avail;
  title?: Avail;
  toolCalls?: Avail;
  fileReads?: Avail;
  userTurns?: Avail;
  /** 用户压缩阈值：manual 覆盖 → manual；默认 200K → fallback */
  userContextLimit?: Avail;
}
/** 官方 UI 真值（手工录入，来自 .qoder-credits/overrides/<sessionId>.json）。
 *  IDE 端转录不含 usage，本地算不出 Credits，这是唯一的真值通道；绝不覆盖 totals，只并列展示。 */
interface ManualTruth {
  file?: string;
  credits: number | null;
  originalCredits: number | null;
  model: string | null;
  startedAt: string | null;
  endedAt: string | null;
  durationMin: number | null;
  note: string | null;
  /** 手工锁定模型窗口（如 qwen3-max=1000000），优先于 runtime-config / 反推 / fallback */
  contextWindow?: number | null;
  /** 手工指定 Qoder 压缩触发阈值（默认 200000），驱动 peakUserAdvice */
  userContextLimit?: number | null;
  /** 本地 credits ÷ 官方真值。有子代理时拿 combined 比（官方 UI 扣费 = 主链 + 子代理），
   *  否则覆盖率会被系统性低估（实测 ca2f7834：主链比 0.46、combined 比 0.89）。 */
  localCoverage?: number | null;
  /** main=只比主链 / combined=比主链+子代理 */
  localScope?: "main" | "combined";
  /** 参与对账的本地 credits（按 localScope 取 totals.credits 或 subagents.combined.credits） */
  localCredits?: number | null;
}
/** 峰值占比的「该不该压」结论。阈值算在数据层（breakdown.mjs peakAdvice），
 *  这里只管展示——否则 Canvas 与终端各持一套阈值迟早走偏。tone 直接喂 Callout。 */
interface PeakAdvice {
  level: "low" | "mid" | "sweet" | "high" | "over";
  tone: "info" | "success" | "warning" | "danger";
  text: string;
}
/** 一次压缩 = 一笔普通模型调用（整份上下文当 prompt、摘要当 completion），是会话里单笔最贵的开销。
 *  pre/post 是客户端自估口径；proxy 是能在代理日志里唯一对上时回填的供应商实测值，对不上就 null。 */
interface CompactionEvent {
  index: number;
  at: string | null;
  trigger: string | null;
  preTokens: number | null;
  postTokens: number | null;
  messagesSummarized: number | null;
  /** 压缩后首轮的往返序号与实测输入 = 压缩把上下文压到的地板 */
  nextRequestIndex: number | null;
  nextInputTokens: number | null;
  savedTokens: number | null;
  /** 数据层归一后的生效值：有代理实测就用实测，否则是客户端自估。
   *  渲染端直接取这两个，别自己按 proxy 分支——否则会与合计的口径分叉。 */
  effectiveInputTokens?: number | null;
  effectiveOutputTokens?: number | null;
  proxy?: {
    promptTokens: number;
    completionTokens: number;
    cachedTokens: number | null;
    ms: number | null;
    matchedBy: string;
  } | null;
}
interface CompactionCost {
  count: number;
  items: CompactionEvent[];
  /** measured = items 里有几笔拿到了供应商实测（其余为客户端自估） */
  totals: { preTokens: number; postTokens: number; savedTokens: number; measured?: number };
}
/** 链路健康度：代理覆盖率 + 最近记录时间 + 最近若干笔的三方归因计数。
 *  逐笔明细属于排障（CLI --request-log），报告只给一行结论。 */
interface LinkHealth {
  matched: number;
  requests: number;
  coverage: number;
  /** proxy / transcript-tokens / transcript-ratio / mixed / transcript（v3 旧报告）/ null */
  usageSource?: "proxy" | "transcript-tokens" | "transcript-ratio" | "mixed" | "transcript" | null;
  /** 三档拆分：代理实测 / 转录 input_tokens / 转录 ratio×window */
  breakdown?: { proxy: number; transcriptTokens: number; transcriptRatio: number };
  logRecords?: number;
  lastRecordAt?: string | null;
  /** 参与归因的最近笔数；0 = 运行中的代理是旧版或还没记到诊断 */
  recentRequests?: number;
  proxyErrors?: number | null;
  upstreamRejected?: number | null;
  aborted?: number | null;
  truncated?: number | null;
  noUsage?: number | null;
  slowestMs?: number | null;
  /** 被更新流量推翻的那条陈旧拉起失败记录；null = 没有 */
  supersededFailure?: { error: string; port?: number; at?: string | null } | null;
}
interface Report {
  schemaVersion: number;
  generatedAt: string;
  pluginVersion?: string;
  /** 转录来自哪一代客户端：桌面端富转录有 usage 真值，IDE 端精简转录没有。v2 旧报告无此字段。 */
  source?: "desktop-rich" | "ide-lite" | "unknown";
  /** usage 数字的来源：proxy=代理实测 / transcript-tokens=转录 input_tokens 真值 /
   *  transcript-ratio=转录 ratio×window 推导 / mixed=多档混合 / transcript=v3 旧报告兼容。无 usage 时 null。 */
  usageSource?: "proxy" | "transcript-tokens" | "transcript-ratio" | "mixed" | "transcript" | null;
  /** 可直接粘贴执行的插件入口命令（generate.mjs 用 proxy.mjs 的 selfCmd() 注入）。
   *  Windows 上是插件自带启动器的绝对路径，不要求用户装 Node；模板是静态文本，拿不到就只能硬编码。 */
  selfCmd?: string | null;
  /** 链路健康度一行结论（逐笔明细留给 CLI --request-log） */
  linkHealth?: LinkHealth | null;
  /** 代理 join 诊断（lib/proxylog.mjs）+ 配置/状态（generate.mjs 注入） */
  proxy?: {
    matched: number;
    requests: number;
    /** 三档拆分：代理实测 / 转录 input_tokens / 转录 ratio×window */
    breakdown?: { proxy: number; transcriptTokens: number; transcriptRatio: number };
    logPath?: string;
    logExists?: boolean;
    logRecords?: number;
    /** config.json 里 upstream 合法 = 用户已配置代理 */
    configured?: boolean;
    enabled?: boolean;
    port?: number;
    /** 最近一条代理记录的时间；null = 从无流量 */
    lastRecordAt?: string | null;
    /** 已配置但长期零流量（多半已改回官方模型）——软提示可 --stop-proxy */
    dormant?: boolean;
    /** 最近一次自动拉起失败（如端口被占）；ok 时为 null */
    status?: { error: string; port?: number; at?: string | null } | null;
    /** status 那条失败记录是否已被更新的流量推翻（代理在它之后还记到了流量）。
     *  status.json 只写不清，陈旧失败会把用户推去改本来正确的 Base URL，故必须判掉。 */
    statusSuperseded?: boolean;
  };
  availability?: Availability;
  manual?: ManualTruth | null;
  session: {
    id: string | null;
    title: string | null;
    /** custom-title / first-user / session-id —— 令产物文件名可解释 */
    titleSource?: string;
    model: string | null;
    /** runtime-config / manual / unavailable */
    modelSource?: string;
    cwd: string | null;
    turns: number;
    roundTrips: number;
    compactions: number;
    startedAt: string | null;
    endedAt: string | null;
  };
  context: {
    contextWindow: number;
    /** runtime-config=实测 / derived-from-usage=从 usage 反推 / manual=ManualTruth 手工锁定 /
     *  fallback=读不到静默回退 200000 / caller=调用方传入（旧枚举，兼容）。
     *  报告里每个 token 数字都要乘它，回退时必须标出来。 */
    contextWindowSource?: string;
    systemPromptTokens: number;
    netContextTokens: number;
    /** 峰值 = 当前窗口口径：自最近一次压缩起算，压缩边界处归零重新累积 */
    peakContextTokens: number;
    peakContextRatio: number;
    /** 本场历史峰值（跨压缩）；整场没压缩过时与上面相等 */
    peakSessionTokens?: number;
    peakSessionRatio?: number;
    /** 历史峰值是否值得单独交代（与当前窗口峰值相差 ≥1 个百分点），渲染端直接取用不再自判 */
    peakSessionNotable?: boolean;
    /** 峰值占比的「该不该压」结论（对模型窗口）；无有效占比时 null */
    peakAdvice?: PeakAdvice | null;
    /** 用户压缩阈值（Qoder 自动触发点），与模型窗口独立。默认 200000，ManualTruth 可覆盖 */
    userContextLimit?: number;
    /** default=内置 200K / manual=overrides 手写 / config=插件配置 / derived=实测反推 */
    userContextLimitSource?: "default" | "manual" | "config" | "derived";
    /** peakContextTokens ÷ userContextLimit */
    peakUserRatio?: number;
    /** 对用户阈值的「快自动压缩了吗」结论；与 peakAdvice 可矛盾（模型窗口未满但用户阈值已超） */
    peakUserAdvice?: PeakAdvice | null;
    /** v3.2 当前占用头条口径：段内单调递增 ⇒ 峰值≡当前，故以 netContextTokens 作头条，peak 退灰字 */
    currentContextTokens?: number;
    netUserRatio?: number;
    netUserAdvice?: PeakAdvice | null;
    netWindowRatio?: number;
    /** v3.2 自动压缩自校准：本场 trigger=auto 的实际触发点（无需外部配置）与「阈值是否被强制」判定 */
    observedAutoCompactions?: number;
    observedAutoTriggerTokens?: number | null;
    autoTriggerRatio?: number | null;
    thresholdNotEnforced?: boolean;
  };
  totals: {
    billedInputTokens: number;
    netContextTokens: number;
    peakContextTokens: number;
    amplification: number;
    attributedTokens: number;
    coverage: number;
    credits: number;
    originalCredits: number;
    /** 输出 token 总量：仅代理路径有真值，否则 0 且 availability.outputTokens=unavailable */
    outputTokens?: number;
    /** 供应商上下文缓存命中的那部分 prompt：仅代理路径有真值 */
    cachedTokens?: number;
    cachedTrips?: number;
    /** 缓存命中 ÷ 计费输入总量（全局实测约 91.5%：重复叠加的前缀正是缓存的命中对象） */
    cachedShare?: number;
    /** 两个不依赖 usage 的计数，IDE 端占比全缺时仍有可展示的真值 */
    toolCalls?: number;
    fileReads?: number;
  };
  /** credits 的分子到底覆盖了多少：与计费输入总量并排展示时，部分覆盖不说就等于把局部真值当全量。 */
  creditsCoverage?: {
    /** 真正累加进 totals.credits 的往返数 */
    trips: number;
    roundTrips: number;
    /** 这些往返的计费输入之和 */
    tokens: number;
    tokenShare: number;
    /** trips === roundTrips：全覆盖时不必再啰嗦覆盖范围 */
    full: boolean;
  };
  /** 压缩单笔成本（此前只显示「压缩 N 次」，而它是会话里单笔最贵的调用） */
  compactionCost?: CompactionCost;
  byCategory: CatRow[];
  byCategoryDetail: CatDetail[];
  byTool: ToolRow[];
  byFile: FileRow[];
  byRequest: ReqRow[];
  subagents?: Subagents;
  identity: { sumAttributed: number; billedInputTokens: number; absDiff: number; ok: boolean | null };
}

// 注入点：下一行的 REPORT 初值会被 render-canvas.mjs 按整行替换为真实报告 JSON。
const REPORT = {"schemaVersion":3.2,"generatedAt":"2026-11-02T17:56:22.973Z","source":"desktop-rich","usageSource":"transcript-ratio","availability":{"credits":"measured","roundTrips":"measured","contextRatio":"measured","tokens":"derived","outputTokens":"unavailable","cachedTokens":"unavailable","categoryShare":"derived","toolShare":"derived","fileShare":"derived","systemPrompt":"derived","contextWindow":"fallback","compactions":"measured","compactionCost":"measured","model":"measured","title":"measured","toolCalls":"measured","fileReads":"measured","userTurns":"measured","userContextLimit":"fallback"},"session":{"id":"6af61636-da3f-4e28-993e-8d3b7b9c1bd7","title":"来自另一个 Qoder 会话（在做 micros","titleSource":"first-user","model":"dfmodel","modelSource":"runtime-config","cwd":"D:\\桌面\\LANLinkShopping\\Shopping","turns":13,"roundTrips":434,"compactions":1,"startedAt":"2026-11-01T11:42:23.101Z","endedAt":"2026-11-02T17:56:22.240Z"},"context":{"contextWindow":200000,"contextWindowSource":"fallback","systemPromptTokens":0,"netContextTokens":60561,"peakContextTokens":60561,"peakContextRatio":0.302806,"peakSessionTokens":119952,"peakSessionRatio":0.599759,"peakSessionNotable":true,"peakAdvice":{"level":"low","tone":"info","text":"低于盈亏线（39%）。此时压缩在 token 上是亏的：省下的还不够付摘要输出加压缩后重读，别压"},"userContextLimit":200000,"userContextLimitSource":"default","peakUserRatio":0.302806,"peakUserAdvice":{"level":"low","tone":"info","text":"低于盈亏线（39%）。此时压缩在 token 上是亏的：省下的还不够付摘要输出加压缩后重读，别压"},"currentContextTokens":60561,"netUserRatio":0.302805,"netUserAdvice":{"level":"low","tone":"info","text":"低于盈亏线（39%）。此时压缩在 token 上是亏的：省下的还不够付摘要输出加压缩后重读，别压"},"netWindowRatio":0.302805,"observedAutoCompactions":1,"observedAutoTriggerTokens":600048,"autoTriggerRatio":3.00024,"thresholdNotEnforced":false},"totals":{"billedInputTokens":28403060,"netContextTokens":60561,"peakContextTokens":60561,"amplification":469,"attributedTokens":28403060,"coverage":1,"credits":458.087,"originalCredits":458.087,"outputTokens":0,"cachedTokens":0,"cachedTrips":0,"cachedShare":0,"toolCalls":556,"fileReads":354},"creditsCoverage":{"trips":434,"roundTrips":434,"tokens":28403060,"tokenShare":0.9999999859170102,"full":true},"compactionCost":{"count":1,"items":[{"index":1,"at":"2026-11-02T00:00:03.107Z","trigger":"auto","preTokens":600048,"postTokens":10846,"messagesSummarized":1129,"nextRequestIndex":260,"nextInputTokens":16133,"savedTokens":583915,"proxy":null,"effectiveInputTokens":600048,"effectiveOutputTokens":10846}],"totals":{"preTokens":600048,"postTokens":10846,"savedTokens":583915,"measured":0}},"proxy":{"matched":0,"requests":434,"breakdown":{"proxy":0,"transcriptTokens":0,"transcriptRatio":434},"logPath":"C:\\Users\\35144\\.qoder-credits-proxy\\usage.jsonl","logExists":false,"logRecords":0,"configured":false,"enabled":true,"port":49787,"lastRecordAt":null,"dormant":false,"status":null,"statusSuperseded":false},"byCategory":[{"key":"tool_result","label":"工具返回","tokens":9484052,"share":0.3339095021666309},{"key":"compact_summary","label":"压缩摘要","tokens":356992,"share":0.012568797315732625},{"key":"assistant_thinking","label":"模型思考","tokens":6801404,"share":0.2394602688736838},{"key":"assistant_tool_use","label":"工具调用","tokens":3797035,"share":0.13368400725396704},{"key":"assistant_text","label":"模型回复","tokens":406983,"share":0.014328833650123557},{"key":"user_input","label":"用户输入","tokens":510994,"share":0.0179908163796351},{"key":"attachment","label":"附件/技能","tokens":7045599,"share":0.2480577602772447}],"byCategoryDetail":[{"key":"tool_result","label":"工具返回","tokens":9484052,"share":0.3339095021666309,"children":[{"tool":"Bash","label":"（无路径）","kind":"shell","tokens":2094535,"share":0.07374328575182199,"catShare":0.2208481198448251,"trips":16328},{"tool":"Agent","label":"（无路径）","kind":"other","tokens":1330365,"share":0.04683878385102376,"catShare":0.1402738872272338,"trips":3754},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeIngestService.java","kind":"read","tokens":883604,"share":0.031109462532818377,"catShare":0.09316734723318482,"trips":1970},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/RedisVectorStore.java","kind":"read","tokens":474369,"share":0.01670132010492978,"catShare":0.050017504732750365,"trips":1194},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/controller/AiToolsInternalController.java","kind":"read","tokens":417323,"share":0.014692878827820789,"catShare":0.04400257774182359,"trips":922},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeRagService.java","kind":"read","tokens":398405,"share":0.014026833378028514,"catShare":0.0420078892245142,"trips":737},{"tool":"Read","label":"README.md","kind":"read","tokens":389650,"share":0.013718579513751075,"catShare":0.04108472332993114,"trips":257},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/service/ProductService.java","kind":"read","tokens":375370,"share":0.01321583749147848,"catShare":0.0395790997432693,"trips":491},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/support/OutboundModelConfigService.java","kind":"read","tokens":229253,"share":0.008071431527403227,"catShare":0.024172512237688105,"trips":743},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolExecutionService.java","kind":"read","tokens":207110,"share":0.007291836729132475,"catShare":0.021837763471294177,"trips":1113},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/dto/AiToolDto.java","kind":"read","tokens":183005,"share":0.00644315677657547,"catShare":0.01929611686630032,"trips":375},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolGuard.java","kind":"read","tokens":157384,"share":0.005541093507152776,"catShare":0.016594596653280037,"trips":408},{"tool":"Read","label":"backend/src/main/java/com/lanlink/shopping/module/search/ProductVectorStore.java","kind":"read","tokens":155655,"share":0.005480225608601803,"catShare":0.01641230804467195,"trips":240},{"tool":"Read","label":"backend/src/test/java/com/lanlink/shopping/module/aikb/ProductKnowledgeSyncTest.java","kind":"read","tokens":154940,"share":0.0054550330612332856,"catShare":0.016336860813595717,"trips":300},{"tool":"Read","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolsInternalControllerTest.java","kind":"read","tokens":140130,"share":0.004933636198336436,"catShare":0.014775369273182298,"trips":496},{"tool":null,"label":"其他 106 项","kind":null,"tokens":1892953,"share":0.0666461073065151,"catShare":0.19959332356243245,"trips":43100}]},{"key":"compact_summary","label":"压缩摘要","tokens":356992,"share":0.012568797315732625,"children":[]},{"key":"assistant_thinking","label":"模型思考","tokens":6801404,"share":0.2394602688736838,"children":[]},{"key":"assistant_tool_use","label":"工具调用","tokens":3797035,"share":0.13368400725396704,"children":[{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeIngestService.java","kind":"write","tokens":685381,"share":0.02413053586125122,"catShare":0.18050428287513165,"trips":6056},{"tool":"Agent","label":"（无路径）","kind":"other","tokens":577807,"share":0.02034311184846513,"catShare":0.15217311529133154,"trips":3754},{"tool":"Bash","label":"（无路径）","kind":"shell","tokens":335705,"share":0.011819313334356603,"catShare":0.088412320793936,"trips":16328},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/controller/AiToolsInternalController.java","kind":"write","tokens":238859,"share":0.008409637687578405,"catShare":0.06290683426030266,"trips":3491},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolExecutionService.java","kind":"write","tokens":228515,"share":0.008045449741941674,"catShare":0.06018258957974891,"trips":2725},{"tool":"Write","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KbOutboxService.java","kind":"write","tokens":176333,"share":0.006208229756573341,"catShare":0.04643958454042462,"trips":458},{"tool":"Write","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolGuard.java","kind":"write","tokens":128454,"share":0.004522535354633247,"catShare":0.03383004031321062,"trips":275},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/service/ProductService.java","kind":"write","tokens":114048,"share":0.004015330455942395,"catShare":0.030035982152406944,"trips":1428},{"tool":"Write","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolExceptionHandlerTest.java","kind":"write","tokens":108162,"share":0.0038080953755128526,"catShare":0.028485796122780783,"trips":299},{"tool":"Write","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KbOutboxWorker.java","kind":"write","tokens":93557,"share":0.003293917156312993,"catShare":0.024639575248932753,"trips":457},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolGuard.java","kind":"write","tokens":89850,"share":0.0031633939720896735,"catShare":0.023663219236688467,"trips":877},{"tool":"Edit","label":"ai-gateway/app/tools.py","kind":"write","tokens":55822,"share":0.001965356205269195,"catShare":0.014701505779487123,"trips":674},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/RedisVectorStore.java","kind":"write","tokens":55467,"share":0.0019528545917506985,"catShare":0.014607989630657544,"trips":447},{"tool":"Edit","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeRagService.java","kind":"write","tokens":54924,"share":0.001933744799768956,"catShare":0.014465042150444458,"trips":726},{"tool":"Edit","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolExceptionHandlerTest.java","kind":"write","tokens":53100,"share":0.0018694993019160151,"catShare":0.013984464860964422,"trips":836},{"tool":null,"label":"其他 106 项","kind":null,"tokens":801052,"share":0.02820300181060421,"catShare":0.21096765716354818,"trips":33597}]},{"key":"assistant_text","label":"模型回复","tokens":406983,"share":0.014328833650123557,"children":[]},{"key":"user_input","label":"用户输入","tokens":510994,"share":0.0179908163796351,"children":[]},{"key":"attachment","label":"附件/技能","tokens":7045599,"share":0.2480577602772447,"children":[]}],"byTool":[{"tool":"Read","tokens":5723012,"calls":110,"perCall":52027,"share":0.20149278956600153},{"tool":"Bash","tokens":2430240,"calls":144,"perCall":16877,"share":0.0855625990861783},{"tool":"Edit","tokens":2232427,"calls":215,"perCall":10383,"share":0.07859812320057426},{"tool":"Agent","tokens":1908171,"calls":28,"perCall":68149,"share":0.06718189569948894},{"tool":"Write","tokens":707414,"calls":15,"perCall":47161,"share":0.024906270328663924},{"tool":"Grep","tokens":138801,"calls":11,"perCall":12618,"share":0.004886819622428045},{"tool":"Glob","tokens":75513,"calls":3,"perCall":25171,"share":0.002658634082584798},{"tool":"TaskCreate","tokens":41799,"calls":11,"perCall":3800,"share":0.001471633677723812},{"tool":"mcp_call","tokens":9922,"calls":1,"perCall":9922,"share":0.00034933193270836517},{"tool":"AskUserQuestion","tokens":6465,"calls":3,"perCall":2155,"share":0.00022762966769942917},{"tool":"TaskUpdate","tokens":6260,"calls":13,"perCall":482,"share":0.00022041505936906417},{"tool":"Skill","tokens":1061,"calls":2,"perCall":531,"share":0.000037367497167940934}],"byFile":[{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\KnowledgeIngestService.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeIngestService.java","kind":"read","tool":"Read","selfTokens":37735,"reads":42,"trips":16052,"billed":1640517,"perRead":39060,"share":0.05775846891338668},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\controller\\AiToolsInternalController.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/controller/AiToolsInternalController.java","kind":"read","tool":"Read","selfTokens":24442,"reads":35,"trips":8826,"billed":696774,"perRead":19908,"share":0.024531665174356905},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\RedisVectorStore.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/RedisVectorStore.java","kind":"read","tool":"Read","selfTokens":11239,"reads":7,"trips":3282,"billed":543627,"perRead":77661,"share":0.01913971790656639},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\service\\ProductService.java","label":"backend/src/main/java/com/lanlink/shopping/service/ProductService.java","kind":"read","tool":"Read","selfTokens":10515,"reads":9,"trips":3838,"billed":504655,"perRead":56073,"share":0.01776764530588485},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\service\\AiToolExecutionService.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolExecutionService.java","kind":"read","tool":"Read","selfTokens":18867,"reads":37,"trips":8018,"billed":479554,"perRead":12961,"share":0.01688387065815203},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\KnowledgeRagService.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KnowledgeRagService.java","kind":"read","tool":"Read","selfTokens":9819,"reads":7,"trips":2926,"billed":465715,"perRead":66531,"share":0.016396655053206147},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\README.md","label":"README.md","kind":"read","tool":"Read","selfTokens":7393,"reads":1,"trips":514,"billed":390548,"perRead":390548,"share":0.013750197714908036},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\service\\AiToolGuard.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/service/AiToolGuard.java","kind":"read","tool":"Read","selfTokens":12392,"reads":11,"trips":3120,"billed":389309,"perRead":35392,"share":0.013706582244185021},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\OutboundModelConfigService.java","label":"backend/src/main/java/com/lanlink/shopping/support/OutboundModelConfigService.java","kind":"read","tool":"Read","selfTokens":13436,"reads":23,"trips":2990,"billed":280637,"perRead":12202,"share":0.009880525035642695},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\app\\main.py","label":"ai-gateway/app/main.py","kind":"search","tool":"Grep","selfTokens":8115,"reads":8,"trips":1794,"billed":244537,"perRead":30567,"share":0.008609512620967968},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\dto\\AiToolDto.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/dto/AiToolDto.java","kind":"read","tool":"Read","selfTokens":6095,"reads":8,"trips":2300,"billed":227331,"perRead":28416,"share":0.008003757150805249},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\KbOutboxService.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KbOutboxService.java","kind":"write","tool":"Write","selfTokens":4600,"reads":4,"trips":1662,"billed":206731,"perRead":51683,"share":0.007278480143701568},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\module\\aitool\\AiToolsInternalControllerTest.java","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolsInternalControllerTest.java","kind":"read","tool":"Read","selfTokens":6544,"reads":4,"trips":1198,"billed":187910,"perRead":46977,"share":0.006615827012885512},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\module\\aitool\\AiToolExceptionHandlerTest.java","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolExceptionHandlerTest.java","kind":"write","tool":"Write","selfTokens":8795,"reads":13,"trips":2270,"billed":173798,"perRead":13369,"share":0.0061190048376342345},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\module\\aikb\\ProductKnowledgeSyncTest.java","label":"backend/src/test/java/com/lanlink/shopping/module/aikb/ProductKnowledgeSyncTest.java","kind":"read","tool":"Read","selfTokens":6413,"reads":8,"trips":964,"billed":167094,"perRead":20887,"share":0.005882965055617402},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\CustomerCommunicationSchemaInitializer.java","label":"backend/src/main/java/com/lanlink/shopping/support/CustomerCommunicationSchemaInitializer.java","kind":"read","tool":"Read","selfTokens":3479,"reads":5,"trips":2320,"billed":166617,"perRead":33323,"share":0.005866161432139595},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\search\\ProductVectorStore.java","label":"backend/src/main/java/com/lanlink/shopping/module/search/ProductVectorStore.java","kind":"read","tool":"Read","selfTokens":3234,"reads":1,"trips":480,"billed":157457,"perRead":157457,"share":0.005543650177734824},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\controller\\KnowledgeAdminController.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/controller/KnowledgeAdminController.java","kind":"read","tool":"Read","selfTokens":2968,"reads":4,"trips":1516,"billed":134466,"perRead":33616,"share":0.004734203394869494},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\app\\tools.py","label":"ai-gateway/app/tools.py","kind":"read","tool":"Read","selfTokens":9667,"reads":13,"trips":1972,"billed":125854,"perRead":9681,"share":0.0044310043519272075},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\KbOutboxWorker.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KbOutboxWorker.java","kind":"write","tool":"Write","selfTokens":2738,"reads":4,"trips":1634,"billed":120175,"perRead":30044,"share":0.004231056486207307},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\config\\AuthInterceptor.java","label":"backend/src/main/java/com/lanlink/shopping/config/AuthInterceptor.java","kind":"read","tool":"Read","selfTokens":2259,"reads":1,"trips":508,"billed":117318,"perRead":117318,"share":0.0041304807795576794},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\openapi\\knowledge-tools.yaml","label":"openapi/knowledge-tools.yaml","kind":"write","tool":"Write","selfTokens":6197,"reads":12,"trips":2026,"billed":114936,"perRead":9578,"share":0.0040466007451835945},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\ExternalFallbackService.java","label":"backend/src/main/java/com/lanlink/shopping/support/ExternalFallbackService.java","kind":"read","tool":"Read","selfTokens":3371,"reads":6,"trips":1352,"billed":107943,"perRead":17991,"share":0.0038004017470597835},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\ProductKnowledgeSyncListener.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/ProductKnowledgeSyncListener.java","kind":"read","tool":"Read","selfTokens":2104,"reads":4,"trips":1614,"billed":94832,"perRead":23708,"share":0.0033388097829657017},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\SECURITY.md","label":"SECURITY.md","kind":"read","tool":"Read","selfTokens":1757,"reads":1,"trips":514,"billed":92816,"perRead":92816,"share":0.003267834084281543},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\common\\DesensitizeUtil.java","label":"backend/src/main/java/com/lanlink/shopping/common/DesensitizeUtil.java","kind":"read","tool":"Read","selfTokens":2184,"reads":3,"trips":830,"billed":88271,"perRead":29424,"share":0.0031078001802299863},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\controller\\AiToolExceptionHandler.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/controller/AiToolExceptionHandler.java","kind":"write","tool":"Write","selfTokens":4744,"reads":8,"trips":1286,"billed":84444,"perRead":10556,"share":0.0029730662557596625},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\module\\aitool\\AiToolExecutionServiceTest.java","label":"backend/src/test/java/com/lanlink/shopping/module/aitool/AiToolExecutionServiceTest.java","kind":"read","tool":"Read","selfTokens":2335,"reads":3,"trips":1044,"billed":78777,"perRead":26259,"share":0.002773540331533157},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\pom.xml","label":"backend/pom.xml","kind":"read","tool":"Read","selfTokens":1569,"reads":1,"trips":484,"billed":77073,"perRead":77073,"share":0.002713530954180497},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping","label":"D:/桌面/LANLinkShopping/Shopping","kind":"search","tool":"Glob","selfTokens":1425,"reads":3,"trips":1136,"billed":73593,"perRead":24531,"share":0.0025910402692156027},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\AiApiKeyService.java","label":"backend/src/main/java/com/lanlink/shopping/support/AiApiKeyService.java","kind":"write","tool":"Edit","selfTokens":2764,"reads":2,"trips":554,"billed":71591,"perRead":35795,"share":0.0025205271763000274},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\common\\GlobalExceptionHandler.java","label":"backend/src/main/java/com/lanlink/shopping/common/GlobalExceptionHandler.java","kind":"read","tool":"Read","selfTokens":3037,"reads":7,"trips":1334,"billed":61403,"perRead":8772,"share":0.0021618410760463106},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\controller\\AuthController.java","label":"backend/src/main/java/com/lanlink/shopping/controller/AuthController.java","kind":"read","tool":"Read","selfTokens":883,"reads":1,"trips":484,"billed":43375,"perRead":43375,"share":0.0015271178027669726},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\controller\\KnowledgeQueryController.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/controller/KnowledgeQueryController.java","kind":"read","tool":"Read","selfTokens":812,"reads":1,"trips":504,"billed":41763,"perRead":41763,"share":0.0014703585499499545},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\config\\AuthRateLimitInterceptor.java","label":"backend/src/main/java/com/lanlink/shopping/config/AuthRateLimitInterceptor.java","kind":"read","tool":"Read","selfTokens":1707,"reads":2,"trips":484,"billed":41310,"perRead":20655,"share":0.0014544322524294344},{"attr":"file:D:\\DevCache\\.qoder-cn\\memory\\MEMORY.md","label":"D:/DevCache/.qoder-cn/memory/MEMORY.md","kind":"read","tool":"Read","selfTokens":701,"reads":3,"trips":1386,"billed":32772,"perRead":10924,"share":0.0011538204166597372},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\support\\OutboundModelConfigServiceTest.java","label":"backend/src/test/java/com/lanlink/shopping/support/OutboundModelConfigServiceTest.java","kind":"read","tool":"Read","selfTokens":3709,"reads":4,"trips":426,"billed":32588,"perRead":8147,"share":0.0011473375599434087},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\service\\AuditService.java","label":"backend/src/main/java/com/lanlink/shopping/service/AuditService.java","kind":"read","tool":"Read","selfTokens":651,"reads":1,"trips":480,"billed":31696,"perRead":31696,"share":0.0011159295812323336},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\integration\\event\\ProductLifecycleEvent.java","label":"backend/src/main/java/com/lanlink/shopping/integration/event/ProductLifecycleEvent.java","kind":"read","tool":"Read","selfTokens":539,"reads":1,"trips":502,"billed":27593,"perRead":27593,"share":0.0009714801729001027},{"attr":"file:D:\\DevCache\\.qoder-cn\\memory\\feedback-subagent-review-per-step.md","label":"D:/DevCache/.qoder-cn/memory/feedback-subagent-review-per-step.md","kind":"write","tool":"Write","selfTokens":523,"reads":2,"trips":924,"billed":24479,"perRead":12239,"share":0.0008618387540595345},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\entity\\Product.java","label":"backend/src/main/java/com/lanlink/shopping/entity/Product.java","kind":"read","tool":"Read","selfTokens":465,"reads":1,"trips":502,"billed":23805,"perRead":23805,"share":0.0008381044163238361},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\EmbeddingService.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/EmbeddingService.java","kind":"read","tool":"Read","selfTokens":501,"reads":2,"trips":894,"billed":22663,"perRead":11332,"share":0.000797910319109024},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\IntentVectorService.java","label":"backend/src/main/java/com/lanlink/shopping/support/IntentVectorService.java","kind":"read","tool":"Read","selfTokens":630,"reads":2,"trips":650,"billed":20387,"perRead":10194,"share":0.0007177896626287713},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\AGENTS.md","label":"AGENTS.md","kind":"read","tool":"Read","selfTokens":348,"reads":1,"trips":514,"billed":18384,"perRead":18384,"share":0.0006472431766249156},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\.env.example","label":".env.example","kind":"search","tool":"Grep","selfTokens":1784,"reads":4,"trips":338,"billed":17511,"perRead":4378,"share":0.000616526202962542},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\docker-compose.yml","label":"docker-compose.yml","kind":"search","tool":"Grep","selfTokens":1087,"reads":6,"trips":868,"billed":16205,"perRead":2701,"share":0.0005705217200410353},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\docker-compose.yml","label":"ai-gateway/docker-compose.yml","kind":"search","tool":"Grep","selfTokens":1236,"reads":4,"trips":430,"billed":13900,"perRead":3475,"share":0.0004893877284466698},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java","label":"backend/src/main/java","kind":"search","tool":"Grep","selfTokens":199,"reads":1,"trips":506,"billed":10284,"perRead":10284,"share":0.0003620880790558983},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\common\\R.java","label":"backend/src/main/java/com/lanlink/shopping/common/R.java","kind":"read","tool":"Read","selfTokens":527,"reads":2,"trips":394,"billed":10278,"perRead":5139,"share":0.00036186354884748013},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\tests\\test_gateway.py","label":"ai-gateway/tests/test_gateway.py","kind":"read","tool":"Read","selfTokens":809,"reads":2,"trips":234,"billed":9439,"perRead":4720,"share":0.00033233064222687133},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aikb\\KbIngestBusyException.java","label":"backend/src/main/java/com/lanlink/shopping/module/aikb/KbIngestBusyException.java","kind":"write","tool":"Write","selfTokens":213,"reads":1,"trips":388,"billed":8276,"perRead":8276,"share":0.00029136660339474594},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\frontend\\src\\views\\admin\\AdminProducts.vue","label":"frontend/src/views/admin/AdminProducts.vue","kind":"read","tool":"Read","selfTokens":635,"reads":2,"trips":254,"billed":8003,"perRead":4001,"share":0.0002817502486735066},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\support\\IntentVectorServiceTest.java","label":"backend/src/test/java/com/lanlink/shopping/support/IntentVectorServiceTest.java","kind":"read","tool":"Read","selfTokens":1197,"reads":2,"trips":116,"billed":7673,"perRead":3837,"share":0.0002701616748025194},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\resources\\application.yml","label":"backend/src/main/resources/application.yml","kind":"write","tool":"Edit","selfTokens":153,"reads":1,"trips":400,"billed":6139,"perRead":6139,"share":0.00021614955937906776},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\config\\WebConfig.java","label":"backend/src/main/java/com/lanlink/shopping/config/WebConfig.java","kind":"write","tool":"Edit","selfTokens":249,"reads":1,"trips":240,"billed":5920,"perRead":5920,"share":0.00020843046007932737},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\app\\rate_limiter.py","label":"ai-gateway/app/rate_limiter.py","kind":"read","tool":"Read","selfTokens":350,"reads":2,"trips":330,"billed":5362,"perRead":2681,"share":0.0001887681708902787},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\app","label":"ai-gateway/app","kind":"search","tool":"Grep","selfTokens":348,"reads":1,"trips":122,"billed":4208,"perRead":4208,"share":0.00014815329008769458},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\KnowledgeRagService.java","label":"backend/src/main/java/com/lanlink/shopping/support/KnowledgeRagService.java","kind":"read","tool":"Read","selfTokens":69,"reads":1,"trips":506,"billed":3566,"perRead":3566,"share":0.00012554812791385422},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\aitool\\AiToolGuard.java","label":"backend/src/main/java/com/lanlink/shopping/module/aitool/AiToolGuard.java","kind":"read","tool":"Read","selfTokens":68,"reads":1,"trips":504,"billed":3497,"perRead":3497,"share":0.00012313347462635074},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\controller\\KnowledgeQueryController.java","label":"backend/src/main/java/com/lanlink/shopping/controller/KnowledgeQueryController.java","kind":"read","tool":"Read","selfTokens":65,"reads":1,"trips":508,"billed":3376,"perRead":3376,"share":0.00011884960189076989},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\controller\\AiToolsInternalController.java","label":"backend/src/main/java/com/lanlink/shopping/controller/AiToolsInternalController.java","kind":"read","tool":"Read","selfTokens":65,"reads":1,"trips":508,"billed":3376,"perRead":3376,"share":0.00011884960189076989},{"attr":"file:D:\\桌面\\桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/aitool/dto/AiToolDto.java","label":"D:/桌面/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/aitool/dto/AiToolDto.java","kind":"write","tool":"Edit","selfTokens":127,"reads":1,"trips":262,"billed":3299,"perRead":3299,"share":0.0001161462003051849},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\support\\DesensitizeUtil.java","label":"backend/src/main/java/com/lanlink/shopping/support/DesensitizeUtil.java","kind":"read","tool":"Read","selfTokens":62,"reads":1,"trips":484,"billed":3046,"perRead":3046,"share":0.0001072268445883944},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\test\\java\\com\\lanlink\\shopping\\common\\DesensitizeUtilTest.java","label":"backend/src/test/java/com/lanlink/shopping/common/DesensitizeUtilTest.java","kind":"write","tool":"Write","selfTokens":324,"reads":1,"trips":88,"billed":2830,"perRead":2830,"share":0.00009964966622794706},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\backend","label":"backend","kind":"search","tool":"Glob","selfTokens":48,"reads":1,"trips":500,"billed":2446,"perRead":2446,"share":0.00008611473439865},{"attr":"file:D:\\桌面\\LANLinkShopping\\Shopping\\ai-gateway\\tests","label":"ai-gateway/tests","kind":"search","tool":"Grep","selfTokens":119,"reads":1,"trips":120,"billed":1415,"perRead":1415,"share":0.00004983390180373126}],"byAttr":[{"attr":"attachment","tokens":7045599,"share":0.2480577602772447},{"attr":"assistant_thinking","tokens":6801404,"share":0.2394602688736838},{"attr":"tool_result|Read","tokens":5585625,"share":0.1966557413270877},{"attr":"tool_result|Bash","tokens":2094535,"share":0.07374328575182199},{"attr":"tool_use|Edit","tokens":2000561,"share":0.0704346915890546},{"attr":"tool_result|Agent","tokens":1330365,"share":0.04683878385102376},{"attr":"tool_use|Write","tokens":683090,"share":0.024049868727460722},{"attr":"tool_use|Agent","tokens":577807,"share":0.02034311184846513},{"attr":"user_input","tokens":510994,"share":0.0179908163796351},{"attr":"assistant_text","tokens":406983,"share":0.014328833650123557},{"attr":"compact_summary","tokens":356992,"share":0.012568797315732625},{"attr":"tool_use|Bash","tokens":335705,"share":0.011819313334356603},{"attr":"tool_result|Edit","tokens":231866,"share":0.0081634316115212},{"attr":"tool_use|Read","tokens":137387,"share":0.004837048238912653},{"attr":"tool_result|Grep","tokens":125403,"share":0.0044151144901622775},{"attr":"tool_result|Glob","tokens":70202,"share":0.002471645467509007},{"attr":"tool_use|TaskCreate","tokens":33919,"share":0.0011942009020070414},{"attr":"tool_result|Write","tokens":24324,"share":0.0008564016012031813},{"attr":"tool_use|Grep","tokens":13398,"share":0.00047170513226577965},{"attr":"tool_result|mcp_call","tokens":9818,"share":0.00034567639907636137},{"attr":"tool_result|TaskCreate","tokens":7880,"share":0.0002774327757167662},{"attr":"tool_use|AskUserQuestion","tokens":5492,"share":0.00019337556960010634},{"attr":"tool_use|Glob","tokens":5311,"share":0.00018698861507578706},{"attr":"tool_use|TaskUpdate","tokens":3756,"share":0.0001322490356214403},{"attr":"tool_result|TaskUpdate","tokens":2504,"share":0.00008816602374762681},{"attr":"tool_result|AskUserQuestion","tokens":973,"share":0.00003425409809932281},{"attr":"tool_result|Skill","tokens":556,"share":0.000019568769653237647},{"attr":"tool_use|Skill","tokens":506,"share":0.000017798727514703297},{"attr":"tool_use|mcp_call","tokens":104,"share":0.0000036555336320038214},{"attr":"system","tokens":0,"share":0}],"byRequest":[{"index":1,"time":"12:24","ratio":0.04972,"inputTokens":9944,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.166,"originalCredits":1.166,"afterCompact":false},{"index":2,"time":"12:24","ratio":0.055102,"inputTokens":11020,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.359,"originalCredits":0.359,"afterCompact":false},{"index":3,"time":"12:24","ratio":0.06684,"inputTokens":13368,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.61,"originalCredits":0.61,"afterCompact":false},{"index":4,"time":"12:25","ratio":0.06923,"inputTokens":13846,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.542,"originalCredits":0.542,"afterCompact":false},{"index":5,"time":"12:38","ratio":0.082775,"inputTokens":16555,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.71,"originalCredits":0.71,"afterCompact":false},{"index":6,"time":"12:39","ratio":0.094553,"inputTokens":18911,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.688,"originalCredits":0.688,"afterCompact":false},{"index":7,"time":"12:39","ratio":0.102475,"inputTokens":20495,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.636,"originalCredits":0.636,"afterCompact":false},{"index":8,"time":"12:39","ratio":0.111186,"inputTokens":22237,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.7,"originalCredits":0.7,"afterCompact":false},{"index":9,"time":"12:40","ratio":0.11682,"inputTokens":23364,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.65,"originalCredits":0.65,"afterCompact":false},{"index":10,"time":"12:40","ratio":0.120592,"inputTokens":24118,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.636,"originalCredits":0.636,"afterCompact":false},{"index":11,"time":"12:41","ratio":0.123741,"inputTokens":24748,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.656,"originalCredits":0.656,"afterCompact":false},{"index":12,"time":"12:41","ratio":0.128036,"inputTokens":25607,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.67,"originalCredits":0.67,"afterCompact":false},{"index":13,"time":"12:42","ratio":0.128608,"inputTokens":25722,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.898,"originalCredits":0.898,"afterCompact":false},{"index":14,"time":"12:43","ratio":0.137835,"inputTokens":27567,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.263,"originalCredits":1.263,"afterCompact":false},{"index":15,"time":"12:44","ratio":0.14878,"inputTokens":29756,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.275,"originalCredits":1.275,"afterCompact":false},{"index":16,"time":"12:45","ratio":0.158191,"inputTokens":31638,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.084,"originalCredits":1.084,"afterCompact":false},{"index":17,"time":"12:47","ratio":0.168874,"inputTokens":33775,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.395,"originalCredits":1.395,"afterCompact":false},{"index":18,"time":"12:47","ratio":0.184569,"inputTokens":36914,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.173,"originalCredits":1.173,"afterCompact":false},{"index":19,"time":"12:47","ratio":0.202335,"inputTokens":40467,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.259,"originalCredits":1.259,"afterCompact":false},{"index":20,"time":"12:48","ratio":0.213112,"inputTokens":42622,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.203,"originalCredits":1.203,"afterCompact":false},{"index":21,"time":"12:49","ratio":0.221807,"inputTokens":44361,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.533,"originalCredits":1.533,"afterCompact":false},{"index":22,"time":"12:51","ratio":0.236941,"inputTokens":47388,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.96,"originalCredits":1.96,"afterCompact":false},{"index":23,"time":"12:51","ratio":0.257426,"inputTokens":51485,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.531,"originalCredits":1.531,"afterCompact":false},{"index":24,"time":"12:53","ratio":0.261003,"inputTokens":52201,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.855,"originalCredits":1.855,"afterCompact":false},{"index":25,"time":"12:53","ratio":0.276296,"inputTokens":55259,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.508,"originalCredits":1.508,"afterCompact":false},{"index":26,"time":"12:54","ratio":0.276729,"inputTokens":55346,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.28,"originalCredits":1.28,"afterCompact":false},{"index":27,"time":"13:22","ratio":0.297845,"inputTokens":59569,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.811,"originalCredits":0.811,"afterCompact":false},{"index":28,"time":"13:29","ratio":0.302333,"inputTokens":60467,"outputTokens":null,"usageSource":"transcript-ratio","credits":8.876,"originalCredits":8.876,"afterCompact":false},{"index":29,"time":"13:30","ratio":0.310942,"inputTokens":62188,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.067,"originalCredits":1.067,"afterCompact":false},{"index":30,"time":"13:36","ratio":0.317162,"inputTokens":63432,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.098,"originalCredits":1.098,"afterCompact":false},{"index":31,"time":"13:37","ratio":0.321114,"inputTokens":64223,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.831,"originalCredits":0.831,"afterCompact":false},{"index":32,"time":"13:37","ratio":0.321438,"inputTokens":64288,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.849,"originalCredits":0.849,"afterCompact":false},{"index":33,"time":"13:38","ratio":0.323612,"inputTokens":64722,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.838,"originalCredits":0.838,"afterCompact":false},{"index":34,"time":"13:40","ratio":0.324735,"inputTokens":64947,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.149,"originalCredits":1.149,"afterCompact":false},{"index":35,"time":"13:40","ratio":0.333611,"inputTokens":66722,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.995,"originalCredits":0.995,"afterCompact":false},{"index":36,"time":"13:41","ratio":0.33411,"inputTokens":66822,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.811,"originalCredits":0.811,"afterCompact":false},{"index":37,"time":"13:41","ratio":0.33509,"inputTokens":67018,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.781,"originalCredits":0.781,"afterCompact":false},{"index":38,"time":"13:41","ratio":0.335333,"inputTokens":67067,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.778,"originalCredits":0.778,"afterCompact":false},{"index":39,"time":"13:42","ratio":0.33586,"inputTokens":67172,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.775,"originalCredits":0.775,"afterCompact":false},{"index":40,"time":"13:42","ratio":0.336179,"inputTokens":67236,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.824,"originalCredits":0.824,"afterCompact":false},{"index":41,"time":"13:43","ratio":0.337383,"inputTokens":67477,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.925,"originalCredits":0.925,"afterCompact":false},{"index":42,"time":"13:43","ratio":0.339858,"inputTokens":67972,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.846,"originalCredits":0.846,"afterCompact":false},{"index":43,"time":"13:44","ratio":0.340378,"inputTokens":68076,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.876,"originalCredits":0.876,"afterCompact":false},{"index":44,"time":"13:44","ratio":0.342237,"inputTokens":68447,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.814,"originalCredits":0.814,"afterCompact":false},{"index":45,"time":"13:45","ratio":0.344207,"inputTokens":68841,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.921,"originalCredits":0.921,"afterCompact":false},{"index":46,"time":"13:45","ratio":0.346034,"inputTokens":69207,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.856,"originalCredits":0.856,"afterCompact":false},{"index":47,"time":"13:46","ratio":0.346769,"inputTokens":69354,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.825,"originalCredits":0.825,"afterCompact":false},{"index":48,"time":"13:46","ratio":0.347418,"inputTokens":69484,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.799,"originalCredits":0.799,"afterCompact":false},{"index":49,"time":"13:46","ratio":0.348489,"inputTokens":69698,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.815,"originalCredits":0.815,"afterCompact":false},{"index":50,"time":"13:47","ratio":0.350642,"inputTokens":70128,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.843,"originalCredits":0.843,"afterCompact":false},{"index":51,"time":"13:47","ratio":0.351177,"inputTokens":70235,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.895,"originalCredits":0.895,"afterCompact":false},{"index":52,"time":"13:48","ratio":0.352912,"inputTokens":70582,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.847,"originalCredits":0.847,"afterCompact":false},{"index":53,"time":"13:48","ratio":0.353273,"inputTokens":70655,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.816,"originalCredits":0.816,"afterCompact":false},{"index":54,"time":"13:49","ratio":0.353689,"inputTokens":70738,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.828,"originalCredits":0.828,"afterCompact":false},{"index":55,"time":"13:49","ratio":0.354479,"inputTokens":70896,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.845,"originalCredits":0.845,"afterCompact":false},{"index":56,"time":"13:49","ratio":0.355142,"inputTokens":71028,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.832,"originalCredits":0.832,"afterCompact":false},{"index":57,"time":"13:50","ratio":0.355619,"inputTokens":71124,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.823,"originalCredits":0.823,"afterCompact":false},{"index":58,"time":"13:50","ratio":0.356014,"inputTokens":71203,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.836,"originalCredits":0.836,"afterCompact":false},{"index":59,"time":"13:50","ratio":0.356638,"inputTokens":71328,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.828,"originalCredits":0.828,"afterCompact":false},{"index":60,"time":"13:51","ratio":0.357009,"inputTokens":71402,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.818,"originalCredits":0.818,"afterCompact":false},{"index":61,"time":"13:52","ratio":0.357343,"inputTokens":71469,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.867,"originalCredits":0.867,"afterCompact":false},{"index":62,"time":"13:52","ratio":0.358469,"inputTokens":71694,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.832,"originalCredits":0.832,"afterCompact":false},{"index":63,"time":"13:53","ratio":0.358663,"inputTokens":71733,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.862,"originalCredits":0.862,"afterCompact":false},{"index":64,"time":"14:04","ratio":0.363197,"inputTokens":72639,"outputTokens":null,"usageSource":"transcript-ratio","credits":10.374,"originalCredits":10.374,"afterCompact":false},{"index":65,"time":"14:04","ratio":0.370111,"inputTokens":74022,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.01,"originalCredits":1.01,"afterCompact":false},{"index":66,"time":"14:04","ratio":0.370368,"inputTokens":74074,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.851,"originalCredits":0.851,"afterCompact":false},{"index":67,"time":"14:05","ratio":0.370777,"inputTokens":74155,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.914,"originalCredits":0.914,"afterCompact":false},{"index":68,"time":"14:05","ratio":0.37217,"inputTokens":74434,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.908,"originalCredits":0.908,"afterCompact":false},{"index":69,"time":"14:06","ratio":0.373197,"inputTokens":74639,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.879,"originalCredits":0.879,"afterCompact":false},{"index":70,"time":"14:06","ratio":0.373654,"inputTokens":74731,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.874,"originalCredits":0.874,"afterCompact":false},{"index":71,"time":"14:06","ratio":0.374238,"inputTokens":74848,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.853,"originalCredits":0.853,"afterCompact":false},{"index":72,"time":"14:07","ratio":0.37441,"inputTokens":74882,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.858,"originalCredits":0.858,"afterCompact":false},{"index":73,"time":"14:07","ratio":0.374853,"inputTokens":74971,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.865,"originalCredits":0.865,"afterCompact":false},{"index":74,"time":"14:07","ratio":0.375268,"inputTokens":75054,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.864,"originalCredits":0.864,"afterCompact":false},{"index":75,"time":"14:08","ratio":0.375676,"inputTokens":75135,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.866,"originalCredits":0.866,"afterCompact":false},{"index":76,"time":"14:08","ratio":0.37608,"inputTokens":75216,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.892,"originalCredits":0.892,"afterCompact":false},{"index":77,"time":"14:08","ratio":0.376898,"inputTokens":75380,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.883,"originalCredits":0.883,"afterCompact":false},{"index":78,"time":"14:09","ratio":0.377359,"inputTokens":75472,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.875,"originalCredits":0.875,"afterCompact":false},{"index":79,"time":"14:09","ratio":0.378032,"inputTokens":75606,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.897,"originalCredits":0.897,"afterCompact":false},{"index":80,"time":"14:10","ratio":0.378757,"inputTokens":75751,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.876,"originalCredits":0.876,"afterCompact":false},{"index":81,"time":"14:10","ratio":0.379089,"inputTokens":75818,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.861,"originalCredits":0.861,"afterCompact":false},{"index":82,"time":"14:10","ratio":0.379326,"inputTokens":75865,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.855,"originalCredits":0.855,"afterCompact":false},{"index":83,"time":"14:11","ratio":0.379516,"inputTokens":75903,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.919,"originalCredits":0.919,"afterCompact":false},{"index":84,"time":"14:21","ratio":0.423216,"inputTokens":84643,"outputTokens":null,"usageSource":"transcript-ratio","credits":11.837,"originalCredits":11.837,"afterCompact":false},{"index":85,"time":"14:22","ratio":0.424666,"inputTokens":84933,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.987,"originalCredits":0.987,"afterCompact":false},{"index":86,"time":"14:22","ratio":0.425037,"inputTokens":85007,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.973,"originalCredits":0.973,"afterCompact":false},{"index":87,"time":"14:22","ratio":0.425407,"inputTokens":85081,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.965,"originalCredits":0.965,"afterCompact":false},{"index":88,"time":"14:23","ratio":0.425634,"inputTokens":85127,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.981,"originalCredits":0.981,"afterCompact":false},{"index":89,"time":"14:23","ratio":0.426176,"inputTokens":85235,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.979,"originalCredits":0.979,"afterCompact":false},{"index":90,"time":"14:23","ratio":0.426532,"inputTokens":85306,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.992,"originalCredits":0.992,"afterCompact":false},{"index":91,"time":"14:24","ratio":0.427176,"inputTokens":85435,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.993,"originalCredits":0.993,"afterCompact":false},{"index":92,"time":"14:24","ratio":0.427685,"inputTokens":85537,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.002,"originalCredits":1.002,"afterCompact":false},{"index":93,"time":"14:24","ratio":0.428385,"inputTokens":85677,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.007,"originalCredits":1.007,"afterCompact":false},{"index":94,"time":"14:25","ratio":0.429407,"inputTokens":85881,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.015,"originalCredits":1.015,"afterCompact":false},{"index":95,"time":"14:26","ratio":0.430193,"inputTokens":86039,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.99,"originalCredits":0.99,"afterCompact":false},{"index":96,"time":"14:26","ratio":0.430505,"inputTokens":86101,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.971,"originalCredits":0.971,"afterCompact":false},{"index":97,"time":"14:26","ratio":0.431003,"inputTokens":86201,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.981,"originalCredits":0.981,"afterCompact":false},{"index":98,"time":"14:27","ratio":0.431232,"inputTokens":86246,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.97,"originalCredits":0.97,"afterCompact":false},{"index":99,"time":"14:28","ratio":0.431422,"inputTokens":86284,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.018,"originalCredits":1.018,"afterCompact":false},{"index":100,"time":"14:28","ratio":0.432425,"inputTokens":86485,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.047,"originalCredits":1.047,"afterCompact":false},{"index":101,"time":"14:29","ratio":0.433691,"inputTokens":86738,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.143,"originalCredits":1.143,"afterCompact":false},{"index":102,"time":"14:29","ratio":0.436181,"inputTokens":87236,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.039,"originalCredits":1.039,"afterCompact":false},{"index":103,"time":"14:30","ratio":0.438373,"inputTokens":87675,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.061,"originalCredits":1.061,"afterCompact":false},{"index":104,"time":"14:30","ratio":0.438939,"inputTokens":87788,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.025,"originalCredits":1.025,"afterCompact":false},{"index":105,"time":"14:31","ratio":0.43959,"inputTokens":87918,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.066,"originalCredits":1.066,"afterCompact":false},{"index":106,"time":"14:32","ratio":0.440857,"inputTokens":88171,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.042,"originalCredits":1.042,"afterCompact":false},{"index":107,"time":"14:32","ratio":0.441453,"inputTokens":88291,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.099,"originalCredits":1.099,"afterCompact":false},{"index":108,"time":"14:33","ratio":0.443222,"inputTokens":88644,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.065,"originalCredits":1.065,"afterCompact":false},{"index":109,"time":"14:33","ratio":0.443908,"inputTokens":88782,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.022,"originalCredits":1.022,"afterCompact":false},{"index":110,"time":"14:34","ratio":0.447592,"inputTokens":89518,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.096,"originalCredits":1.096,"afterCompact":false},{"index":111,"time":"14:34","ratio":0.448662,"inputTokens":89732,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.068,"originalCredits":1.068,"afterCompact":false},{"index":112,"time":"14:35","ratio":0.449456,"inputTokens":89891,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.065,"originalCredits":1.065,"afterCompact":false},{"index":113,"time":"14:35","ratio":0.450288,"inputTokens":90058,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.08,"originalCredits":1.08,"afterCompact":false},{"index":114,"time":"14:36","ratio":0.451569,"inputTokens":90314,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.063,"originalCredits":1.063,"afterCompact":false},{"index":115,"time":"14:36","ratio":0.452383,"inputTokens":90477,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.051,"originalCredits":1.051,"afterCompact":false},{"index":116,"time":"14:37","ratio":0.452865,"inputTokens":90573,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.049,"originalCredits":1.049,"afterCompact":false},{"index":117,"time":"14:37","ratio":0.453429,"inputTokens":90686,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.032,"originalCredits":1.032,"afterCompact":false},{"index":118,"time":"14:37","ratio":0.453648,"inputTokens":90730,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.035,"originalCredits":1.035,"afterCompact":false},{"index":119,"time":"14:38","ratio":0.454061,"inputTokens":90812,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.073,"originalCredits":1.073,"afterCompact":false},{"index":120,"time":"14:38","ratio":0.455015,"inputTokens":91003,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.046,"originalCredits":1.046,"afterCompact":false},{"index":121,"time":"14:39","ratio":0.455249,"inputTokens":91050,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.067,"originalCredits":1.067,"afterCompact":false},{"index":122,"time":"14:39","ratio":0.45841,"inputTokens":91682,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.135,"originalCredits":1.135,"afterCompact":false},{"index":123,"time":"14:40","ratio":0.459764,"inputTokens":91953,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.066,"originalCredits":1.066,"afterCompact":false},{"index":124,"time":"14:40","ratio":0.461518,"inputTokens":92304,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.086,"originalCredits":1.086,"afterCompact":false},{"index":125,"time":"14:41","ratio":0.461805,"inputTokens":92361,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.041,"originalCredits":1.041,"afterCompact":false},{"index":126,"time":"14:41","ratio":0.465588,"inputTokens":93118,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.143,"originalCredits":1.143,"afterCompact":false},{"index":127,"time":"14:42","ratio":0.46767,"inputTokens":93534,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.115,"originalCredits":1.115,"afterCompact":false},{"index":128,"time":"14:42","ratio":0.468183,"inputTokens":93637,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.063,"originalCredits":1.063,"afterCompact":false},{"index":129,"time":"14:42","ratio":0.468376,"inputTokens":93675,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.055,"originalCredits":1.055,"afterCompact":false},{"index":130,"time":"14:43","ratio":0.468582,"inputTokens":93716,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.089,"originalCredits":1.089,"afterCompact":false},{"index":131,"time":"14:43","ratio":0.469352,"inputTokens":93870,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.07,"originalCredits":1.07,"afterCompact":false},{"index":132,"time":"14:43","ratio":0.469567,"inputTokens":93913,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.056,"originalCredits":1.056,"afterCompact":false},{"index":133,"time":"14:44","ratio":0.469957,"inputTokens":93991,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.065,"originalCredits":1.065,"afterCompact":false},{"index":134,"time":"14:44","ratio":0.470202,"inputTokens":94040,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.059,"originalCredits":1.059,"afterCompact":false},{"index":135,"time":"14:45","ratio":0.470346,"inputTokens":94069,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.111,"originalCredits":1.111,"afterCompact":false},{"index":136,"time":"14:53","ratio":0.475576,"inputTokens":95115,"outputTokens":null,"usageSource":"transcript-ratio","credits":13.36,"originalCredits":13.36,"afterCompact":false},{"index":137,"time":"14:54","ratio":0.482434,"inputTokens":96487,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.361,"originalCredits":1.361,"afterCompact":false},{"index":138,"time":"14:54","ratio":0.484601,"inputTokens":96920,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.151,"originalCredits":1.151,"afterCompact":false},{"index":139,"time":"14:55","ratio":0.484989,"inputTokens":96998,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.113,"originalCredits":1.113,"afterCompact":false},{"index":140,"time":"14:55","ratio":0.485463,"inputTokens":97093,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.114,"originalCredits":1.114,"afterCompact":false},{"index":141,"time":"14:56","ratio":0.485902,"inputTokens":97180,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.218,"originalCredits":1.218,"afterCompact":false},{"index":142,"time":"14:56","ratio":0.488093,"inputTokens":97619,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.295,"originalCredits":1.295,"afterCompact":false},{"index":143,"time":"14:57","ratio":0.490747,"inputTokens":98149,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.168,"originalCredits":1.168,"afterCompact":false},{"index":144,"time":"14:57","ratio":0.493918,"inputTokens":98784,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.2,"originalCredits":1.2,"afterCompact":false},{"index":145,"time":"14:57","ratio":0.494342,"inputTokens":98868,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.163,"originalCredits":1.163,"afterCompact":false},{"index":146,"time":"14:58","ratio":0.495298,"inputTokens":99060,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.19,"originalCredits":1.19,"afterCompact":false},{"index":147,"time":"14:58","ratio":0.496446,"inputTokens":99289,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.147,"originalCredits":1.147,"afterCompact":false},{"index":148,"time":"14:59","ratio":0.496957,"inputTokens":99391,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.127,"originalCredits":1.127,"afterCompact":false},{"index":149,"time":"14:59","ratio":0.497206,"inputTokens":99441,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.123,"originalCredits":1.123,"afterCompact":false},{"index":150,"time":"14:59","ratio":0.497458,"inputTokens":99492,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.12,"originalCredits":1.12,"afterCompact":false},{"index":151,"time":"15:00","ratio":0.499785,"inputTokens":99957,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.374,"originalCredits":1.374,"afterCompact":false},{"index":152,"time":"15:00","ratio":0.503238,"inputTokens":100648,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.24,"originalCredits":1.24,"afterCompact":false},{"index":153,"time":"15:01","ratio":0.503853,"inputTokens":100771,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.151,"originalCredits":1.151,"afterCompact":false},{"index":154,"time":"15:01","ratio":0.504167,"inputTokens":100833,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.143,"originalCredits":1.143,"afterCompact":false},{"index":155,"time":"15:01","ratio":0.504475,"inputTokens":100895,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.144,"originalCredits":1.144,"afterCompact":false},{"index":156,"time":"15:02","ratio":0.506678,"inputTokens":101336,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.304,"originalCredits":1.304,"afterCompact":false},{"index":157,"time":"15:02","ratio":0.508786,"inputTokens":101757,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.193,"originalCredits":1.193,"afterCompact":false},{"index":158,"time":"15:03","ratio":0.509158,"inputTokens":101832,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.29,"originalCredits":1.29,"afterCompact":false},{"index":159,"time":"15:04","ratio":0.511702,"inputTokens":102340,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.26,"originalCredits":1.26,"afterCompact":false},{"index":160,"time":"15:12","ratio":0.515616,"inputTokens":103123,"outputTokens":null,"usageSource":"transcript-ratio","credits":14.373,"originalCredits":14.373,"afterCompact":false},{"index":161,"time":"15:12","ratio":0.517593,"inputTokens":103519,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.242,"originalCredits":1.242,"afterCompact":false},{"index":162,"time":"15:12","ratio":0.518352,"inputTokens":103670,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.187,"originalCredits":1.187,"afterCompact":false},{"index":163,"time":"15:13","ratio":0.51868,"inputTokens":103736,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.172,"originalCredits":1.172,"afterCompact":false},{"index":164,"time":"15:13","ratio":0.519137,"inputTokens":103827,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.23,"originalCredits":1.23,"afterCompact":false},{"index":165,"time":"15:13","ratio":0.520288,"inputTokens":104058,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.195,"originalCredits":1.195,"afterCompact":false},{"index":166,"time":"15:14","ratio":0.520514,"inputTokens":104103,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.175,"originalCredits":1.175,"afterCompact":false},{"index":167,"time":"15:14","ratio":0.520779,"inputTokens":104156,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.177,"originalCredits":1.177,"afterCompact":false},{"index":168,"time":"15:14","ratio":0.521071,"inputTokens":104214,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.199,"originalCredits":1.199,"afterCompact":false},{"index":169,"time":"15:15","ratio":0.521707,"inputTokens":104341,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.187,"originalCredits":1.187,"afterCompact":false},{"index":170,"time":"15:15","ratio":0.521979,"inputTokens":104396,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.176,"originalCredits":1.176,"afterCompact":false},{"index":171,"time":"15:16","ratio":0.522157,"inputTokens":104431,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.175,"originalCredits":1.175,"afterCompact":false},{"index":172,"time":"15:16","ratio":0.522394,"inputTokens":104479,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.195,"originalCredits":1.195,"afterCompact":false},{"index":173,"time":"15:16","ratio":0.522931,"inputTokens":104586,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.19,"originalCredits":1.19,"afterCompact":false},{"index":174,"time":"15:16","ratio":0.524587,"inputTokens":104917,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.217,"originalCredits":1.217,"afterCompact":false},{"index":175,"time":"15:17","ratio":0.524793,"inputTokens":104959,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.181,"originalCredits":1.181,"afterCompact":false},{"index":176,"time":"15:17","ratio":0.525005,"inputTokens":105001,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.183,"originalCredits":1.183,"afterCompact":false},{"index":177,"time":"15:17","ratio":0.525242,"inputTokens":105048,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.188,"originalCredits":1.188,"afterCompact":false},{"index":178,"time":"15:18","ratio":0.525552,"inputTokens":105110,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.182,"originalCredits":1.182,"afterCompact":false},{"index":179,"time":"15:18","ratio":0.526232,"inputTokens":105246,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.217,"originalCredits":1.217,"afterCompact":false},{"index":180,"time":"15:18","ratio":0.526789,"inputTokens":105358,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.199,"originalCredits":1.199,"afterCompact":false},{"index":181,"time":"15:19","ratio":0.527991,"inputTokens":105598,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.215,"originalCredits":1.215,"afterCompact":false},{"index":182,"time":"15:19","ratio":0.528223,"inputTokens":105645,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.189,"originalCredits":1.189,"afterCompact":false},{"index":183,"time":"15:19","ratio":0.528838,"inputTokens":105768,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.21,"originalCredits":1.21,"afterCompact":false},{"index":184,"time":"15:19","ratio":0.529423,"inputTokens":105885,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.199,"originalCredits":1.199,"afterCompact":false},{"index":185,"time":"15:20","ratio":0.530173,"inputTokens":106035,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.209,"originalCredits":1.209,"afterCompact":false},{"index":186,"time":"15:20","ratio":0.530421,"inputTokens":106084,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.193,"originalCredits":1.193,"afterCompact":false},{"index":187,"time":"15:20","ratio":0.531103,"inputTokens":106221,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.222,"originalCredits":1.222,"afterCompact":false},{"index":188,"time":"15:21","ratio":0.531561,"inputTokens":106312,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.219,"originalCredits":1.219,"afterCompact":false},{"index":189,"time":"15:21","ratio":0.532045,"inputTokens":106409,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.25,"originalCredits":1.25,"afterCompact":false},{"index":190,"time":"15:21","ratio":0.53517,"inputTokens":107034,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.282,"originalCredits":1.282,"afterCompact":false},{"index":191,"time":"15:22","ratio":0.536088,"inputTokens":107218,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.24,"originalCredits":1.24,"afterCompact":false},{"index":192,"time":"15:22","ratio":0.536559,"inputTokens":107312,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.221,"originalCredits":1.221,"afterCompact":false},{"index":193,"time":"15:22","ratio":0.537094,"inputTokens":107419,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.214,"originalCredits":1.214,"afterCompact":false},{"index":194,"time":"15:23","ratio":0.538211,"inputTokens":107642,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.241,"originalCredits":1.241,"afterCompact":false},{"index":195,"time":"15:23","ratio":0.538533,"inputTokens":107707,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.21,"originalCredits":1.21,"afterCompact":false},{"index":196,"time":"15:23","ratio":0.539041,"inputTokens":107808,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.229,"originalCredits":1.229,"afterCompact":false},{"index":197,"time":"15:24","ratio":0.539386,"inputTokens":107877,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.225,"originalCredits":1.225,"afterCompact":false},{"index":198,"time":"15:24","ratio":0.540404,"inputTokens":108081,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.243,"originalCredits":1.243,"afterCompact":false},{"index":199,"time":"15:24","ratio":0.54109,"inputTokens":108218,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.23,"originalCredits":1.23,"afterCompact":false},{"index":200,"time":"15:25","ratio":0.541378,"inputTokens":108276,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.22,"originalCredits":1.22,"afterCompact":false},{"index":201,"time":"15:25","ratio":0.542215,"inputTokens":108443,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.236,"originalCredits":1.236,"afterCompact":false},{"index":202,"time":"15:25","ratio":0.542622,"inputTokens":108524,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.24,"originalCredits":1.24,"afterCompact":false},{"index":203,"time":"15:26","ratio":0.543061,"inputTokens":108612,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.242,"originalCredits":1.242,"afterCompact":false},{"index":204,"time":"15:26","ratio":0.543523,"inputTokens":108705,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.229,"originalCredits":1.229,"afterCompact":false},{"index":205,"time":"15:26","ratio":0.543826,"inputTokens":108765,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.221,"originalCredits":1.221,"afterCompact":false},{"index":206,"time":"15:27","ratio":0.544972,"inputTokens":108994,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.271,"originalCredits":1.271,"afterCompact":false},{"index":207,"time":"15:27","ratio":0.545519,"inputTokens":109104,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.236,"originalCredits":1.236,"afterCompact":false},{"index":208,"time":"15:27","ratio":0.545702,"inputTokens":109140,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.246,"originalCredits":1.246,"afterCompact":false},{"index":209,"time":"15:28","ratio":0.547985,"inputTokens":109597,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.283,"originalCredits":1.283,"afterCompact":false},{"index":210,"time":"15:29","ratio":0.550721,"inputTokens":110144,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.316,"originalCredits":1.316,"afterCompact":false},{"index":211,"time":"15:29","ratio":0.552875,"inputTokens":110575,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.312,"originalCredits":1.312,"afterCompact":false},{"index":212,"time":"15:29","ratio":0.553391,"inputTokens":110678,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.251,"originalCredits":1.251,"afterCompact":false},{"index":213,"time":"15:30","ratio":0.55356,"inputTokens":110712,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.271,"originalCredits":1.271,"afterCompact":false},{"index":214,"time":"15:30","ratio":0.554204,"inputTokens":110841,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.274,"originalCredits":1.274,"afterCompact":false},{"index":215,"time":"15:31","ratio":0.554707,"inputTokens":110941,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.271,"originalCredits":1.271,"afterCompact":false},{"index":216,"time":"15:31","ratio":0.555199,"inputTokens":111040,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.315,"originalCredits":1.315,"afterCompact":false},{"index":217,"time":"15:32","ratio":0.556402,"inputTokens":111280,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.284,"originalCredits":1.284,"afterCompact":false},{"index":218,"time":"15:32","ratio":0.55677,"inputTokens":111354,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.272,"originalCredits":1.272,"afterCompact":false},{"index":219,"time":"15:32","ratio":0.557484,"inputTokens":111497,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.263,"originalCredits":1.263,"afterCompact":false},{"index":220,"time":"15:33","ratio":0.559473,"inputTokens":111895,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.326,"originalCredits":1.326,"afterCompact":false},{"index":221,"time":"15:33","ratio":0.560117,"inputTokens":112023,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.27,"originalCredits":1.27,"afterCompact":false},{"index":222,"time":"15:34","ratio":0.560279,"inputTokens":112056,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.267,"originalCredits":1.267,"afterCompact":false},{"index":223,"time":"15:34","ratio":0.561097,"inputTokens":112219,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.281,"originalCredits":1.281,"afterCompact":false},{"index":224,"time":"15:35","ratio":0.561778,"inputTokens":112356,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.276,"originalCredits":1.276,"afterCompact":false},{"index":225,"time":"15:35","ratio":0.568825,"inputTokens":113765,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.503,"originalCredits":1.503,"afterCompact":false},{"index":226,"time":"15:36","ratio":0.57375,"inputTokens":114750,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.41,"originalCredits":1.41,"afterCompact":false},{"index":227,"time":"15:36","ratio":0.574719,"inputTokens":114944,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.312,"originalCredits":1.312,"afterCompact":false},{"index":228,"time":"15:36","ratio":0.574949,"inputTokens":114990,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.29,"originalCredits":1.29,"afterCompact":false},{"index":229,"time":"15:36","ratio":0.575145,"inputTokens":115029,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.292,"originalCredits":1.292,"afterCompact":false},{"index":230,"time":"15:37","ratio":0.575353,"inputTokens":115071,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.29,"originalCredits":1.29,"afterCompact":false},{"index":231,"time":"15:37","ratio":0.575878,"inputTokens":115176,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.327,"originalCredits":1.327,"afterCompact":false},{"index":232,"time":"15:37","ratio":0.576503,"inputTokens":115301,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.302,"originalCredits":1.302,"afterCompact":false},{"index":233,"time":"15:38","ratio":0.577064,"inputTokens":115413,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.338,"originalCredits":1.338,"afterCompact":false},{"index":234,"time":"15:38","ratio":0.57781,"inputTokens":115562,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.322,"originalCredits":1.322,"afterCompact":false},{"index":235,"time":"15:38","ratio":0.578997,"inputTokens":115799,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.332,"originalCredits":1.332,"afterCompact":false},{"index":236,"time":"15:39","ratio":0.579508,"inputTokens":115902,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.312,"originalCredits":1.312,"afterCompact":false},{"index":237,"time":"15:40","ratio":0.580465,"inputTokens":116093,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.328,"originalCredits":1.328,"afterCompact":false},{"index":238,"time":"15:40","ratio":0.581129,"inputTokens":116226,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.32,"originalCredits":1.32,"afterCompact":false},{"index":239,"time":"15:40","ratio":0.581375,"inputTokens":116275,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.309,"originalCredits":1.309,"afterCompact":false},{"index":240,"time":"15:41","ratio":0.582073,"inputTokens":116415,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.345,"originalCredits":1.345,"afterCompact":false},{"index":241,"time":"15:41","ratio":0.584934,"inputTokens":116987,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.382,"originalCredits":1.382,"afterCompact":false},{"index":242,"time":"15:42","ratio":0.585141,"inputTokens":117028,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.321,"originalCredits":1.321,"afterCompact":false},{"index":243,"time":"15:43","ratio":0.587226,"inputTokens":117445,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.426,"originalCredits":1.426,"afterCompact":false},{"index":244,"time":"15:52","ratio":0.591802,"inputTokens":118360,"outputTokens":null,"usageSource":"transcript-ratio","credits":16.477,"originalCredits":16.477,"afterCompact":false},{"index":245,"time":"15:52","ratio":0.593413,"inputTokens":118683,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.395,"originalCredits":1.395,"afterCompact":false},{"index":246,"time":"15:53","ratio":0.594229,"inputTokens":118846,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.353,"originalCredits":1.353,"afterCompact":false},{"index":247,"time":"15:53","ratio":0.594789,"inputTokens":118958,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.343,"originalCredits":1.343,"afterCompact":false},{"index":248,"time":"15:53","ratio":0.595001,"inputTokens":119000,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.337,"originalCredits":1.337,"afterCompact":false},{"index":249,"time":"15:54","ratio":0.595218,"inputTokens":119044,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.335,"originalCredits":1.335,"afterCompact":false},{"index":250,"time":"15:54","ratio":0.595396,"inputTokens":119079,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.34,"originalCredits":1.34,"afterCompact":false},{"index":251,"time":"15:54","ratio":0.595677,"inputTokens":119135,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.345,"originalCredits":1.345,"afterCompact":false},{"index":252,"time":"15:54","ratio":0.595953,"inputTokens":119191,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.345,"originalCredits":1.345,"afterCompact":false},{"index":253,"time":"15:55","ratio":0.596216,"inputTokens":119243,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.342,"originalCredits":1.342,"afterCompact":false},{"index":254,"time":"15:55","ratio":0.597597,"inputTokens":119519,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.383,"originalCredits":1.383,"afterCompact":false},{"index":255,"time":"15:56","ratio":0.598434,"inputTokens":119687,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.376,"originalCredits":1.376,"afterCompact":false},{"index":256,"time":"15:56","ratio":0.598904,"inputTokens":119781,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.361,"originalCredits":1.361,"afterCompact":false},{"index":257,"time":"15:56","ratio":0.59926,"inputTokens":119852,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.352,"originalCredits":1.352,"afterCompact":false},{"index":258,"time":"15:57","ratio":0.599504,"inputTokens":119901,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.35,"originalCredits":1.35,"afterCompact":false},{"index":259,"time":"15:57","ratio":0.599759,"inputTokens":119952,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.354,"originalCredits":1.354,"afterCompact":false},{"index":260,"time":"16:00","ratio":0.080667,"inputTokens":16133,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.706,"originalCredits":1.706,"afterCompact":true},{"index":261,"time":"16:00","ratio":0.080888,"inputTokens":16178,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.198,"originalCredits":0.198,"afterCompact":false},{"index":262,"time":"16:01","ratio":0.082678,"inputTokens":16536,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.259,"originalCredits":0.259,"afterCompact":false},{"index":263,"time":"16:01","ratio":0.083286,"inputTokens":16657,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.214,"originalCredits":0.214,"afterCompact":false},{"index":264,"time":"16:01","ratio":0.083745,"inputTokens":16749,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.209,"originalCredits":0.209,"afterCompact":false},{"index":265,"time":"16:01","ratio":0.084573,"inputTokens":16915,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.217,"originalCredits":0.217,"afterCompact":false},{"index":266,"time":"16:02","ratio":0.085635,"inputTokens":17127,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.356,"originalCredits":0.356,"afterCompact":false},{"index":267,"time":"16:07","ratio":0.091385,"inputTokens":18277,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.397,"originalCredits":0.397,"afterCompact":false},{"index":268,"time":"16:07","ratio":0.093154,"inputTokens":18631,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.265,"originalCredits":0.265,"afterCompact":false},{"index":269,"time":"16:07","ratio":0.093444,"inputTokens":18689,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.235,"originalCredits":0.235,"afterCompact":false},{"index":270,"time":"16:08","ratio":0.094399,"inputTokens":18880,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.3,"originalCredits":0.3,"afterCompact":false},{"index":271,"time":"16:08","ratio":0.096028,"inputTokens":19206,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.287,"originalCredits":0.287,"afterCompact":false},{"index":272,"time":"16:08","ratio":0.096734,"inputTokens":19347,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.244,"originalCredits":0.244,"afterCompact":false},{"index":273,"time":"16:08","ratio":0.096971,"inputTokens":19394,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.241,"originalCredits":0.241,"afterCompact":false},{"index":274,"time":"16:09","ratio":0.097641,"inputTokens":19528,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.248,"originalCredits":0.248,"afterCompact":false},{"index":275,"time":"16:09","ratio":0.097939,"inputTokens":19588,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.238,"originalCredits":0.238,"afterCompact":false},{"index":276,"time":"16:09","ratio":0.098212,"inputTokens":19642,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.245,"originalCredits":0.245,"afterCompact":false},{"index":277,"time":"16:09","ratio":0.098595,"inputTokens":19719,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.24,"originalCredits":0.24,"afterCompact":false},{"index":278,"time":"16:09","ratio":0.099093,"inputTokens":19819,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.243,"originalCredits":0.243,"afterCompact":false},{"index":279,"time":"16:10","ratio":0.100077,"inputTokens":20015,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.265,"originalCredits":0.265,"afterCompact":false},{"index":280,"time":"16:10","ratio":0.100413,"inputTokens":20083,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.242,"originalCredits":0.242,"afterCompact":false},{"index":281,"time":"16:10","ratio":0.101197,"inputTokens":20239,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.269,"originalCredits":0.269,"afterCompact":false},{"index":282,"time":"16:10","ratio":0.101722,"inputTokens":20344,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.257,"originalCredits":0.257,"afterCompact":false},{"index":283,"time":"16:11","ratio":0.102442,"inputTokens":20488,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.337,"originalCredits":0.337,"afterCompact":false},{"index":284,"time":"16:11","ratio":0.104033,"inputTokens":20807,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.297,"originalCredits":0.297,"afterCompact":false},{"index":285,"time":"16:11","ratio":0.104869,"inputTokens":20974,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.268,"originalCredits":0.268,"afterCompact":false},{"index":286,"time":"16:12","ratio":0.1054,"inputTokens":21080,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.343,"originalCredits":0.343,"afterCompact":false},{"index":287,"time":"16:12","ratio":0.107059,"inputTokens":21412,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.298,"originalCredits":0.298,"afterCompact":false},{"index":288,"time":"16:13","ratio":0.108272,"inputTokens":21654,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.309,"originalCredits":0.309,"afterCompact":false},{"index":289,"time":"16:13","ratio":0.109453,"inputTokens":21891,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.284,"originalCredits":0.284,"afterCompact":false},{"index":290,"time":"16:13","ratio":0.109894,"inputTokens":21979,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.266,"originalCredits":0.266,"afterCompact":false},{"index":291,"time":"16:13","ratio":0.111341,"inputTokens":22268,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.322,"originalCredits":0.322,"afterCompact":false},{"index":292,"time":"16:13","ratio":0.112185,"inputTokens":22437,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.317,"originalCredits":0.317,"afterCompact":false},{"index":293,"time":"16:14","ratio":0.113021,"inputTokens":22604,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.285,"originalCredits":0.285,"afterCompact":false},{"index":294,"time":"16:14","ratio":0.113434,"inputTokens":22687,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.279,"originalCredits":0.279,"afterCompact":false},{"index":295,"time":"16:14","ratio":0.11376,"inputTokens":22752,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.274,"originalCredits":0.274,"afterCompact":false},{"index":296,"time":"16:15","ratio":0.114314,"inputTokens":22863,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.279,"originalCredits":0.279,"afterCompact":false},{"index":297,"time":"16:15","ratio":0.114561,"inputTokens":22912,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.292,"originalCredits":0.292,"afterCompact":false},{"index":298,"time":"16:15","ratio":0.116057,"inputTokens":23211,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.32,"originalCredits":0.32,"afterCompact":false},{"index":299,"time":"16:16","ratio":0.116518,"inputTokens":23304,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.283,"originalCredits":0.283,"afterCompact":false},{"index":300,"time":"16:16","ratio":0.116785,"inputTokens":23357,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.279,"originalCredits":0.279,"afterCompact":false},{"index":301,"time":"16:17","ratio":0.117946,"inputTokens":23589,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.32,"originalCredits":0.32,"afterCompact":false},{"index":302,"time":"16:17","ratio":0.118664,"inputTokens":23733,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.307,"originalCredits":0.307,"afterCompact":false},{"index":303,"time":"16:17","ratio":0.119602,"inputTokens":23920,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.333,"originalCredits":0.333,"afterCompact":false},{"index":304,"time":"16:17","ratio":0.12057,"inputTokens":24114,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.306,"originalCredits":0.306,"afterCompact":false},{"index":305,"time":"16:18","ratio":0.120827,"inputTokens":24165,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.284,"originalCredits":0.284,"afterCompact":false},{"index":306,"time":"16:18","ratio":0.121021,"inputTokens":24204,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.285,"originalCredits":0.285,"afterCompact":false},{"index":307,"time":"16:18","ratio":0.12123,"inputTokens":24246,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.307,"originalCredits":0.307,"afterCompact":false},{"index":308,"time":"16:18","ratio":0.121802,"inputTokens":24360,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.303,"originalCredits":0.303,"afterCompact":false},{"index":309,"time":"16:19","ratio":0.122478,"inputTokens":24496,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.348,"originalCredits":0.348,"afterCompact":false},{"index":310,"time":"16:19","ratio":0.123591,"inputTokens":24718,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.338,"originalCredits":0.338,"afterCompact":false},{"index":311,"time":"16:20","ratio":0.12419,"inputTokens":24838,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.407,"originalCredits":0.407,"afterCompact":false},{"index":312,"time":"16:29","ratio":0.128838,"inputTokens":25768,"outputTokens":null,"usageSource":"transcript-ratio","credits":3.639,"originalCredits":3.639,"afterCompact":false},{"index":313,"time":"16:29","ratio":0.130474,"inputTokens":26095,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.389,"originalCredits":0.389,"afterCompact":false},{"index":314,"time":"16:29","ratio":0.13194,"inputTokens":26388,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.351,"originalCredits":0.351,"afterCompact":false},{"index":315,"time":"16:29","ratio":0.132337,"inputTokens":26467,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.333,"originalCredits":0.333,"afterCompact":false},{"index":316,"time":"16:30","ratio":0.13288,"inputTokens":26576,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.328,"originalCredits":0.328,"afterCompact":false},{"index":317,"time":"16:30","ratio":0.13329,"inputTokens":26658,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.332,"originalCredits":0.332,"afterCompact":false},{"index":318,"time":"16:30","ratio":0.133972,"inputTokens":26794,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.34,"originalCredits":0.34,"afterCompact":false},{"index":319,"time":"16:30","ratio":0.134454,"inputTokens":26891,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.323,"originalCredits":0.323,"afterCompact":false},{"index":320,"time":"16:30","ratio":0.134715,"inputTokens":26943,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.332,"originalCredits":0.332,"afterCompact":false},{"index":321,"time":"16:30","ratio":0.135229,"inputTokens":27046,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.321,"originalCredits":0.321,"afterCompact":false},{"index":322,"time":"16:31","ratio":0.135428,"inputTokens":27086,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.314,"originalCredits":0.314,"afterCompact":false},{"index":323,"time":"16:31","ratio":0.13572,"inputTokens":27144,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.336,"originalCredits":0.336,"afterCompact":false},{"index":324,"time":"16:32","ratio":0.136206,"inputTokens":27241,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.326,"originalCredits":0.326,"afterCompact":false},{"index":325,"time":"16:32","ratio":0.137455,"inputTokens":27491,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.376,"originalCredits":0.376,"afterCompact":false},{"index":326,"time":"16:32","ratio":0.138366,"inputTokens":27673,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.385,"originalCredits":0.385,"afterCompact":false},{"index":327,"time":"16:32","ratio":0.139538,"inputTokens":27908,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.361,"originalCredits":0.361,"afterCompact":false},{"index":328,"time":"16:33","ratio":0.139919,"inputTokens":27984,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.334,"originalCredits":0.334,"afterCompact":false},{"index":329,"time":"16:33","ratio":0.140563,"inputTokens":28113,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.335,"originalCredits":0.335,"afterCompact":false},{"index":330,"time":"16:33","ratio":0.14119,"inputTokens":28238,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.344,"originalCredits":0.344,"afterCompact":false},{"index":331,"time":"16:33","ratio":0.141943,"inputTokens":28389,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.369,"originalCredits":0.369,"afterCompact":false},{"index":332,"time":"16:33","ratio":0.142764,"inputTokens":28553,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.345,"originalCredits":0.345,"afterCompact":false},{"index":333,"time":"16:34","ratio":0.143198,"inputTokens":28640,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.371,"originalCredits":0.371,"afterCompact":false},{"index":334,"time":"16:34","ratio":0.143934,"inputTokens":28787,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.357,"originalCredits":0.357,"afterCompact":false},{"index":335,"time":"16:34","ratio":0.144287,"inputTokens":28857,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.354,"originalCredits":0.354,"afterCompact":false},{"index":336,"time":"16:34","ratio":0.144853,"inputTokens":28971,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.351,"originalCredits":0.351,"afterCompact":false},{"index":337,"time":"16:35","ratio":0.145193,"inputTokens":29039,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.342,"originalCredits":0.342,"afterCompact":false},{"index":338,"time":"16:35","ratio":0.145721,"inputTokens":29144,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.357,"originalCredits":0.357,"afterCompact":false},{"index":339,"time":"16:35","ratio":0.146312,"inputTokens":29262,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.351,"originalCredits":0.351,"afterCompact":false},{"index":340,"time":"16:36","ratio":0.146615,"inputTokens":29323,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.343,"originalCredits":0.343,"afterCompact":false},{"index":341,"time":"16:36","ratio":0.14683,"inputTokens":29366,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.386,"originalCredits":0.386,"afterCompact":false},{"index":342,"time":"16:36","ratio":0.147875,"inputTokens":29575,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.363,"originalCredits":0.363,"afterCompact":false},{"index":343,"time":"16:37","ratio":0.148147,"inputTokens":29629,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.395,"originalCredits":0.395,"afterCompact":false},{"index":344,"time":"16:37","ratio":0.149273,"inputTokens":29855,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.379,"originalCredits":0.379,"afterCompact":false},{"index":345,"time":"16:37","ratio":0.149896,"inputTokens":29979,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.356,"originalCredits":0.356,"afterCompact":false},{"index":346,"time":"16:38","ratio":0.150132,"inputTokens":30026,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.359,"originalCredits":0.359,"afterCompact":false},{"index":347,"time":"16:38","ratio":0.150701,"inputTokens":30140,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.361,"originalCredits":0.361,"afterCompact":false},{"index":348,"time":"16:38","ratio":0.151291,"inputTokens":30258,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.37,"originalCredits":0.37,"afterCompact":false},{"index":349,"time":"16:39","ratio":0.151623,"inputTokens":30325,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.466,"originalCredits":0.466,"afterCompact":false},{"index":350,"time":"16:47","ratio":0.15602,"inputTokens":31204,"outputTokens":null,"usageSource":"transcript-ratio","credits":4.44,"originalCredits":4.44,"afterCompact":false},{"index":351,"time":"16:48","ratio":0.158016,"inputTokens":31603,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.449,"originalCredits":0.449,"afterCompact":false},{"index":352,"time":"16:48","ratio":0.158966,"inputTokens":31793,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.393,"originalCredits":0.393,"afterCompact":false},{"index":353,"time":"16:48","ratio":0.159265,"inputTokens":31853,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.372,"originalCredits":0.372,"afterCompact":false},{"index":354,"time":"16:48","ratio":0.160367,"inputTokens":32073,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.404,"originalCredits":0.404,"afterCompact":false},{"index":355,"time":"16:49","ratio":0.160726,"inputTokens":32145,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.426,"originalCredits":0.426,"afterCompact":false},{"index":356,"time":"16:49","ratio":0.16198,"inputTokens":32396,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.404,"originalCredits":0.404,"afterCompact":false},{"index":357,"time":"16:49","ratio":0.162227,"inputTokens":32445,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.38,"originalCredits":0.38,"afterCompact":false},{"index":358,"time":"16:49","ratio":0.162971,"inputTokens":32594,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.461,"originalCredits":0.461,"afterCompact":false},{"index":359,"time":"16:50","ratio":0.164358,"inputTokens":32872,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.441,"originalCredits":0.441,"afterCompact":false},{"index":360,"time":"16:50","ratio":0.165111,"inputTokens":33022,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.399,"originalCredits":0.399,"afterCompact":false},{"index":361,"time":"16:50","ratio":0.165394,"inputTokens":33079,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.385,"originalCredits":0.385,"afterCompact":false},{"index":362,"time":"16:50","ratio":0.165859,"inputTokens":33172,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.387,"originalCredits":0.387,"afterCompact":false},{"index":363,"time":"16:51","ratio":0.165988,"inputTokens":33198,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.379,"originalCredits":0.379,"afterCompact":false},{"index":364,"time":"16:51","ratio":0.166172,"inputTokens":33234,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.386,"originalCredits":0.386,"afterCompact":false},{"index":365,"time":"16:51","ratio":0.166582,"inputTokens":33316,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.402,"originalCredits":0.402,"afterCompact":false},{"index":366,"time":"16:52","ratio":0.166977,"inputTokens":33395,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.418,"originalCredits":0.418,"afterCompact":false},{"index":367,"time":"16:52","ratio":0.167668,"inputTokens":33534,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.399,"originalCredits":0.399,"afterCompact":false},{"index":368,"time":"16:52","ratio":0.168166,"inputTokens":33633,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.424,"originalCredits":0.424,"afterCompact":false},{"index":369,"time":"16:52","ratio":0.169069,"inputTokens":33814,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.41,"originalCredits":0.41,"afterCompact":false},{"index":370,"time":"16:53","ratio":0.16931,"inputTokens":33862,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.411,"originalCredits":0.411,"afterCompact":false},{"index":371,"time":"16:53","ratio":0.169828,"inputTokens":33966,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.401,"originalCredits":0.401,"afterCompact":false},{"index":372,"time":"16:53","ratio":0.170206,"inputTokens":34041,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.41,"originalCredits":0.41,"afterCompact":false},{"index":373,"time":"16:54","ratio":0.170632,"inputTokens":34126,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.467,"originalCredits":0.467,"afterCompact":false},{"index":374,"time":"16:54","ratio":0.172062,"inputTokens":34412,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.438,"originalCredits":0.438,"afterCompact":false},{"index":375,"time":"16:55","ratio":0.172407,"inputTokens":34481,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.48,"originalCredits":0.48,"afterCompact":false},{"index":376,"time":"17:03","ratio":0.17556,"inputTokens":35112,"outputTokens":null,"usageSource":"transcript-ratio","credits":4.901,"originalCredits":4.901,"afterCompact":false},{"index":377,"time":"17:03","ratio":0.176387,"inputTokens":35277,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.431,"originalCredits":0.431,"afterCompact":false},{"index":378,"time":"17:03","ratio":0.176739,"inputTokens":35348,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.414,"originalCredits":0.414,"afterCompact":false},{"index":379,"time":"17:03","ratio":0.177159,"inputTokens":35432,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.433,"originalCredits":0.433,"afterCompact":false},{"index":380,"time":"17:04","ratio":0.177694,"inputTokens":35539,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.471,"originalCredits":0.471,"afterCompact":false},{"index":381,"time":"17:04","ratio":0.179003,"inputTokens":35801,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.454,"originalCredits":0.454,"afterCompact":false},{"index":382,"time":"17:04","ratio":0.179425,"inputTokens":35885,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.462,"originalCredits":0.462,"afterCompact":false},{"index":383,"time":"17:05","ratio":0.180371,"inputTokens":36074,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.514,"originalCredits":0.514,"afterCompact":false},{"index":384,"time":"17:05","ratio":0.18193,"inputTokens":36386,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.451,"originalCredits":0.451,"afterCompact":false},{"index":385,"time":"17:05","ratio":0.182412,"inputTokens":36482,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.426,"originalCredits":0.426,"afterCompact":false},{"index":386,"time":"17:06","ratio":0.182619,"inputTokens":36524,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.421,"originalCredits":0.421,"afterCompact":false},{"index":387,"time":"17:06","ratio":0.18285,"inputTokens":36570,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.423,"originalCredits":0.423,"afterCompact":false},{"index":388,"time":"17:06","ratio":0.183142,"inputTokens":36628,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.429,"originalCredits":0.429,"afterCompact":false},{"index":389,"time":"17:07","ratio":0.183668,"inputTokens":36734,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.431,"originalCredits":0.431,"afterCompact":false},{"index":390,"time":"17:08","ratio":0.183976,"inputTokens":36795,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.479,"originalCredits":0.479,"afterCompact":false},{"index":391,"time":"17:08","ratio":0.185178,"inputTokens":37036,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.484,"originalCredits":0.484,"afterCompact":false},{"index":392,"time":"17:09","ratio":0.18712,"inputTokens":37424,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.517,"originalCredits":0.517,"afterCompact":false},{"index":393,"time":"17:09","ratio":0.18812,"inputTokens":37624,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.464,"originalCredits":0.464,"afterCompact":false},{"index":394,"time":"17:09","ratio":0.189593,"inputTokens":37919,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.478,"originalCredits":0.478,"afterCompact":false},{"index":395,"time":"17:10","ratio":0.189941,"inputTokens":37988,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.44,"originalCredits":0.44,"afterCompact":false},{"index":396,"time":"17:10","ratio":0.190353,"inputTokens":38071,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.453,"originalCredits":0.453,"afterCompact":false},{"index":397,"time":"17:10","ratio":0.190739,"inputTokens":38148,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.474,"originalCredits":0.474,"afterCompact":false},{"index":398,"time":"17:11","ratio":0.192213,"inputTokens":38443,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.507,"originalCredits":0.507,"afterCompact":false},{"index":399,"time":"18:44","ratio":0.192994,"inputTokens":38599,"outputTokens":null,"usageSource":"transcript-ratio","credits":5.381,"originalCredits":5.381,"afterCompact":false},{"index":400,"time":"18:44","ratio":0.195621,"inputTokens":39124,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.566,"originalCredits":0.566,"afterCompact":false},{"index":401,"time":"18:45","ratio":0.196962,"inputTokens":39392,"outputTokens":null,"usageSource":"transcript-ratio","credits":4.985,"originalCredits":4.985,"afterCompact":false},{"index":402,"time":"18:45","ratio":0.197962,"inputTokens":39592,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.488,"originalCredits":0.488,"afterCompact":false},{"index":403,"time":"18:46","ratio":0.198355,"inputTokens":39671,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.473,"originalCredits":0.473,"afterCompact":false},{"index":404,"time":"22:13","ratio":0.20892,"inputTokens":41784,"outputTokens":null,"usageSource":"transcript-ratio","credits":5.909,"originalCredits":5.909,"afterCompact":false},{"index":405,"time":"22:13","ratio":0.210811,"inputTokens":42162,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.559,"originalCredits":0.559,"afterCompact":false},{"index":406,"time":"22:13","ratio":0.211583,"inputTokens":42317,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.599,"originalCredits":0.599,"afterCompact":false},{"index":407,"time":"22:14","ratio":0.214284,"inputTokens":42857,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.609,"originalCredits":0.609,"afterCompact":false},{"index":408,"time":"22:15","ratio":0.21541,"inputTokens":43082,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.598,"originalCredits":0.598,"afterCompact":false},{"index":409,"time":"22:15","ratio":0.217039,"inputTokens":43408,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.574,"originalCredits":0.574,"afterCompact":false},{"index":410,"time":"22:16","ratio":0.218194,"inputTokens":43639,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.643,"originalCredits":0.643,"afterCompact":false},{"index":411,"time":"22:16","ratio":0.220361,"inputTokens":44072,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.564,"originalCredits":0.564,"afterCompact":false},{"index":412,"time":"22:17","ratio":0.220741,"inputTokens":44148,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.541,"originalCredits":0.541,"afterCompact":false},{"index":413,"time":"22:17","ratio":0.221712,"inputTokens":44342,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.536,"originalCredits":0.536,"afterCompact":false},{"index":414,"time":"22:17","ratio":0.222064,"inputTokens":44413,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.524,"originalCredits":0.524,"afterCompact":false},{"index":415,"time":"22:18","ratio":0.222466,"inputTokens":44493,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.527,"originalCredits":0.527,"afterCompact":false},{"index":416,"time":"22:18","ratio":0.223073,"inputTokens":44615,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.528,"originalCredits":0.528,"afterCompact":false},{"index":417,"time":"22:18","ratio":0.2237,"inputTokens":44740,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.521,"originalCredits":0.521,"afterCompact":false},{"index":418,"time":"22:18","ratio":0.223967,"inputTokens":44793,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.511,"originalCredits":0.511,"afterCompact":false},{"index":419,"time":"22:19","ratio":0.224681,"inputTokens":44936,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.552,"originalCredits":0.552,"afterCompact":false},{"index":420,"time":"22:19","ratio":0.225521,"inputTokens":45104,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.535,"originalCredits":0.535,"afterCompact":false},{"index":421,"time":"22:19","ratio":0.225752,"inputTokens":45150,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.519,"originalCredits":0.519,"afterCompact":false},{"index":422,"time":"22:19","ratio":0.226244,"inputTokens":45249,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.565,"originalCredits":0.565,"afterCompact":false},{"index":423,"time":"22:20","ratio":0.227085,"inputTokens":45417,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.582,"originalCredits":0.582,"afterCompact":false},{"index":424,"time":"22:20","ratio":0.228112,"inputTokens":45622,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.549,"originalCredits":0.549,"afterCompact":false},{"index":425,"time":"22:20","ratio":0.228794,"inputTokens":45759,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.553,"originalCredits":0.553,"afterCompact":false},{"index":426,"time":"22:21","ratio":0.229414,"inputTokens":45883,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.551,"originalCredits":0.551,"afterCompact":false},{"index":427,"time":"22:21","ratio":0.23012,"inputTokens":46024,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.562,"originalCredits":0.562,"afterCompact":false},{"index":428,"time":"22:21","ratio":0.230725,"inputTokens":46145,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.582,"originalCredits":0.582,"afterCompact":false},{"index":429,"time":"09:39","ratio":0.241522,"inputTokens":48304,"outputTokens":null,"usageSource":"transcript-ratio","credits":5.591,"originalCredits":5.591,"afterCompact":false},{"index":430,"time":"09:39","ratio":0.246124,"inputTokens":49225,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.244,"originalCredits":0.244,"afterCompact":false},{"index":431,"time":"09:39","ratio":0.24729,"inputTokens":49458,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.139,"originalCredits":0.139,"afterCompact":false},{"index":432,"time":"09:40","ratio":0.247615,"inputTokens":49523,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.381,"originalCredits":0.381,"afterCompact":false},{"index":433,"time":"09:50","ratio":0.257368,"inputTokens":51474,"outputTokens":null,"usageSource":"transcript-ratio","credits":0.607,"originalCredits":0.607,"afterCompact":false},{"index":434,"time":"09:56","ratio":0.302806,"inputTokens":60561,"outputTokens":null,"usageSource":"transcript-ratio","credits":1.354,"originalCredits":1.354,"afterCompact":false}],"identity":{"sumAttributed":28403060,"billedInputTokens":28403060,"absDiff":0,"ok":true},"pluginVersion":"2.7.4","selfCmd":"\"D:\\DevCache\\.qoder-cn\\plugins\\cache\\qoder-marketplace\\qoder-credits-inspector\\2.7.4\\bin\\credits-inspector.cmd\" cli","linkHealth":{"matched":0,"requests":434,"coverage":0,"usageSource":"transcript-ratio","breakdown":{"proxy":0,"transcriptTokens":0,"transcriptRatio":434},"logRecords":0,"lastRecordAt":null,"recentRequests":0,"proxyErrors":null,"clientAbort":null,"clientAbortMaxMs":null,"upstreamRejected":null,"aborted":null,"truncated":null,"noUsage":null,"slowestMs":null,"supersededFailure":null},"subagents":{"scanned":true,"dir":"D:\\DevCache\\.qoder-cn\\projects\\D-----LANLinkShopping-Shopping\\6af61636-da3f-4e28-993e-8d3b7b9c1bd7\\subagents","probedPaths":["D:\\DevCache\\.qoder-cn\\projects\\D-----LANLinkShopping-Shopping\\6af61636-da3f-4e28-993e-8d3b7b9c1bd7\\subagents","D:\\DevCache\\.qoder-cn\\projects\\6af61636-da3f-4e28-993e-8d3b7b9c1bd7\\subagents"],"reason":"ok","count":28,"items":[{"agentId":"aExplore-4570c0da62873aaf","agentType":"Explore","description":"排查现有 AI/RAG 链路","toolUseId":"call_8490ab3581d24878adca7a40","roundTrips":37,"billedInputTokens":995921,"peakContextRatio":0.1866,"credits":26.229,"originalCredits":26.229,"error":null},{"agentId":"aExplore-da477b9d807c8804","agentType":"Explore","description":"排查商品数据与集成点","toolUseId":"call_0d6b4a7fe61b47f09901df6c","roundTrips":35,"billedInputTokens":686646,"peakContextRatio":0.1412,"credits":18.763,"originalCredits":18.763,"error":null},{"agentId":"aExplore-c15a7aba244534a5","agentType":"Explore","description":"排查安全与部署暴露面","toolUseId":"call_b68d41fbdc1049d9b82769e9","roundTrips":31,"billedInputTokens":617571,"peakContextRatio":0.1392,"credits":16.832,"originalCredits":16.832,"error":null},{"agentId":"aPlan-2fbaf9e5facab252","agentType":"Plan","description":"复核并发修复","toolUseId":"call_0624a974d2114d6a813a7285","roundTrips":31,"billedInputTokens":691850,"peakContextRatio":0.1497,"credits":12.494,"originalCredits":12.494,"error":null},{"agentId":"ageneral-purpose-efd537e24d727d8f","agentType":"general-purpose","description":"调研电商 RAG 与安全方案","toolUseId":"call_291a3abef02a466e9c6fa281","roundTrips":15,"billedInputTokens":304383,"peakContextRatio":0.1433,"credits":10.39,"originalCredits":10.39,"error":null},{"agentId":"aPlan-8a4549d5e1027217","agentType":"Plan","description":"最终审查安全改动","toolUseId":"call_152d57a696044168a44cc666","roundTrips":26,"billedInputTokens":480056,"peakContextRatio":0.1298,"credits":9.478,"originalCredits":9.478,"error":null},{"agentId":"aPlan-050d74b26214e7b0","agentType":"Plan","description":"审查知识 API","toolUseId":"call_ed3b7c5a2961457b8abeeba0","roundTrips":16,"billedInputTokens":361403,"peakContextRatio":0.1595,"credits":9.052,"originalCredits":9.052,"error":null},{"agentId":"aExplore-1c2d10cf4a58a204","agentType":"Explore","description":"Review final round changes","toolUseId":"call_4803217d74524b9294963d2e","roundTrips":27,"billedInputTokens":462123,"peakContextRatio":0.1116,"credits":8.94,"originalCredits":8.94,"error":null},{"agentId":"ageneral-purpose-abae8d96a3f43c8d","agentType":"general-purpose","description":"调研同步一致性方案","toolUseId":"call_b1f398bf610c4f0894de3f65","roundTrips":19,"billedInputTokens":391141,"peakContextRatio":0.1301,"credits":8.471,"originalCredits":8.471,"error":null},{"agentId":"aPlan-12a5c0990bce0d8b","agentType":"Plan","description":"审查 API 鉴权改动","toolUseId":"call_e0f7dc1d391048fa9d386b03","roundTrips":23,"billedInputTokens":404703,"peakContextRatio":0.1136,"credits":8.156,"originalCredits":8.156,"error":null},{"agentId":"aExplore-7f0fab0120a487dd","agentType":"Explore","description":"Review audit and breaker round","toolUseId":"call_41107a2b1d2d4e7fba9603c0","roundTrips":26,"billedInputTokens":384271,"peakContextRatio":0.0931,"credits":7.59,"originalCredits":7.59,"error":null},{"agentId":"aExplore-22cff117617e4067","agentType":"Explore","description":"Find HTTP status change consumers","toolUseId":"call_617d5357ce7e471dbc2a9bc7","roundTrips":21,"billedInputTokens":358134,"peakContextRatio":0.1079,"credits":7.204,"originalCredits":7.204,"error":null},{"agentId":"aPlan-3dedcc0b17200897","agentType":"Plan","description":"审查商品同步第3步","toolUseId":"call_7796ced4f3f04ae1b08bdff7","roundTrips":17,"billedInputTokens":310831,"peakContextRatio":0.1156,"credits":7.133,"originalCredits":7.133,"error":null},{"agentId":"aPlan-9d53591f6d5d51dc","agentType":"Plan","description":"审查 outbox 第一步","toolUseId":"call_a7526310617a4d2bb0ba9812","roundTrips":17,"billedInputTokens":301887,"peakContextRatio":0.1143,"credits":7.006,"originalCredits":7.006,"error":null},{"agentId":"aExplore-346ac6728caf951a","agentType":"Explore","description":"Final review of last round","toolUseId":"call_8c4d4c9df1d149f7b6229b6a","roundTrips":15,"billedInputTokens":279590,"peakContextRatio":0.1101,"credits":6.908,"originalCredits":6.908,"error":null},{"agentId":"aExplore-2bfc0684ab94a949","agentType":"Explore","description":"Map features to services","toolUseId":"call_01_uWb2auG8hLBr6rsQlrpO0999","roundTrips":42,"billedInputTokens":932168,"peakContextRatio":0.1472,"credits":6.604,"originalCredits":6.604,"error":null},{"agentId":"aExplore-580f968835b18495","agentType":"Explore","description":"Verify external AI switch points","toolUseId":"call_00_bCYmVqLEcmYftVugYYgG9437","roundTrips":30,"billedInputTokens":768631,"peakContextRatio":0.1658,"credits":6.281,"originalCredits":6.281,"error":null},{"agentId":"ageneral-purpose-8b5c591406fe8045","agentType":"general-purpose","description":"Compare breaker and audit norms","toolUseId":"call_2316a68cb09d423992ff2017","roundTrips":16,"billedInputTokens":245962,"peakContextRatio":0.1009,"credits":6.206,"originalCredits":6.206,"error":null},{"agentId":"ageneral-purpose-cc1eb9b3fe49e03f","agentType":"general-purpose","description":"调研对外集成契约","toolUseId":"call_e626e7057ea54ff8b8026386","roundTrips":14,"billedInputTokens":230204,"peakContextRatio":0.1057,"credits":5.922,"originalCredits":5.922,"error":null},{"agentId":"aPlan-dcc30f619eee6d42","agentType":"Plan","description":"审查 outbox 实现","toolUseId":"call_e662b0c9cf744ccf9ad1bcfa","roundTrips":13,"billedInputTokens":204449,"peakContextRatio":0.1044,"credits":5.349,"originalCredits":5.349,"error":null},{"agentId":"aExplore-bf7edc85944d2f14","agentType":"Explore","description":"Audit final security fixes","toolUseId":"call_6f389404b6c044edaf4bb079","roundTrips":11,"billedInputTokens":190173,"peakContextRatio":0.1037,"credits":5.218,"originalCredits":5.218,"error":null},{"agentId":"ageneral-purpose-5e980edd8f2285da","agentType":"general-purpose","description":"Research e-commerce agent API standards","toolUseId":"call_2aa2741a884045eebff64c47","roundTrips":10,"billedInputTokens":167921,"peakContextRatio":0.1078,"credits":5.168,"originalCredits":5.168,"error":null},{"agentId":"ageneral-purpose-f666a6ab941363d1","agentType":"general-purpose","description":"调研状态码兼容策略","toolUseId":"call_0843dbddc30448aabe38023e","roundTrips":15,"billedInputTokens":223882,"peakContextRatio":0.0881,"credits":5.164,"originalCredits":5.164,"error":null},{"agentId":"ageneral-purpose-538bf87fca42f939","agentType":"general-purpose","description":"复核锁方案对比","toolUseId":"call_92d28ab2f25a435c88e1f572","roundTrips":12,"billedInputTokens":182218,"peakContextRatio":0.0943,"credits":5.078,"originalCredits":5.078,"error":null},{"agentId":"ageneral-purpose-7cc5ad70d4a76663","agentType":"general-purpose","description":"调研对比入库方案","toolUseId":"call_33d180cbb0274af2a3f43a4d","roundTrips":7,"billedInputTokens":122185,"peakContextRatio":0.1221,"credits":4.846,"originalCredits":4.846,"error":null},{"agentId":"ageneral-purpose-94fcca969b8db460","agentType":"general-purpose","description":"Research 2C4G capacity data","toolUseId":"call_02_JtX0e64SPH7IxhW1KIFL7611","roundTrips":9,"billedInputTokens":190945,"peakContextRatio":0.1508,"credits":4.578,"originalCredits":4.578,"error":null},{"agentId":"ageneral-purpose-ad0f82f8381e0163","agentType":"general-purpose","description":"最终集成方案对比","toolUseId":"call_6bbf2c7a81a94c4683788832","roundTrips":7,"billedInputTokens":95450,"peakContextRatio":0.0868,"credits":3.55,"originalCredits":3.55,"error":null},{"agentId":"aExplore-2aba4f9722778b0b","agentType":"Explore","description":"Inventory services and footprints","toolUseId":"call_00_aMt4Sv52Ko7A2bg5aoRy9917","roundTrips":10,"billedInputTokens":139125,"peakContextRatio":0.0908,"credits":2.953,"originalCredits":2.953,"error":null}],"totals":{"roundTrips":552,"billedInputTokens":10723823,"credits":231.563,"originalCredits":231.563},"combined":{"roundTrips":986,"billedInputTokens":39126883,"credits":689.65,"originalCredits":689.65}},"manual":null,"artifacts":{"report":"report.json","canvas":"来自另一个 Qoder 会话（在做 micros__6af61636.canvas.tsx"}} as unknown as Report;

function human(n: number): string {
  if (!isFinite(n)) return "—";
  const abs = Math.abs(n);
  if (abs >= 1e9) return (n / 1e9).toFixed(2) + "B";
  if (abs >= 1e6) return (n / 1e6).toFixed(2) + "M";
  if (abs >= 1e3) return (n / 1e3).toFixed(1) + "K";
  return String(Math.round(n));
}

function pct(x: number): string {
  return (x * 100).toFixed(1) + "%";
}

// —— 可用性口径：把 report.availability 翻成人话，并决定某个数字该显示值、「≈」还是「—」——
const AVAIL_LABEL: Record<string, string> = {
  measured: "实测",
  derived: "推导",
  fallback: "回退",
  manual: "手工",
  unavailable: "不可用",
};

const SOURCE_LABEL: Record<string, string> = {
  "desktop-rich": "桌面端富转录",
  "ide-lite": "IDE 端精简转录",
  unknown: "来源未知",
};

function availOf(av: Availability | undefined, key: keyof Availability, dflt: Avail = "measured"): Avail {
  return (av?.[key] as Avail | undefined) ?? dflt;
}

/** 段标题旁的性质标注：实测不标（默认就是实测），其余标出来。 */
function availTag(av: Availability | undefined, key: keyof Availability, dflt: Avail = "measured"): string {
  const v = availOf(av, key, dflt);
  return v === "measured" ? "" : `（${AVAIL_LABEL[v]}）`;
}

/** 不可用的量显示「—」而不是 0：IDE 端的 0 是「读不到」，不是「没发生」。 */
function orDash(
  v: number,
  av: Availability | undefined,
  key: keyof Availability,
  fmt: (n: number) => string = String
): string {
  return availOf(av, key) === "unavailable" ? "—" : fmt(v);
}

function kindLabel(kind: string): string {
  switch (kind) {
    case "read":
      return "读取";
    case "write":
      return "写入";
    case "search":
      return "搜索";
    case "shell":
      return "命令";
    default:
      return "其他";
  }
}

function shortTime(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  const p = (v: number) => String(v).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export default function SessionTokensReport() {
  if (!REPORT) {
    return (
      <ReportShell width="wide" ariaLabel="会话 token 消耗截析">
        <Stack gap="component">
          <H1>会话 token 消耗截析</H1>
          <Text tone="secondary">报告数据尚未注入。请在会话中触发一次 Stop 钩子，或运行 CLI 生成 report.json。</Text>
        </Stack>
      </ReportShell>
    );
  }

  const r = REPORT;
  const s = r.session;
  const c = r.context;
  const t = r.totals;
  const av = r.availability;
  const manual = r.manual ?? null;
  const sourceLabel = SOURCE_LABEL[r.source ?? "unknown"] ?? "来源未知";
  // 有没有 usage 决定整份报告是「真值/推导」还是「一律 —」。手工回填的 credits 不算有 usage，
  // 否则下面按 ratio 推导的图表会照画一堆 0。
  const hasUsage = availOf(av, "roundTrips") === "measured";
  const manualCredits = manual?.credits ?? null;

  // 报告里出现的命令一律用数据层注入的插件入口（Windows 上是自带启动器的绝对路径，免装 Node）。
  // 旧报告没有这个字段时回退原写法，不比改动前更差。
  const CLI = r.selfCmd || "node scripts/cli.mjs";

  // credits 覆盖范围：credits 与「计费输入总量」并排放在头部，读者一除就得到单价。
  // 混合会话里 credits 只来自转录路径的那几笔（实测某会话 413 笔里只有 1 笔带 credits），
  // 部分覆盖不说出来 = 把局部真值当全量展示。
  const cov = r.creditsCoverage ?? null;
  const covPartial = !!(cov && !cov.full && cov.roundTrips > 0);
  const covNote = covPartial ? ` · 仅覆盖 ${cov!.trips}/${cov!.roundTrips} 笔往返（占计费输入 ${pct(cov!.tokenShare)}）` : "";

  // 缓存命中：代理记录里一直带着 cachedTokens，此前采集了却从不呈现。
  const cachedMeasured = availOf(av, "cachedTokens") === "measured";
  const cachedText = cachedMeasured
    ? `输入 ${human(t.billedInputTokens)}，其中缓存命中 ${human(t.cachedTokens ?? 0)}（${pct(t.cachedShare ?? 0)}，${t.cachedTrips ?? 0} 笔往返带缓存）——供应商对重复叠加的前缀打折，故按 token 数的节省大于按计费的节省。`
    : "";

  // 代理告警只在「失败记录仍然成立」时才报：status.json 只写不清，陈旧失败会把用户推去改
  // 本来正确的 Base URL，越修越坏；而同一份报告里的 lastRecordAt / matched 是它在跑的硬证据。
  const proxyAlarm = r.proxy?.status && !r.proxy.statusSuperseded ? r.proxy.status : null;
  const proxyAlive = !proxyAlarm && (r.proxy?.logRecords ?? 0) > 0;

  // 官方 UI 一场会话扣费 = 主链 + 子代理，故有子代理时 headline 必须给 combined，否则比 UI 少一截
  const creditScope =
    r.subagents && r.subagents.count > 0
      ? {
          credits: r.subagents.combined.credits,
          note: `主链 ${t.credits} + 子代理 ${r.subagents.totals.credits} · 原始 ${r.subagents.combined.originalCredits}`,
        }
      : { credits: t.credits, note: `原始 ${t.originalCredits}（实测）` };

  const headline: MetricItem[] = [
    {
      label: "计费输入总量",
      value: hasUsage ? human(t.billedInputTokens) : "—",
      description: hasUsage
        ? availOf(av, "tokens") === "measured"
          ? `Σ prompt_tokens（实测真值）${covPartial ? ` · 覆盖全部 ${cov!.roundTrips} 笔往返` : ""}`
          : `Σ 上下文 × 往返${availTag(av, "tokens", "derived")}${covPartial ? ` · 覆盖全部 ${cov!.roundTrips} 笔往返` : ""}`
        : "转录无 usage",
    },
    {
      label: "当前上下文",
      value: hasUsage ? human(t.netContextTokens) : "—",
      description: hasUsage
        ? `峰值 ${pct(c.peakContextRatio)}${s.compactions ? "（压缩后起算）" : ""}`
        : "转录无 usage",
    },
    {
      label: "重发放大",
      value: hasUsage ? `${t.amplification}×` : "—",
      description: `往返 ${hasUsage ? `${s.roundTrips} 次` : "—"}`,
    },
    {
      label: "Credits",
      // 代理路径（自定义模型）token 是实测真值但 credits 无来源：hasUsage 为真也要显示「—」，
      // 否则 t.credits=0 会被读成「这次没花钱」。
      value:
        manualCredits != null
          ? `${manualCredits}`
          : hasUsage && availOf(av, "credits") !== "unavailable"
            ? `${creditScope.credits}`
            : "—",
      description:
        manualCredits != null
          ? `官方 UI 手工录入 · 本地 ${creditScope.credits}`
          : hasUsage && availOf(av, "credits") !== "unavailable"
            ? `${creditScope.note}${covNote}`
            : r.usageSource === "proxy" || r.usageSource === "transcript-tokens" || r.usageSource === "mixed"
              ? "自定义模型（BYOK）不经 Qoder 计费网关，无 credits 真值"
              : hasUsage
                ? "转录带 usage 但没有 credits 字段，本地无计费真值"
                : "本地不可用，见下方告警",
    },
  ];

  const pieData = r.byCategory.map((x) => ({ label: x.label, value: x.tokens }));

  // 系统提示词是报告里少数「用户能直接动」的一项：它每轮被完整重发，总量 = 每请求规模 × 往返数，
  // 而它的大小由装了多少插件与技能决定。占比小时不必啰嗦。
  const sysCat = r.byCategory.find((x) => x.key === "system");
  const sysNote =
    hasUsage && sysCat && sysCat.share >= 0.1
      ? ` 系统提示词占 ${pct(sysCat.share)}（${human(c.systemPromptTokens)}/请求 × ${s.roundTrips} 次往返 = ${human(sysCat.tokens)}）：它每轮都被完整重发，大小由你装了多少插件与技能决定，精简技能能直接压低这一项——本插件自己的技能描述也常驻在里面（约 170 token）。`
      : "";

  const tools = r.byTool.slice(0, 8);
  const toolCategories = tools.map((x) => x.tool);
  const toolSeries = [{ name: "重发 tokens", data: tools.map((x) => x.tokens) }];
  // 「单次」= 平均每次调用带来的重发 token。总量榜会埋掉次数少但每次极贵的工具：
  // 实测 Read 只 29 次却吃 335 万，单次是 Edit 的 2.6 倍。
  const toolRows = tools.map((x) => [
    x.tool,
    human(x.tokens),
    pct(x.share),
    String(x.calls),
    x.perCall == null ? "—" : human(x.perCall),
  ]);

  const files = r.byFile.slice(0, 20);
  const fileRows = files.map((f) => [
    f.label,
    kindLabel(f.kind),
    human(f.billed),
    pct(f.share),
    f.perRead == null ? "—" : human(f.perRead),
    String(f.trips),
    String(f.reads),
  ]);

  const byReq = r.byRequest ?? [];
  const reqLabels = byReq.map((x, i) => x.time || `#${x.index ?? i + 1}`);
  const reqInputs = byReq.map((x) => x.inputTokens);
  const reqCredits = byReq.map((x) => x.credits);
  // credits 只在覆盖全部往返时才画曲线：部分覆盖（BYOK 混合会话实测 413 笔里 1 笔有 credits）
  // 画出来是一地零，既读不出形状，还会暗示「其余往返没花钱」。
  const showCreditsChart = hasUsage && !covPartial && availOf(av, "credits") !== "unavailable";

  // 按类别下钻：类别小计行（accent/neutral）+ 其工具/文件明细行（default），用 rowTone 分组着色。
  const catDetail = r.byCategoryDetail ?? [];
  const detailRows: string[][] = [];
  const detailTones: ("accent" | "neutral" | "default")[] = [];
  for (const cc of catDetail) {
    const hasKids = cc.children.length > 0;
    detailRows.push([cc.label, "—", hasKids ? "小计" : "（整体，无工具/文件归属）", human(cc.tokens), pct(cc.share), "100%", "—"]);
    detailTones.push(hasKids ? "accent" : "neutral");
    for (const k of cc.children) {
      detailRows.push(["", k.tool || "—", k.label, human(k.tokens), pct(k.share), pct(k.catShare), String(k.trips)]);
      detailTones.push("default");
    }
  }

  // 子代理账：Agent 派发的子代理消耗不在主链里，单独一段呈现（无子代理则整段不渲染）。
  const sub = r.subagents;
  const subItems = sub?.items ?? [];
  const subRows: string[][] = [];
  const subTones: ("accent" | "default")[] = [];
  for (const a of subItems) {
    subRows.push([
      a.description || a.agentId,
      a.agentType || "—",
      String(a.roundTrips),
      human(a.billedInputTokens),
      pct(a.peakContextRatio),
      String(a.credits),
      String(a.originalCredits),
      a.error || "—",
    ]);
    subTones.push("default");
  }
  if (sub && subItems.length > 0) {
    subRows.push([
      "合计（主链 + 子代理）",
      "—",
      String(sub.combined.roundTrips),
      human(sub.combined.billedInputTokens),
      "—",
      String(sub.combined.credits),
      String(sub.combined.originalCredits),
      `主链 ${t.credits} / 子代理 ${sub.totals.credits}`,
    ]);
    subTones.push("accent");
  }

  // 压缩单笔成本：输入/摘要一律取数据层归一后的生效值（有代理实测就是实测，否则是客户端自估），
  // 并用「口径」列把两者分开——混着显示会让自估值被当成实测。
  const ccost = r.compactionCost;
  const ccItems = ccost?.items ?? [];
  const compactRows: string[][] = ccItems.map((e) => [
    `#${e.index}`,
    shortTime(e.at),
    e.trigger || "—",
    e.effectiveInputTokens == null ? "—" : human(e.effectiveInputTokens),
    e.effectiveOutputTokens == null ? "—" : human(e.effectiveOutputTokens),
    e.nextInputTokens == null ? "—" : human(e.nextInputTokens),
    e.savedTokens == null ? "—" : human(e.savedTokens),
    e.proxy && e.proxy.ms != null ? `${Math.round(e.proxy.ms / 1000)}s` : "—",
    e.proxy ? "实测" : "自估",
  ]);

  // IDE 端仅剩的真值：工具调用次数与文件读取次数（不依赖 usage，两种转录都写 tool_use 块）。
  const residueRows: string[][] = [];
  if (!hasUsage) {
    for (const x of r.byTool.filter((v) => v.calls > 0).sort((a, b) => b.calls - a.calls).slice(0, 15)) {
      residueRows.push([x.tool, "工具", String(x.calls), "—", "—"]);
    }
    for (const f of r.byFile.filter((v) => v.reads > 0).sort((a, b) => b.reads - a.reads).slice(0, 15)) {
      residueRows.push([f.label, kindLabel(f.kind), "—", String(f.reads), String(f.trips)]);
    }
  }

  // A1 三值：一次带 usage 的往返都没有时 0 <= max(2,0) 恒成立，会把「没数据」判成「校验通过」，
  // 故 breakdown.mjs 在 reqCount===0 时给 null；这里必须显示「不适用」而不是绿色通过。
  const identityText =
    r.identity.ok == null
      ? "恒等式 A1 不适用（本转录没有一次带 usage 的往返）。"
      : r.identity.ok
        ? `归因覆盖 ${pct(t.coverage)}${availTag(av, "categoryShare", "derived")}，恒等式 A1 通过（|Δ|=${r.identity.absDiff}）。`
        : `归因覆盖 ${pct(t.coverage)}，恒等式 A1 未通过（|Δ|=${r.identity.absDiff}）。`;
  // 窗口读不到时是静默回退的 200000，而所有 token 数字都乘它 —— 必须显式标 ≈ 与来源。
  const cwIsFallback = availOf(av, "contextWindow") === "fallback";
  const cwText = cwIsFallback ? `≈${human(c.contextWindow)}（回退值）` : human(c.contextWindow);
  // 当前占用头条：以最新上下文（currentContextTokens）为准；段内单调递增 ⇒ 旧版峰值与当前恒等，故合并为一条。
  const curCtx = c.currentContextTokens ?? c.peakContextTokens;
  const netAdv = c.netUserAdvice || c.peakUserAdvice || c.peakAdvice || null;
  const limitSrcLabel =
    c.userContextLimitSource === "manual" ? "手工"
    : c.userContextLimitSource === "config" ? "配置"
    : c.userContextLimitSource === "derived" ? "实测反推"
    : "默认";
  // 阈值来源非 manual/config/derived ⇒ 用的是内置兜底 200K，不是用户在 Qoder 设的真值，需显式提示如何改。
  const limitIsDefault = c.userContextLimitSource !== "manual" && c.userContextLimitSource !== "config" && c.userContextLimitSource !== "derived";
  // 上下文对比表：把旧版挤成一段小字的「界面显示 / 自动压缩实况 / 历史高点」拆成可扫读的行，无数据不占位。
  const ctxRows: string[][] = [];
  if (c.userContextLimit != null && Number.isFinite(c.contextWindow)) {
    ctxRows.push(
      c.contextWindow > c.userContextLimit
        ? ["Qoder 界面进度条", pct(c.netWindowRatio ?? c.peakContextRatio), `按模型物理窗口 ${cwText} 算，比你真实占比低约 ${(c.contextWindow / c.userContextLimit).toFixed(1)} 倍——界面显得偏空、有迷惑性，别信它`]
        : ["Qoder 界面进度条", pct(c.netWindowRatio ?? c.peakContextRatio), `按模型窗口 ${cwText} 算，与你实设上限一致，显示无偏差`],
    );
  }
  if (c.observedAutoCompactions != null && c.observedAutoCompactions > 0 && c.observedAutoTriggerTokens != null) {
    ctxRows.push(["自动压缩实况", `自动 ${c.observedAutoCompactions} 次`, `触发点在 ~${human(c.observedAutoTriggerTokens)}（窗口的 ${pct(c.autoTriggerRatio ?? 0)}）`]);
  } else if (s.compactions > 0) {
    ctxRows.push(["压缩实况", `手动 ${s.compactions} 次`, "本场未见自动压缩，均为你手动触发"]);
  }
  if (c.peakSessionNotable) {
    ctxRows.push(["压缩前历史高点", `${pct(c.peakSessionRatio ?? 0)}（${human(c.peakSessionTokens ?? 0)}）`, "本场曾达到的最高占用"]);
  }

  // 链路健康度：把 --request-log 的逐笔归因压成一行结论（明细属于排障，留在 CLI）。
  // 分两档：本会话确实走在代理链路上（proxy/mixed）才印「最近 N 笔」的失败统计与排障命令；
  // token 全来自转录的会话里，那些统计说的是别的会话，印成 warning + 命令是噪音，只留覆盖率与最近记录时间。
  // 纯官方模型用户从没配过代理，整行不渲染。
  const lhRaw = r.linkHealth ?? null;
  const lh = lhRaw && (r.proxy?.configured || lhRaw.matched > 0) ? lhRaw : null;
  const lhOnPath = !!lh && (lh.matched > 0 || lh.usageSource === "proxy" || lh.usageSource === "mixed");
  const lhErrors = lh ? lh.proxyErrors ?? 0 : 0;
  const lhBad = lhOnPath && lhErrors > 0;
  const lhTone: "info" | "warning" = lhBad ? "warning" : "info";
  // 三档拆分一行说清：代理实测 / 转录 input_tokens / 转录 ratio×window 各多少笔。
  // 旧报告（v3）无 breakdown 字段时退回到只报 matched。
  const lhBd = lh?.breakdown ?? null;
  const lhBdText = lhBd
    ? `拆分：代理 ${lhBd.proxy} 笔 · 转录 input_tokens ${lhBd.transcriptTokens} 笔 · 转录 ratio×窗口 ${lhBd.transcriptRatio} 笔`
    : "";
  const lhSrcNote = !lh
    ? ""
    : lh.usageSource === "proxy"
      ? "：全部为供应商实测"
      : lh.usageSource === "transcript-tokens"
        ? "：本会话 token 全来自转录 input_tokens（BYOK，代理未在链路上）"
        : lh.usageSource === "transcript-ratio"
          ? "：本会话 token 由 ratio×窗口推导（官方模型）"
          : lh.usageSource === "mixed"
            ? "：多源混合，以下拆分列为准"
            : lh.usageSource === "transcript"
              ? "：本会话 token 全部来自转录，代理不在链路上"
              : "";
  const lhText = !lh
    ? ""
    : [
        lh.requests > 0
          ? `代理覆盖 ${lh.matched}/${lh.requests} 笔往返（${pct(lh.coverage)}）${lhSrcNote}`
          : "本会话没有带 usage 的往返，代理无从覆盖",
        lhBdText,
        lh.lastRecordAt ? `代理最近记录 ${shortTime(lh.lastRecordAt)}` : proxyAlive ? "代理有记录但无时间戳" : "代理从未记到流量",
        lhOnPath && lh.recentRequests
          ? `最近 ${lh.recentRequests} 笔：代理失败 ${lh.proxyErrors} · 上游报错 ${lh.upstreamRejected} · 客户端提前断开 ${lh.aborted} · 成功但无 usage ${lh.noUsage}${
              lh.slowestMs != null ? ` · 最慢 ${Math.round(lh.slowestMs / 1000)}s` : ""
            }`
          : lhOnPath
            ? "请求诊断日志为空（运行中的代理是旧版，或还没记到）"
            : "",
        lhBad ? `有「代理失败」= 请求没出得去或代理自己抛了，跑 ${CLI} --request-log 看归因` : "",
        !lhOnPath && lhErrors > 0 ? `代理另有 ${lhErrors} 笔失败，属于走代理的那些会话` : "",
        lh.supersededFailure
          ? `${shortTime(lh.supersededFailure.at ?? null)} 那条「拉起失败（${lh.supersededFailure.error}）」已被之后的流量推翻，无需处理`
          : "",
      ]
        .filter(Boolean)
        .join("。") + "。";

  return (
    <ReportShell width="wide" ariaLabel="会话 token 消耗截析">
      <Stack gap="sectionCompact">
        <header>
          <Stack gap="component">
            <H1>会话 token 消耗截析</H1>
            <Text tone="secondary">
              {s.model || "未知模型"}
              {s.modelSource === "manual" ? "（手工录入）" : ""} · {sourceLabel} · 会话{" "}
              {String(s.id || "").slice(0, 8)}
              {s.title ? `「${s.title}」` : ""} · {shortTime(s.startedAt)} →{" "}
              {shortTime(s.endedAt)} · 压缩{" "}
              {availOf(av, "compactions") === "unavailable" ? "—" : `${s.compactions} 次`}
              {r.usageSource ? ` · usage 来源 ${
                r.usageSource === "proxy"
                  ? "代理实测"
                  : r.usageSource === "transcript-tokens"
                    ? "转录 input_tokens"
                    : r.usageSource === "transcript-ratio"
                      ? "转录 ratio×窗口"
                      : r.usageSource === "mixed"
                        ? "多源混合"
                        : "转录"
              }` : ""}
              {r.pluginVersion ? ` · v${r.pluginVersion}` : ""} · schema v{r.schemaVersion}
            </Text>
            <MetricsGrid variant="header" columns={4} items={headline} />
          </Stack>
        </header>

        {!hasUsage && r.source === "desktop-rich" && (
          <Callout tone="danger" title="本报告数值不可用：自定义模型（BYOK）不经 Qoder 计费网关">
            转录是桌面端富布局，但 assistant entry 里没有 message.usage——自定义模型（如千问
            tokenplan）的响应不经 Qoder 计费网关，credits / context_usage_ratio 无来源，提供商返回的
            usage 客户端也不落盘（已实测扫过 ~/.qoder-cn、~/.qoder、~/.qoder-cli 与 %APPDATA%\QoderCN）。
            本地补救（改一个配置文件即可，免装 Node、之后全自动）：编辑 {"`~/.qoder-credits-proxy/config.json`"}，
            把 {"`upstream`"} 填成你的供应商根地址（= Base URL 去掉结尾 /v1），再把自定义模型 Base URL 的 host:port
            换成 {`127.0.0.1:${r.proxy?.port ?? 49787}`}（路径保留），新开一个会话即自动拉起代理、按 message.id
            精确 join 出实测 token（credits 仍无真值）。装了 Node 也可用 {"`--setup-proxy`"} / {"`--check-proxy`"} 一步到位。
            手工回填通道同样可用：
            {"`.qoder-credits/overrides/<sessionId>.json`"}。
          </Callout>
        )}

        {!hasUsage && r.source !== "desktop-rich" && (
          <Callout tone="danger" title="本报告数值不可用：转录不含 message.usage">
            这是 {sourceLabel}（IDE 端客户端）。它的转录只写 session_meta / user / assistant / progress 四种 entry，
            message.usage 整个字段不存在，也不落到本地任何其它文件（已实测扫过 ~/.qoder-cn、~/.qoder、
            ~/.qoder-cli 与 %APPDATA%\QoderCN）。因此 Credits、token 与各类占比一律显示「—」而不是 0 ——
            0 会被误读成「这次没花钱」。真值只有官方 UI 有：把它填进{" "}
            {"`.qoder-credits/overrides/<sessionId>.json`"} 后重跑，上方会出现「官方 UI 真值」一段并与本地并列对账。
          </Callout>
        )}

        {manual && (
          <Callout tone="success" title="官方 UI 真值（手工录入，未覆盖任何本地数字）">
            Credits {manual.credits ?? "—"} · 原价 {manual.originalCredits ?? "—"}
            {manual.model ? ` · 模型 ${manual.model}` : ""}
            {manual.durationMin != null ? ` · 时长 ${manual.durationMin} min` : ""}
            {manual.startedAt ? ` · ${shortTime(manual.startedAt)}` : ""}
            {manual.localCoverage != null
              ? ` · 本地${manual.localScope === "combined" ? "合计（主链+子代理）" : "主链"} ${manual.localCredits ?? t.credits}，覆盖 ${pct(manual.localCoverage)}`
              : " · 本地无 usage，无法对账"}
            {manual.note ? `。备注：${manual.note}` : ""}
          </Callout>
        )}

        {sub && sub.count === 0 && sub.reason && sub.reason !== "no-dir" && (
          <Callout tone="warning" title={`子代理账未汇总（${sub.reason}）`}>
            探测过的候选目录：{(sub.probedPaths ?? []).join("  |  ") || "（无）"}
          </Callout>
        )}

        {proxyAlarm && (
          <Callout tone="warning" title={`代理自动启动失败（${proxyAlarm.error}）`}>
            {proxyAlarm.error === "EADDRINUSE"
              ? `端口 ${proxyAlarm.port ?? "—"} 被占用，自定义模型将无法对话。请换一个空闲端口重启代理：${CLI} --setup-proxy --port <新端口>，并把模型 Base URL 改成新端口。`
              : `代理未就绪（${proxyAlarm.error}），自定义模型可能无法对话。请运行 ${CLI} --check-proxy 查看链路状态。`}
          </Callout>
        )}

        {r.proxy?.dormant && !proxyAlarm && (
          <Callout tone="info" title="代理长期空闲">
            代理已配置但超过 14 天没有记录到流量（可能你已改回官方模型）。如不再使用自定义模型，可运行{" "}
            {CLI} --stop-proxy 停用代理；保留也不影响官方模型。
          </Callout>
        )}

        {cwIsFallback && (
          <Callout tone="warning" title="上下文窗口为回退值 200K（未从 runtime-config / usage 反推 / ManualTruth 拿到真值）">
            本会话所有以窗口为分母的占比与「峰值建议」都可能偏大，而绝对 token 数（已改以 S 真值为底）不受影响。
            建议在 {"`.qoder-credits/overrides/<sessionId>.json`"} 里手工填 {"`contextWindow`"}（如 qwen3-max=1000000），或等一笔带 input_tokens+ratio 的往返写入转录后自动反推生效。
          </Callout>
        )}

        {hasUsage && netAdv && c.userContextLimit != null && (
          <Stack gap="component">
            <Callout
              tone={netAdv.tone}
              title={`你真实的上下文占用 ${pct(c.netUserRatio ?? c.peakUserRatio ?? 0)}（${human(curCtx)} / 阈值 ${human(c.userContextLimit)}・${limitSrcLabel}）`}
            >
              {netAdv.text}
              {limitIsDefault && (
                <Text tone="secondary">
                  {` ⚙ 这里的 ${human(c.userContextLimit)} 是插件内置默认值，不是你在 Qoder「模型管理」里设的真实上限（插件读不到那个设置）。想按真实阈值算：在 ~/.qoder-credits-proxy/config.json 填 "userContextLimit": <你的上限>（对所有会话生效），或对本会话在 .qoder-credits/overrides/<会话id>.json 填同名字段，重跑报告即生效。`}
                </Text>
              )}
            </Callout>
            {ctxRows.length > 0 && (
              <Table
                headers={["对比口径", "数值", "说明"]}
                rows={ctxRows}
                density="compact"
              />
            )}
          </Stack>
        )}

        {c.thresholdNotEnforced && (
          <Callout tone="warning" title="⚠ 自动压缩不会在你设的阈值触发（本条最重要）">
            你的模型物理窗口是 {cwText}，Qoder 的自动压缩要等上下文涨到窗口 ~85%（≈{human(Math.round(c.contextWindow * 0.85))}）才触发；
            你在模型管理里设的 {human(c.userContextLimit)} 上限远在其下，永远不会触发自动压缩。
            请照上面「你真实的上下文占用」那条，到点自己手动压缩，别等它自动压。
          </Callout>
        )}

        {lh && (
          <Callout tone={lhTone} title="链路健康度">
            {lhText}
          </Callout>
        )}

        <Callout tone="info" title="度量口径">
          计费输入总量逐笔锁定真值（优先级：代理 promptTokens &gt; 转录 usage.input_tokens &gt; ratio{availTag(av, "contextRatio")} × {cwText}
          {cwIsFallback ? "，未拿到真窗口、全部 token 数字随之带 ≈" : ""}）；各类别/文件按块估算规模比例分摊
          {availTag(av, "categoryShare", "derived")}。{identityText}
          {cachedText ? ` ${cachedText}` : ""}
          {covPartial
            ? ` Credits 只来自 ${cov!.trips}/${cov!.roundTrips} 笔往返（占计费输入 ${pct(cov!.tokenShare)}），其余往返走代理实测、不经 Qoder 计费，故不要拿 Credits 去除以计费输入总量算单价。`
            : ""}
        </Callout>

        {byReq.length > 0 && (
          <ReportSection
            title="逐请求明细（每一次往返）"
            description={
              showCreditsChart
                ? "每个 round-trip 的真实输入规模与 credits；曲线骤降处为上下文压缩重置（顶部四项为会话累计，此处为每一次）"
                : `每个 round-trip 的真实输入规模；曲线骤降处为上下文压缩重置。credits 曲线未画：本会话只有 ${cov?.trips ?? 0}/${cov?.roundTrips ?? byReq.length} 笔往返带 credits（其余走代理实测、不经 Qoder 计费），画出来是一地零。`
            }
            meta={`${byReq.length} 次往返 · 压缩 ${orDash(s.compactions, av, "compactions")} 次`}
            divided
          >
            {showCreditsChart ? (
              <ChartComparisonGrid>
                <ChartContainer title="每次输入 tokens" ariaLabel="每次输入 tokens">
                  <LineChart
                    categories={reqLabels}
                    series={[{ name: "输入 tokens", data: reqInputs, tone: "info" }]}
                    height={220}
                    valueFormatter={human}
                    ariaLabel="每次请求输入 tokens"
                  />
                </ChartContainer>
                <ChartContainer title="每次 credits" ariaLabel="每次 credits">
                  <LineChart
                    categories={reqLabels}
                    series={[{ name: "credits", data: reqCredits, tone: "warning" }]}
                    height={220}
                    ariaLabel="每次请求 credits"
                  />
                </ChartContainer>
              </ChartComparisonGrid>
            ) : (
              <ChartContainer title="每次输入 tokens" ariaLabel="每次输入 tokens">
                <LineChart
                  categories={reqLabels}
                  series={[{ name: "输入 tokens", data: reqInputs, tone: "info" }]}
                  height={220}
                  valueFormatter={human}
                  ariaLabel="每次请求输入 tokens"
                />
              </ChartContainer>
            )}
          </ReportSection>
        )}

        {hasUsage && ccItems.length > 0 && (
          <ReportSection
            title="压缩单笔成本（会话里最贵的那几笔调用）"
            description="每次压缩本身就是一笔普通模型调用：整份上下文当 prompt 进去、摘要当 completion 出来。此前报告只显示「压缩 N 次」，把单笔最贵的开销藏成了一个计数。「省下」= 压缩前规模 − 压缩后首轮实测输入（地板）。口径列：实测=能在代理日志里唯一对上的供应商真值；自估=客户端在压缩边界里写的前后规模。"
            meta={`${ccItems.length} 次 · 输入累计 ${human(ccost?.totals.preTokens ?? 0)} · 摘要累计 ${human(ccost?.totals.postTokens ?? 0)} · 累计省下 ${human(ccost?.totals.savedTokens ?? 0)} · 其中 ${ccost?.totals.measured ?? 0} 笔为供应商实测`}
            divided
          >
            <Table
              headers={["第几次", "时刻", "触发", "输入", "摘要输出", "压缩后首轮", "省下", "耗时", "口径"]}
              rows={compactRows}
              density="compact"
              stickyHeader
            />
          </ReportSection>
        )}

        {sub && subItems.length > 0 && (
          <ReportSection
            title="子代理账（Agent 派发）"
            description="子代理的每次往返只写进它自己的独立转录，不计入上方主链任何数字；官方 UI 的一场会话扣费 = 主链 + 各子代理。"
            meta={`${subItems.length} 个子代理 · 主链 ${t.credits} + 子代理 ${sub.totals.credits} = 合计 ${sub.combined.credits} Credits`}
            divided
          >
            <Table
              headers={["子代理", "类型", "往返", "计费输入", "峰值占比", "Credits", "原价", "备注"]}
              rows={subRows}
              rowTone={subTones}
              density="compact"
              stickyHeader
            />
          </ReportSection>
        )}

        {hasUsage && r.byCategory.length > 0 && (
          <ReportSection
            title="按类别占比"
            description={`会话计费输入 token 在各来源间的分布（完整划分，占比之和 = 100%）${sysNote}`}
            meta={human(t.billedInputTokens) + ` tokens${availTag(av, "categoryShare", "derived")}`}
            divided
          >
            <ChartContainer ariaLabel="按类别占比">
              <PieChart donut data={pieData} centerLabel="计费输入" valueFormatter={human} />
            </ChartContainer>
          </ReportSection>
        )}

        {hasUsage && catDetail.length > 0 && (
          <ReportSection
            title="按类别下钻（工具 / 文件路径）"
            description="每个类别的消耗再拆到工具与具体文件/路径：工具返回、工具调用可精确到路径，其余类别为整体（无文件归属）。数值均为重发计费 token。"
            meta={`占总额=占计费输入总量 · 占本类=占该类别 · 程数=存活往返累计${availTag(av, "fileShare", "derived")}`}
            divided
          >
            <Table
              headers={["类别", "工具", "文件 / 路径", "重发 tokens", "占总额", "占本类", "程数"]}
              rows={detailRows}
              rowTone={detailTones}
              density="compact"
              stickyHeader
            />
          </ReportSection>
        )}

        {hasUsage && tools.length > 0 && (
          <ReportSection
            title="按工具占比"
            description="各工具相关内容（调用参数 + 返回）重发累计的计费 token 占比（仅工具相关块，非完整划分，占比之和 < 100%）。「单次」= 平均每次调用带来的重发 token：总量榜会把「次数少但每次极贵」的工具埋掉，这一列专门把它捞出来。"
            meta={`token 占比${availTag(av, "toolShare", "derived")} · 调用次数为实测`}
            divided
          >
            <Stack gap="component">
              <ChartContainer ariaLabel="按工具占比">
                <BarChart horizontal categories={toolCategories} series={toolSeries} valueFormatter={human} ariaLabel="按工具占比" />
              </ChartContainer>
              <Table
                headers={["工具", "重发 tokens", "占比", "调用", "单次"]}
                rows={toolRows}
                density="compact"
                stickyHeader
              />
            </Stack>
          </ReportSection>
        )}

        {hasUsage && fileRows.length > 0 && (
          <ReportSection
            title="按文件占比"
            description="精确到路径/文件名：内容随上下文被重复发送累计的计费 token 占比（Top 20，仅可归因文件的块）。「单次读取」= 这个文件平均每次被读取最终烧掉多少（含此后每一程的重发）——读一次就烧掉十几万的文件，在按总量排序的榜上毫不起眼。"
            meta={`程数=存活往返累计 · 读取=返回次数（实测）${availTag(av, "fileShare", "derived")}`}
            divided
          >
            <Table
              headers={["文件", "种类", "重发 tokens", "占比", "单次读取", "程数", "读取"]}
              rows={fileRows}
              density="compact"
              stickyHeader
            />
          </ReportSection>
        )}

        {!hasUsage && residueRows.length > 0 && (
          <ReportSection
            title="工具调用与文件读取（计数为实测）"
            description="占比与 token 一律不可用，但 tool_use 块两种转录都写，所以「谁被调了几次、谁被读了几次」仍是真值 —— IDE 端不是一无所有。"
            meta={`${t.toolCalls ?? 0} 次工具调用${availTag(av, "toolCalls")} · ${t.fileReads ?? 0} 次文件读取${availTag(av, "fileReads")} · ${s.turns} 轮对话`}
            divided
          >
            <Table
              headers={["工具 / 文件", "种类", "调用", "读取", "程数"]}
              rows={residueRows}
              density="compact"
              stickyHeader
            />
          </ReportSection>
        )}
      </Stack>
    </ReportShell>
  );
}
