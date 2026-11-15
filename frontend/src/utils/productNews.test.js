import { describe, expect, it } from 'vitest'
import { storyAffectsProduct } from './productNews'

const cement = { title: '普通硅酸盐水泥 P.O 42.5', brand: '海螺' }
const building = { industryName: '建筑材料' }

describe('storyAffectsProduct', () => {
  it('留下会影响当前商品的资讯', () => {
    expect(storyAffectsProduct(
      { title: '华东水泥价格上调', summary: '熟料库存下降' },
      cement,
      building
    )).toBe(true)
  })

  it('挡掉和当前商品无关的消费稿', () => {
    expect(storyAffectsProduct(
      { title: '以旧换新 小米米家智能净烟机', summary: '友望 S20 洗地机' },
      cement,
      building
    )).toBe(false)
  })

  it('没有商品词时不把全局资讯算进来', () => {
    expect(storyAffectsProduct({ title: '华东水泥价格上调' }, null, null)).toBe(false)
  })
})
