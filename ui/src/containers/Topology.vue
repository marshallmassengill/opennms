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

<template>
  <div class="topology-page" :class="store.isEditMode ? 'is-edit' : 'is-view'">
    <PToolbar class="topology-toolbar">
      <template #start>
        <div class="toolbar-start">
          <span class="topology-title">Topology (Preview)</span>
          <PSelect
            v-model="currentViewId"
            :options="store.catalog"
            option-label="name"
            option-value="id"
            placeholder="Select a view"
            class="view-chooser"
            aria-label="Choose a topology view"
          />
          <PButton label="New" severity="secondary" outlined @click="onNew" />
          <PButton label="Save" :loading="store.isSaving" :disabled="!canSave" @click="onSave" />
          <PButton
            label="Save As"
            severity="secondary"
            outlined
            :disabled="store.isSaving"
            @click="onSaveAs"
          />
          <PButton
            label="Rename"
            severity="secondary"
            outlined
            :disabled="!store.currentView"
            @click="onRename"
          />
          <PButton
            label="Delete"
            severity="danger"
            outlined
            :disabled="!canDelete"
            @click="onDelete"
          />
        </div>
      </template>
      <template #end>
        <div class="toolbar-controls">
          <PSelectButton
            v-model="mode"
            :options="modeOptions"
            option-label="label"
            option-value="value"
            :allow-empty="false"
            aria-label="View or Edit mode"
            class="mode-select"
          />
          <PButton
            label="Refresh status"
            severity="secondary"
            outlined
            @click="store.refreshStatus()"
          />
          <PButton
            v-if="store.isEditMode"
            :label="store.isEdgeDrawMode ? 'Edge: ON' : 'Draw Edge'"
            :severity="store.isEdgeDrawMode ? 'primary' : 'secondary'"
            :outlined="!store.isEdgeDrawMode"
            @click="store.setEdgeDrawMode(!store.isEdgeDrawMode)"
          />
          <PButton label="Fit" severity="secondary" outlined @click="canvasRef?.fit()" />
        </div>
      </template>
    </PToolbar>

    <div class="topology-body">
      <!-- Palette is an Edit-mode tool (compose); hidden in View. -->
      <TopologyPalette v-if="store.isEditMode" class="topology-palette-pane" />
      <TopologyCanvas ref="canvasRef" class="topology-canvas-pane" />
      <!-- View: full read-only Inspector on the left (order -1).
           Edit: slim Properties panel on the right, only when a label/edge
           is selected (nodes have no editable props here). -->
      <TopologyInspector
        v-if="inspectorVisible"
        :canvas="canvasRef"
        :variant="store.isEditMode ? 'props' : 'full'"
        class="topology-inspector-pane"
        :style="{ order: store.isEditMode ? 0 : -1 }"
      />
    </div>

    <PConfirmDialog />
    <PToast position="bottom-right" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Toolbar from 'primevue/toolbar'
import Button from 'primevue/button'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import TopologyCanvas from '@/components/Topology/TopologyCanvas.vue'
import TopologyPalette from '@/components/Topology/TopologyPalette.vue'
import TopologyInspector from '@/components/Topology/TopologyInspector.vue'
import { useTopologyStore } from '@/stores/topologyStore'
import { nodeIdFromPlacedId } from '@/components/Topology/nodeIds'

const PToolbar = Toolbar
const PButton = Button
const PSelect = Select
const PSelectButton = SelectButton
const PToast = Toast
const PConfirmDialog = ConfirmDialog

const store = useTopologyStore()
const toast = useToast()
const confirm = useConfirm()

const canvasRef = ref<InstanceType<typeof TopologyCanvas> | null>(null)

// Segmented View/Edit control (clear, always-visible mode indicator).
const modeOptions = [
  { label: 'View', value: false },
  { label: 'Edit', value: true }
]
const mode = computed<boolean>({
  get: () => store.isEditMode,
  set: (value) => store.setEditMode(value)
})

// A single selection that has editable properties = a label or an edge
// (i.e. not a node). Drives the Edit-mode "Properties" panel.
const hasEditableSelection = computed<boolean>(
  () => store.selectedIds.length === 1 && nodeIdFromPlacedId(store.selectedIds[0]) === null
)

// View: always show the full read-only Inspector. Edit: show the slim
// Properties panel only when a label/edge is selected.
const inspectorVisible = computed<boolean>(
  () => !store.isEditMode || hasEditableSelection.value
)

// Load the catalog, then land on the seeded 'Default' view (View mode).
onMounted(async () => {
  await store.refreshCatalog()
  await loadDefault()
})

// Status auto-refresh: poll in View mode, frozen in Edit mode (so the
// canvas doesn't repaint while arranging). The manual "Refresh status"
// button works in either mode.
const STATUS_INTERVAL_MS = 30000
let statusTimer: ReturnType<typeof setInterval> | null = null

const stopPolling = () => {
  if (statusTimer !== null) {
    clearInterval(statusTimer)
    statusTimer = null
  }
}

watch(
  () => store.isEditMode,
  (editMode) => {
    stopPolling()
    if (!editMode) {
      // Entering View mode: drop any in-flight edge-draw (an Edit-only tool),
      // then refresh status now and on an interval.
      store.setEdgeDrawMode(false)
      store.refreshStatus()
      statusTimer = setInterval(() => store.refreshStatus(), STATUS_INTERVAL_MS)
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopPolling)

const canSave = computed<boolean>(
  () => !!store.currentView && !store.isSaving && (store.currentView?.name?.trim().length ?? 0) > 0
)

// Delete acts on a saved view, but never the seeded 'Default' baseline.
const canDelete = computed<boolean>(
  () => !!store.currentView?.id && store.currentView?.name !== 'Default'
)

// The chooser's selection mirrors the open view; picking another loads it.
const currentViewId = computed<string | null>({
  get: () => store.currentView?.id ?? null,
  set: (id) => {
    if (id && id !== store.currentView?.id) openIntoCanvas(id)
  }
})

const saveCurrent = async (): Promise<boolean> => {
  const snapshot = canvasRef.value?.serialize()
  if (!snapshot) return false
  const ok = await store.saveCurrentView(snapshot)
  toast.add(
    ok
      ? { severity: 'success', summary: 'View saved', detail: store.currentView?.name, life: 3000 }
      : { severity: 'error', summary: 'Save failed', detail: 'Could not save the view.', life: 5000 }
  )
  return ok
}

// Load a saved view by id into the canvas. No toast (used by the chooser,
// the initial Default load, and after a delete).
const openIntoCanvas = async (id: string): Promise<boolean> => {
  const view = await store.openView(id)
  if (!view) {
    toast.add({ severity: 'error', summary: 'Open failed', detail: 'Could not load the view.', life: 5000 })
    return false
  }
  canvasRef.value?.loadView(view)
  return true
}

// Land on the seeded 'Default' view if present, else a blank canvas.
const loadDefault = async (): Promise<void> => {
  const def = store.catalog.find((v) => v.name === 'Default')
  if (def) {
    await openIntoCanvas(def.id)
  } else {
    store.newView()
    if (store.currentView) canvasRef.value?.loadView(store.currentView)
  }
}

const onSave = () => saveCurrent()

const onNew = async () => {
  const name = window.prompt('Name the new view:', '')
  if (!name || !name.trim()) return
  store.newView()
  store.renameCurrent(name.trim())
  store.setEditMode(true)
  if (store.currentView) canvasRef.value?.loadView(store.currentView)
  await saveCurrent()
}

const onSaveAs = async () => {
  const name = window.prompt('Save view as:', store.currentView?.name ?? 'Untitled view')
  if (!name || !name.trim() || !store.currentView) return
  // Drop the id so the save creates a new catalog entry under the new name.
  store.currentView = { ...store.currentView, id: undefined, name: name.trim() }
  await saveCurrent()
}

const onRename = async () => {
  const cur = store.currentView
  if (!cur) return
  const name = window.prompt('Rename view:', cur.name)
  if (!name || !name.trim() || name.trim() === cur.name) return
  if (cur.id) {
    const ok = await store.renameView(cur.id, name.trim())
    toast.add(
      ok
        ? { severity: 'success', summary: 'View renamed', detail: name.trim(), life: 3000 }
        : { severity: 'error', summary: 'Rename failed', detail: cur.name, life: 5000 }
    )
  } else {
    // Unsaved view: just set the name locally (persisted on the next save).
    store.renameCurrent(name.trim())
  }
}

const onDelete = () => {
  const cur = store.currentView
  if (!cur?.id) return
  const id = cur.id
  const name = cur.name
  confirm.require({
    header: 'Delete view',
    message: `Delete view "${name}"? This cannot be undone.`,
    icon: 'pi pi-exclamation-triangle',
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    acceptProps: { label: 'Delete', severity: 'danger' },
    accept: async () => {
      const ok = await store.removeView(id)
      toast.add(
        ok
          ? { severity: 'success', summary: 'View deleted', detail: name, life: 3000 }
          : { severity: 'error', summary: 'Delete failed', detail: name, life: 5000 }
      )
      if (ok) await loadDefault()
    }
  })
}
</script>

<style scoped>
.topology-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem;
  /* Fill the content area without overflowing past the layout footer (which
     was pushing it down and showing a scrollbar). Matches the geomap page's
     full-height allowance for the header + footer. */
  height: calc(100vh - 80px);
  box-sizing: border-box;
}

.topology-toolbar {
  flex: 0 0 auto;
  /* Ambient mode cue: a colored top accent reinforces the segmented
     View/Edit control so the current context is obvious at a glance. */
  border-top: 3px solid transparent;
}

.topology-page.is-edit .topology-toolbar {
  border-top-color: #f59e0b; /* amber = editing */
}

.topology-page.is-view .topology-toolbar {
  border-top-color: #00bfcb; /* teal accent = viewing */
}

.topology-title {
  font-size: 1.25rem;
  font-weight: 600;
}

.toolbar-start {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.view-chooser {
  min-width: 12rem;
}

.toolbar-controls {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.topology-body {
  flex: 1 1 auto;
  display: flex;
  gap: 0.75rem;
  min-height: 400px;
  min-height: 0;
}

.topology-palette-pane {
  flex: 0 0 auto;
}

.topology-canvas-pane {
  flex: 1 1 auto;
  min-width: 0;
}

.topology-inspector-pane {
  flex: 0 0 auto;
}
</style>
