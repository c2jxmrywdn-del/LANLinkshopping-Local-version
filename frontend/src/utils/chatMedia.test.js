import { describe, expect, it } from 'vitest'
import { KIND_LIMITS, formatBytes, mediaPlaceholder, pickKind, validateChatFile } from './chatMedia'

/** 生成指定字节数的测试文件（内容不重要，只看 size/name/type） */
function makeFile(name, size, type = '') {
  return new File([new ArrayBuffer(Math.max(0, size))], name, { type })
}

describe('KIND_LIMITS', () => {
  it('三种 kind 的上限与后端口径一致（image 8MB / file 20MB / voice 10MB）', () => {
    expect(KIND_LIMITS.image.max).toBe(8 * 1024 * 1024)
    expect(KIND_LIMITS.file.max).toBe(20 * 1024 * 1024)
    expect(KIND_LIMITS.voice.max).toBe(10 * 1024 * 1024)
    for (const label of Object.values(KIND_LIMITS).map(v => v.label)) {
      expect(typeof label).toBe('string')
      expect(label.length).toBeGreaterThan(0)
    }
  })
})

describe('pickKind', () => {
  it('image/* 归为 image', () => {
    expect(pickKind(makeFile('a.jpg', 10, 'image/jpeg'))).toBe('image')
    expect(pickKind(makeFile('a.png', 10, 'image/png'))).toBe('image')
  })

  it('audio/* 归为 voice（含带 codecs 参数的 MIME）', () => {
    expect(pickKind(makeFile('a.webm', 10, 'audio/webm'))).toBe('voice')
    expect(pickKind(makeFile('a.m4a', 10, 'audio/mp4'))).toBe('voice')
    expect(pickKind(makeFile('a.webm', 10, 'audio/webm;codecs=opus'))).toBe('voice')
  })

  it('其余一律归为 file', () => {
    expect(pickKind(makeFile('a.pdf', 10, 'application/pdf'))).toBe('file')
    expect(pickKind(makeFile('a.zip', 10, 'application/zip'))).toBe('file')
    expect(pickKind(makeFile('a.mp4', 10, 'video/mp4'))).toBe('file')
  })

  it('MIME 缺失或入参为空时回落 file', () => {
    expect(pickKind(makeFile('a.bin', 10))).toBe('file')
    expect(pickKind(null)).toBe('file')
    expect(pickKind(undefined)).toBe('file')
  })
})

describe('validateChatFile', () => {
  it('合法文件返回 null', () => {
    expect(validateChatFile(makeFile('照片.jpg', 1024, 'image/jpeg'), 'image')).toBeNull()
    expect(validateChatFile(makeFile('动画.webp', 2048, 'image/webp'), 'image')).toBeNull()
    expect(validateChatFile(makeFile('报价单.pdf', 4096, 'application/pdf'), 'file')).toBeNull()
    expect(validateChatFile(makeFile('表格.xlsx', 4096, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'), 'file')).toBeNull()
    expect(validateChatFile(makeFile('备注.txt', 100, 'text/plain'), 'file')).toBeNull()
    expect(validateChatFile(makeFile('录音.webm', 2048, 'audio/webm;codecs=opus'), 'voice')).toBeNull()
    expect(validateChatFile(makeFile('录音.m4a', 2048, 'audio/mp4'), 'voice')).toBeNull()
  })

  it('0 字节与空文件给出中文提示', () => {
    expect(validateChatFile(makeFile('空.jpg', 0, 'image/jpeg'), 'image')).toContain('空')
    expect(validateChatFile(null, 'image')).toContain('空')
    expect(validateChatFile(undefined, 'voice')).toContain('空')
  })

  it('图片超过 8MB 拒绝并给出人类可读上限', () => {
    const err = validateChatFile(makeFile('大图.jpg', 8 * 1024 * 1024 + 1, 'image/jpeg'), 'image')
    expect(err).toContain('8MB')
    // 恰好 8MB 放行
    expect(validateChatFile(makeFile('贴线.jpg', 8 * 1024 * 1024, 'image/jpeg'), 'image')).toBeNull()
  })

  it('文件超过 20MB 拒绝', () => {
    const err = validateChatFile(makeFile('压缩包.zip', 20 * 1024 * 1024 + 1, 'application/zip'), 'file')
    expect(err).toContain('20MB')
    expect(validateChatFile(makeFile('压缩包.zip', 20 * 1024 * 1024, 'application/zip'), 'file')).toBeNull()
  })

  it('txt/csv 单独收紧到 2MB', () => {
    expect(validateChatFile(makeFile('清单.txt', 2 * 1024 * 1024 + 1, 'text/plain'), 'file')).toContain('2MB')
    expect(validateChatFile(makeFile('数据.csv', 2 * 1024 * 1024 + 1, 'text/csv'), 'file')).toContain('2MB')
    expect(validateChatFile(makeFile('清单.txt', 2 * 1024 * 1024, 'text/plain'), 'file')).toBeNull()
    // 其他文档类型不受 2MB 限制（仍受 20MB 限制）
    expect(validateChatFile(makeFile('文档.docx', 3 * 1024 * 1024, 'application/msword'), 'file')).toBeNull()
  })

  it('语音超过 10MB 拒绝', () => {
    const err = validateChatFile(makeFile('录音.webm', 10 * 1024 * 1024 + 1, 'audio/webm'), 'voice')
    expect(err).toContain('10MB')
    expect(validateChatFile(makeFile('录音.webm', 10 * 1024 * 1024, 'audio/webm'), 'voice')).toBeNull()
  })

  it('未知扩展名与缺失扩展名拒绝', () => {
    expect(validateChatFile(makeFile('相册.heic', 100, 'image/heic'), 'image')).toContain('JPG')
    expect(validateChatFile(makeFile('程序.exe', 100, 'application/octet-stream'), 'file')).toContain('PDF')
    expect(validateChatFile(makeFile('无扩展名', 100, 'image/png'), 'image')).toContain('JPG')
    expect(validateChatFile(makeFile('音乐.mp4', 100, 'audio/mp4'), 'voice')).toContain('WEBM')
  })

  it('MIME 与扩展名不符拒绝（防伪装扩展名），MIME 缺失时仅按扩展名放行', () => {
    expect(validateChatFile(makeFile('假图.jpg', 100, 'application/zip'), 'image')).toContain('JPG')
    expect(validateChatFile(makeFile('假音频.webm', 100, 'video/mp4'), 'voice')).toContain('WEBM')
    expect(validateChatFile(makeFile('假文档.pdf', 100, 'image/png'), 'file')).toContain('PDF')
    // 浏览器识别不出 MIME（空串）时只按扩展名判断
    expect(validateChatFile(makeFile('真图.jpg', 100), 'image')).toBeNull()
    expect(validateChatFile(makeFile('真文档.pdf', 100), 'file')).toBeNull()
  })

  it('未知 kind 返回错误文案', () => {
    expect(validateChatFile(makeFile('a.jpg', 100, 'image/jpeg'), 'video')).toContain('不支持')
  })
})

describe('formatBytes', () => {
  it('0 与小于 1KB 显示 B', () => {
    expect(formatBytes(0)).toBe('0B')
    expect(formatBytes(1)).toBe('1B')
    expect(formatBytes(512)).toBe('512B')
    expect(formatBytes(1023)).toBe('1023B')
  })

  it('1024 边界与 KB 显示一位小数', () => {
    expect(formatBytes(1024)).toBe('1.0KB')
    expect(formatBytes(1536)).toBe('1.5KB')
    expect(formatBytes(1024 * 1024 - 1)).toBe('1024.0KB')
  })

  it('MB 显示一位小数', () => {
    expect(formatBytes(1024 * 1024)).toBe('1.0MB')
    expect(formatBytes(1.5 * 1024 * 1024)).toBe('1.5MB')
    expect(formatBytes(8 * 1024 * 1024)).toBe('8.0MB')
  })

  it('非法入参返回空串', () => {
    expect(formatBytes(null)).toBe('')
    expect(formatBytes(undefined)).toBe('')
    expect(formatBytes(-5)).toBe('')
    expect(formatBytes(Number.NaN)).toBe('')
  })
})

describe('mediaPlaceholder', () => {
  it('图片消息返回 [图片]', () => {
    expect(mediaPlaceholder({ msgType: 'image', mediaUrl: '/api/uploads/chat/x.jpg' })).toBe('[图片]')
  })

  it('文件消息带原始文件名，缺名回退未命名文件', () => {
    expect(mediaPlaceholder({ msgType: 'file', mediaName: '报价单.pdf' })).toBe('[文件] 报价单.pdf')
    expect(mediaPlaceholder({ msgType: 'file' })).toBe('[文件] 未命名文件')
  })

  it('语音消息带秒数（″），缺秒数回退 [语音]', () => {
    expect(mediaPlaceholder({ msgType: 'voice', voiceSeconds: 12 })).toBe('[语音] 12″')
    expect(mediaPlaceholder({ msgType: 'voice', voiceSeconds: 0 })).toBe('[语音]')
    expect(mediaPlaceholder({ msgType: 'voice' })).toBe('[语音]')
  })

  it('文本与缺省回退 content，空入参返回空串', () => {
    expect(mediaPlaceholder({ msgType: 'text', content: '你好' })).toBe('你好')
    expect(mediaPlaceholder({ content: '纯文本' })).toBe('纯文本')
    expect(mediaPlaceholder({ msgType: 'text' })).toBe('')
    expect(mediaPlaceholder(null)).toBe('')
  })
})
