import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import NexusJsonField from '../fields/NexusJsonField.vue'

const stubs = { LigojTextarea: { props: ['modelValue', 'placeholder'], emits: ['blur'], template: '<textarea class="ta" :data-placeholder="placeholder" @blur="$emit(\'blur\')" />' } }
const mountField = (id, props) => mount(NexusJsonField, { props: { parameter: { id }, ...props }, global: { stubs } })

// The JSON settings are saved trimmed: the value is trimmed when the field loses the focus (the submit click included).
describe('NexusJsonField', () => {
  beforeEach(() => { setActivePinia(createPinia()) })

  it('trims the value on blur', async () => {
    const w = mountField('service:registry:nexus:roles', { modelValue: '  \n{"dev":{"view-permissions":["read"]}}\n\n ' })
    await w.find('.ta').trigger('blur')
    expect(w.emitted('update:modelValue')).toEqual([['{"dev":{"view-permissions":["read"]}}']])
  })

  it('does not emit when already trimmed or empty', async () => {
    const w = mountField('service:registry:nexus:configuration', { modelValue: '{"yum":{}}' })
    await w.find('.ta').trigger('blur')
    const e = mountField('service:registry:nexus:configuration', { modelValue: null })
    await e.find('.ta').trigger('blur')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(e.emitted('update:modelValue')).toBeUndefined()
  })

  it('shows the settings sample of the selected type', () => {
    const w = mountField('service:registry:nexus:configuration', { formValues: { 'service:registry:nexus:type': '5' } })
    expect(w.find('.ta').attributes('data-placeholder')).toContain('repodataDepth')
    expect(mountField('service:registry:nexus:configuration', {}).find('.ta').attributes('data-placeholder')).toContain('blobStoreName')
    expect(mountField('service:registry:nexus:roles', {}).find('.ta').attributes('data-placeholder')).toContain('view-permissions')
  })
})
