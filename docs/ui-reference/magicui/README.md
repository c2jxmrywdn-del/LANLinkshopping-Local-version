# Magic UI 动效组件 — 原始源码存档

来源：21st.dev 上 Magic UI 官方系列（作者 dillionverma），上游仓库 [magicuidesign/magicui](https://github.com/magicuidesign/magicui)（MIT）。
抓取方式（2026-10-25）：GitHub git trees API 定位 `apps/www/registry/magicui/<name>.tsx` → git blobs API 取 base64 内容解码。根 `registry.json` 为全部 210 个组件的清单（含依赖声明）。

## 图标体系（lucide）

Magic UI / shadcn / 21st.dev 生态的标配图标库是 **Lucide**（所有上游组件均 `import ... from "lucide-react"`）。
项目内通过 Vue 官方等价包 `lucide-vue-next@0.577.0`（npm，ISC License）引用同一套图标：
活动中心的业务图标（Ticket 券票 / Zap 秒杀 / Gift 激励 / UserRoundPlus 注册有礼 / ShoppingBag 消费有礼 /
Store 商户 / Flame 促销 / Sparkles / Receipt 等）均出自该库，与已移植动效组件同源同风格（24px 网格 2px 线性描边）。

按项目实际需求挑选的两批共 8 个组件（依赖声明 motion 的已用 rAF 弹簧/CSS/直写 DOM 等价重写，零运行时依赖）：

## 第一批

| 文件 | 上游实现 | 项目落点 | Vue 移植 |
|---|---|---|---|
| `number-ticker.tsx` | motion `useSpring`(damping 60 / stiffness 100) + `useInView(once)` + `textContent` 直写 | 活动中心 Hero 统计数字 | `NumberTicker.vue` |
| `marquee.tsx` | 纯 CSS：内容重复 N 份，`translateX(calc(-100% - var(--gap)))` 无缝循环，group-hover 暂停 | Mall 促销条（促销多时无缝滚动） | `Marquee.vue` |
| `animated-shiny-text.tsx` | 纯 CSS：`bg-clip-text` 渐变 + `background-position` 8s 循环扫光 | 活动中心秒杀徽章 | `AnimatedShinyText.vue` |
| `shimmer-button.tsx` | 纯 CSS：`container-type:size` + conic-gradient 旋转流光三层结构（spark/backdrop/highlight） | 活动中心 Hero CTA | `ShimmerButton.vue` |

## 第二批

| 文件 | 上游实现 | 项目落点 | Vue 移植 |
|---|---|---|---|
| `magic-card.tsx` | motion 驱动：border-box 双层背景边框光 + hover 光斑（gradient 模式） | 商户中心营销卡（激励/大促报名卡） | `MagicCard.vue`（零侵入宿主：mask 边框光 + setProperty 直写） |
| `meteors.tsx` | random 生成流星（rotate --angle + translateX 划过）+ 尾巴渐隐 | 秒杀深色卡背景（金色流星） | `Meteors.vue`（left 改容器百分比、新增 color/tailLength） |
| `dot-pattern.tsx` | SVG 逐 circle + ResizeObserver，glow 逐点呼吸 | 活动中心 Hero 背景点阵 | `DotPattern.vue`（>2500 点保护） |
| `animated-gradient-text.tsx` | 纯 CSS：`bg-size 300%` 渐变流动 `bg-clip:text` | Mall 促销标题（橙→品红流动） | `AnimatedGradientText.vue` |

上游源码的关键坑（移植时已处理）：
- NumberTicker 直写 `textContent` 不走框架响应式（每帧无重渲染）；格式化用 `Intl.NumberFormat`。
- Marquee 无缝的关键是**每组位移恰好 `自身宽度 + gap`**，repeat 份数必须铺满容器。
- AnimatedShinyText 的渐变中间色 dark 模式要用白色（`via-black/80` ↔ `via-white/80`）。
- ShimmerButton 的 spark 层依赖 `container-type: size`（cqw/cqh 单位）+ `-z-30` 层级与 backdrop `-z-20` 的叠放。
