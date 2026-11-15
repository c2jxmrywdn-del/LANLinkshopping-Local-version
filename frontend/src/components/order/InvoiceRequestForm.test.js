import { describe, it, expect } from 'vitest'
import { h, reactive, createApp, nextTick, defineComponent } from 'vue'
import InvoiceRequestForm from './InvoiceRequestForm.vue'

// antd 桩（vitest 环境 vue 为 runtime-only 构建）：checkbox/radio 以原生 input 承载交互，
// input/textarea 直出原生元素，v-model 值经 emitted 事件回传
const ACheckboxStub = defineComponent({
  props: { checked: { type: Boolean, default: false } },
  emits: ['update:checked'],
  setup(props, { slots, emit }) {
    return () => h('label', { class: 'a-checkbox-stub', 'data-testid': 'invoice-needed' }, [
      h('input', {
        type: 'checkbox',
        checked: props.checked,
        onChange: (e) => emit('update:checked', e.target.checked)
      }),
      slots.default?.()
    ])
  }
})
const ARadioStub = defineComponent({
  props: { value: { type: [String, Number], default: '' } },
  setup(props, { slots }) {
    return () => h('label', { class: 'a-radio-stub', 'data-value': props.value }, slots.default?.())
  }
})
const ARadioGroupStub = defineComponent({
  props: { value: { type: String, default: '' }, testid: { type: String, default: '' } },
  emits: ['update:value'],
  setup(props, { slots, emit }) {
    return () => h('div', {
      class: 'a-radio-group-stub',
      'data-testid': props.testid,
      'data-value': props.value,
      onClick: (e) => {
        const label = e.target.closest('.a-radio-stub')
        if (label) emit('update:value', label.getAttribute('data-value'))
      }
    }, slots.default?.())
  }
})
const AInputStub = defineComponent({
  props: { value: { type: String, default: '' }, testid: { type: String, default: '' }, maxlength: { type: [String, Number], default: undefined } },
  emits: ['update:value'],
  setup(props, { emit }) {
    return () => h('input', {
      class: 'a-input-stub',
      'data-testid': props.testid,
      maxlength: props.maxlength,
      value: props.value,
      onInput: (e) => emit('update:value', e.target.value)
    })
  }
})
const ATextareaStub = defineComponent({
  props: { value: { type: String, default: '' }, testid: { type: String, default: '' }, rows: { type: [String, Number], default: 2 } },
  emits: ['update:value'],
  setup(props, { emit }) {
    return () => h('textarea', {
      class: 'a-textarea-stub',
      'data-testid': props.testid,
      rows: props.rows,
      value: props.value,
      onInput: (e) => emit('update:value', e.target.value)
    })
  }
})

// P1-5 发票需求表单交互：勾选展开、单位/个人联动税号、category/detail 联动清空、v-model 回传与复位
function mountForm(initial) {
  const props = reactive({ modelValue: initial === undefined ? null : { ...initial } })
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(InvoiceRequestForm, {
        modelValue: props.modelValue,
        'onUpdate:modelValue': (v) => { props.modelValue = v }
      })
    }
  })
  app.component('a-checkbox', ACheckboxStub)
  app.component('a-radio', ARadioStub)
  app.component('a-radio-group', ARadioGroupStub)
  app.component('a-input', AInputStub)
  app.component('a-textarea', ATextareaStub)
  app.mount(root)
  return {
    root, props,
    box: () => root.querySelector('[data-testid="invoice-box"]'),
    neededInput: () => root.querySelector('[data-testid="invoice-needed"] input[type="checkbox"]'),
    grid: () => root.querySelector('.inv-grid'),
    field: (name) => root.querySelector(`[data-testid="${name}"]`),
    radioGroup: (name) => root.querySelector(`[data-testid="${name}"]`),
    async clickRadio(name, value) {
      const label = [...root.querySelectorAll(`[data-testid="${name}"] .a-radio-stub`)]
        .find(l => l.getAttribute('data-value') === value)
      if (!label) throw new Error('radio not found: ' + name + '/' + value)
      label.click()
      await nextTick()
      await nextTick()
    },
    async type(name, text) {
      const el = root.querySelector(`[data-testid="${name}"]`)
      el.value = text
      el.dispatchEvent(new Event('input'))
      await nextTick()
    },
    async checkNeeded() {
      this.neededInput().click()
      await nextTick()
    },
    unmount() { app.unmount(); root.remove() }
  }
}

describe('InvoiceRequestForm', () => {
  it('renders only the toggle when invoice is not requested', () => {
    const t = mountForm(null)
    expect(t.box()).toBeTruthy()
    expect(t.grid()).toBeNull()
    t.unmount()
  })

  it('expands fields after checking needed and emits model update', async () => {
    const t = mountForm(null)
    await t.checkNeeded()
    expect(t.grid()).toBeTruthy()
    expect(t.props.modelValue).toMatchObject({ needed: true, titleType: 'company', contentType: 'category' })
    t.unmount()
  })

  it('shows taxNo field only for company title type', async () => {
    const t = mountForm(null)
    await t.checkNeeded()
    expect(t.field('invoice-tax-no')).toBeTruthy()
    await t.clickRadio('invoice-title-type', 'personal')
    expect(t.field('invoice-tax-no')).toBeNull()
    await t.clickRadio('invoice-title-type', 'company')
    expect(t.field('invoice-tax-no')).toBeTruthy()
    t.unmount()
  })

  it('switching content type swaps category and detail fields', async () => {
    const t = mountForm(null)
    await t.checkNeeded()
    expect(t.field('invoice-category')).toBeTruthy()
    expect(t.field('invoice-detail')).toBeNull()
    await t.clickRadio('invoice-content-type', 'detail')
    expect(t.field('invoice-category')).toBeNull()
    expect(t.field('invoice-detail')).toBeTruthy()
    t.unmount()
  })

  it('propagates typed title and taxNo into emitted modelValue', async () => {
    const t = mountForm(null)
    await t.checkNeeded()
    await t.type('invoice-title', '测试科技有限公司')
    await t.type('invoice-tax-no', '91330100MA2XYZ1234')
    expect(t.props.modelValue.title).toBe('测试科技有限公司')
    expect(t.props.modelValue.taxNo).toBe('91330100MA2XYZ1234')
    t.unmount()
  })

  it('resets to collapsed state when parent resets modelValue to null', async () => {
    const t = mountForm({ needed: true, titleType: 'company', title: 'X', taxNo: '', contentType: 'category', category: 'Y', detail: '' })
    expect(t.grid()).toBeTruthy()
    await t.root // 占位保持结构一致
    t.props.modelValue = null
    await nextTick()
    await nextTick()
    expect(t.grid()).toBeNull()
    t.unmount()
  })
})
