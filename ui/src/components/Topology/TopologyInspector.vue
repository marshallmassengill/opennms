<!--
Licensed to The OpenNMS Group, Inc (TOG) under one or more
contributor license agreements.  See the LICENSE.md file
distributed with this work for additional information
regarding copyright ownership.

TOG licenses this file to You under the GNU Affero General
Public License Version 3 (the "License") or (at your option)
any later version.  You may not use this file except in
compliance with the License.  You may obtain a copy of the
License at:

     https://www.gnu.org/licenses/agpl-3.0.txt

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
-->

<!--
  Selection-driven properties panel. Shows (and, for labels, edits) the
  currently-selected canvas element: a free-standing label's text/color/
  size, or a placed node's OpenNMS detail and current alarm severity.
  Node and edge editing beyond this is a follow-up.
-->

<template>
  <PCard class="topology-inspector">
    <template #title>
      <span class="ti-title">{{ variant === 'props' ? 'Properties' : 'Inspector' }}</span>
    </template>
    <template #content>
      <!-- Nothing selected (full/View only) -->
      <p v-if="kind === 'none' && variant === 'full'" class="ti-empty">
        Select a node, edge, or label to see its properties.
      </p>

      <!-- Multiple items (full/View only) -->
      <p v-else-if="kind === 'multi' && variant === 'full'" class="ti-empty">
        {{ store.selectedIds.length }} items selected.
      </p>

      <!-- A free-standing label (editable in Edit mode, read-only in View) -->
      <div v-else-if="kind === 'label' && label" class="ti-section">
        <div class="ti-field">
          <label class="ti-label">Text</label>
          <PInputText v-model="labelText" class="ti-input" :disabled="!editable" />
        </div>
        <div class="ti-field">
          <label class="ti-label">Color</label>
          <input type="color" :value="labelColor" class="ti-color" :disabled="!editable" @input="onLabelColor" />
        </div>
        <div class="ti-field">
          <label class="ti-label">Font size</label>
          <PInputNumber v-model="labelFontSize" :min="8" :max="48" show-buttons buttonLayout="horizontal" :disabled="!editable" />
        </div>
      </div>

      <!-- A placed OpenNMS node (detail is read-only; full/View only) -->
      <div v-else-if="kind === 'node' && variant === 'full'" class="ti-section">
        <div v-if="nodeLoading" class="ti-empty">Loading node…</div>
        <template v-else-if="nodeDetail">
          <div class="ti-node-header">
            <span class="ti-severity-dot" :style="{ background: severityColor(nodeSeverity) }" />
            <span class="ti-node-label">{{ nodeDetail.label }}</span>
          </div>
          <dl class="ti-detail">
            <dt>Node ID</dt><dd>{{ nodeDetail.id }}</dd>
            <dt>Severity</dt><dd>{{ nodeSeverity || 'Normal / none' }}</dd>
            <dt>Location</dt><dd>{{ nodeDetail.location || '—' }}</dd>
            <dt>Foreign source</dt><dd>{{ nodeDetail.foreignSource || '—' }}</dd>
            <dt>Categories</dt>
            <dd>{{ nodeDetail.categories?.length ? nodeDetail.categories.map((c) => c.name).join(', ') : '—' }}</dd>
          </dl>
        </template>
        <p v-else class="ti-empty">Node details unavailable.</p>
      </div>

      <!-- An edge between two nodes -->
      <div v-else-if="kind === 'edge' && edge" class="ti-section">
        <div class="ti-node-header">
          <span class="ti-edge-endpoints">{{ edge.sourceLabel }} → {{ edge.targetLabel }}</span>
        </div>
        <div class="ti-field">
          <label class="ti-label">Edge label</label>
          <PInputText v-model="edgeLabel" class="ti-input" placeholder="(none)" :disabled="!editable" />
        </div>
      </div>

      <p v-else-if="variant === 'full'" class="ti-empty">Edge selected.</p>
    </template>
  </PCard>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import { useTopologyStore } from '@/stores/topologyStore'
import { isLabelId, nodeIdFromPlacedId } from '@/components/Topology/nodeIds'
import { severityColor } from '@/components/Topology/severity'
import { getNodeById } from '@/services/nodeService'
import type { Node } from '@/types'

const PCard = Card
const PInputText = InputText
const PInputNumber = InputNumber

/** Minimal edge read/write surface the canvas exposes (via defineExpose). */
interface CanvasEdgeApi {
  getEdge: (id: string) => { label: string; sourceLabel: string; targetLabel: string } | null
  setEdgeLabel: (id: string, label: string) => void
}

const props = defineProps<{
  canvas: CanvasEdgeApi | null
  /**
   * 'full' (View): node detail + label/edge + empty/multi states.
   * 'props' (Edit): only the editable label/edge property fields; the page
   * mounts this variant solely when a label or edge is selected.
   */
  variant?: 'full' | 'props'
}>()

const variant = computed<'full' | 'props'>(() => props.variant ?? 'full')

const store = useTopologyStore()

// Properties are editable only in Edit mode; View mode is read-only.
const editable = computed<boolean>(() => store.isEditMode)

const selectedId = computed<string | null>(() =>
  store.selectedIds.length === 1 ? store.selectedIds[0] : null
)

const kind = computed<'none' | 'multi' | 'label' | 'node' | 'edge'>(() => {
  if (store.selectedIds.length === 0) return 'none'
  if (store.selectedIds.length > 1) return 'multi'
  const id = selectedId.value as string
  if (isLabelId(id)) return 'label'
  if (nodeIdFromPlacedId(id) !== null) return 'node'
  return 'edge'
})

/* ---- Label editing (store-backed) ---- */
const label = computed(() => (selectedId.value && isLabelId(selectedId.value) ? store.getLabel(selectedId.value) : undefined))

const labelText = computed<string>({
  get: () => label.value?.text ?? '',
  set: (text) => label.value && store.updateLabel(label.value.id, { text })
})
const labelColor = computed<string>(() => label.value?.color ?? '#1d2939')
const onLabelColor = (event: Event) => {
  if (label.value) store.updateLabel(label.value.id, { color: (event.target as HTMLInputElement).value })
}
const labelFontSize = computed<number>({
  get: () => label.value?.fontSize ?? 12,
  set: (fontSize) => label.value && store.updateLabel(label.value.id, { fontSize })
})

/* ---- Node detail (read-only, fetched on selection) ---- */
const nodeDetail = ref<Node | null>(null)
const nodeLoading = ref(false)

const nodeSeverity = computed<string | undefined>(() => {
  const id = selectedId.value
  if (!id) return undefined
  const nid = nodeIdFromPlacedId(id)
  return nid !== null ? store.severities[nid] : undefined
})

watch(
  selectedId,
  async (id) => {
    nodeDetail.value = null
    const nid = id ? nodeIdFromPlacedId(id) : null
    if (nid === null) return
    nodeLoading.value = true
    try {
      const res = await getNodeById(String(nid))
      nodeDetail.value = res === false ? null : res
    } finally {
      nodeLoading.value = false
    }
  },
  { immediate: true }
)

/* ---- Edge editing (reads/writes the canvas graph via the exposed API) ---- */
const edge = ref<{ label: string; sourceLabel: string; targetLabel: string } | null>(null)

watch(
  selectedId,
  (id) => {
    edge.value = id && kind.value === 'edge' && props.canvas ? props.canvas.getEdge(id) : null
  },
  { immediate: true }
)

const edgeLabel = computed<string>({
  get: () => edge.value?.label ?? '',
  set: (value) => {
    const id = selectedId.value
    if (!id || !props.canvas) return
    props.canvas.setEdgeLabel(id, value)
    if (edge.value) edge.value = { ...edge.value, label: value }
  }
})
</script>

<style scoped>
.topology-inspector {
  width: 18rem;
  height: 100%;
  overflow-y: auto;
}

.ti-title {
  font-size: 1rem;
  font-weight: 600;
}

.ti-empty {
  color: #667085;
  font-size: 0.875rem;
}

.ti-section {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.ti-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.ti-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: #475467;
}

.ti-input {
  width: 100%;
}

.ti-color {
  width: 3rem;
  height: 2rem;
  padding: 0;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  background: none;
}

.ti-node-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.ti-severity-dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 50%;
  flex: 0 0 auto;
  border: 1px solid rgba(0, 0, 0, 0.15);
}

.ti-node-label {
  font-weight: 600;
}

.ti-edge-endpoints {
  font-weight: 600;
  word-break: break-word;
}

.ti-detail {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.25rem 0.75rem;
  margin: 0;
  font-size: 0.8125rem;
}

.ti-detail dt {
  color: #667085;
}

.ti-detail dd {
  margin: 0;
  word-break: break-word;
}
</style>
