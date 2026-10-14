import { describe, it, expect } from 'vitest'
import {
  REG_PHONE, REG_EMAIL,
  checkBirthday, passwordStrength, sanitizeBio
} from './validators'

describe('REG_PHONE / REG_EMAIL', () => {
  it('手机号：合法 13x-19x 11 位', () => {
    expect(REG_PHONE.test('13900000001')).toBe(true)
    expect(REG_PHONE.test('19912345678')).toBe(true)
  })
  it('手机号：非法号码被拒', () => {
    expect(REG_PHONE.test('12345678901')).toBe(false) // 1 开头但第二位 2
    expect(REG_PHONE.test('1390000000')).toBe(false)  // 10 位
    expect(REG_PHONE.test('139000000011')).toBe(false) // 12 位
    expect(REG_PHONE.test('1390000000a')).toBe(false)
  })
  it('邮箱：RFC 实用子集', () => {
    expect(REG_EMAIL.test('a@b.com')).toBe(true)
    expect(REG_EMAIL.test('user.name+tag@sub.example.cn')).toBe(true)
    expect(REG_EMAIL.test('bad-email')).toBe(false)
    expect(REG_EMAIL.test('a@b')).toBe(false)      // 无后缀
    expect(REG_EMAIL.test('@b.com')).toBe(false)   // 无用户名
  })
})

describe('checkBirthday', () => {
  it('满 14 周岁的生日合法', () => {
    const ok = checkBirthday('2000-01-01')
    expect(ok.ok).toBe(true)
  })
  it('拒绝晚于今天的日期', () => {
    const r = checkBirthday(new Date(Date.now() + 86400000).toISOString().slice(0, 10))
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('晚于今天')
  })
  it('拒绝早于 1900 年', () => {
    const r = checkBirthday('1899-12-31')
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('1900')
  })
  it('拒绝未满 14 周岁', () => {
    const r = checkBirthday(new Date().toISOString().slice(0, 10))
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('14 周岁')
  })
  it('空值允许（选填）', () => {
    expect(checkBirthday('').ok).toBe(true)
  })
})

describe('passwordStrength', () => {
  it('空串为 0 分', () => {
    expect(passwordStrength('').score).toBe(0)
    expect(passwordStrength(null).score).toBe(0)
  })
  it('弱密码 1 分', () => {
    expect(passwordStrength('abcdefgh').score).toBe(1)
  })
  it('强密码 3 分（10 位四类字符）', () => {
    const s = passwordStrength('Abcd1234!@')
    if (s.score !== 3) throw new Error('expected 3, got ' + s.score)
    expect(s.label).toBe('强')
  })
  it('很强 4 分（≥12 位四类字符）', () => {
    const s = passwordStrength('Abcd1234!@#$')
    expect(s.score).toBe(4)
    expect(s.label).toBe('很强')
  })
})

describe('sanitizeBio', () => {
  it('剔除 script 块', () => {
    expect(sanitizeBio('文本<script>alert(1)</script>结束')).toBe('文本结束')
  })
  it('剔除任意 HTML 标签', () => {
    expect(sanitizeBio('<p>hi</p><a href="#">x</a>')).toBe('hix')
  })
  it('超长截断到 500 字', () => {
    const long = 'a'.repeat(600)
    expect(sanitizeBio(long).length).toBe(500)
  })
  it('null/undefined 返回空串', () => {
    expect(sanitizeBio(null)).toBe('')
    expect(sanitizeBio(undefined)).toBe('')
  })
})