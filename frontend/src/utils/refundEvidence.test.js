import { describe, expect, it } from 'vitest'
import {
  EVIDENCE_KIND_LIMITS,
  formatBytes,
  ownerUserIdOf,
  parseEvidences,
  pickEvidenceKind,
  serializeEvidences,
  toEvidenceItem,
  validateEvidenceFile,
} from './refundEvidence'
import {
  ALLOWED_REFUND_EVIDENCE_EXT,
  ALLOWED_REFUND_EVIDENCE_TYPES,
  REFUND_EVIDENCE_MAX_COUNT,
  REFUND_EVIDENCE_URL_PREFIX,
} from './validationRules'

/** 生成指定字节数的测试文件（内容不重要，只看 size/name/type） */
function makeFile(name, size, type = '') {
  return new File([new ArrayBuffer(Math.max(0, size))], name, { type })
}

describe('契约口径与后端一致', () => {
  it('数量上限 9、URL 前缀 /api/uploads/、三类白名单（与 ValidationRules 契约同源）', () => {
    expect(REFUND_EVIDENCE_MAX_COUNT).toBe(9)
    expect(REFUND_EVIDENCE_URL_PREFIX).toBe('/api/uploads/')
    expect(ALLOWED_REFUND_EVIDENCE_TYPES).toEqual(['image', 'video', 'file'])
    expect(ALLOWED_REFUND_EVIDENCE_EXT).toEqual({
      image: ['jpg', 'png', 'webp', 'gif'],
      video: ['mp4', 'mov'],
      file: ['pdf', 'txt', 'csv', 'bin'],
    })
    expect(EVIDENCE_KIND_LIMITS.image.max).toBe(8 * 1024 * 1024)
    expect(EVIDENCE_KIND_LIMITS.video.max).toBe(20 * 1024 * 1024)
    expect(EVIDENCE_KIND_LIMITS.file.max).toBe(20 * 1024 * 1024)
  })
})

describe('pickEvidenceKind', () => {
  it('image / video / file 三类判定', () => {
    expect(pickEvidenceKind(makeFile('a.jpg', 10, 'image/jpeg'))).toBe('image')
    expect(pickEvidenceKind(makeFile('a.webp', 10, 'image/webp'))).toBe('image')
    expect(pickEvidenceKind(makeFile('a.mp4', 10, 'video/mp4'))).toBe('video')
    expect(pickEvidenceKind(makeFile('a.mov', 10, 'video/quicktime'))).toBe('video')
    expect(pickEvidenceKind(makeFile('a.pdf', 10, 'application/pdf'))).toBe('file')
    expect(pickEvidenceKind(makeFile('a.zip', 10, 'application/zip'))).toBe('file')
  })

  it('扩展名或 MIME 越界返回 null（不在证据白名单）', () => {
    expect(pickEvidenceKind(makeFile('a.exe', 10, 'application/octet-stream'))).toBeNull()
    expect(pickEvidenceKind(makeFile('a.mp3', 10, 'audio/mpeg'))).toBeNull()
    expect(pickEvidenceKind(makeFile('a.avi', 10, 'video/x-msvideo'))).toBeNull()
    expect(pickEvidenceKind(null)).toBeNull()
  })
})

describe('validateEvidenceFile', () => {
  it('合法文件返回 null', () => {
    expect(validateEvidenceFile(makeFile('截图.png', 1024, 'image/png'))).toBeNull()
    expect(validateEvidenceFile(makeFile('记录.mp4', 1024, 'video/mp4'))).toBeNull()
    expect(validateEvidenceFile(makeFile('说明.pdf', 1024, 'application/pdf'))).toBeNull()
    expect(validateEvidenceFile(makeFile('备注.txt', 100, 'text/plain'))).toBeNull()
  })

  it('空文件与不支持的格式给出中文提示', () => {
    expect(validateEvidenceFile(makeFile('空.jpg', 0, 'image/jpeg'))).toContain('空')
    expect(validateEvidenceFile(null)).toContain('空')
    expect(validateEvidenceFile(makeFile('程序.exe', 10, 'application/octet-stream'))).toContain('不支持')
  })

  it('体积门槛：image 8MB / video 与 file 20MB / txt·csv 收紧 2MB', () => {
    expect(validateEvidenceFile(makeFile('大图.jpg', 8 * 1024 * 1024 + 1, 'image/jpeg'))).toContain('8MB')
    expect(validateEvidenceFile(makeFile('大片.mp4', 20 * 1024 * 1024 + 1, 'video/mp4'))).toContain('20MB')
    expect(validateEvidenceFile(makeFile('大.pdf', 20 * 1024 * 1024 + 1, 'application/pdf'))).toContain('20MB')
    expect(validateEvidenceFile(makeFile('大.txt', 2 * 1024 * 1024 + 1, 'text/plain'))).toContain('2MB')
    expect(validateEvidenceFile(makeFile('可.csv', 2 * 1024 * 1024, 'text/csv'))).toBeNull()
  })
})

describe('serializeEvidences / toEvidenceItem', () => {
  it('只保留 type/url/name 三字段（与后端归一化输出同构）', () => {
    const list = [
      { type: 'image', url: '/api/uploads/chat/202611/7_1.jpg', name: '截图.png', junk: 'inject' },
      { type: 'video', url: '/api/uploads/chat/202611/7_2.mp4', name: '视频.mp4' },
      { url: '/api/uploads/chat/202611/7_3.jpg' }, // 缺 type → 剔除
    ]
    const out = JSON.parse(serializeEvidences(list))
    expect(out).toHaveLength(2)
    expect(out[0]).toEqual({ type: 'image', url: '/api/uploads/chat/202611/7_1.jpg', name: '截图.png' })
    expect(JSON.stringify(out)).not.toContain('junk')
  })

  it('toEvidenceItem 截断超长 name', () => {
    const it0 = toEvidenceItem('image', '/api/uploads/chat/202611/7_1.jpg', '名'.repeat(300))
    expect(it0.name.length).toBeLessThanOrEqual(200)
  })
})

describe('parseEvidences 容错解析', () => {
  it('坏数据降级空数组而不抛错', () => {
    expect(parseEvidences('not-json')).toEqual([])
    expect(parseEvidences('{"a":1}')).toEqual([])
    expect(parseEvidences(null)).toEqual([])
    expect(parseEvidences(undefined)).toEqual([])
  })

  it('剔除非法 type 与非平台前缀 URL，超量截断到 9', () => {
    const good = { type: 'image', url: '/api/uploads/chat/202611/7_1.jpg', name: 'a.png' }
    const bad1 = { type: 'audio', url: '/api/uploads/chat/202611/7_2.m4a', name: 'x' }
    const bad2 = { type: 'image', url: 'https://evil.com/x.jpg', name: 'y' }
    expect(parseEvidences(JSON.stringify([good, bad1, bad2]))).toEqual([good])

    const many = Array.from({ length: 12 }, (_, i) => ({
      type: 'image',
      url: `/api/uploads/chat/202611/7_${i}.jpg`,
      name: `p${i}`,
    }))
    expect(parseEvidences(JSON.stringify(many))).toHaveLength(9)
  })

  it('直接接受数组输入（组件内部状态复用）', () => {
    expect(parseEvidences([{ type: 'file', url: '/api/uploads/chat/202611/7_9.pdf', name: 'f.pdf' }]))
      .toEqual([{ type: 'file', url: '/api/uploads/chat/202611/7_9.pdf', name: 'f.pdf' }])
  })
})

describe('ownerUserIdOf / formatBytes', () => {
  it('从落盘名首段解析归属 userId', () => {
    expect(ownerUserIdOf('/api/uploads/chat/202611/1001_1730000000.jpg')).toBe(1001)
    expect(ownerUserIdOf('/api/uploads/chat/202611/abc_1.jpg')).toBeNull()
    expect(ownerUserIdOf('')).toBeNull()
    expect(ownerUserIdOf(null)).toBeNull()
  })

  it('formatBytes 与 chatMedia 同口径', () => {
    expect(formatBytes(8 * 1024 * 1024)).toBe('8.0MB')
    expect(formatBytes(null)).toBe('')
  })
})
