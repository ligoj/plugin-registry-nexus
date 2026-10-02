/*
 * Service layer for plugin "registry-nexus".
 *
 * Tool-level plugin (lives at `service:registry:nexus`). The parent
 * `plugin-registry` delegates the subscription-row hooks to us:
 *
 *   - renderFeatures        → a "home" link to the Nexus browse view of the
 *     subscription repository (the node base URL without repository).
 *   - renderDetailsKey      → the registry chip, prefixed with the icon of the
 *     configured artifact type, with a two-line tooltip (type + name).
 *   - renderDetailsFeatures → the live component count, refreshed from the
 *     subscription status data.
 *
 * Kept free of Vue SFC imports so it can be unit-tested without a DOM.
 */
import { h } from 'vue'
import { pluginRegistry, renderServiceLink, renderDetailsChip, useI18nStore, VChip, VIcon, VTooltip } from '@ligoj/host'

import { PARAM_URL, PARAM_TYPE, PARAM_REGISTRY, PARAM_CONFIGURATION, PARAM_ROLES, resolveType } from './types.js'
import NexusJsonField from './fields/NexusJsonField.vue'
import NexusRegistryNameField from './fields/NexusRegistryNameField.vue'

/**
 * Artifact-type icon as a VNode, drawn by the shared RegistryTypeIcon that the
 * parent plugin-registry hosts (a plugin can't import another plugin's SFC, so
 * we ask the parent via its `renderTypeIcon` feature). Falls back to a generic
 * package icon if the parent isn't loaded / is too old. `attrs` forwards
 * size / start / class through to the icon.
 */
function typeIconVNode(type, attrs = {}) {
  const registry = pluginRegistry.get('registry')
  if (registry) {
    try { return registry.feature('renderTypeIcon', { type, ...attrs }) } catch { /* older parent without the feature */ }
  }
  return h(VIcon, attrs, () => 'mdi-package-variant')
}

/** "Home" link to the Nexus browse view of the subscription repository, the Nexus home page without repository. */
function renderFeatures(subscription) {
  const url = subscription?.parameters?.[PARAM_URL]
  if (!url) return []
  const { t } = useI18nStore()
  const base = url.replace(/\/+$/, '')
  const registry = subscription.parameters[PARAM_REGISTRY]
  // Nexus 3 UI route of a repository content: #browse/browse:<repository>
  const href = registry ? `${base}/#browse/browse:${encodeURIComponent(registry)}` : base
  return [renderServiceLink({ icon: 'mdi-home', href, title: t('service:registry:nexus') })]
}

/**
 * Registry chip prefixed with the artifact-type icon. The tooltip has two
 * lines: the type (with its icon) and the repository name.
 */
function renderDetailsKey(subscription) {
  const params = subscription?.parameters
  const registry = params?.[PARAM_REGISTRY]
  if (!registry) return null
  const type = resolveType(params[PARAM_TYPE])
  return h(VTooltip, { location: 'bottom' }, {
    activator: ({ props }) => h(VChip, { ...props, size: 'small', variant: 'tonal', class: 'mr-1' },
      () => [typeIconVNode(type, { start: true, size: 'small' }), ' ', registry]),
    default: () => (type
      ? [h('div', { class: 'd-flex align-center ga-1' }, [typeIconVNode(type, { size: 'x-small' }), type]), h('div', registry)]
      : [h('div', registry)]),
  })
}

/** Live component count, refreshed from the subscription status data. */
function renderDetailsFeatures(subscription) {
  const components = subscription?.data?.components
  if (components == null) return null
  const { t } = useI18nStore()
  return [renderDetailsChip({ icon: 'mdi-package-variant', text: String(components), title: t('service:registry:nexus:components') })]
}

/**
 * Subscribe-wizard parameter layout: the repository type before the registry (the wizard's default is name
 * ascending), then on a creation the format settings and the role mapping. In node context (isNode) we return nothing
 * so the parent registry plugin's connection ordering (url, user, secret) applies.
 */
function parameterLayout({ mode, isNode } = {}) {
  if (isNode) return []
  const m = String(mode).toLowerCase()
  if (m === 'link') return [{ parameters: [PARAM_TYPE, PARAM_REGISTRY] }]
  if (m === 'create') return [{ parameters: [PARAM_TYPE, PARAM_REGISTRY, PARAM_CONFIGURATION, PARAM_ROLES] }]
  return []
}

/**
 * Subscribe-wizard fields of the CREATE mode: JSON inputs for the format settings and the role mapping, and a free
 * name for the repository to create. Everything else, including the registry search of the LINK mode and the type
 * picker, falls back to the parent plugin-registry (null).
 */
function parameterField({ parameter, mode, isNode } = {}) {
  if (isNode) return null
  const id = parameter?.id
  if (id === PARAM_CONFIGURATION || id === PARAM_ROLES) return NexusJsonField
  if (id === PARAM_REGISTRY && String(mode).toLowerCase() === 'create') return NexusRegistryNameField
  return null
}

export default { renderFeatures, renderDetailsKey, renderDetailsFeatures, parameterLayout, parameterField, resolveType }
