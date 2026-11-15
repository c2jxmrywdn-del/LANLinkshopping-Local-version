import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref } from 'vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  route: { query: {} },
  register: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push })
}))
vi.mock('../store/user', () => ({
  useUserStore: () => ({ register: mocks.register })
}))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}))

import Register from './Register.vue'

// 仓库未安装 @vue/test-utils；这里用 createApp + 轻量桩挂载真实编译后的页面。
const ACardStub = defineComponent({
  props: ['title'],
  setup(props, { slots }) {
    return () => h('section', { class: 'a-card-stub' }, [
      props.title ? h('h1', props.title) : null,
      slots.default?.()
    ])
  }
})

const AFormStub = defineComponent({
  props: ['model', 'layout'],
  emits: ['finish'],
  setup(_, { slots, emit }) {
    return () => h('form', {
      onSubmit: (event) => {
        event.preventDefault()
        emit('finish')
      }
    }, slots.default?.())
  }
})

const AFormItemStub = defineComponent({
  props: ['label', 'name', 'rules'],
  setup(props, { slots }) {
    return () => h('div', { class: 'a-form-item-stub' }, [
      props.label ? h('label', props.label) : null,
      slots.default?.()
    ])
  }
})

const AInputStub = defineComponent({
  props: {
    value: { type: String, default: '' },
    maxlength: { type: [String, Number], default: undefined }
  },
  emits: ['update:value'],
  setup(props, { attrs, emit }) {
    return () => h('input', {
      ...attrs,
      value: props.value,
      maxlength: props.maxlength,
      onInput: (event) => emit('update:value', event.target.value)
    })
  }
})

const ACheckboxStub = defineComponent({
  props: { checked: { type: Boolean, default: false } },
  emits: ['update:checked'],
  setup(props, { slots, emit }) {
    return () => h('label', [
      h('input', {
        type: 'checkbox',
        checked: props.checked,
        onChange: (event) => emit('update:checked', event.target.checked)
      }),
      slots.default?.()
    ])
  }
})

const AButtonStub = defineComponent({
  emits: ['click'],
  setup(_, { attrs, slots, emit }) {
    return () => h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.())
  }
})

const AAlertStub = defineComponent({
  props: ['message', 'type', 'showIcon'],
  setup(props) {
    return () => h('div', { class: 'a-alert-stub', role: 'alert' }, props.message)
  }
})

function mountRegister(query = {}) {
  mocks.route.query = query
  const host = document.createElement('div')
  document.body.appendChild(host)
  const instance = ref(null)
  const app = createApp({ render: () => h(Register, { ref: instance }) })
  app.component('ACard', ACardStub)
  app.component('AForm', AFormStub)
  app.component('AFormItem', AFormItemStub)
  app.component('AInput', AInputStub)
  app.component('AInputPassword', AInputStub)
  app.component('ACheckbox', ACheckboxStub)
  app.component('AButton', AButtonStub)
  app.component('AAlert', AAlertStub)
  app.mount(host)
  return {
    host,
    instance,
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('Register 邀请注册', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.route.query = {}
    mocks.register.mockResolvedValue(undefined)
  })

  it('携带邀请码时提交 inviteCode，且不再提交 roleCode，成功后跳转登录页', async () => {
    const view = mountRegister({ invite: 'INVABC123' })
    await nextTick()

    expect(view.host.querySelector('.a-alert-stub').textContent).toContain('邀请码 INVABC123')
    await view.instance.value.submit()

    expect(mocks.register).toHaveBeenCalledTimes(1)
    const payload = mocks.register.mock.calls[0][0]
    expect(payload).toMatchObject({ inviteCode: 'INVABC123' })
    expect(payload).not.toHaveProperty('roleCode')
    expect(mocks.push).toHaveBeenCalledWith('/login')
    view.unmount()
  })

  it('未携带邀请码时 payload 不含 inviteCode', async () => {
    const view = mountRegister()
    await nextTick()

    expect(view.host.querySelector('.a-alert-stub')).toBeNull()
    await view.instance.value.submit()

    const payload = mocks.register.mock.calls[0][0]
    expect(payload).not.toHaveProperty('inviteCode')
    expect(payload).not.toHaveProperty('roleCode')
    view.unmount()
  })

  it('不提供商户注册身份单选，并给出 /merchant 入驻申请入口', async () => {
    const view = mountRegister()
    await nextTick()

    const text = view.host.textContent
    expect(text).toContain('普通用户（采购方）')
    expect(text).not.toContain('商户/供应商')
    expect(view.host.querySelector('a-radio-group, a-radio-button, .ant-radio-group, input[type="radio"]')).toBeNull()

    const merchantLink = [...view.host.querySelectorAll('a')]
      .find((link) => link.textContent.includes('商户入驻申请'))
    expect(merchantLink).toBeTruthy()
    expect(merchantLink.getAttribute('href')).toBe('/merchant')
    view.unmount()
  })
})
