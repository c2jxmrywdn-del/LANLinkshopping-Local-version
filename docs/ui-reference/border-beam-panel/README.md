# Border Beam Panel — 原始源码存档

来源：21st.dev [@rmahammad/components/border-beam-panel](https://21st.dev/@rmahammad/components/border-beam-panel)
上游仓库：[rmahammad/motiq](https://github.com/rmahammad/motiq)（MIT）
抓取方式：GitHub Contents API（`apps/docs/public/r/border-beam-panel.json` registry JSON + `apps/docs/app/_previews/border-beam-panel.tsx` 演示），2026-10-25 完整爬取。

## 文件

- `border-beam-panel.tsx` — 组件本体（React，从 registry.json files[0] 提取，8.9KB 完整源码）
- `registry.json` — shadcn registry 原始 JSON（含 cssVars 明暗两套主题色）
- `demo.tsx` — 作者官方演示用法

## 效果原理（作者自述 + 源码注释）

双彗星绕 2px 边框环运行；边环是旋转的 conic-gradient，用两层 CSS alpha mask（`mask-composite: exclude`）裁切（刻意不用 SVG luminance mask——Chromium 会静默失效）。**角速度本身由弹簧驱动**（k=30, d=11）：hover 时风速升至 240°/s，离开后惯性滑回 42°/s。每帧只写一个自定义属性 `--mk-beam-a`，内容永不重绘。

关键细节：
- 彗星渐变尾巴用 `color-mix(in srgb, color 4%, transparent)` 而非 `transparent`（rgba(0,0,0,0) 会令 conic 渐变发灰）
- 第二颗彗星默认珊瑚签名色（dark #ff6b5e / light #e9564a），与主彗星 198° 对冲
- 起始角 = seed × 137.508（黄金角）% 360，SSR 确定性
- 滚出视口 / 标签页隐藏自动暂停；prefers-reduced-motion 时静态停靠 40°；forced-colors 降级

## 项目内使用

Vue 3 移植版：`frontend/src/components/ui/BeamPanel.vue`（忠实移植，含 Spring 物理类、双彗星渐变、可见性暂停），当前用于活动中心"限时秒杀"卡。
