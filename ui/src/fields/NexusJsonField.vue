<template>
  <!-- CREATE mode JSON settings of Nexus: the format configuration merged over the defaults, or the role mapping keyed
       by group. The live check keeps a malformed value from reaching the backend, which validates it again. -->
  <LigojTextarea
    :model-value="modelValue"
    :label="label"
    :placeholder="placeholder"
    :hint="t(`${parameter.id}-description`)"
    :rules="rules"
    persistent-hint
    variant="outlined"
    density="compact"
    rows="5"
    auto-grow
    class="nx-json"
    @blur="trimValue"
    @update:model-value="(v) => emit('update:modelValue', v ?? '')"
  />
</template>

<script setup>
import { computed } from 'vue'
import { useI18nStore, LigojTextarea } from '@ligoj/host'
import { configurationError, rolesError, CONFIGURATION_SAMPLES, CONFIGURATION_SAMPLE, ROLES_SAMPLE } from './nexusJson.js'
import { resolveType, PARAM_TYPE, PARAM_ROLES } from '../types.js'

const props = defineProps({
  modelValue: { type: [String, null], default: null },
  parameter: { type: Object, required: true },
  formValues: { type: Object, default: () => ({}) },
  mode: { type: String, default: null },
  isNode: { type: Boolean, default: false },
  nodeId: { type: String, default: null },
  instanceNodeId: { type: String, default: null },
  project: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue'])

/** Saved trimmed: trim when the field loses the focus, which the submit click triggers first (not while typing). */
function trimValue() {
  const value = props.modelValue
  if (typeof value === 'string' && value.trim() !== value) emit('update:modelValue', value.trim())
}
const { t } = useI18nStore()

const isRoles = computed(() => props.parameter.id === PARAM_ROLES)
const label = computed(() => t(props.parameter.id))
const placeholder = computed(() => (isRoles.value ? ROLES_SAMPLE
  : CONFIGURATION_SAMPLES[resolveType(props.formValues?.[PARAM_TYPE])] || CONFIGURATION_SAMPLE))
const rules = [(v) => { const key = (isRoles.value ? rolesError : configurationError)(v); return key ? t(key) : true }]
</script>

<style scoped>
.nx-json :deep(textarea) { font-family: var(--mono, ui-monospace, monospace); font-size: 12.5px; }
</style>
