/*
 * Service layer for plugin "registry-nexus".
 *
 * Tool-level plugin (lives at `service:registry:nexus`). The parent
 * `plugin-registry` delegates the subscription-row hooks to us:
 *
 *   - renderFeatures   → a link to the Nexus repository browser
 *     (`<url>/#browse/browse:<registry>`, or the browse root when no
 *     registry is set yet).
 *   - renderDetailsKey → the registry chip
 *     (`service:registry:nexus:registry`).
 *
 * `url` is a node-validation parameter; `registry` is collected at
 * subscription time. Kept free of Vue SFC imports so it can be
 * unit-tested without a DOM.
 */
import { renderServiceLink, renderDetailsChip, useI18nStore } from '@ligoj/host'

const PARAM_URL = 'service:registry:nexus:url'
const PARAM_REGISTRY = 'service:registry:nexus:registry'

/** Nexus repository browser link. Requires the node-level base URL. */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  const url = params?.[PARAM_URL]
  if (!url) return []
  const { t } = useI18nStore()
  const base = url.replace(/\/+$/, '')
  const registry = params[PARAM_REGISTRY]
  const href = registry ? `${base}/#browse/browse:${registry}` : `${base}/#browse/browse`
  return [renderServiceLink({ icon: 'mdi-cube-outline', href, title: t('service:registry:nexus:registry') })]
}

/** Registry chip. Mirrors the resource-key chip of the scm tools. */
function renderDetailsKey(subscription) {
  const registry = subscription?.parameters?.[PARAM_REGISTRY]
  if (!registry) return null
  const { t } = useI18nStore()
  return renderDetailsChip({ icon: 'mdi-cube-outline', text: registry, title: t('service:registry:nexus:registry') })
}

export default { renderFeatures, renderDetailsKey }
