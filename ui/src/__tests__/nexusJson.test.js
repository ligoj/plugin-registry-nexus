import { describe, it, expect } from 'vitest'
import { configurationError, rolesError } from '../fields/nexusJson.js'

// Client-side mirror of the backend checks of the CREATE mode JSON parameters:
// the message shows while typing, the backend stays the reference.
describe('configurationError', () => {
  it('accepts blank and a JSON object', () => {
    expect(configurationError('')).toBeNull()
    expect(configurationError(null)).toBeNull()
    expect(configurationError('{"maven":{"versionPolicy":"SNAPSHOT"}}')).toBeNull()
  })
  it('rejects invalid JSON and non-objects', () => {
    expect(configurationError('{')).toBe('error.rule.nexus-configuration-json')
    expect(configurationError('[1]')).toBe('error.rule.nexus-configuration-json')
  })
})

describe('rolesError', () => {
  it('accepts a content selector with an expression and its permissions, alone or with the other permissions', () => {
    const selector = '"content-selector":{"permissions":["browse","read","delete"],"expression":"format == \\"maven2\\" and path =^ \\"/org\\""}'
    expect(rolesError(`{"dev":{"view-permissions":["browse"],${selector}}}`)).toBeNull()
    expect(rolesError(`{"dev":{${selector}}}`)).toBeNull()
  })
  it('rejects a content selector without expression, without permission, or with an unknown action', () => {
    expect(rolesError('{"dev":{"content-selector":{"permissions":["read"],"expression":" "}}}')).toBe('error.rule.nexus-roles-selector')
    expect(rolesError('{"dev":{"content-selector":{"permissions":[],"expression":"path =^ \\"/org\\""}}}')).toBe('error.rule.nexus-roles-selector')
    expect(rolesError('{"dev":{"content-selector":"path"}}')).toBe('error.rule.nexus-roles-selector')
    expect(rolesError('{"dev":{"content-selector":{"permissions":["write"],"expression":"path =^ \\"/org\\""}}}')).toBe('error.rule.nexus-roles-permission')
  })
  it('accepts blank and the documented mapping', () => {
    expect(rolesError(' ')).toBeNull()
    expect(rolesError('{"admin":{"view-permissions":["*"],"admin-permissions":["*"]},"test":{"view-permissions":["browse"]}}')).toBeNull()
  })
  it('rejects invalid JSON, a role without permission and an unknown action', () => {
    expect(rolesError('{"dev":')).toBe('error.rule.nexus-roles-json')
    expect(rolesError('["dev"]')).toBe('error.rule.nexus-roles-json')
    expect(rolesError('{"dev":{}}')).toBe('error.rule.nexus-roles-empty')
    expect(rolesError('{"dev":{"view-permissions":["write"]}}')).toBe('error.rule.nexus-roles-permission')
    expect(rolesError('{"dev":null}')).toBe('error.rule.nexus-roles-json')
    expect(rolesError('{"dev":["read"]}')).toBe('error.rule.nexus-roles-json')
    expect(rolesError('{"dev":{"admin-permissions":[null]}}')).toBe('error.rule.nexus-roles-permission')
    expect(rolesError('{"dev":{"admin-permissions":["browse"]}}')).toBeNull()
  })
})
