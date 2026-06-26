/*
 * Contract tests for plugin-registry-nexus, incl. the parent → child
 * delegation: when registry-nexus is registered, plugin-registry's
 * renderFeatures/renderDetailsKey resolve to this tool for a matching node.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { pluginRegistry, useI18nStore } from '@ligoj/host'
import def from '../index.js'
import parentDef from '../../../../plugin-registry/ui/src/index.js'

beforeEach(() => { setActivePinia(createPinia()) })

describe('plugin-registry-nexus manifest', () => {
  it('exposes a valid tool-level manifest', () => {
    expect(def.id).toBe('registry-nexus')
    expect(def.label).toBe('Nexus')
    expect(def.requires).toEqual(['registry'])
    expect(def.routes).toBeUndefined()
    expect(def.component).toBeUndefined()
    expect(typeof def.install).toBe('function')
    expect(typeof def.feature).toBe('function')
    expect(def.service).toBeTypeOf('object')
    expect(def.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('merges en + fr i18n on install', () => {
    const i18n = useI18nStore()
    def.install()
    expect(i18n.t('service:registry:nexus:registry')).toBeTypeOf('string')
    expect(i18n.t('service:registry:nexus:type')).toBe('Artifact type')
    i18n.setLocale('fr')
    expect(i18n.t('service:registry:nexus:type')).toBe("Type d'artefact")
  })

  it('throws for an unknown feature', () => {
    expect(() => def.feature('nope')).toThrow(/Plugin "registry-nexus" has no feature "nope"/)
  })

  it('renderFeatures deep-links to the browsed repository, trailing slash trimmed', () => {
    def.install()
    const vnodes = def.feature('renderFeatures', {
      parameters: { 'service:registry:nexus:url': 'https://nexus.acme.io/', 'service:registry:nexus:registry': 'maven-releases' },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.target).toBe('_blank')
    expect(vnodes[0].props.href).toBe('https://nexus.acme.io/#browse/browse:maven-releases')
  })

  it('renderFeatures falls back to the browse root when no registry is set', () => {
    def.install()
    const vnodes = def.feature('renderFeatures', { parameters: { 'service:registry:nexus:url': 'https://nexus.acme.io' } })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].props.href).toBe('https://nexus.acme.io/#browse/browse')
  })

  it('renderFeatures returns [] without the node URL', () => {
    def.install()
    expect(def.feature('renderFeatures', { parameters: {} })).toEqual([])
    expect(def.feature('renderFeatures', {})).toEqual([])
  })

  it('renderDetailsKey returns the registry chip when present, else null', () => {
    def.install()
    const chip = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'maven-releases' } })
    expect(chip).toBeTruthy()
    expect(chip.__v_isVNode).toBe(true)
    expect(def.feature('renderDetailsKey', { parameters: {} })).toBeNull()
    expect(def.feature('renderDetailsKey', {})).toBeNull()
  })
})

describe('plugin-registry → plugin-registry-nexus delegation', () => {
  beforeEach(() => {
    parentDef.install({ router: { addRoute() {} } })
    def.install()
    pluginRegistry.register('registry-nexus', def)
  })
  afterEach(() => { pluginRegistry.remove('registry-nexus') })

  it('parent renderFeatures resolves to this tool for a matching node', () => {
    const out = parentDef.feature('renderFeatures', {
      node: { id: 'service:registry:nexus:1' },
      parameters: { 'service:registry:nexus:url': 'https://nexus.acme.io' },
    })
    expect(Array.isArray(out)).toBe(true)
    expect(out.length).toBe(1)
    expect(out[0].__v_isVNode).toBe(true)
  })

  it('parent renderDetailsKey resolves to this tool for a matching node', () => {
    const out = parentDef.feature('renderDetailsKey', {
      node: { id: 'service:registry:nexus:1' },
      parameters: { 'service:registry:nexus:registry': 'maven-releases' },
    })
    expect(Array.isArray(out)).toBe(true)
    expect(out.length).toBe(1)
    expect(out[0].__v_isVNode).toBe(true)
  })

  it('does not delegate for a different tool', () => {
    const out = parentDef.feature('renderDetailsKey', {
      node: { id: 'service:registry:other:1' },
      parameters: {},
    })
    expect(out).toBeNull()
  })
})
