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
      <span>Selected: {{ store.selectedIds.length }}</span>
    </div>
    <div ref="canvasEl" class="topology-canvas" />
    <div
      v-if="rubberBand && rubberBandWidth > 1 && rubberBandHeight > 1"
      class="topology-rubber-band"
      :style="rubberBandStyle"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import Graph from 'graphology'
import Sigma from 'sigma'
import { PALETTE_DRAG_MIME, type PaletteDragPayload } from '@/components/Topology/dragTypes'
import { useTopologyStore } from '@/stores/topologyStore'

const props = defineProps<{
  nodeCount: number
}>()

const store = useTopologyStore()

const canvasEl = ref<HTMLDivElement>()
const edgeCount = ref(0)
const placedCount = ref(0)
const isDropHover = ref(false)
interface RubberBandState {
  startX: number
  startY: number
  currentX: number
  currentY: number
}
const rubberBand = ref<RubberBandState | null>(null)
const rubberBandWidth = computed(() =>
  rubberBand.value ? Math.abs(rubberBand.value.currentX - rubberBand.value.startX) : 0
)
const rubberBandHeight = computed(() =>
  rubberBand.value ? Math.abs(rubberBand.value.currentY - rubberBand.value.startY) : 0
)
const rubberBandStyle = computed(() => {
  if (!rubberBand.value) return {}
  const { startX, startY, currentX, currentY } = rubberBand.value
  return {
    left: Math.min(startX, currentX) + 'px',
    top: Math.min(startY, currentY) + 'px',
    width: rubberBandWidth.value + 'px',
    height: rubberBandHeight.value + 'px'
  }
})
let sigma: Sigma | null = null
let graph: Graph | null = null
let placedSequence = 0
let draggedNode: string | null = null
let dragStartPos: { x: number; y: number } | null = null

/**
 * Undo/redo. Each user action that mutates the canvas (add from
 * palette, move, delete) pushes a Command onto undoStack. Ctrl+Z pops
 * undoStack, runs cmd.undo(), pushes to redoStack. Ctrl+Shift+Z
 * (or Ctrl+Y) reverses. The do/undo closures capture the graph and
 * store references at command-creation time but check them defensively
 * at execution time -- the graph reference can change across rebuild
 * (e.g., when the mock-node-count slider changes), and history is
 * cleared at that point.
 */
interface Command {
  label: string
  do: () => void
  undo: () => void
}
const MAX_HISTORY = 100
const undoStack: Command[] = []
const redoStack: Command[] = []

const pushCommand = (cmd: Command) => {
  undoStack.push(cmd)
  if (undoStack.length > MAX_HISTORY) undoStack.shift()
  redoStack.length = 0
}

const undo = () => {
  const cmd = undoStack.pop()
  if (!cmd) return
  cmd.undo()
  redoStack.push(cmd)
}

const redo = () => {
  const cmd = redoStack.pop()
  if (!cmd) return
  cmd.do()
  undoStack.push(cmd)
}

const clearHistory = () => {
  undoStack.length = 0
  redoStack.length = 0
}

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
  draggedNode = null
  dragStartPos = null
  // Rebuild replaces the graph wholesale; previous commands reference
  // ids that no longer exist.
  clearHistory()
  // Re-attach selection visuals after a rebuild wipes the graph.
  store.clearSelection()

  if (canvasEl.value && graph) {
    sigma = new Sigma(graph, canvasEl.value, {
      renderEdgeLabels: false,
      // Defaults are reasonable; pan/zoom/drag are on by default.
    })
    attachInteractionHandlers(sigma, graph)
  }
}

/**
 * Wires drag-to-move and click-to-select behavior onto a freshly-built
 * sigma instance + graphology graph.
 *
 * Drag pattern follows the canonical sigma example:
 *   - mousedown on a node captures it as the dragged node
 *   - mousemove on the captor (body-level) updates the node's x/y
 *   - mouseup releases the node
 *   - preventSigmaDefault() prevents sigma's camera pan during the drag
 *
 * Selection writes into the topology store; the store's selectedIds is
 * watched separately to reflect highlight state on the graph.
 */
const attachInteractionHandlers = (s: Sigma, g: Graph) => {
  // Window-level mouseup listener installed only while a node drag is in
  // progress, so that releasing the mouse outside the canvas (over the
  // palette, the toolbar, or off the page entirely) still ends the drag.
  // Sigma only exposes a `mousemovebody` event, not a `mouseupbody`.
  const windowMouseUp = () => finishDrag()
  const finishDrag = () => {
    if (draggedNode && dragStartPos && graph && graph.hasNode(draggedNode)) {
      const id = draggedNode
      const start = dragStartPos
      const end = {
        x: graph.getNodeAttribute(id, 'x') as number,
        y: graph.getNodeAttribute(id, 'y') as number
      }
      // Only push a Move command if the node actually moved beyond a
      // sub-pixel threshold -- a plain click on a node should not
      // pollute the undo stack.
      if (Math.abs(end.x - start.x) > 0.001 || Math.abs(end.y - start.y) > 0.001) {
        pushCommand({
          label: `Move ${id}`,
          do: () => {
            if (!graph || !graph.hasNode(id)) return
            graph.setNodeAttribute(id, 'x', end.x)
            graph.setNodeAttribute(id, 'y', end.y)
          },
          undo: () => {
            if (!graph || !graph.hasNode(id)) return
            graph.setNodeAttribute(id, 'x', start.x)
            graph.setNodeAttribute(id, 'y', start.y)
          }
        })
      }
    }
    draggedNode = null
    dragStartPos = null
    window.removeEventListener('mouseup', windowMouseUp)
  }

  s.on('downNode', (e) => {
    draggedNode = e.node
    if (g.hasNode(e.node)) {
      dragStartPos = {
        x: g.getNodeAttribute(e.node, 'x') as number,
        y: g.getNodeAttribute(e.node, 'y') as number
      }
    }
    // Freeze sigma's auto-rescale by locking the bounding box at drag
    // start. Without this, dragging a node beyond the current natural
    // bbox grows the bbox, and sigma compensates by zooming the camera
    // out -- producing a "canvas zooms further out as I drag" effect.
    if (!s.getCustomBBox()) {
      s.setCustomBBox(s.getBBox())
    }
    window.addEventListener('mouseup', windowMouseUp)
  })

  // Rubber-band selection: shift+drag on empty stage. Plain drag still
  // pans the camera (sigma's default). The window-level mouseup handler
  // finishes the rubber band even if the user releases outside the
  // canvas.
  const windowRubberMouseUp = () => finishRubberBand()
  const finishRubberBand = () => {
    if (rubberBand.value && sigma && graph && canvasEl.value) {
      const { startX, startY, currentX, currentY } = rubberBand.value
      const x0 = Math.min(startX, currentX)
      const x1 = Math.max(startX, currentX)
      const y0 = Math.min(startY, currentY)
      const y1 = Math.max(startY, currentY)
      // Below a minimum drag distance, treat the gesture as a click and
      // make no selection change. Without this, a quick shift+click on
      // empty stage would clear selection unexpectedly.
      if (Math.abs(x1 - x0) > 3 || Math.abs(y1 - y0) > 3) {
        const inside: string[] = []
        graph.forEachNode((nodeId) => {
          const gx = graph!.getNodeAttribute(nodeId, 'x') as number
          const gy = graph!.getNodeAttribute(nodeId, 'y') as number
          const v = sigma!.graphToViewport({ x: gx, y: gy })
          if (v.x >= x0 && v.x <= x1 && v.y >= y0 && v.y <= y1) {
            inside.push(nodeId)
          }
        })
        // Shift modifier was held to start the rubber band; treat it as
        // additive (matches shift+click behavior).
        store.addToSelection(inside)
      }
    }
    rubberBand.value = null
    window.removeEventListener('mouseup', windowRubberMouseUp)
  }

  s.on('downStage', (e) => {
    const original = e.event.original as MouseEvent | undefined
    if (!original?.shiftKey || !canvasEl.value) return
    const rect = canvasEl.value.getBoundingClientRect()
    const x = original.clientX - rect.left
    const y = original.clientY - rect.top
    rubberBand.value = { startX: x, startY: y, currentX: x, currentY: y }
    window.addEventListener('mouseup', windowRubberMouseUp)
  })

  const captor = s.getMouseCaptor()
  captor.on('mousemovebody', (e) => {
    // Rubber band takes priority over camera pan when active.
    if (rubberBand.value && canvasEl.value) {
      e.preventSigmaDefault()
      e.original.preventDefault()
      const rect = canvasEl.value.getBoundingClientRect()
      const original = e.original as MouseEvent
      rubberBand.value.currentX = original.clientX - rect.left
      rubberBand.value.currentY = original.clientY - rect.top
      return
    }
    if (!draggedNode) return
    // Prevent sigma's camera pan while dragging a node.
    e.preventSigmaDefault()
    e.original.preventDefault()
    e.original.stopPropagation()
    const pos = s.viewportToGraph(e)
    g.setNodeAttribute(draggedNode, 'x', pos.x)
    g.setNodeAttribute(draggedNode, 'y', pos.y)
  })

  // In-canvas mouseup still finishes the drag (the window listeners are
  // belt-and-suspenders for mouseups outside the canvas).
  captor.on('mouseup', () => {
    finishDrag()
    finishRubberBand()
  })

  s.on('clickNode', (e) => {
    const original = e.event.original as MouseEvent | undefined
    if (original?.shiftKey) {
      store.toggleSelection(e.node)
    } else {
      store.selectOnly(e.node)
    }
  })

  s.on('clickStage', (e) => {
    const original = e.event.original as MouseEvent | undefined
    // Shift+click on empty stage is reserved for rubber band; never
    // clear selection on it.
    if (original?.shiftKey) return
    store.clearSelection()
  })
}

/**
 * Reflects the store's selectedIds into the graph as the `highlighted`
 * attribute (sigma's built-in selection visual). When the watcher fires
 * after a rebuild the graph reference may have changed; we guard for
 * that by checking hasNode.
 */
watch(
  () => store.selectedIds.slice(),
  (newIds, oldIds) => {
    if (!graph) return
    ;(oldIds ?? []).forEach((id) => {
      if (graph && graph.hasNode(id)) graph.removeNodeAttribute(id, 'highlighted')
    })
    newIds.forEach((id) => {
      if (graph && graph.hasNode(id)) graph.setNodeAttribute(id, 'highlighted', true)
    })
  }
)

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

/**
 * Canvas node id format for palette-dropped nodes. Deterministic
 * (no per-drop sequence suffix) so the canvas id ↔ palette id mapping
 * is one-to-one. Stays in sync with the placedNodeIds set in the store.
 */
const PLACED_PREFIX = 'placed-'
const placedIdFor = (paletteId: string) => `${PLACED_PREFIX}${paletteId}`
const paletteIdFromPlacedId = (placedId: string): string | null =>
  placedId.startsWith(PLACED_PREFIX) ? placedId.slice(PLACED_PREFIX.length) : null

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

  if (store.isPlaced(payload.nodeId)) {
    // Defensive: the palette filters out already-placed nodes, but if
    // the user finds a way to drag one anyway, select the existing
    // canvas node instead of creating a duplicate.
    const existingId = placedIdFor(payload.nodeId)
    if (graph.hasNode(existingId)) {
      store.selectOnly(existingId)
    }
    return
  }

  const placedId = placedIdFor(payload.nodeId)
  if (graph.hasNode(placedId)) return
  const attrs = {
    label: payload.label,
    x: coords.x,
    y: coords.y,
    size: 10,
    color: '#1f5fb0'
  }
  const paletteId = payload.nodeId
  // Execute the addition, then record the inverse for undo.
  graph.addNode(placedId, attrs)
  store.markPlaced(paletteId)
  placedCount.value++
  placedSequence++ // retained for stats; not used in id construction
  pushCommand({
    label: `Add ${payload.label}`,
    do: () => {
      if (!graph || graph.hasNode(placedId)) return
      graph.addNode(placedId, attrs)
      store.markPlaced(paletteId)
      placedCount.value++
    },
    undo: () => {
      if (!graph || !graph.hasNode(placedId)) return
      graph.dropNode(placedId)
      store.markUnplaced(paletteId)
      placedCount.value = Math.max(0, placedCount.value - 1)
    }
  })
}

/**
 * Snapshot of a node + its incident edges, captured before deletion
 * so that an undo can restore the full graph topology around it.
 */
interface DeletedNodeSnapshot {
  id: string
  attrs: Record<string, unknown>
  paletteId: string | null
  edges: Array<{ source: string; target: string; attrs: Record<string, unknown> }>
}

/**
 * Delete the currently-selected canvas nodes. For palette-placed nodes,
 * the placed-id ↔ palette-id mapping is reversed so the palette entry
 * is restored. Mock-graph nodes (n0, n1, ...) just disappear -- they
 * have no palette counterpart. graphology.dropNode removes incident
 * edges automatically; we capture them first so undo can rebuild them.
 */
const deleteSelected = () => {
  if (!graph) return
  const ids = store.selectedIds.slice()
  if (ids.length === 0) return

  const snapshots: DeletedNodeSnapshot[] = []
  for (const id of ids) {
    if (!graph.hasNode(id)) continue
    const attrs = { ...graph.getNodeAttributes(id) }
    // `highlighted` is transient visual state owned by the selection
    // watcher, not user-meaningful node data. Preserving it across a
    // delete/undo cycle leaves the restored node visually selected
    // without selectedIds containing it -- the watcher then has no
    // diff to apply and the stale highlight sticks.
    delete attrs.highlighted
    const paletteId = paletteIdFromPlacedId(id)
    const edges: DeletedNodeSnapshot['edges'] = []
    graph.forEachEdge(id, (_key, edgeAttrs, source, target) => {
      // Capture each incident edge once; for an edge whose endpoints
      // are both in the deletion set, this still records it from each
      // side, but the undo step de-dupes via hasEdge.
      edges.push({ source, target, attrs: { ...edgeAttrs } })
    })
    snapshots.push({ id, attrs, paletteId, edges })
  }

  const applyDelete = () => {
    if (!graph) return
    for (const s of snapshots) {
      if (!graph.hasNode(s.id)) continue
      if (s.paletteId !== null) {
        store.markUnplaced(s.paletteId)
        placedCount.value = Math.max(0, placedCount.value - 1)
      }
      graph.dropNode(s.id)
    }
    edgeCount.value = graph.size
  }

  const applyUndo = () => {
    if (!graph) return
    for (const s of snapshots) {
      if (!graph.hasNode(s.id)) {
        graph.addNode(s.id, s.attrs)
        if (s.paletteId !== null) {
          store.markPlaced(s.paletteId)
          placedCount.value++
        }
      }
    }
    for (const s of snapshots) {
      for (const e of s.edges) {
        if (
          graph.hasNode(e.source) &&
          graph.hasNode(e.target) &&
          !graph.hasEdge(e.source, e.target) &&
          !graph.hasEdge(e.target, e.source)
        ) {
          graph.addEdge(e.source, e.target, e.attrs)
        }
      }
    }
    edgeCount.value = graph.size
  }

  applyDelete()
  store.clearSelection()
  pushCommand({
    label: `Delete ${snapshots.length} node(s)`,
    do: applyDelete,
    undo: applyUndo
  })
}

/**
 * Window keyboard handler. Handles Delete/Backspace (delete selected),
 * Ctrl+Z (undo), and Ctrl+Shift+Z or Ctrl+Y (redo). Skips when the user
 * is typing in a form field so it doesn't hijack the palette search box.
 */
const onKeyDown = (e: KeyboardEvent) => {
  const target = e.target as HTMLElement | null
  if (target) {
    const tag = target.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return
  }
  const ctrlOrMeta = e.ctrlKey || e.metaKey
  if (ctrlOrMeta && (e.key === 'z' || e.key === 'Z')) {
    e.preventDefault()
    if (e.shiftKey) redo()
    else undo()
    return
  }
  if (ctrlOrMeta && (e.key === 'y' || e.key === 'Y')) {
    e.preventDefault()
    redo()
    return
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (store.selectedIds.length === 0) return
    e.preventDefault()
    deleteSelected()
  }
}

onMounted(() => {
  rebuild(props.nodeCount)
  window.addEventListener('keydown', onKeyDown)
})

watch(
  () => props.nodeCount,
  (n) => rebuild(n)
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
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

.topology-rubber-band {
  position: absolute;
  pointer-events: none;
  border: 1px dashed #1f5fb0;
  background: rgba(31, 95, 176, 0.08);
  z-index: 2;
}
</style>
