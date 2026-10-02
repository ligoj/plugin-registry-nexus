/*
 * Nexus parameter ids and artifact types, shared by the service hooks and the subscribe-wizard fields.
 */
export const PARAM_URL = 'service:registry:nexus:url'
export const PARAM_TYPE = 'service:registry:nexus:type'
export const PARAM_REGISTRY = 'service:registry:nexus:registry'
export const PARAM_CONFIGURATION = 'service:registry:nexus:configuration'
export const PARAM_ROLES = 'service:registry:nexus:roles'

/**
 * Artifact types in the SELECT parameter's declared order — MUST match csv/parameter.csv and the backend NexusFormat
 * enum. A subscription persists a SELECT as its option INDEX, so new types are only appended.
 */
export const TYPE_VALUES = ['docker', 'maven', 'nuget', 'npm', 'python', 'yum', 'apt', 'raw', 'helm', 'rubygems', 'r', 'gitlfs']

/**
 * Resolve the stored artifact type. A SELECT is persisted as its option INDEX (e.g. "1"), so map that back to the
 * value; a value passed straight through (e.g. "maven") is kept as-is. Returns "" when there is nothing to resolve.
 */
export function resolveType(raw) {
  return String(TYPE_VALUES[Number(raw)] ?? raw ?? '').toLowerCase()
}
