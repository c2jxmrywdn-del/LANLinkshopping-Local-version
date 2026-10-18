import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发代理密钥：与后端 application-dev.yml 的 support.proxy-secret 对应。
// 仅本地开发使用；生产环境由 Vercel 代理函数注入真实密钥（SUPPORT_PROXY_SECRET），不得使用此默认值。
const LOCAL_DEV_PROXY_SECRET = 'local-dev-support-proxy-secret-000000000000'

// 客服问答接口要求可信代理注入签名头（生产由 Vercel 代理函数负责）。
// 本地 Vite 代理承担同等职责，否则后端守卫会返回 403，前端显示“客服暂时无法响应”。
// 签名头用静态 headers 注入：http-proxy 在 setupOutgoing 中无条件合并 options.headers，
// 比 proxyReq 事件（依赖 socket 时序）更可靠。客户端 IP 为动态值，走 proxyReq 事件补充，
// 后端对缺失的 IP 头另有 getRemoteAddr 兜底，故偶发缺失也不会导致 403。
function injectSupportProxyHeaders(proxy) {
  proxy.on('proxyReq', (proxyReq, req) => {
    const rawIp = (req.socket && req.socket.remoteAddress) || 'unknown'
    proxyReq.setHeader('X-Support-Client-IP', rawIp.replace(/^::ffff:/, ''))
  })
}

const supportProxy = {
  target: 'http://localhost:8080',
  changeOrigin: true,
  configure: injectSupportProxyHeaders,
  headers: { 'X-Support-Proxy-Secret': process.env.SUPPORT_PROXY_SECRET || LOCAL_DEV_PROXY_SECRET }
}

export default defineConfig({
  plugins: [vue()],
  build: {
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
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 生产构建预览（Lighthouse 评测等）同样代理 /api 到后端
  preview: {
    port: 4173,
    proxy: {
      '/api/support/ask': supportProxy,
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
