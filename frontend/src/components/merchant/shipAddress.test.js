import { describe, expect, it } from 'vitest'
import { provinceFromAddress } from '../../components/merchant/shipAddress'

describe('receiver province extraction for recommend params', () => {
  it('extracts province prefix from receiver address', () => {
    expect(provinceFromAddress('江苏省苏州市工业园区星湖街328号')).toBe('江苏')
    expect(provinceFromAddress('广东省广州市天河区')).toBe('广东')
    expect(provinceFromAddress('内蒙古自治区呼和浩特市')).toBe('内蒙古')
  })

  it('returns empty string for blank or unrecognized addresses', () => {
    expect(provinceFromAddress('')).toBe('')
    expect(provinceFromAddress('无省份标记地址')).toBe('')
  })
})
