/*
 * Plugin "registry-nexus" — Sonatype Nexus implementation of plugin-registry.
 *
 * Tool-level plugin (`service:registry:nexus`). Augments the parent
 * `plugin-registry` via i18n parameter labels + row features (repository
 * browse link + registry chip) merged in through plugin-registry's
 * `subPluginIdFor` delegation hook.
 *
 * Nexus is multi-format, so the artifact `type` is a real choice
 * (`docker` / `maven` / `nuget` / `npm` / `python`) — see csv/parameter.csv.
 *
 * Authored as source — compiled to `/main/registry-nexus/vue/index.js`.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
  renderDetailsKey: service.renderDetailsKey,
  renderDetailsFeatures: service.renderDetailsFeatures,
  parameterLayout: service.parameterLayout,
}

export default {
  id: 'registry-nexus',
  label: 'Nexus',
  requires: ['registry'],
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "registry-nexus" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-cube-outline', color: 'green-darken-2' },
}

export { service }
