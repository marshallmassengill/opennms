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
    :class="{ 'is-drop-target': isDropHover, 'is-link-draw-mode': store.isLinkDrawMode, 'is-hovering-link': hoveredLinkId !== null }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
    @mousemove="onCanvasMouseMove"
    @contextmenu.prevent
  >
    <div class="topology-canvas-stats">
      <span>Nodes: {{ placedCount }}</span>
      <span>Links: {{ linkCount }}</span>
      <span>Labels: {{ store.labels.length }}</span>
      <span>Selected: {{ store.selectedIds.length }}</span>
    </div>
    <div ref="canvasEl" class="topology-canvas" />
    <div class="topology-labels-layer">
      <!-- Labels are reactively positioned in viewport space via
           cameraVersion (bumped on sigma's afterRender); references it
           in the style binding so Vue re-evaluates each render. -->
      <div
        v-for="label in store.labels"
        :key="label.id"
        class="topology-label"
        :class="{ 'is-selected': store.selectedIds.includes(label.id), 'is-editing': editingLabelId === label.id }"
        :style="labelStyle(label, cameraVersion)"
        @mousedown.stop="onLabelMouseDown($event, label)"
        @click.stop="onLabelClick($event, label)"
        @dblclick.stop="onLabelDoubleClick(label)"
      >
        <input
          v-if="editingLabelId === label.id"
          ref="editingInputRef"
          v-model="editingText"
          class="topology-label-input"
          :style="{ color: label.color || undefined }"
          @keydown.enter.prevent="commitEdit"
          @keydown.escape.prevent="cancelEdit"
          @blur="commitEdit"
        />
        <span v-else class="topology-label-text" :style="{ color: label.color || undefined }">
          {{ label.text }}
        </span>
      </div>
    </div>
    <div
      v-if="rubberBand && rubberBandWidth > 1 && rubberBandHeight > 1"
      class="topology-rubber-band"
      :style="rubberBandStyle"
    />
    <svg v-if="linkPreview" class="topology-link-preview" xmlns="http://www.w3.org/2000/svg">
      <line
        :x1="linkPreview.x1"
        :y1="linkPreview.y1"
        :x2="linkPreview.x2"
        :y2="linkPreview.y2"
        stroke="#1f5fb0"
        stroke-width="2"
        stroke-dasharray="6 4"
      />
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import Graph from 'graphology'
import Sigma from 'sigma'
import { createNodeImageProgram } from '@sigma/node-image'
import { downloadAsImage } from '@sigma/export-image'
import { PALETTE_DRAG_MIME, type PaletteDragPayload } from '@/components/Topology/dragTypes'
import { useTopologyStore } from '@/stores/topologyStore'
import { severityColor } from '@/components/Topology/severity'
import { DEVICE_ICON_SVG } from '@/components/Topology/deviceIcons'
import {
  LABEL_PREFIX,
  isLabelId,
  placedIdFor,
  paletteIdFromPlacedId,
  nodeIdFromPlacedId
} from '@/components/Topology/nodeIds'
import { layoutDiscoveredGraph, layoutHierarchyGraph } from '@/components/Topology/layout'
import type { CanvasLink, CanvasLabel, CanvasNode, DiscoveredGraph, TopologyView } from '@/types/topology'

const store = useTopologyStore()

/**
 * Right-click on a node bubbles up to the page, which hosts the context menu
 * (it owns the router/source context for the actions). Payload carries the
 * native event (for positioning), the real OnmsNode id (null for decorative
 * nodes), and the canvas node key (for focus).
 */
const emit = defineEmits<{
  (e: 'node-contextmenu', payload: { event: MouseEvent; nodeId: number | null; nodeKey: string }): void
}>()

const canvasEl = ref<HTMLDivElement>()
const linkCount = ref(0)
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
// Keeps sigma's cached dimensions in sync when the canvas container resizes
// (mode toggle, inspector/browse panels, window). Without this sigma's
// mouse->graph hit-detection drifts after a resize -- enough that thin edges
// can't be clicked (big nodes still hit), which broke edge selection.
let resizeObserver: ResizeObserver | null = null
// Half-extent of the fixed coordinate frame used when there's no content to
// frame yet (empty canvas). Only its stability matters for placement -- drops
// land under the cursor because the frame doesn't move between viewportToGraph
// and render; fitCamera/setContentBBox narrow it to the content once present.
const DEFAULT_BBOX = 500
// Link thickness, in sigma's edge-size units. Sigma derives an edge's
// clickable zone from its *rendered* thickness, so these widths double as
// hit-target sizes. A roomy base makes links easy to hover; hover then
// fattens further so the click target is generous right when you're aiming
// at it (the affordance pattern Cytoscape/Grafana use). The edgeReducer is
// the single place these are applied, so per-link creation sizes don't matter.
const LINK_SIZE = 3
const LINK_HOVER_SIZE = 6
const LINK_SELECTED_SIZE = 4
// Transient hovered link id (cleared on leave). Drives the reducer + cursor.
const hoveredLinkId = ref<string | null>(null)
let placedSequence = 0
let draggedNode: string | null = null
let dragStartPos: { x: number; y: number } | null = null

/**
 * Bumped on sigma's afterRender event so label DOM positions reactively
 * re-project. The labels reference cameraVersion in their style binding,
 * which forces Vue to re-evaluate labelStyle each render.
 */
const cameraVersion = ref(0)

/**
 * Label edit state. Only one label edits at a time. `editingOriginalText`
 * captures the text at edit start so commit can compare and push an
 * undoable change only when the text actually changed. `editingIsNew`
 * marks labels that were just created (so Esc removes them rather than
 * reverting).
 */
const editingLabelId = ref<string | null>(null)
const editingText = ref('')
const editingInputRef = ref<HTMLInputElement[] | HTMLInputElement | null>(null)
let editingOriginalText = ''
let editingIsNew = false

/**
 * Label drag state. Separate from node drag so the two don't interfere.
 */
let draggingLabel: { id: string; startLabelX: number; startLabelY: number; startMouseGraphX: number; startMouseGraphY: number } | null = null

/**
 * Edge-draw state. `linkDrawSource` is the node id captured on first
 * click while in edge-draw mode; the next clickNode commits an edge
 * source -> target. Click on empty stage cancels the in-flight; Esc
 * exits edge-draw mode entirely.
 */
const linkDrawSource = ref<string | null>(null)
const cursorViewport = ref<{ x: number; y: number } | null>(null)

const linkPreview = computed<{ x1: number; y1: number; x2: number; y2: number } | null>(() => {
  if (!linkDrawSource.value || !cursorViewport.value || !sigma || !graph) return null
  if (!graph.hasNode(linkDrawSource.value)) return null
  void cameraVersion.value
  const sx = graph.getNodeAttribute(linkDrawSource.value, 'x') as number
  const sy = graph.getNodeAttribute(linkDrawSource.value, 'y') as number
  const src = sigma.graphToViewport({ x: sx, y: sy })
  return { x1: src.x, y1: src.y, x2: cursorViewport.value.x, y2: cursorViewport.value.y }
})

const onCanvasMouseMove = (event: MouseEvent) => {
  if (!store.isLinkDrawMode || !linkDrawSource.value || !canvasEl.value) return
  const rect = canvasEl.value.getBoundingClientRect()
  cursorViewport.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  }
}

let linkIdSequence = 0
const newLinkId = () => `link-${Date.now()}-${linkIdSequence++}`

const isLinkId = (id: string): boolean => {
  if (!graph) return false
  return graph.hasEdge(id)
}

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

/**
 * (Re)create the sigma instance over a graph, killing any prior one and
 * re-wiring interaction handlers. Shared by the mock rebuild and by
 * loadView so the renderer options stay in one place.
 */
const mountSigma = (g: Graph) => {
  if (sigma) {
    sigma.kill()
    sigma = null
  }
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (!canvasEl.value) return
  sigma = new Sigma(g, canvasEl.value, {
    renderEdgeLabels: true,
    // Sigma v3 disables edge mouse events by default; enable them so an edge
    // can be clicked to select it (and then have its label edited in the
    // Inspector). Without this the 'clickEdge' handler below never fires.
    enableEdgeEvents: true,
    // This is a positioning editor: node x/y are absolute graph coordinates we
    // persist and expect to render consistently. Disable sigma's auto-rescale
    // (which re-normalizes coordinates to fit the node extent on every change)
    // so a node stays exactly where it's dropped/saved regardless of how many
    // other nodes are present. Framing is handled explicitly by fitCamera().
    autoRescale: false,
    // Recognized device types render as a glyph (drawn over the node's color
    // disc) via the image node program; everything else stays a plain circle.
    nodeProgramClasses: {
      image: createNodeImageProgram({ drawingMode: 'background', padding: 0.15 })
    },
    // Color placed nodes by their node's current alarm severity (held in
    // the store, refreshed on an interval in View mode). Nodes without a
    // known severity -- decorative/mock nodes, or before a status fetch --
    // keep their own color. A known device type additionally renders an icon
    // (sysObjectId-derived; store.nodeIconIds). The severities/nodeIconIds
    // watchers below trigger a sigma.refresh() so changes repaint.
    nodeReducer: (node, attrs) => {
      const paletteId = paletteIdFromPlacedId(node)
      // All nodes render at the store's (density-defaulted, slider-adjustable) size.
      let res: typeof attrs = { ...attrs, size: store.nodeSize }
      if (paletteId !== null && /^\d+$/.test(paletteId)) {
        const nid = Number(paletteId)
        const severity = store.severities[nid]
        if (severity) res = { ...res, color: severityColor(severity) }
        const iconId = store.nodeIconIds[nid]
        if (iconId) res = { ...res, type: 'image', image: DEVICE_ICON_SVG[iconId] }
      }
      return res
    },
    // The reducer is the single source of truth for link thickness and the
    // selected/hover visuals -- it never mutates the stored attributes. Hover
    // wins over selection so the element under the cursor is always the
    // fattest/clearest target. _selected is set by the selection watcher;
    // hover is tracked in hoveredLinkId via enter/leaveEdge.
    edgeReducer: (edge, attrs) => {
      if (edge === hoveredLinkId.value) {
        return { ...attrs, color: '#1f5fb0', size: LINK_HOVER_SIZE }
      }
      if ((attrs as { _selected?: boolean })._selected) {
        return { ...attrs, color: '#1f5fb0', size: LINK_SELECTED_SIZE }
      }
      return { ...attrs, size: LINK_SIZE }
    }
  })
  // Pin a fixed coordinate frame. With autoRescale:false sigma still
  // re-derives its normalization from a viewport-sized box around the node
  // *centroid* on every render, so coordinates shift as nodes are added --
  // that's why the first few palette drops land in the wrong spot. Setting an
  // explicit customBBox makes sigma normalize from it instead, so the frame is
  // stable no matter how many nodes are present. fitCamera() narrows this box
  // to the actual content (and the camera back to 0.5/0.5) when framing.
  sigma.setCustomBBox({ x: [-DEFAULT_BBOX, DEFAULT_BBOX], y: [-DEFAULT_BBOX, DEFAULT_BBOX] })
  // Re-sync sigma's dimensions whenever its container changes size, so
  // hit-detection (especially for thin edges) stays accurate.
  resizeObserver = new ResizeObserver(() => {
    if (!sigma) return
    // sigma's resize() resyncs the canvas/WebGL dimensions but does NOT
    // re-render (it only emits "resize"), so the scene stays blank until the
    // next interaction -- the "nodes don't show until I zoom" symptom. A
    // follow-up refresh() repaints at the *current* camera, so the view
    // reappears at the size it had before, without changing the user's
    // zoom/pan (which is why we deliberately don't fitCamera here).
    sigma.resize()
    sigma.refresh()
  })
  resizeObserver.observe(canvasEl.value)
  attachInteractionHandlers(sigma, g)
}

/**
 * Start from an empty canvas. The user composes by dragging nodes from the
 * palette; loadView replaces this when a saved view is opened.
 */
const initGraph = () => {
  graph = new Graph()
  linkCount.value = 0
  placedCount.value = 0
  placedSequence = 0
  draggedNode = null
  dragStartPos = null
  clearHistory()
  store.clearSelection()
  mountSigma(graph)
}

/**
 * Serialize the current canvas into the flat shape persisted in a
 * TopologyView: graph nodes/edges plus the sigma camera viewport. Labels
 * are not included here -- they live in the store and are merged at save
 * time. The viewport stores the sigma camera state directly (ratio as
 * `zoom`, x/y as pan) so it round-trips exactly on load.
 */
const serialize = (): Pick<TopologyView, 'nodes' | 'links' | 'viewport'> => {
  const nodes: CanvasNode[] = []
  const links: CanvasLink[] = []
  if (graph) {
    graph.forEachNode((id, attrs) => {
      const paletteId = paletteIdFromPlacedId(id)
      const nodeId = paletteId !== null && /^\d+$/.test(paletteId) ? Number(paletteId) : undefined
      nodes.push({
        id,
        nodeId,
        label: (attrs.label as string) ?? '',
        x: attrs.x as number,
        y: attrs.y as number,
        color: attrs.color as string | undefined
      })
    })
    graph.forEachEdge((id, attrs, source, target) => {
      links.push({
        id,
        sourceId: source,
        targetId: target,
        label: (attrs.label as string | undefined) || undefined,
        origin: (attrs.origin as string) === 'discovered' ? 'discovered' : 'user'
      })
    })
  }
  const cam = sigma?.getCamera().getState()
  const viewport = cam
    ? { zoom: cam.ratio, panX: cam.x, panY: cam.y }
    : { zoom: 1, panX: 0, panY: 0 }
  return { nodes, links, viewport }
}

/**
 * Replace the canvas with a saved view: rebuild the graph from its
 * nodes/edges, restore the placed-node set and labels in the store, and
 * set the camera to the saved viewport. Clears undo/redo history (the old
 * commands reference a graph that no longer exists).
 */
const loadView = (view: TopologyView) => {
  const g = new Graph()
  for (const n of view.nodes) {
    if (g.hasNode(n.id)) continue
    g.addNode(n.id, {
      label: n.label,
      x: n.x,
      y: n.y,
      size: 20,
      color: n.color ?? '#1f5fb0'
    })
  }
  for (const e of view.links) {
    if (
      g.hasNode(e.sourceId) &&
      g.hasNode(e.targetId) &&
      !g.hasEdge(e.id) &&
      !g.hasEdge(e.sourceId, e.targetId)
    ) {
      g.addEdgeWithKey(e.id, e.sourceId, e.targetId, {
        size: 2,
        color: '#1f5fb0',
        origin: e.origin,
        label: e.label
      })
    }
  }
  graph = g
  linkCount.value = g.size
  draggedNode = null
  dragStartPos = null
  clearHistory()
  store.clearSelection()

  // Rebuild the placed-node set so the palette hides what's on the canvas.
  const placed: string[] = []
  for (const n of view.nodes) {
    const pid = paletteIdFromPlacedId(n.id)
    if (pid !== null) placed.push(pid)
  }
  store.setPlacedNodeIds(placed)
  placedCount.value = placed.length
  store.setLabels(view.labels)
  store.setNodeSizeForCount(g.order) // density-based default node size

  mountSigma(g)
  if (sigma) {
    const vp = view.viewport
    // Restore the saved zoom/pan when the view actually has one; the default
    // sentinel (zoom 1, pan 0/0) means no meaningful camera was saved -- e.g.
    // views created via the REST API -- so frame the content instead.
    const hasSavedCamera = !(vp.zoom === 1 && vp.panX === 0 && vp.panY === 0)
    if (hasSavedCamera) {
      // The saved camera (pan/ratio) is relative to the content-fitted frame,
      // so point the customBBox at the content first, then restore it exactly.
      setContentBBox()
      sigma.getCamera().setState({ x: vp.panX, y: vp.panY, ratio: vp.zoom, angle: 0 })
    } else {
      fitCamera(false)
      // Re-fit once after the resize observer's first post-load resize, which
      // would otherwise re-frame and push tall content off-screen.
    }
  }
}

/**
 * Point the sigma customBBox at the current content -- the bounding box over
 * all placed nodes plus free-standing labels, padded so nothing sits hard
 * against the edge. Because sigma normalizes coordinates from this box, making
 * it equal to the content means the content maps to the [0,1] frame and the
 * camera frames it with a plain centered state (see fitCamera). With nothing
 * to frame we fall back to the default square so an empty canvas still has a
 * stable frame for the first drops.
 *
 * Note this is only re-pointed on explicit framing (fit/load), never while
 * dragging or dropping -- keeping it fixed between frames is exactly what makes
 * node placement land where the cursor is.
 */
const setContentBBox = () => {
  if (!sigma || !graph) return
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  let count = 0
  graph.forEachNode((_id, a) => {
    const x = a.x as number, y = a.y as number
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
    count++
  })
  for (const l of store.labels) {
    if (l.x < minX) minX = l.x
    if (l.x > maxX) maxX = l.x
    if (l.y < minY) minY = l.y
    if (l.y > maxY) maxY = l.y
    count++
  }
  if (count === 0) {
    sigma.setCustomBBox({ x: [-DEFAULT_BBOX, DEFAULT_BBOX], y: [-DEFAULT_BBOX, DEFAULT_BBOX] })
    return
  }
  // Pad ~15% (floored) so edge nodes and their labels aren't clipped.
  const padX = Math.max((maxX - minX) * 0.15, 120)
  const padY = Math.max((maxY - minY) * 0.15, 120)
  sigma.setCustomBBox({ x: [minX - padX, maxX + padX], y: [minY - padY, maxY + padY] })
}

/**
 * Frame all placed nodes: narrow the coordinate frame to the content (via
 * setContentBBox) and center the camera on it. Since the customBBox now equals
 * the padded content box, a plain centered camera (0.5/0.5, ratio 1) frames it
 * exactly -- no scale calibration needed. `animate` is true for the Fit button,
 * false for the instant framing done on load.
 */
const fitCamera = (animate = true) => {
  if (!sigma || !graph || graph.order === 0) return
  setContentBBox()
  const target = { x: 0.5, y: 0.5, ratio: 1, angle: 0 }
  if (animate) {
    sigma.getCamera().animate(target, { duration: 300 })
  } else {
    sigma.getCamera().setState(target)
  }
}

/**
 * Render a discovered (auto-generated) topology read-only. The Graph REST API
 * gives no positions, so we auto-lay-out with d3-force, then build the
 * graphology graph and mount sigma. Editing stays disabled because the page
 * forces View mode for discovered sources (the interaction handlers are all
 * gated on store.isEditMode); pan/zoom/select still work for exploration.
 * Discovered edges render muted to read as "from discovery, not drawn."
 */
const loadDiscoveredGraph = (dg: DiscoveredGraph) => {
  // Density-based default node size, then lay out with spacing scaled to it.
  // Tree-shaped sources (path outage) use the tiered hierarchy layout; the
  // mesh-like ones (enlinkd) stay force-directed.
  store.setNodeSizeForCount(dg.nodes.length)
  const positioned =
    dg.source.layout === 'hierarchy'
      ? layoutHierarchyGraph(dg.nodes, dg.links, {
          levelSpacing: Math.max(80, store.nodeSize * 6),
          // Wide enough that a node's right-hand label clears its next sibling.
          siblingSpacing: Math.max(70, store.nodeSize * 6)
        })
      : layoutDiscoveredGraph(dg.nodes, dg.links, {
          collideRadius: Math.max(24, store.nodeSize * 3)
        })
  const g = new Graph()
  for (const n of positioned) {
    if (g.hasNode(n.id)) continue
    g.addNode(n.id, {
      label: n.label,
      x: n.x,
      y: n.y,
      // Discovered graphs can be large (100+ nodes); a smaller node keeps them
      // legible without overlap. Hand-composed views use the larger size 20.
      size: 12,
      color: n.color ?? '#1f5fb0'
    })
  }
  for (const e of dg.links) {
    if (
      g.hasNode(e.sourceId) &&
      g.hasNode(e.targetId) &&
      !g.hasEdge(e.id) &&
      !g.hasEdge(e.sourceId, e.targetId)
    ) {
      g.addEdgeWithKey(e.id, e.sourceId, e.targetId, { size: 2, color: '#9aa7b8', origin: e.origin })
    }
  }
  graph = g
  linkCount.value = g.size
  placedCount.value = positioned.length
  draggedNode = null
  dragStartPos = null
  clearHistory()
  store.clearSelection()
  store.setLabels([]) // discovered topologies have no free-standing labels
  mountSigma(g)
  fitCamera()
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
    // No node dragging in View mode (read-only canvas).
    if (!store.isEditMode) return
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
        // Labels live outside the graphology graph but share the same
        // graph coordinate system; project each and test against the
        // rubber band rectangle the same way.
        for (const label of store.labels) {
          const v = sigma!.graphToViewport({ x: label.x, y: label.y })
          if (v.x >= x0 && v.x <= x1 && v.y >= y0 && v.y <= y1) {
            inside.push(label.id)
          }
        }
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
    if (store.isLinkDrawMode) {
      // Seed cursorViewport from the click position so the preview line
      // is rendered immediately (rather than waiting for the next
      // mousemove to set it).
      if (original && canvasEl.value) {
        const rect = canvasEl.value.getBoundingClientRect()
        cursorViewport.value = {
          x: original.clientX - rect.left,
          y: original.clientY - rect.top
        }
      }
      handleLinkDrawClick(e.node)
      return
    }
    if (original?.shiftKey) {
      store.toggleSelection(e.node)
    } else {
      store.selectOnly(e.node)
    }
  })

  s.on('rightClickNode', (e) => {
    const original = e.event.original as MouseEvent | undefined
    if (!original) return
    original.preventDefault()
    emit('node-contextmenu', {
      event: original,
      nodeId: nodeIdFromPlacedId(e.node),
      nodeKey: e.node
    })
  })

  s.on('clickEdge', (e) => {
    if (store.isLinkDrawMode) return
    const original = e.event.original as MouseEvent | undefined
    if (original?.shiftKey) {
      store.toggleSelection(e.edge)
    } else {
      store.selectOnly(e.edge)
    }
  })

  // Hover affordance: fatten + recolor the link under the cursor (via the
  // reducer) and switch to a pointer cursor, so links read as clickable. In
  // link-draw mode the cursor stays a crosshair and clicks won't select, so
  // we skip the affordance there to avoid implying the link is clickable.
  s.on('enterEdge', (e) => {
    if (store.isLinkDrawMode) return
    hoveredLinkId.value = e.edge
    s.refresh()
  })
  s.on('leaveEdge', (e) => {
    if (hoveredLinkId.value !== e.edge) return
    hoveredLinkId.value = null
    s.refresh()
  })

  s.on('clickStage', (e) => {
    const original = e.event.original as MouseEvent | undefined
    if (store.isLinkDrawMode) {
      // Click on empty stage cancels the in-flight edge but stays in
      // edge-draw mode (so the user can keep chaining edges).
      linkDrawSource.value = null
      return
    }
    // Shift+click on empty stage is reserved for rubber band; never
    // clear selection on it.
    if (original?.shiftKey) return
    store.clearSelection()
  })

  s.on('doubleClickStage', (e) => {
    // Double-click on empty stage creates a new free-standing label at
    // the cursor's graph coordinates and enters edit mode. Edit mode only;
    // in View mode let sigma's default double-click zoom happen.
    if (!store.isEditMode) return
    const original = e.event.original as MouseEvent | undefined
    if (!original || !canvasEl.value) return
    // sigma also fires its zoom-on-doubleClick by default; suppress it.
    e.preventSigmaDefault()
    const rect = canvasEl.value.getBoundingClientRect()
    const pos = s.viewportToGraph({
      x: original.clientX - rect.left,
      y: original.clientY - rect.top
    })
    createLabelAt(pos.x, pos.y)
  })

  // Bump cameraVersion on each rendered frame so the label DOM overlay
  // re-projects in lock-step with sigma's WebGL render.
  s.on('afterRender', () => {
    cameraVersion.value++
  })
}

/* ---------- Edges (user-drawn connections between nodes) ---------- */

/**
 * In edge-draw mode, the first node click captures the source; the
 * next clickNode (a different node) commits the edge. graphology
 * assigns the edge key via addEdgeWithKey; we use a deterministic id
 * (newLinkId) so undo/redo can re-create the exact same edge object.
 */
const handleLinkDrawClick = (nodeId: string) => {
  if (!graph || !store.isEditMode) return
  if (linkDrawSource.value === null) {
    linkDrawSource.value = nodeId
    return
  }
  const source = linkDrawSource.value
  linkDrawSource.value = null
  if (source === nodeId) return // ignore clicks on the same node (no self-loops)
  if (graph.hasEdge(source, nodeId) || graph.hasEdge(nodeId, source)) {
    // Don't create duplicate edges between the same endpoints.
    return
  }
  const edgeId = newLinkId()
  const attrs = { size: 2, color: '#1f5fb0', origin: 'user' }
  graph.addEdgeWithKey(edgeId, source, nodeId, attrs)
  linkCount.value = graph.size
  pushCommand({
    label: 'Add edge',
    do: () => {
      if (!graph || graph.hasEdge(edgeId)) return
      graph.addEdgeWithKey(edgeId, source, nodeId, attrs)
      linkCount.value = graph.size
    },
    undo: () => {
      if (!graph || !graph.hasEdge(edgeId)) return
      graph.dropEdge(edgeId)
      linkCount.value = graph.size
    }
  })
}

// When the user toggles edge-draw mode off mid-flight, drop any
// captured source so re-entering the mode starts fresh.
watch(
  () => store.isLinkDrawMode,
  (on) => {
    if (!on) linkDrawSource.value = null
  }
)

/* ---------- Labels (free-standing DOM overlay annotations) ---------- */

let labelSequence = 0
const newLabelId = () => `${LABEL_PREFIX}${Date.now()}-${labelSequence++}`

/**
 * Projects a label's graph coordinates into viewport space and returns a
 * CSS style object positioning it inside .topology-labels-layer. The
 * cameraVersion argument is unused in computation but referenced so Vue's
 * reactivity re-evaluates this function on every render frame.
 */
const labelStyle = (label: CanvasLabel, _cameraVersion: number) => {
  if (!sigma) return { display: 'none' }
  void _cameraVersion
  const v = sigma.graphToViewport({ x: label.x, y: label.y })
  return {
    left: v.x + 'px',
    top: v.y + 'px',
    fontSize: label.fontSize ? `${label.fontSize}px` : undefined
  }
}

const createLabelAt = (graphX: number, graphY: number) => {
  const id = newLabelId()
  const label: CanvasLabel = { id, text: '', x: graphX, y: graphY }
  store.addLabel(label)
  startEditLabel(id, '', true)
}

const startEditLabel = (id: string, originalText: string, isNew: boolean) => {
  editingLabelId.value = id
  editingText.value = originalText
  editingOriginalText = originalText
  editingIsNew = isNew
  nextTick(() => {
    const ref = editingInputRef.value
    const input = Array.isArray(ref) ? ref[0] : ref
    input?.focus()
    input?.select()
  })
}

const commitEdit = () => {
  const id = editingLabelId.value
  if (id === null) return
  const text = editingText.value.trim()
  editingLabelId.value = null
  if (text.length === 0) {
    // Empty text on commit removes the label entirely.
    store.removeLabel(id)
    return
  }
  if (editingIsNew) {
    store.updateLabel(id, { text })
    const final = store.getLabel(id)
    if (!final) return
    const snapshot: CanvasLabel = { ...final }
    pushCommand({
      label: `Add label "${text}"`,
      do: () => {
        if (!store.getLabel(snapshot.id)) store.addLabel(snapshot)
      },
      undo: () => {
        store.removeLabel(snapshot.id)
      }
    })
    return
  }
  if (text === editingOriginalText) return
  const original = editingOriginalText
  store.updateLabel(id, { text })
  pushCommand({
    label: `Edit label`,
    do: () => store.updateLabel(id, { text }),
    undo: () => store.updateLabel(id, { text: original })
  })
}

const cancelEdit = () => {
  const id = editingLabelId.value
  if (id === null) return
  editingLabelId.value = null
  if (editingIsNew) {
    // Cancel of a freshly-created label drops it -- never lands in
    // history (no add command was pushed yet).
    store.removeLabel(id)
  }
}

const onLabelClick = (event: MouseEvent, label: CanvasLabel) => {
  if (editingLabelId.value === label.id) return
  if (event.shiftKey) {
    store.toggleSelection(label.id)
  } else {
    store.selectOnly(label.id)
  }
}

const onLabelDoubleClick = (label: CanvasLabel) => {
  if (!store.isEditMode) return
  startEditLabel(label.id, label.text, false)
}

const onLabelMouseDown = (event: MouseEvent, label: CanvasLabel) => {
  // Only left button; do not interfere with edit-mode input field.
  if (event.button !== 0) return
  // No label dragging in View mode (selection-to-inspect still works via click).
  if (!store.isEditMode) return
  if (editingLabelId.value === label.id) return
  if (!sigma || !canvasEl.value) return
  const rect = canvasEl.value.getBoundingClientRect()
  const mouseGraph = sigma.viewportToGraph({
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  })
  draggingLabel = {
    id: label.id,
    startLabelX: label.x,
    startLabelY: label.y,
    startMouseGraphX: mouseGraph.x,
    startMouseGraphY: mouseGraph.y
  }
  window.addEventListener('mousemove', onLabelMouseMove)
  window.addEventListener('mouseup', onLabelMouseUp)
}

const onLabelMouseMove = (event: MouseEvent) => {
  if (!draggingLabel || !sigma || !canvasEl.value) return
  const rect = canvasEl.value.getBoundingClientRect()
  const cur = sigma.viewportToGraph({
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  })
  const newX = draggingLabel.startLabelX + (cur.x - draggingLabel.startMouseGraphX)
  const newY = draggingLabel.startLabelY + (cur.y - draggingLabel.startMouseGraphY)
  store.updateLabel(draggingLabel.id, { x: newX, y: newY })
}

const onLabelMouseUp = () => {
  window.removeEventListener('mousemove', onLabelMouseMove)
  window.removeEventListener('mouseup', onLabelMouseUp)
  if (!draggingLabel) return
  const id = draggingLabel.id
  const start = { x: draggingLabel.startLabelX, y: draggingLabel.startLabelY }
  const current = store.getLabel(id)
  draggingLabel = null
  if (!current) return
  if (Math.abs(current.x - start.x) < 0.001 && Math.abs(current.y - start.y) < 0.001) return
  const end = { x: current.x, y: current.y }
  pushCommand({
    label: `Move label`,
    do: () => store.updateLabel(id, { x: end.x, y: end.y }),
    undo: () => store.updateLabel(id, { x: start.x, y: start.y })
  })
}

/**
 * Reflects the store's selectedIds into the graph. Nodes get the
 * `highlighted` attribute (sigma's built-in selection visual); edges
 * get a transient `_selected` flag that the edgeReducer maps to blue.
 * Label selection is rendered via a CSS class in the template -- no
 * graph mutation needed. Rebuild may have changed the graph reference,
 * so each id is guarded by hasNode/hasEdge.
 */
watch(
  () => store.selectedIds.slice(),
  (newIds, oldIds) => {
    if (!graph) return
    ;(oldIds ?? []).forEach((id) => {
      if (!graph) return
      if (graph.hasNode(id)) graph.removeNodeAttribute(id, 'highlighted')
      else if (graph.hasEdge(id)) graph.removeEdgeAttribute(id, '_selected')
    })
    newIds.forEach((id) => {
      if (!graph) return
      if (graph.hasNode(id)) graph.setNodeAttribute(id, 'highlighted', true)
      else if (graph.hasEdge(id)) graph.setEdgeAttribute(id, '_selected', true)
    })
    sigma?.refresh()
  }
)

/**
 * Repaint when node severities change so the nodeReducer recolors. Deep
 * watch because the store replaces the severities object on each refresh
 * but we also want to catch in-place updates defensively.
 */
watch(
  () => store.severities,
  () => sigma?.refresh(),
  { deep: true }
)

/**
 * Switching between View and Edit mounts/unmounts the palette pane and
 * reorders the inspector, which resizes the canvas. The ResizeObserver
 * usually catches that, but its callback can land a frame after Vue flushes
 * the DOM, leaving a blank canvas until the next paint. React to the mode
 * change directly as well: once the new layout settles (nextTick + a frame),
 * resync sigma's dimensions and repaint. We refresh() rather than fit so the
 * user's current zoom/pan is preserved across the switch.
 */
watch(
  () => store.isEditMode,
  () => {
    if (!sigma) return
    nextTick(() => {
      requestAnimationFrame(() => {
        if (!sigma) return
        sigma.resize()
        sigma.refresh()
      })
    })
  }
)

// Repaint when device icons resolve (fetched when the placed-node set changes).
watch(
  () => store.nodeIconIds,
  () => sigma?.refresh(),
  { deep: true }
)

// Repaint when the node size changes (slider or density default).
watch(
  () => store.nodeSize,
  () => sigma?.refresh()
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

const onDrop = (event: DragEvent) => {
  isDropHover.value = false
  if (!store.isEditMode) return
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
    size: 20,
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
  if (!graph || !store.isEditMode) return
  const ids = store.selectedIds.slice()
  if (ids.length === 0) return

  // Partition into label ids, edge ids, and node ids. Labels live in
  // the store; edges and nodes live in the graphology graph.
  const labelIds = ids.filter(isLabelId)
  const edgeIds = ids.filter((id) => !isLabelId(id) && graph!.hasEdge(id))
  const nodeIds = ids.filter((id) => !isLabelId(id) && graph!.hasNode(id))
  const labelSnapshots: CanvasLabel[] = labelIds
    .map((id) => store.getLabel(id))
    .filter((l): l is CanvasLabel => l !== undefined)
    .map((l) => ({ ...l }))
  const edgeSnapshots: Array<{ id: string; source: string; target: string; attrs: Record<string, unknown> }> = []
  for (const eid of edgeIds) {
    if (!graph.hasEdge(eid)) continue
    edgeSnapshots.push({
      id: eid,
      source: graph.source(eid),
      target: graph.target(eid),
      attrs: { ...graph.getEdgeAttributes(eid) }
    })
  }

  const snapshots: DeletedNodeSnapshot[] = []
  for (const id of nodeIds) {
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
    // Edges first so node-deletion cascade doesn't trip the explicit
    // edge-drop step (dropNode also drops incident edges).
    for (const e of edgeSnapshots) {
      if (graph.hasEdge(e.id)) graph.dropEdge(e.id)
    }
    for (const s of snapshots) {
      if (!graph.hasNode(s.id)) continue
      if (s.paletteId !== null) {
        store.markUnplaced(s.paletteId)
        placedCount.value = Math.max(0, placedCount.value - 1)
      }
      graph.dropNode(s.id)
    }
    for (const l of labelSnapshots) {
      store.removeLabel(l.id)
    }
    linkCount.value = graph.size
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
    for (const e of edgeSnapshots) {
      if (
        graph.hasNode(e.source) &&
        graph.hasNode(e.target) &&
        !graph.hasEdge(e.id)
      ) {
        graph.addEdgeWithKey(e.id, e.source, e.target, e.attrs)
      }
    }
    for (const l of labelSnapshots) {
      if (!store.getLabel(l.id)) store.addLabel(l)
    }
    linkCount.value = graph.size
  }

  applyDelete()
  store.clearSelection()
  const totalDeleted = snapshots.length + labelSnapshots.length + edgeSnapshots.length
  if (totalDeleted === 0) return
  pushCommand({
    label: `Delete ${totalDeleted} item(s)`,
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
  // All keyboard editing (undo/redo, delete, edit-mode escapes) is Edit-only.
  if (!store.isEditMode) return
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
  if (e.key === 'Escape') {
    if (store.isLinkDrawMode) {
      e.preventDefault()
      store.setLinkDrawMode(false)
      return
    }
    if (editingLabelId.value !== null) {
      e.preventDefault()
      cancelEdit()
      return
    }
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (store.selectedIds.length === 0) return
    e.preventDefault()
    deleteSelected()
  }
}

onMounted(() => {
  initGraph()
  window.addEventListener('keydown', onKeyDown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (sigma) {
    sigma.kill()
    sigma = null
  }
})

/**
 * Read a link's label and its endpoint labels, for the inspector. Returns
 * null if the id isn't a current link (graphology edge).
 */
const getLink = (id: string): { label: string; sourceLabel: string; targetLabel: string } | null => {
  if (!graph || !graph.hasEdge(id)) return null
  const source = graph.source(id)
  const target = graph.target(id)
  return {
    label: (graph.getEdgeAttribute(id, 'label') as string) ?? '',
    sourceLabel: (graph.getNodeAttribute(source, 'label') as string) ?? source,
    targetLabel: (graph.getNodeAttribute(target, 'label') as string) ?? target
  }
}

/**
 * Set an edge's label (rendered on the canvas and persisted via serialize).
 * Called per keystroke from the inspector, so it does not push an undo
 * command -- edge-label edits aren't individually undoable.
 */
const setLinkLabel = (id: string, label: string) => {
  if (!graph || !graph.hasEdge(id)) return
  graph.setEdgeAttribute(id, 'label', label)
  sigma?.refresh()
}

/**
 * Export the current map as a raster image. Uses @sigma/export-image, which
 * re-renders the scene into a temporary renderer (so the WebGL layers capture
 * correctly) and downloads it. `fileName` is the base name; the format
 * extension is appended by the library. Note: free-standing text labels are
 * DOM overlays and are not yet included in the export.
 */
const exportImage = async (fileName: string, format: 'png' | 'jpeg' = 'png'): Promise<void> => {
  if (!sigma) return
  await downloadAsImage(sigma, { format, fileName, backgroundColor: '#ffffff' })
}

defineExpose({
  // Reset to the default centered view, but zoomed out slightly so the
  // edge nodes' labels aren't clipped: sigma's auto-fit bounds the node
  // x/y positions only, not the rendered label width that extends past
  // each node. The default reset state is { x: 0.5, y: 0.5, ratio: 1 };
  // a ratio above 1 zooms out, leaving margin on all sides.
  fit: fitCamera,
  serialize,
  loadView,
  loadDiscoveredGraph,
  getLink,
  setLinkLabel,
  exportImage
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

.topology-link-preview {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}

.topology-canvas-root.is-link-draw-mode {
  cursor: crosshair;
}

.topology-canvas-root.is-link-draw-mode .topology-canvas canvas {
  cursor: crosshair;
}

/* Pointer over a hovered link signals it's clickable. Draw mode keeps its
   crosshair (the rule above wins by being listed where draw mode is active,
   but be explicit so a hovered link mid-draw doesn't flip to a pointer). */
.topology-canvas-root.is-hovering-link:not(.is-link-draw-mode) .topology-canvas canvas {
  cursor: pointer;
}

.topology-labels-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 3;
}

.topology-label {
  position: absolute;
  transform: translate(-50%, -50%);
  pointer-events: auto;
  cursor: grab;
  user-select: none;
  padding: 2px 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.85);
  font-size: 12px;
  font-weight: 500;
  color: #1d2939;
  white-space: nowrap;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  border: 1px solid transparent;
}

.topology-label:hover {
  border-color: #c0d1e8;
}

.topology-label.is-selected {
  border-color: #1f5fb0;
  background: rgba(255, 255, 255, 0.95);
}

.topology-label.is-editing {
  background: #fff;
  border-color: #1f5fb0;
  padding: 0;
  cursor: text;
}

.topology-label:active {
  cursor: grabbing;
}

.topology-label-input {
  border: none;
  outline: none;
  padding: 2px 6px;
  font: inherit;
  background: transparent;
  width: 12ch;
  min-width: 4ch;
  font-family: inherit;
}

.topology-label-text {
  display: inline-block;
}
</style>
