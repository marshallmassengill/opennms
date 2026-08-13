<template>
  <IconField>
    <OnmsInputText
      :id="inputId"
      :modelValue="modelValue"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      :fluid="fluid"
      :data-test="dataTest"
      @update:modelValue="emit('update:modelValue', $event)"
    />
    <InputIcon>
      <OnmsIcon :icon="SearchGlyph" />
    </InputIcon>
  </IconField>
</template>

<script setup lang="ts">
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import SearchGlyph from '../icons/SearchGlyph.vue'
import OnmsIcon from './OnmsIcon.vue'
import OnmsInputText from './OnmsInputText.vue'

// Seam composite (NMS-20081): the OpenNMS search field — text input with a
// trailing search icon. Replaces every hand-rolled IconField/InputIcon/
// search-icon arrangement. Input-targeting attrs are declared props because
// fallthrough lands on the container div, not the <input> — `fluid` included:
// a width class on this component sizes the container, and without `fluid` the
// input inside keeps its intrinsic width.
withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
  inputId?: string
  ariaLabel?: string
  dataTest?: string
  fluid?: boolean
}>(), {
  modelValue: undefined,
  placeholder: undefined,
  inputId: undefined,
  ariaLabel: undefined,
  dataTest: undefined,
  fluid: undefined
})

const emit = defineEmits<{
  'update:modelValue': [value: string | undefined]
}>()
</script>
