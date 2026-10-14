/*
 * `parameterSummary` feature: the short text of the JSON parameters in the subscription status tooltip, instead of
 * the raw JSON. The role mapping is shown by its groups, the repository settings by their sections.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import def from '../index.js'
import { ROLES_SAMPLE } from '../fields/nexusJson.js'

const summary = (parameter, value) => def.feature('parameterSummary', { parameter, value })
const ROLES = 'service:registry:nexus:roles'
const CONFIGURATION = 'service:registry:nexus:configuration'

describe('registry-nexus parameterSummary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    def.install()
    useI18nStore().setLocale('en')
  })

  it('shows the role mapping as its groups', () => {
    expect(summary(ROLES, ROLES_SAMPLE)).toBe('3 roles: admin, dev, test')
    expect(summary(ROLES, '{"dev":{"view-permissions":["browse"]}}')).toBe('1 role: dev')
    expect(summary(ROLES, '{}')).toBe('No role')
  })

  it('names the first groups only, then how many others', () => {
    expect(summary(ROLES, JSON.stringify({ a: {}, b: {}, c: {}, d: {}, e: {} }))).toBe('5 roles: a, b, c +2')
  })

  it('shows the repository settings as their sections', () => {
    expect(summary(CONFIGURATION, '{"storage":{"blobStoreName":"default"},"cleanup":{"policyNames":["weekly"]}}')).toBe('2 settings: storage, cleanup')
  })

  it('translates the summary', () => {
    useI18nStore().setLocale('fr')
    expect(summary(ROLES, ROLES_SAMPLE)).toBe('3 rôles : admin, dev, test')
  })

  it('leaves the other parameters and an unreadable value to the default display', () => {
    expect(summary('service:registry:nexus:url', 'https://nexus.example.com')).toBeNull()
    expect(summary(ROLES, '{ not json')).toBeNull()
    expect(summary(ROLES, '["admin"]')).toBeNull()
    expect(summary(ROLES, '')).toBeNull()
  })
})
