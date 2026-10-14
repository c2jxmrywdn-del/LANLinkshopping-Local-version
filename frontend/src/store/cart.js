import { defineStore } from 'pinia'
import { cartApi } from '../api'

export const useCartStore = defineStore('cart', {
  state: () => ({ items: [] }),
  getters: {
    count: (s) => s.items.reduce((n, i) => n + i.quantity, 0),
    checkedItems: (s) => s.items.filter((i) => i.checked),
    total: (s) => s.checkedItems.reduce((n, i) => n + Number(i.subtotal), 0)
  },
  actions: {
    async load() { this.items = await cartApi.list() || [] },
    async add(prodId, quantity) { await cartApi.add({ prodId, quantity }); await this.load() },
    async remove(cartId) { await cartApi.remove(cartId); await this.load() },
    async setQty(cartId, q) { await cartApi.updateQty(cartId, q); await this.load() },
    async setChecked(cartId, checked) { await cartApi.setChecked(cartId, checked ? 1 : 0); const item=this.items.find(i=>i.cartId===cartId); if(item) item.checked=checked ? 1 : 0 },
    async setAllChecked(checked) { const targets=this.items.filter(i=>Boolean(i.checked)!==checked); await Promise.all(targets.map(i=>cartApi.setChecked(i.cartId, checked?1:0))); await this.load() }
  }
})
