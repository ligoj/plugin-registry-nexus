/*
 * Contract tests for plugin-registry-nexus, incl. the parent → child
 * delegation: when registry-nexus is registered, plugin-registry's
 * renderFeatures/renderDetailsKey/renderDetailsFeatures resolve to this tool
 * for a matching node.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { pluginRegistry, useI18nStore } from '@ligoj/host'
import def from '../index.js'
import parentDef from '../../../../plugin-registry/ui/src/index.js'

// The type icon in the chip is the shared RegistryTypeIcon, drawn by the parent
// plugin-registry via its `renderTypeIcon` feature — register the parent so the
// tool can resolve it (as at runtime, where the parent is always loaded).
beforeEach(() => { setActivePinia(createPinia()); pluginRegistry.register('registry', parentDef) })
afterEach(() => { pluginRegistry.remove('registry') })

/** Extract the mdi icon name from a renderServiceLink (VBtn) or renderDetailsChip (VChip) vnode. */
function iconOf(vnode) {
  const kids = vnode.children.default()
  const iconVNode = Array.isArray(kids) ? kids[0] : kids
  return iconVNode.children.default()
}

// renderDetailsKey now returns a VTooltip wrapping a chip activator.
const chipOf = (tooltip) => tooltip.children.activator({ props: {} })
const linesOf = (tooltip) => tooltip.children.default()
const chipText = (chip) => { const k = chip.children.default(); return k[k.length - 1] }
// The type icon is the shared RegistryTypeIcon; assert the artifact `type` handed
// to it (the type→mdi mapping is verified in plugin-registry's own tests).
const chipType = (chip) => chip.children.default()[0].props.type
const lineType = (iconVNode) => iconVNode.props.type

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
    expect(i18n.t('service:registry:nexus:components')).toBe('Components')
    i18n.setLocale('fr')
    expect(i18n.t('service:registry:nexus:type')).toBe("Type d'artefact")
    expect(i18n.t('service:registry:nexus:components')).toBe('Composants')
  })

  it('throws for an unknown feature', () => {
    expect(() => def.feature('nope')).toThrow(/Plugin "registry-nexus" has no feature "nope"/)
  })

  it('parameterLayout orders type before registry only on a link subscription', () => {
    expect(def.feature('parameterLayout', { mode: 'link', isNode: false }))
      .toEqual([{ parameters: ['service:registry:nexus:type', 'service:registry:nexus:registry'] }])
    // Node context defers to the parent registry's connection ordering.
    expect(def.feature('parameterLayout', { mode: 'link', isNode: true })).toEqual([])
    expect(def.feature('parameterLayout', { mode: 'create' })).toEqual([])
    expect(def.feature('parameterLayout', {})).toEqual([])
  })

  it('renderFeatures is a home link to the node base URL, trailing slash trimmed', () => {
    def.install()
    const vnodes = def.feature('renderFeatures', {
      parameters: { 'service:registry:nexus:url': 'https://nexus.acme.io/', 'service:registry:nexus:registry': 'maven-releases' },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.target).toBe('_blank')
    expect(vnodes[0].props.href).toBe('https://nexus.acme.io')
    expect(iconOf(vnodes[0])).toBe('mdi-home')
  })

  it('renderFeatures returns [] without the node URL', () => {
    def.install()
    expect(def.feature('renderFeatures', { parameters: {} })).toEqual([])
    expect(def.feature('renderFeatures', {})).toEqual([])
  })

  it('renderDetailsKey builds a type-icon chip + 2-line icon tooltip (type value given directly)', () => {
    def.install()
    const maven = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'libs', 'service:registry:nexus:type': 'maven' } })
    expect(maven.__v_isVNode).toBe(true)
    const chip = chipOf(maven)
    expect(chipType(chip)).toBe('maven')
    expect(chipText(chip)).toBe('libs')
    const lines = linesOf(maven)
    expect(lines).toHaveLength(2)
    expect(lines[0].children[1]).toBe('maven')            // line 1: the type text
    expect(lineType(lines[0].children[0])).toBe('maven')  // line 1: the type icon
    expect(lines[1].children).toBe('libs')                // line 2: the repository name
  })

  it('renderDetailsKey resolves the persisted SELECT index to the artifact type', () => {
    def.install()
    // A subscription stores the SELECT as its option INDEX: 0=docker, 1=maven, ...
    const docker = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'app', 'service:registry:nexus:type': '0' } })
    expect(chipType(chipOf(docker))).toBe('docker')
    expect(linesOf(docker)[0].children[1]).toBe('docker')
    const maven = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'libs', 'service:registry:nexus:type': '1' } })
    expect(chipType(chipOf(maven))).toBe('maven')
  })

  it('renderDetailsKey passes an unknown/absent type through, with a single-line tooltip when absent', () => {
    def.install()
    // 'rust' isn't a known type — it's passed through to the shared icon, which
    // renders the generic package fallback (verified in plugin-registry).
    const unknown = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'r', 'service:registry:nexus:type': 'rust' } })
    expect(chipType(chipOf(unknown))).toBe('rust')
    const noType = def.feature('renderDetailsKey', { parameters: { 'service:registry:nexus:registry': 'r' } })
    expect(chipType(chipOf(noType))).toBe('')  // no type → empty string handed to the icon
    const lines = linesOf(noType)
    expect(lines).toHaveLength(1)          // no type → only the name line
    expect(lines[0].children).toBe('r')
  })

  it('renderDetailsKey renders a generic package icon when the parent cannot provide one', () => {
    def.install()
    const params = { 'service:registry:nexus:registry': 'r', 'service:registry:nexus:type': 'maven' }
    const iconName = (tooltip) => chipOf(tooltip).children.default()[0].children.default()
    // (a) parent registry not loaded at all
    pluginRegistry.remove('registry')
    expect(iconName(def.feature('renderDetailsKey', { parameters: params }))).toBe('mdi-package-variant')
    // (b) older parent bundle without the renderTypeIcon feature (feature() throws)
    pluginRegistry.register('registry', { id: 'registry', feature: () => { throw new Error('no feature "renderTypeIcon"') } })
    expect(iconName(def.feature('renderDetailsKey', { parameters: params }))).toBe('mdi-package-variant')
  })

  it('renderDetailsKey returns null without a registry', () => {
    def.install()
    expect(def.feature('renderDetailsKey', { parameters: {} })).toBeNull()
    expect(def.feature('renderDetailsKey', {})).toBeNull()
  })

  it('renderDetailsFeatures shows the live component count', () => {
    def.install()
    const out = def.feature('renderDetailsFeatures', { data: { components: 42 } })
    expect(out).toHaveLength(1)
    expect(out[0].__v_isVNode).toBe(true)
    const kids = out[0].children.default()
    expect(kids[kids.length - 1]).toBe('42')
    expect(iconOf(out[0])).toBe('mdi-package-variant')
  })

  it('renderDetailsFeatures returns null without status data', () => {
    def.install()
    expect(def.feature('renderDetailsFeatures', { data: {} })).toBeNull()
    expect(def.feature('renderDetailsFeatures', {})).toBeNull()
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
      parameters: { 'service:registry:nexus:registry': 'maven-releases', 'service:registry:nexus:type': 'maven' },
    })
    expect(Array.isArray(out)).toBe(true)
    expect(out.length).toBe(1)
    expect(out[0].__v_isVNode).toBe(true)
  })

  it('parent renderDetailsFeatures resolves to this tool for a matching node', () => {
    const out = parentDef.feature('renderDetailsFeatures', {
      node: { id: 'service:registry:nexus:1' },
      data: { components: 7 },
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
