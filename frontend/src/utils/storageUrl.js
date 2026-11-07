/**
 * 商品图片 URL 集中解析层（对象存储兼容层，out/storage-mq-plan.md 任务 A5）。
 *
 * 背景：库内 cover_url 存量行是 Vite public 相对路径（/products/xxx.webp）；对象存储
 * 上线后新数据出站由后端统一拼公开绝对 URL（ StorageUrlSerializer ），但历史行与
 * 兜底路径仍会以相对形态到达前端。渲染前统一过本层做防御式归一，三层回退全部保留：
 *  1. 后端 LANLINK_STORAGE_ENABLED=false → 出站原样输出，本层对绝对 URL/相对路径透传不变；
 *  2. 未配置 VITE_STORAGE_PUBLIC_BASE → /products/... 原样返回，回退 Vite public
 *     目录（frontend/public/products/ 全量保留为回退源）；
 *  3. 存储域整体故障可由 nginx 将 /products/ 反代回存储域（key 与存量文件名一致）。
 */
import { normalizeUploadUrl } from './uploadUrl'

export function resolveStorageUrl(u) {
  // 空/非字符串原样返回，保留组件现有的 v-if、|| fallback 占位语义
  if (typeof u !== 'string' || !u) return u
  // 绝对 URL（本存储域、外域 CDN、data:/blob: 之外的 http(s)）已是最终形态，原样透传
  if (/^https?:\/\//i.test(u)) return u
  // 历史上传媒体缺 context-path 前缀的 /uploads/... → 复用 uploadUrl 归一语义（已带 /api 的原样）
  if (u.startsWith('/uploads/')) return normalizeUploadUrl(u)
  // 对象存储相对路径：去掉与桶同名的 /products 前缀后拼公开读取基址（基址含桶路径，
  // 如 http://localhost:9000/products → /products/1001.webp 渲染为 base + /1001.webp）；
  // 未配置该 env = 原样返回 = 回退 Vite public
  if (u.startsWith('/products/')) {
    const base = (import.meta.env.VITE_STORAGE_PUBLIC_BASE || '').replace(/\/+$/, '')
    return base ? base + u.slice('/products'.length) : u
  }
  // 其他（前端静态资源、data:、无前导斜杠路径等）一律原样
  return u
}
