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
  Sigma.js-based canvas. Owns the WebGL node/edge layer (sigma) and a
  graphology Graph instance as its data model. Mock-graph generation is
  Step 2 spike scaffolding -- the real palette / view document drive the
  graph from Step 3 onward.
-->

<template>
  <div
    class="topology-canvas-root"
    :class="{ 'is-drop-target': isDropHover }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <div class="topology-canvas-stats">
      <span>Mock: {{ nodeCount }}</span>
      <span>Placed: {{ placedCount }}</span>
      <span>Edges: {{ edgeCount }}</span>
    </div>
    <div ref="canvasEl" class="topology-canvas" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import Graph from 'graphology'
import Sigma from 'sigma'
import { PALETTE_DRAG_MIME, type PaletteDragPayload } from '@/components/Topology/dragTypes'

const props = defineProps<{
  nodeCount: number
}>()

const canvasEl = ref<HTMLDivElement>()
const edgeCount = ref(0)
const placedCount = ref(0)
const isDropHover = ref(false)
let sigma: Sigma | null = null
let graph: Graph | null = null
let placedSequence = 0

const generateMockGraph = (n: number): Graph => {
  const g = new Graph()
  // Random positions in a 1000x1000 area; sigma's camera handles fit/pan/zoom.
  for (let i = 0; i < n; i++) {
    g.addNode(`n${i}`, {
      label: `Node ${i}`,
      x: Math.random() * 1000,
      y: Math.random() * 1000,
      size: 4 + Math.random() * 6,
      // Sample a small palette so the canvas reads as "many distinct things"
      // without burning CPU on random hex generation.
      color: ['#3a78c2', '#f6b352', '#56b870', '#d62728', '#8e44ad'][i % 5]
    })
  }
  // Add roughly n * 1.5 random edges; skip self-loops and duplicates.
  const targetEdgeCount = Math.floor(n * 1.5)
  let added = 0
  let attempts = 0
  while (added < targetEdgeCount && attempts < targetEdgeCount * 5) {
    const a = Math.floor(Math.random() * n)
    const b = Math.floor(Math.random() * n)
    attempts++
    if (a === b) continue
    const sId = `n${a}`
    const tId = `n${b}`
    if (g.hasEdge(sId, tId) || g.hasEdge(tId, sId)) continue
    g.addEdge(sId, tId, { size: 1, color: '#cccccc' })
    added++
  }
  return g
}

const rebuild = (n: number) => {
  if (sigma) {
    sigma.kill()
    sigma = null
  }
  graph = generateMockGraph(n)
  edgeCount.value = graph.size
  placedCount.value = 0
  placedSequence = 0

  if (canvasEl.value && graph) {
    sigma = new Sigma(graph, canvasEl.value, {
      renderEdgeLabels: false,
      // Defaults are reasonable; pan/zoom/drag are on by default.
    })
  }
}

/**
 * Translate a DragEvent's viewport (clientX/clientY) coordinates into the
 * graph's coordinate space, accounting for the canvas container's position
 * and sigma's current camera state.
 */
const eventToGraphCoords = (event: DragEvent): { x: number; y: number } | null => {
  if (!sigma || !canvasEl.value) return null
  const rect = canvasEl.value.getBoundingClientRect()
  const localX = event.clientX - rect.left
  const localY = event.clientY - rect.top
  return sigma.viewportToGraph({ x: localX, y: localY })
}

const isPaletteDrag = (event: DragEvent): boolean => {
  if (!event.dataTransfer) return false
  // Some browsers expose types via dataTransfer.types (lowercased).
  return Array.from(event.dataTransfer.types).includes(PALETTE_DRAG_MIME)
}

const onDragEnter = (event: DragEvent) => {
  if (isPaletteDrag(event)) {
    isDropHover.value = true
  }
}

const onDragOver = (event: DragEvent) => {
  if (isPaletteDrag(event) && event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
    isDropHover.value = true
  }
}

const onDragLeave = (event: DragEvent) => {
  // Only clear the hover state when the drag leaves the root, not when it
  // crosses internal element boundaries.
  if (event.currentTarget === event.target) {
    isDropHover.value = false
  }
}

const onDrop = (event: DragEvent) => {
  isDropHover.value = false
  if (!event.dataTransfer || !graph) return
  const raw = event.dataTransfer.getData(PALETTE_DRAG_MIME)
  if (!raw) return
  let payload: PaletteDragPayload
  try {
    payload = JSON.parse(raw)
  } catch {
    return
  }
  const coords = eventToGraphCoords(event)
  if (!coords) return

  placedSequence++
  const placedId = `placed-${payload.nodeId}-${placedSequence}`
  if (graph.hasNode(placedId)) return
  graph.addNode(placedId, {
    label: payload.label,
    x: coords.x,
    y: coords.y,
    size: 10,
    color: '#1f5fb0'
  })
  placedCount.value++
}

onMounted(() => {
  rebuild(props.nodeCount)
})

watch(
  () => props.nodeCount,
  (n) => rebuild(n)
)

onBeforeUnmount(() => {
  if (sigma) {
    sigma.kill()
    sigma = null
  }
})

defineExpose({
  fit: () => {
    if (sigma) {
      sigma.getCamera().animatedReset()
    }
  }
})
</script>

<style scoped>
.topology-canvas-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 500px;
  display: flex;
  flex-direction: column;
  background: #fafafa;
  border: 1px solid #e0e0e0;
  transition: box-shadow 100ms ease-in;
}

.topology-canvas-root.is-drop-target {
  box-shadow: inset 0 0 0 2px #1f5fb0;
}

.topology-canvas-stats {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  z-index: 1;
  background: rgba(255, 255, 255, 0.85);
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  display: flex;
  gap: 1rem;
  pointer-events: none;
  font-family: monospace;
}

.topology-canvas {
  flex: 1 1 auto;
  width: 100%;
  min-height: 500px;
}
</style>
