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
  Node and link editing beyond this is a follow-up.
-->

<template>
  <PCard class="topology-inspector">
    <template #title>
      <span class="ti-title">{{ variant === 'props' ? 'Properties' : 'Inspector' }}</span>
    </template>
    <template #content>
      <!-- Nothing selected (full/View only) -->
      <p v-if="kind === 'none' && variant === 'full'" class="ti-empty">
        Select a node, link, or label to see its properties.
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
          <!-- Operator-configured info-panel items (etc/infopanel templates),
               rendered server-side and sanitized before display. -->
          <section
            v-for="item in infoPanelItems"
            :key="item.title"
            class="ti-infopanel-item"
          >
            <h4 class="ti-infopanel-title">{{ item.title }}</h4>
            <div class="ti-infopanel-html" v-html="sanitizeHtml(item.html)" />
          </section>
        </template>
        <p v-else class="ti-empty">Node details unavailable.</p>
      </div>

      <!-- A link between two nodes -->
      <div v-else-if="kind === 'link' && link" class="ti-section">
        <div class="ti-node-header">
          <span class="ti-link-endpoints">{{ link.sourceLabel }} → {{ link.targetLabel }}</span>
        </div>
        <div class="ti-field">
          <label class="ti-label">Link label</label>
          <PInputText v-model="linkLabel" class="ti-input" placeholder="(none)" :disabled="!editable" />
          <p v-if="editable" class="ti-hint">Applies as you type — <strong>Save</strong> the view to keep it.</p>
        </div>
      </div>

      <p v-else-if="variant === 'full'" class="ti-empty">Link selected.</p>

      <!-- Edit-mode Properties panel with nothing editable selected. The panel
           is always present (reserves layout) so selecting a link/label never
           shifts the canvas. -->
      <p v-else-if="variant === 'props'" class="ti-empty">
        Select a link or label to edit its properties.
      </p>
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
import { getNodeInfoPanel, type NodeInfoPanelItem } from '@/services/topologyService'
import DOMPurify from 'dompurify'
import type { Node } from '@/types'

const PCard = Card
const PInputText = InputText
const PInputNumber = InputNumber

/** Minimal link read/write surface the canvas exposes (via defineExpose). */
interface CanvasLinkApi {
  getLink: (id: string) => { label: string; sourceLabel: string; targetLabel: string } | null
  setLinkLabel: (id: string, label: string) => void
}

const props = defineProps<{
  canvas: CanvasLinkApi | null
  /**
   * 'full' (View): node detail + label/link + empty/multi states.
   * 'props' (Edit): only the editable label/link property fields; the page
   * mounts this variant solely when a label or link is selected.
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

const kind = computed<'none' | 'multi' | 'label' | 'node' | 'link'>(() => {
  if (store.selectedIds.length === 0) return 'none'
  if (store.selectedIds.length > 1) return 'multi'
  const id = selectedId.value as string
  if (isLabelId(id)) return 'label'
  if (nodeIdFromPlacedId(id) !== null) return 'node'
  return 'link'
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

/* ---- Operator-configured info-panel items (etc/infopanel templates) ---- */
const infoPanelItems = ref<NodeInfoPanelItem[]>([])

// Server HTML is sanitized before it ever reaches v-html. The templates are
// admin-authored, but they can interpolate node-derived data (e.g. a device's
// sysName), so we don't trust the output blindly.
const sanitizeHtml = (html: string): string => DOMPurify.sanitize(html)

watch(
  selectedId,
  async (id) => {
    infoPanelItems.value = []
    const nid = id ? nodeIdFromPlacedId(id) : null
    if (nid === null) return
    infoPanelItems.value = await getNodeInfoPanel(nid)
  },
  { immediate: true }
)

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

/* ---- Link editing (reads/writes the canvas graph via the exposed API) ---- */
const link = ref<{ label: string; sourceLabel: string; targetLabel: string } | null>(null)

watch(
  selectedId,
  (id) => {
    link.value = id && kind.value === 'link' && props.canvas ? props.canvas.getLink(id) : null
  },
  { immediate: true }
)

const linkLabel = computed<string>({
  get: () => link.value?.label ?? '',
  set: (value) => {
    const id = selectedId.value
    if (!id || !props.canvas) return
    props.canvas.setLinkLabel(id, value)
    if (link.value) link.value = { ...link.value, label: value }
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

.ti-link-endpoints {
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

.ti-infopanel-item {
  margin-top: 0.85rem;
  padding-top: 0.6rem;
  border-top: 1px solid #eaecf0;
}

.ti-infopanel-title {
  margin: 0 0 0.35rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: #667085;
}

.ti-infopanel-html {
  font-size: 0.85rem;
  color: #1d2939;
  overflow-x: auto;
}

.ti-infopanel-html :deep(table) {
  width: 100%;
  border-collapse: collapse;
}

.ti-hint {
  margin: 0.35rem 0 0;
  font-size: 0.75rem;
  color: #667085;
}
</style>
