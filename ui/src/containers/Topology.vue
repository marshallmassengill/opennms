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
  <div class="topology-page">
    <PToolbar class="topology-toolbar">
      <template #start>
        <div class="toolbar-start">
          <span class="topology-title">Topology (Preview)</span>
          <PInputText
            v-model="viewName"
            class="view-name-input"
            placeholder="View name"
            aria-label="View name"
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
          <PButton label="Open" severity="secondary" outlined @click="managerVisible = true" />
        </div>
      </template>
      <template #end>
        <div class="toolbar-controls">
          <PButton
            :label="store.isEditMode ? 'Mode: Edit' : 'Mode: View'"
            :severity="store.isEditMode ? 'secondary' : 'primary'"
            :outlined="store.isEditMode"
            @click="store.setEditMode(!store.isEditMode)"
          />
          <PButton
            label="Refresh status"
            severity="secondary"
            outlined
            @click="store.refreshStatus()"
          />
          <PButton
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
      <TopologyPalette class="topology-palette-pane" />
      <TopologyCanvas ref="canvasRef" class="topology-canvas-pane" />
      <TopologyInspector class="topology-inspector-pane" />
    </div>

    <ViewManager v-model:visible="managerVisible" @open="onOpen" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Toolbar from 'primevue/toolbar'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import TopologyCanvas from '@/components/Topology/TopologyCanvas.vue'
import TopologyPalette from '@/components/Topology/TopologyPalette.vue'
import TopologyInspector from '@/components/Topology/TopologyInspector.vue'
import ViewManager from '@/components/Topology/ViewManager.vue'
import { useTopologyStore } from '@/stores/topologyStore'

const PToolbar = Toolbar
const PButton = Button
const PInputText = InputText

const store = useTopologyStore()

const canvasRef = ref<InstanceType<typeof TopologyCanvas> | null>(null)
const managerVisible = ref<boolean>(false)

// Start with a blank view document and the catalog loaded.
onMounted(() => {
  if (!store.currentView) store.newView()
  store.refreshCatalog()
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
      // Entering View mode: refresh now, then on an interval.
      store.refreshStatus()
      statusTimer = setInterval(() => store.refreshStatus(), STATUS_INTERVAL_MS)
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopPolling)

// The open view's name, edited in place; persisted on the next save.
const viewName = computed<string>({
  get: () => store.currentView?.name ?? '',
  set: (name: string) => store.renameCurrent(name)
})

const canSave = computed<boolean>(
  () => !!store.currentView && !store.isSaving && (store.currentView?.name?.trim().length ?? 0) > 0
)

const onNew = () => {
  store.newView()
  if (store.currentView) canvasRef.value?.loadView(store.currentView)
}

const onSave = async () => {
  const snapshot = canvasRef.value?.serialize()
  if (!snapshot) return
  await store.saveCurrentView(snapshot)
}

const onSaveAs = async () => {
  const name = window.prompt('Save view as:', store.currentView?.name ?? 'Untitled view')
  if (!name || !store.currentView) return
  // Drop the id so the save creates a new catalog entry under the new name.
  store.currentView = { ...store.currentView, id: undefined, name }
  await onSave()
}

const onOpen = async (id: string) => {
  const view = await store.openView(id)
  if (view) canvasRef.value?.loadView(view)
}
</script>

<style scoped>
.topology-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem;
  height: calc(100vh - 4rem);
}

.topology-toolbar {
  flex: 0 0 auto;
}

.topology-title {
  font-size: 1.25rem;
  font-weight: 600;
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
