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
        <span class="topology-title">Topology (Preview)</span>
      </template>
      <template #end>
        <div class="toolbar-controls">
          <PButton
            :label="store.isEdgeDrawMode ? 'Edge: ON' : 'Draw Edge'"
            :severity="store.isEdgeDrawMode ? 'primary' : 'secondary'"
            :outlined="!store.isEdgeDrawMode"
            @click="store.setEdgeDrawMode(!store.isEdgeDrawMode)"
          />
          <label for="node-count" class="control-label">Mock nodes:</label>
          <PInputNumber
            v-model="nodeCount"
            input-id="node-count"
            :min="10"
            :max="2000"
            :step="50"
            show-buttons
            buttonLayout="horizontal"
            inputClass="node-count-input"
          />
          <PButton label="Fit" severity="secondary" outlined @click="canvasRef?.fit()" />
        </div>
      </template>
    </PToolbar>

    <div class="topology-body">
      <TopologyPalette class="topology-palette-pane" />
      <TopologyCanvas ref="canvasRef" :node-count="nodeCount" class="topology-canvas-pane" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import Toolbar from 'primevue/toolbar'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import TopologyCanvas from '@/components/Topology/TopologyCanvas.vue'
import TopologyPalette from '@/components/Topology/TopologyPalette.vue'
import { useTopologyStore } from '@/stores/topologyStore'

const PToolbar = Toolbar
const PButton = Button
const PInputNumber = InputNumber

const store = useTopologyStore()

const canvasRef = ref<InstanceType<typeof TopologyCanvas> | null>(null)
const nodeCount = ref<number>(500)
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

.control-label {
  font-size: 0.875rem;
}

.toolbar-controls :deep(.node-count-input) {
  width: 6rem;
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
</style>
