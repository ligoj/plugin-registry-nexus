/**
 * Client-side checks of the CREATE mode JSON parameters of Nexus, mirroring the backend rules so the user gets the
 * message while typing (the backend stays the reference). Each function returns the i18n key of the first problem, or
 * null. A blank value is valid: the parameter is optional.
 */

/** Repository actions of the view and admin privileges. */
export const ACTIONS = ['browse', 'read', 'edit', 'add', 'delete', '*']

function parseObject(value) {
  const text = String(value ?? '').trim()
  if (!text) return { empty: true }
  try {
    const json = JSON.parse(text)
    return json && typeof json === 'object' && !Array.isArray(json) ? { json } : { invalid: true }
  } catch {
    return { invalid: true }
  }
}

/**
 * Format settings (`service:registry:nexus:configuration`): a JSON object merged over the format defaults.
 *
 * @param {string|null|undefined} value The typed settings.
 * @returns {string|null} The i18n key of the problem, or null.
 */
export function configurationError(value) {
  return parseObject(value).invalid ? 'error.rule.nexus-configuration-json' : null
}

/**
 * Role mapping (`service:registry:nexus:roles`): an object keyed by group, each role granting at least one known
 * action through `view-permissions`, `admin-permissions` and/or a `content-selector` (an `expression` and its
 * `permissions`).
 *
 * @param {string|null|undefined} value The typed mapping.
 * @returns {string|null} The i18n key of the problem, or null.
 */
export function rolesError(value) {
  const { json, invalid } = parseObject(value)
  if (invalid) return 'error.rule.nexus-roles-json'
  for (const role of Object.values(json || {})) {
    if (!role || typeof role !== 'object' || Array.isArray(role)) return 'error.rule.nexus-roles-json'
    const actions = [...(role['view-permissions'] || []), ...(role['admin-permissions'] || [])]
    const selector = role['content-selector']
    if (selector != null) {
      // A content selector needs an expression and its own permissions
      if (typeof selector !== 'object' || !String(selector.expression ?? '').trim()
        || !Array.isArray(selector.permissions) || !selector.permissions.length) return 'error.rule.nexus-roles-selector'
      actions.push(...selector.permissions)
    }
    if (!actions.length) return 'error.rule.nexus-roles-empty'
    if (actions.some((a) => !ACTIONS.includes(String(a ?? '').trim()))) return 'error.rule.nexus-roles-permission'
  }
  return null
}

/** Settings sample per artifact type, shown as placeholder of the configuration input. */
export const CONFIGURATION_SAMPLES = {
  maven: '{\n  "maven": { "versionPolicy": "SNAPSHOT", "layoutPolicy": "PERMISSIVE" },\n  "storage": { "writePolicy": "ALLOW" }\n}',
  docker: '{\n  "docker": { "forceBasicAuth": true, "httpPort": 8083 }\n}',
  yum: '{\n  "yum": { "repodataDepth": 2, "deployPolicy": "PERMISSIVE" }\n}',
  apt: '{\n  "apt": { "distribution": "bookworm" },\n  "aptSigning": { "keypair": "-----BEGIN PGP PRIVATE KEY BLOCK-----…", "passphrase": "…" }\n}',
  raw: '{\n  "raw": { "contentDisposition": "INLINE" }\n}',
}

/** Default settings sample, for the types without specific settings. */
export const CONFIGURATION_SAMPLE = '{\n  "storage": { "blobStoreName": "default", "writePolicy": "ALLOW_ONCE" },\n  "cleanup": { "policyNames": ["weekly"] }\n}'

/** Role mapping sample, shown as placeholder of the roles input. */
export const ROLES_SAMPLE = '{\n  "admin": { "view-permissions": ["*"], "admin-permissions": ["*"] },\n  "dev": { "view-permissions": ["browse"],\n    "content-selector": { "permissions": ["browse", "read", "add"], "expression": "path =^ \\"/org\\"" } },\n  "test": { "view-permissions": ["browse"] }\n}'
