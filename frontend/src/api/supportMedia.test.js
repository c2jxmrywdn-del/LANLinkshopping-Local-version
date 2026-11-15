import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  resolveActionUrl,
  validateFile,
  resolveKind,
  contentTypeOf,
  extensionOf
} from './supportMedia'

describe('supportMedia API & helpers', () => {
  const originalLocation = window.location

  beforeEach(() => {
    delete window.location
    window.location = new URL('http://localhost:5173/chat')
  })

  afterEach(() => {
    window.location = originalLocation
  })

  it('extensionOf 提取扩展名并转小写', () => {
    expect(extensionOf('木箱子.JPG')).toBe('jpg')
    expect(extensionOf('voice.webm')).toBe('webm')
    expect(extensionOf('archive.tar.gz')).toBe('gz')
    expect(extensionOf('noext')).toBe('')
  })

  it('resolveKind 能正确识别图片、音频、视频与文档', () => {
    expect(resolveKind({ name: '木箱子.jpg', type: 'image/jpeg' })).toBe('IMAGE')
    expect(resolveKind({ name: 'voice.webm', type: 'audio/webm' })).toBe('AUDIO')
    expect(resolveKind({ name: 'demo.mp4', type: 'video/mp4' })).toBe('VIDEO')
    expect(resolveKind({ name: 'doc.pdf', type: 'application/pdf' })).toBe('DOCUMENT')
  })

  it('contentTypeOf 在 File.type 缺失时按扩展名兜底', () => {
    expect(contentTypeOf({ name: 'pic.png', type: '' })).toBe('image/png')
    expect(contentTypeOf({ name: 'test.unknown', type: '' })).toBe('application/octet-stream')
    expect(contentTypeOf({ name: 'audio.wav', type: 'audio/wav' })).toBe('audio/wav')
  })

  it('validateFile 拦截空文件与超限文件', () => {
    expect(validateFile({ name: 'empty.jpg', size: 0 }).ok).toBe(false)
    expect(validateFile({ name: 'huge.jpg', size: 20 * 1024 * 1024 }).ok).toBe(false)
    expect(validateFile({ name: 'valid.jpg', size: 1024 }).ok).toBe(true)
  })

  it('resolveActionUrl 强制转换为当前页面同源地址，避免跨 Host CORS 与 PNA 拦截', () => {
    // 当后端返回 127.0.0.1 绝对地址，而前端运行在 localhost
    expect(resolveActionUrl('http://127.0.0.1:5173/support-media')).toBe('http://localhost:5173/support-media')

    // 当后端返回局域网 IP
    expect(resolveActionUrl('http://192.168.1.100:5173/support-media')).toBe('http://localhost:5173/support-media')

    // 当后端只返回相对路径
    expect(resolveActionUrl('/support-media')).toBe('http://localhost:5173/support-media')

    // 当 action 为空
    expect(resolveActionUrl('')).toBe('http://localhost:5173/support-media')
  })
})
