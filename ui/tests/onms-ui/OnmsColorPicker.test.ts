import { OnmsColorPicker } from '@opennms/onms-ui'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'

const mountIt = (props: Record<string, unknown> = {}) =>
  mount(OnmsColorPicker, { props, global: { plugins: [PrimeVue] }, attachTo: document.body })

describe('OnmsColorPicker contract', () => {
  it('maps props onto PrimeVue ColorPicker and bakes the hex format', () => {
    const inner = mountIt({ modelValue: '#aabbcc' }).findComponent({ name: 'ColorPicker' })
    expect(inner.props('modelValue')).toBe('#aabbcc')
    expect(inner.props('format')).toBe('hex')
    expect(inner.props('appendTo')).toBe('body')
    expect(inner.props('disabled')).toBe(false)
  })

  it('maps disabled through', () => {
    expect(mountIt({ modelValue: '#aabbcc', disabled: true })
      .findComponent({ name: 'ColorPicker' }).props('disabled')).toBe(true)
  })

  // PrimeVue accepts hex with or without the '#' but always emits it without.
  // A bare `aabbcc` is not a valid CSS color, so the seam re-adds the '#'.
  it('re-adds the leading # that PrimeVue drops on the way out', () => {
    const wrapper = mountIt({ modelValue: '#aabbcc' })
    wrapper.findComponent({ name: 'ColorPicker' }).vm.$emit('update:modelValue', '112233')
    expect(wrapper.emitted('update:modelValue')).toEqual([['#112233']])
  })

  it('normalizes case and tolerates a value that already has the #', () => {
    const wrapper = mountIt({ modelValue: '#aabbcc' })
    const inner = wrapper.findComponent({ name: 'ColorPicker' })
    inner.vm.$emit('update:modelValue', 'AABBCC')
    inner.vm.$emit('update:modelValue', '#DDEEFF')
    expect(wrapper.emitted('update:modelValue')).toEqual([['#aabbcc'], ['#ddeeff']])
  })

  it('passes a non-hex value through rather than swallowing it', () => {
    const wrapper = mountIt({ modelValue: '#aabbcc' })
    wrapper.findComponent({ name: 'ColorPicker' }).vm.$emit('update:modelValue', 'rgb(1,2,3)')
    expect(wrapper.emitted('update:modelValue')).toEqual([['rgb(1,2,3)']])
  })

  // The reason this wrapper exists: the overlay is in-page, so PrimeVue can
  // dismiss it on an outside click or Escape, which a native <input type=color>
  // OS dialog cannot be. Only the in-page render is asserted here: PrimeVue
  // binds its outside-click listener from the overlay's transition enter hook,
  // after DOM geometry calls that happy-dom cannot satisfy, so the dismissal
  // itself is not reachable under vitest.
  it('opens its overlay in the page rather than as a native dialog', async () => {
    const wrapper = mountIt({ modelValue: '#aabbcc' })
    expect(document.querySelector('.p-colorpicker-panel')).toBeNull()

    await wrapper.find('.p-colorpicker-preview').trigger('click')
    await nextTick()

    expect(document.querySelector('.p-colorpicker-panel')).not.toBeNull()
    wrapper.unmount()
  })
})
