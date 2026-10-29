import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { gzipSync, brotliCompressSync, constants } from 'node:zlib'
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, extname } from 'node:path'

/**
 * 构建期预压缩插件：为 dist 内 JS/CSS/HTML/JSON/SVG 生成 .gz（Gzip）与 .br（Brotli）文件。
 * 部署侧（Nginx/静态托管）按 Accept-Encoding 直接回源压缩文件，省去实时压缩 CPU，
 * 传输体积较原文件缩小 60%~80%（Brotli 优于 Gzip），显著降低首屏资源加载耗时。
 */
function precompressPlugin() {
  return {
    name: 'precompress-assets',
    apply: 'build',
    closeBundle() {
      const outDir = join(process.cwd(), 'dist')
      const targets = new Set(['.js', '.css', '.html', '.json', '.svg', '.txt', '.wasm'])
      const walk = (dir) => {
        for (const name of readdirSync(dir)) {
          const p = join(dir, name)
          const st = statSync(p)
          if (st.isDirectory()) { walk(p); continue }
          if (!targets.has(extname(name).toLowerCase())) continue
          const buf = readFileSync(p)
          if (buf.length < 1024) continue // 过小文件压缩收益有限，跳过
          writeFileSync(p + '.gz', gzipSync(buf, { level: 9 }))
          writeFileSync(p + '.br', brotliCompressSync(buf, {
            params: { [constants.BROTLI_PARAM_QUALITY]: 11 }
          }))
        }
      }
      walk(outDir)
    }
  }
}

// 本地开发代理密钥：与后端 application-dev.yml 的 support.proxy-secret 对应。
// 安全要求：禁止在仓库中硬编码任何密钥默认值，必须通过环境变量 SUPPORT_PROXY_SECRET 提供
// （本地写入 .env，Vite 启动时自动加载）。缺失时注入空头，后端 SupportRequestGuard 会拒绝
// /support/ask 并输出配置指引日志（fail-closed），避免弱口令默认密钥被外部利用。
const LOCAL_DEV_PROXY_SECRET = process.env.SUPPORT_PROXY_SECRET || ''

// 客服问答接口要求可信代理注入签名头（生产由 Vercel 代理函数负责）。
// 本地 Vite 代理承担同等职责，否则后端守卫会返回 403，前端显示“客服暂时无法响应”。
// 签名头用静态 headers 注入：http-proxy 在 setupOutgoing 中无条件合并 options.headers，
// 比 proxyReq 事件（依赖 socket 时序）更可靠。客户端 IP 为动态值，走 proxyReq 事件补充，
// 后端对缺失的 IP 头另有 getRemoteAddr 兜底，故偶发缺失也不会导致 403。
function injectSupportProxyHeaders(proxy) {
  proxy.on('proxyReq', (proxyReq, req) => {
    const rawIp = (req.socket && req.socket.remoteAddress) || 'unknown'
    proxyReq.setHeader('X-Support-Client-IP', rawIp.replace(/^::ffff:/, ''))
    // 归一化 Origin：后端 CORS 白名单只认可 localhost/127.0.0.1。
    // 用户经局域网 IP（如 192.168.x.x:5173）访问时 Origin 不在白名单，
    // 会被 Spring CORS 过滤器以 “Invalid CORS request” 403 拦截。本地 Vite 代理
    // 作为可信代理，把 Origin/Referer 统一改写为白名单来源，使任意 IP 访问均可用。
    const localOrigin = 'http://127.0.0.1:5173'
    proxyReq.setHeader('Origin', localOrigin)
    const referer = proxyReq.getHeader('Referer')
    if (referer) proxyReq.setHeader('Referer', String(referer).replace(/^https?:\/\/[^/]+/, localOrigin))
  })
}

const supportProxy = {
  // 显式 IPv4：Windows 上 localhost 可能优先解析 ::1，而后端端口映射只监听 127.0.0.1，
  // 会导致 Vite 代理偶发 ECONNREFUSED/Network Error（轮询失败时尤其明显）。
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  // 本地 RAG/7B/VL 推理可能 10-60s，代理侧不允许提前断开
  timeout: 600000,
  proxyTimeout: 600000,
  configure: injectSupportProxyHeaders,
  headers: { 'X-Support-Proxy-Secret': process.env.SUPPORT_PROXY_SECRET || LOCAL_DEV_PROXY_SECRET }
}

// 后端容器重建/重启窗口（docker compose up -d）内的瞬时 ECONNRESET：
// 输出一条可读提示代替整段 Node 堆栈，避免误判为前端故障（后端就绪后自动恢复）。
function friendlyProxyError(proxy) {
  proxy.on('error', (err, req) => {
    const url = req && req.url ? req.url : ''
    if (err && /ECONNRESET|ECONNREFUSED|EPIPE/.test(err.code || '')) {
      console.log(`[vite] 后端暂不可达（重启窗口？）：${url} —— 容器就绪后刷新页面即可`)
    } else {
      console.error('[vite] proxy error:', err.code || err.message, url)
    }
  })
}

// 通用 /api 代理同样做 Origin 归一化，避免登录/注册等接口经局域网 IP 访问时被 CORS 拦截。
function normalizeApiOrigin(proxy) {
  friendlyProxyError(proxy)
  proxy.on('proxyReq', (proxyReq) => {
    const localOrigin = 'http://127.0.0.1:5173'
    proxyReq.setHeader('Origin', localOrigin)
    const referer = proxyReq.getHeader('Referer')
    if (referer) proxyReq.setHeader('Referer', String(referer).replace(/^https?:\/\/[^/]+/, localOrigin))
  })
}

const apiProxy = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  timeout: 600000,
  proxyTimeout: 600000,
  configure: normalizeApiOrigin
}

export default defineConfig({
  plugins: [vue(), precompressPlugin()],
  build: {
    // 生产构建压缩：esbuild 已默认 minify，显式声明便于审查
    minify: 'esbuild',
    // 静态资源内联阈值：小于 4KB 的图片/字体转 base64 内联，减少请求数
    assetsInlineLimit: 4096,
    // 关闭 sourcemap 上传（减小产物体积、避免源码泄露），本地排障用 vite build --sourcemap
    sourcemap: false,
    // chunk 大小告警阈值收紧到 800KB，便于发现异常膨胀
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        // 第三方框架分包：利于浏览器缓存与并行加载，降低首屏 JS 阻塞
        manualChunks: {
          'antd': ['ant-design-vue'],
          'vue-vendor': ['vue', 'vue-router', 'pinia', 'axios'],
          'swiper': ['swiper']
        }
      }
    }
  },
  server: {
    // 显式绑定全部网卡：默认 localhost 在本机仅解析到 IPv6 回环 [::1]，
    // 偏好 IPv4 的浏览器会因 JS 模块请求失败而白屏。0.0.0.0 同时覆盖 127.0.0.1 与局域网。
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      // 注意：更精确的路径必须放在 /api 之前（按声明顺序匹配）
      '/api/support/ask': supportProxy,
      '/api': apiProxy
    }
  },
  // 生产构建预览（Lighthouse 评测等）同样代理 /api 到后端
  preview: {
    port: 4173,
    proxy: {
      '/api/support/ask': supportProxy,
      '/api': apiProxy
    }
  }
})
