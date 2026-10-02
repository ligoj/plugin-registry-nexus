<template>
  <!-- CREATE mode: name of the hosted repository to create (free text, Nexus naming rule), suggested from the project
       key and the artifact type until the user types their own. -->
  <LigojTextField
    :model-value="modelValue"
    :label="`${t(parameter.id)} *`"
    :hint="t('service:registry:nexus:registry-create-description')"
    :rules="rules"
    persistent-hint
    variant="outlined"
    density="comfortable"
    @update:model-value="(v) => emit('update:modelValue', v ?? '')"
  />
</template>

<script setup>
import { watch } from 'vue'
import { useI18nStore, LigojTextField } from '@ligoj/host'
import { resolveType, PARAM_TYPE } from '../types.js'

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
const { t } = useI18nStore()

/** Nexus repository naming rule. */
const NAME = /^[a-zA-Z0-9-][a-zA-Z0-9_.-]*$/
const rules = [
  (v) => !!String(v ?? '').trim() || t('error.rule.NotBlank'),
  (v) => NAME.test(String(v ?? '').trim()) || t('error.rule.nexus-registry-name'),
]

// Suggest "<project key>-<type>" while the value is empty or still the previous suggestion
let suggested = null
watch(() => [props.project?.pkey, resolveType(props.formValues?.[PARAM_TYPE])], ([pkey, type]) => {
  if (!pkey) return
  const next = type ? `${pkey}-${type}` : pkey
  if (!props.modelValue || props.modelValue === suggested) {
    suggested = next
    emit('update:modelValue', next)
  }
}, { immediate: true })
</script>
