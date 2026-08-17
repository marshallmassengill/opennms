<template>
  <ColorPicker
    :modelValue="modelValue"
    :disabled="disabled"
    :appendTo="appendTo"
    format="hex"
    :pt="unsafePt as never"
    @update:modelValue="onUpdate"
  />
</template>

<script setup lang="ts">
import ColorPicker from 'primevue/colorpicker'

// Seam wrapper (NMS-20029) around PrimeVue ColorPicker: a swatch that opens an
// in-page overlay, which PrimeVue dismisses on an outside click or Escape. That
// dismissal is the reason to prefer it over a native <input type="color">,
// whose picker is an OS-level dialog the page can neither close nor observe.
//
// `format` is baked to 'hex'. Every OpenNMS color is a CSS hex string, and the
// rgb/hsb formats would change modelValue's type.
//
// The seam's value is a CSS hex color (`#rrggbb`) in both directions.
// PrimeVue is asymmetric here: it accepts hex with or without the leading '#'
// but always emits it WITHOUT, and a bare `aabbcc` is not a valid CSS color, so
// it would silently fail wherever the value is used as one.
withDefaults(defineProps<{
  modelValue?: string
  disabled?: boolean
  appendTo?: string
  unsafePt?: unknown
}>(), {
  modelValue: undefined,
  disabled: false,
  // The overlay renders to <body> so a scrolling or clipping ancestor cannot
  // cut it off; matches the other overlay wrappers in this package.
  appendTo: 'body',
  unsafePt: undefined
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const SIX_DIGIT_HEX = /^#?([0-9a-fA-F]{6})$/

const onUpdate = (value: unknown) => {
  const match = typeof value === 'string' ? value.match(SIX_DIGIT_HEX) : null
  // Anything that is not a six-digit hex is passed through untouched rather
  // than swallowed, so a format change upstream surfaces instead of going
  // silently missing.
  emit('update:modelValue', match ? `#${match[1].toLowerCase()}` : String(value ?? ''))
}
</script>
