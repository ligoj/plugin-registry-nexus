import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import NexusRegistryNameField from '../fields/NexusRegistryNameField.vue'

const REGISTRY = { id: 'service:registry:nexus:registry', type: 'TEXT', mandatory: true }
const stubs = { LigojTextField: { props: ['label', 'rules', 'modelValue'], template: '<input class="name" :data-label="label" />' } }
const mountField = (props) => mount(NexusRegistryNameField, { props: { parameter: REGISTRY, modelValue: '', mode: 'create', ...props }, global: { stubs } })

// CREATE mode: the repository name is free text, suggested as "<project key>-<type>" until the user types their own.
describe('NexusRegistryNameField', () => {
  beforeEach(() => { setActivePinia(createPinia()) })

  it('suggests the name from the project key and the type, the stored SELECT index included', () => {
    const w = mountField({ project: { pkey: 'demo-2' }, formValues: { 'service:registry:nexus:type': '5' } })
    expect(w.emitted('update:modelValue')[0]).toEqual(['demo-2-yum'])
  })

  it('follows the type while the suggestion is untouched', async () => {
    const w = mountField({ project: { pkey: 'demo-2' }, formValues: { 'service:registry:nexus:type': 'maven' } })
    await w.setProps({ modelValue: 'demo-2-maven', formValues: { 'service:registry:nexus:type': 'python' } })
    expect(w.emitted('update:modelValue').at(-1)).toEqual(['demo-2-python'])
  })

  it('keeps a name typed by the user', async () => {
    const w = mountField({ modelValue: 'my-repo', project: { pkey: 'demo-2' }, formValues: { 'service:registry:nexus:type': 'maven' } })
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(w.find('.name').attributes('data-label')).toContain('*')
  })
})
