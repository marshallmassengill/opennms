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
          <!-- View-source dimension (above Edit/View): Custom vs discovered.
               A compact menu button so the toolbar stays uncluttered and new
               providers slot in as submenus. Each choice navigates the route
               (/topology/:source), so every source stays bookmarkable. -->
          <PButton
            :label="`Source: ${currentSourceShort}`"
            icon="pi pi-chevron-down"
            icon-pos="right"
            severity="secondary"
            outlined
            aria-haspopup="true"
            class="source-button"
            @click="sourceMenuRef?.toggle($event)"
          />
          <PTieredMenu ref="sourceMenuRef" :model="sourceMenuModel" popup />
          <!-- Custom-view management (hidden for read-only discovered sources). -->
          <template v-if="!isDiscovered">
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
          </template>
          <template v-else>
            <!-- Variant picker: which representation of this discovered source
                 (Combined / a single protocol / OSPF-by-area). Bookmarkable
                 via ?variant=. -->
            <PSelect
              v-if="variantOptions.length > 1"
              v-model="selectedVariant"
              :options="variantOptions"
              option-label="label"
              option-value="key"
              class="variant-chooser"
              aria-label="Topology representation"
            />
            <span class="discovered-hint">{{ discoveredHint }}</span>
          </template>
        </div>
      </template>
      <template #end>
        <div class="toolbar-controls">
          <!-- Discovered sources are read-only: no Edit mode. -->
          <PSelectButton
            v-if="!isDiscovered"
            v-model="mode"
            :options="modeOptions"
            option-label="label"
            option-value="value"
            :allow-empty="false"
            aria-label="View or Edit mode"
            class="mode-select"
          />
          <!-- Discovered-view search, focus + Semantic Zoom Level. -->
          <template v-if="isDiscovered">
            <PAutoComplete
              v-model="searchModel"
              :suggestions="searchSuggestions"
              option-label="label"
              :complete-on-focus="true"
              placeholder="Search nodes"
              class="topology-search"
              aria-label="Search nodes to focus"
              @complete="onSearchComplete"
              @item-select="onSearchSelect"
            />
            <PButton
              v-if="!store.focusNodeId"
              label="Focus"
              severity="secondary"
              outlined
              :disabled="!selectedNodeId"
              @click="focusOnSelection"
            />
            <span v-else class="szl-control">
              <PButton
                label="−"
                severity="secondary"
                outlined
                :disabled="store.semanticZoomLevel <= 0"
                aria-label="Decrease zoom level"
                @click="stepSzl(-1)"
              />
              <span class="szl-value">{{ store.semanticZoomLevel }} hop{{ store.semanticZoomLevel === 1 ? '' : 's' }}</span>
              <PButton
                label="+"
                severity="secondary"
                outlined
                aria-label="Increase zoom level"
                @click="stepSzl(1)"
              />
              <PButton label="Show all" severity="secondary" outlined @click="showAll" />
            </span>
          </template>
          <PButton
            label="Refresh status"
            severity="secondary"
            outlined
            @click="store.refreshStatus()"
          />
          <PButton
            v-if="store.isEditMode"
            :label="store.isLinkDrawMode ? 'Link: ON' : 'Draw Link'"
            :severity="store.isLinkDrawMode ? 'primary' : 'secondary'"
            :outlined="!store.isLinkDrawMode"
            @click="store.setLinkDrawMode(!store.isLinkDrawMode)"
          />
          <span class="node-size-control" title="Node size">
            <i class="pi pi-circle-fill node-size-icon-sm" />
            <PSlider
              v-model="nodeSizeModel"
              :min="store.NODE_SIZE_MIN"
              :max="store.NODE_SIZE_MAX"
              class="node-size-slider"
              aria-label="Node size"
            />
            <i class="pi pi-circle-fill node-size-icon-lg" />
          </span>
          <PButton label="Fit" severity="secondary" outlined @click="canvasRef?.fit()" />
          <PButton label="Export PNG" severity="secondary" outlined @click="onExport" />
        </div>
      </template>
    </PToolbar>

    <div class="topology-body">
      <!-- Palette is an Edit-mode tool (compose); hidden in View and for
           read-only discovered sources. -->
      <TopologyPalette v-if="store.isEditMode && !isDiscovered" class="topology-palette-pane" />
      <div class="topology-canvas-wrap">
        <TopologyCanvas
          ref="canvasRef"
          class="topology-canvas-pane"
          @node-contextmenu="onNodeContextMenu"
        />
        <!-- Many discovered sources (OSPF, IS-IS, Bridge, …) have no links
             unless that protocol was discovered; explain the empty canvas. -->
        <div v-if="discoveredEmpty" class="discovered-empty">
          <p>No discovered topology for <strong>{{ store.discoveredGraph?.label }}</strong>.</p>
          <p class="discovered-empty-hint">Nothing was found from current discovery data for this source.</p>
        </div>
      </div>
      <!-- View: full read-only Inspector on the left (order -1).
           Edit: slim Properties panel on the right, only when a label/edge
           is selected (nodes have no editable props here). -->
      <!-- Always rendered (in both modes) so selecting an edge/label doesn't
           reflow the canvas -- a reflow shifts the view and staled sigma's
           hit-detection, which broke selecting a second edge. -->
      <TopologyInspector
        :canvas="canvasRef"
        :variant="store.isEditMode ? 'props' : 'full'"
        class="topology-inspector-pane"
        :style="{ order: store.isEditMode ? 0 : -1 }"
      />
    </div>

    <!-- Bottom browse panel: Nodes / Alarms for the view, tied to selection. -->
    <TopologyBrowsePanel @select="onBrowseSelect" />

    <PContextMenu ref="nodeMenuRef" :model="nodeMenuItems" />
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
import AutoComplete from 'primevue/autocomplete'
import Slider from 'primevue/slider'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import ContextMenu from 'primevue/contextmenu'
import TieredMenu from 'primevue/tieredmenu'
import type { MenuItem } from 'primevue/menuitem'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import { useRoute, useRouter } from 'vue-router'
import TopologyCanvas from '@/components/Topology/TopologyCanvas.vue'
import TopologyPalette from '@/components/Topology/TopologyPalette.vue'
import TopologyInspector from '@/components/Topology/TopologyInspector.vue'
import TopologyBrowsePanel from '@/components/Topology/TopologyBrowsePanel.vue'
import { useTopologyStore } from '@/stores/topologyStore'
import {
  CUSTOM_SOURCE_SLUG,
  TOPOLOGY_SOURCES,
  isDiscoveredSlug,
  sourceForSlug,
  variantForKey,
  graphSourceFor
} from '@/components/Topology/sources'
import { focusSubgraph } from '@/components/Topology/focus'
import { nodeActionLinks } from '@/components/Topology/nodeActions'
import type { CanvasNode } from '@/types/topology'

const PToolbar = Toolbar
const PButton = Button
const PSelect = Select
const PContextMenu = ContextMenu
const PTieredMenu = TieredMenu
const PSelectButton = SelectButton
const PAutoComplete = AutoComplete
const PSlider = Slider
const PToast = Toast
const PConfirmDialog = ConfirmDialog

const store = useTopologyStore()
const toast = useToast()
const confirm = useConfirm()
const route = useRoute()
const router = useRouter()

const canvasRef = ref<InstanceType<typeof TopologyCanvas> | null>(null)

// View-source dimension (the route's :source param). 'custom' is the
// hand-composed catalog; the rest are discovered (read-only) topologies.
const sourceSlug = computed<string>(() => (route.params.source as string) || CUSTOM_SOURCE_SLUG)
const isDiscovered = computed<boolean>(() => isDiscoveredSlug(sourceSlug.value))

const currentSource = computed(() => sourceForSlug(sourceSlug.value))

// Navigate to a source via the route so every source stays bookmarkable.
// Dropping the query resets the variant to the group's default.
const goToSource = (slug: string) => {
  if (slug !== sourceSlug.value) router.push({ name: 'Topology', params: { source: slug } })
}

// Compact label for the source button.
const currentSourceShort = computed<string>(() => currentSource.value?.label ?? 'Custom')

// --- Discovered-source variant (representation) ----------------------------
// The variant (Combined / a single protocol / OSPF-by-area …) is a bookmarkable
// `?variant=<key>` query; absent => the group's default (variants[0]).
const variantKey = computed<string | undefined>(() => {
  const v = route.query.variant
  return typeof v === 'string' ? v : undefined
})
const variantOptions = computed(() => currentSource.value?.variants ?? [])
const selectedVariant = computed<string>({
  get: () => variantForKey(currentSource.value, variantKey.value)?.key ?? '',
  set: (key) => {
    const variants = currentSource.value?.variants
    if (!variants) return
    // Clean URL for the default variant; explicit ?variant otherwise.
    const query = key === variants[0].key ? {} : { variant: key }
    router.push({ name: 'Topology', params: { source: sourceSlug.value }, query })
  }
})

// Grouped source menu: Custom as a leaf, discovered sources under a submenu
// (new providers slot in as further submenus). Each command navigates the
// route. The active source is marked.
const sourceMenuRef = ref<{ toggle: (event: Event) => void } | null>(null)
const sourceMenuModel = computed<MenuItem[]>(() => {
  const item = (slug: string, label: string): MenuItem => ({
    label,
    class: slug === sourceSlug.value ? 'source-item-active' : undefined,
    command: () => goToSource(slug)
  })
  const discovered = TOPOLOGY_SOURCES.filter((s) => s.kind === 'discovered').map((s) =>
    item(s.slug, s.label.replace(/^Discovered · /, ''))
  )
  return [item(CUSTOM_SOURCE_SLUG, 'Custom'), { label: 'Discovered', items: discovered }]
})

const discoveredHint = computed<string>(() => {
  if (store.isDiscoveredLoading) return 'Loading…'
  if (store.discoveredError) return 'Load failed'
  return 'read-only'
})

// A discovered source that loaded successfully but has no vertices.
const discoveredEmpty = computed<boolean>(
  () =>
    isDiscovered.value &&
    !store.isDiscoveredLoading &&
    !store.discoveredError &&
    !!store.discoveredGraph &&
    store.discoveredGraph.nodes.length === 0
)

// Right-click a node -> context menu of node-data cross-links (Node Details,
// Resource Graphs, Events, Alarms), plus "Set as focus point" in discovered
// views. Built per-click for the targeted node.
const nodeMenuRef = ref<{ show: (event: Event) => void } | null>(null)
const nodeMenuItems = ref<MenuItem[]>([])

const onNodeContextMenu = (payload: { event: MouseEvent; nodeId: number | null; nodeKey: string }) => {
  const { event, nodeId, nodeKey } = payload
  const items: MenuItem[] = []
  if (nodeId != null) {
    for (const link of nodeActionLinks(nodeId)) {
      items.push({ label: link.label, command: () => window.open(link.url, '_blank', 'noopener') })
    }
  }
  if (isDiscovered.value) {
    if (items.length) items.push({ separator: true })
    items.push({ label: 'Set as focus point', command: () => store.setFocusNode(nodeKey) })
  }
  if (items.length === 0) return
  nodeMenuItems.value = items
  nodeMenuRef.value?.show(event)
}

// Segmented View/Edit control (clear, always-visible mode indicator).
const modeOptions = [
  { label: 'View', value: false },
  { label: 'Edit', value: true }
]
const mode = computed<boolean>({
  get: () => store.isEditMode,
  set: (value) => store.setEditMode(value)
})

// Load whatever the route's :source points at -- the custom catalog or a
// discovered topology. Re-runs whenever the source changes.
const loadSource = async (): Promise<void> => {
  const option = sourceForSlug(sourceSlug.value)
  if (!option) {
    // Unknown source -> fall back to custom.
    router.replace({ name: 'Topology', params: { source: CUSTOM_SOURCE_SLUG } })
    return
  }
  if (option.kind === 'discovered') {
    // Discovered topologies are read-only; force View mode and load the graph
    // for the selected variant (or the group's default).
    store.setEditMode(false)
    const gs = graphSourceFor(option, variantKey.value)
    const graph = gs ? await store.loadDiscoveredSource(gs) : false
    if (graph && store.discoveredGraph) {
      applyRouteFocus() // restore focus/SZL from the URL before the first render
      renderDiscovered()
      store.refreshStatus()
    } else {
      toast.add({
        severity: 'error',
        summary: 'Load failed',
        detail: `Could not load ${option.label}.`,
        life: 5000
      })
    }
    return
  }
  // Custom source: clear any discovered graph, load the catalog + the ?view=.
  // force=true so the custom view re-renders even when currentView already names
  // it (the canvas was showing a discovered graph until now).
  store.clearDiscovered()
  await store.refreshCatalog()
  await loadFromRoute(true)
}

onMounted(loadSource)

// React to source or variant changes (selector, deep link, back/forward).
// One watcher over both so a group switch (which changes the slug and clears
// the variant in the same tick) reloads only once. loadSource handles the
// custom vs discovered branch; ?view= changes on custom are handled separately.
watch([sourceSlug, variantKey], () => loadSource())

// --- Discovered-view focus + Semantic Zoom Level ---------------------------

// The single selected node (the Focus action's target), or null.
const selectedNodeId = computed<string | null>(() =>
  store.selectedIds.length === 1 ? store.selectedIds[0] : null
)

// Render the discovered graph, reduced to the focus node + SZL hops when a
// focus is set (else the whole graph). Re-runs the auto-layout each time.
const renderDiscovered = () => {
  if (!store.discoveredGraph) return
  const graph = focusSubgraph(store.discoveredGraph, store.focusNodeId, store.semanticZoomLevel)
  canvasRef.value?.loadDiscoveredGraph(graph)
}

// Focus + SZL live in the URL (?focus=<nodeId>&szl=<hops>) so a focused
// discovered view is shareable/bookmarkable, like ?view= and ?variant=. The
// URL is the source of truth: the controls navigate, a watcher mirrors the
// query into the store, and the store drives the render below.
const SZL_DEFAULT = 2 // matches the store's initial semanticZoomLevel
const routeFocus = computed<string | null>(() => {
  const f = route.query.focus
  return typeof f === 'string' && f.length ? f : null
})
const routeSzl = computed<number | null>(() => {
  const s = route.query.szl
  if (typeof s !== 'string') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
})

// Mirror the URL focus/SZL into the store. Idempotent: the store setters no-op
// on an unchanged value, so this can't loop with the navigation below.
const applyRouteFocus = () => {
  store.setFocusNode(routeFocus.value)
  store.setSemanticZoomLevel(routeSzl.value ?? SZL_DEFAULT)
}

// Navigate focus/SZL into the URL. szl only travels alongside a focus (it has
// no effect without one). replace (not push) keeps back/forward uncluttered.
const navFocus = (focus: string | null, szl: number) => {
  const query: Record<string, string> = { ...(route.query as Record<string, string>) }
  if (focus) {
    query.focus = focus
    query.szl = String(szl)
  } else {
    delete query.focus
    delete query.szl
  }
  router.replace({ name: 'Topology', params: { source: sourceSlug.value }, query })
}

const focusOnSelection = () => {
  if (selectedNodeId.value) navFocus(selectedNodeId.value, store.semanticZoomLevel)
}
const showAll = () => navFocus(null, store.semanticZoomLevel)

// Export the current map as a PNG. The file name reflects the open view
// (custom) or the source/variant (discovered); the canvas appends ".png".
// Node-size slider <-> store (clamped in the store setter).
const nodeSizeModel = computed<number>({
  get: () => store.nodeSize,
  set: (n) => store.setNodeSize(n)
})

// Browse-panel row -> select that node on the canvas (or clear to "show all").
const onBrowseSelect = (placedId: string | null) => {
  if (placedId) store.selectOnly(placedId)
  else store.clearSelection()
}

const onExport = () => {
  const base = isDiscovered.value
    ? `topology-${sourceSlug.value}${variantKey.value ? '-' + variantKey.value : ''}`
    : `topology-${store.currentView?.name ?? 'view'}`
  void canvasRef.value?.exportImage(base.replace(/[^\w.-]+/g, '-'))
}
const stepSzl = (delta: number) =>
  navFocus(store.focusNodeId, Math.max(0, Math.min(10, store.semanticZoomLevel + delta)))

// --- Search -> focus -------------------------------------------------------
// Find a node by label or node id in a large discovered graph and focus it.
// Purely client-side over the already-loaded full graph (store.discoveredGraph
// is the whole graph; the focus reduction happens only at render). Selecting a
// result focuses it via the same URL navigation, so the focused view stays
// shareable.
const SEARCH_LIMIT = 12
const searchModel = ref<CanvasNode | string>('')
const searchSuggestions = ref<CanvasNode[]>([])

const onSearchComplete = (event: { query: string }) => {
  const nodes = store.discoveredGraph?.nodes ?? []
  const q = event.query.trim().toLowerCase()
  const matches = q
    ? nodes.filter(
        (n) => n.label.toLowerCase().includes(q) || String(n.nodeId ?? '').includes(q)
      )
    : nodes
  searchSuggestions.value = matches.slice(0, SEARCH_LIMIT)
}

const onSearchSelect = (event: { value: CanvasNode }) => {
  if (event.value?.id) navFocus(event.value.id, store.semanticZoomLevel)
  searchModel.value = '' // clear the field; the focus chip/SZL control reflects the state
}

// URL focus/SZL changed (a control click, a deep link, or back/forward) -> store.
watch([routeFocus, routeSzl], () => {
  if (isDiscovered.value) applyRouteFocus()
})

// Re-render the focused subgraph when focus or the zoom level changes.
watch(
  () => [store.focusNodeId, store.semanticZoomLevel],
  () => {
    if (isDiscovered.value) renderDiscovered()
  }
)

// React to ?view= changes -- custom source only (discovered has no views).
// The loadFromRoute guard makes our own syncRouteToView writes no-ops here.
watch(
  () => route.query.view,
  () => {
    if (!isDiscovered.value) loadFromRoute()
  }
)

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
      store.setLinkDrawMode(false)
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

// Reflect the open view into the URL as ?view=<name> (bookmarkable). Guarded
// so it only writes when the value actually changes.
const syncRouteToView = () => {
  const name = store.currentView?.name
  const source = (route.params.source as string) || 'custom'
  if (name && route.query.view !== name) {
    router.replace({ name: 'Topology', params: { source }, query: { ...route.query, view: name } })
  }
}

// Load whatever ?view= names (or Default). The "already showing it" short-circuit
// avoids reloading on our own syncRouteToView writes -- but it must be bypassed
// when arriving from a discovered source, where currentView still names the last
// custom view even though the canvas is showing the discovered graph. Callers on
// a source switch pass force=true so the custom view actually re-renders.
const loadFromRoute = async (force = false): Promise<void> => {
  const wanted = (route.query.view as string) || 'Default'
  if (!force && store.currentView?.id && store.currentView.name === wanted) return
  const match = store.catalog.find((v) => v.name === wanted)
  if (match) {
    await openIntoCanvas(match.id)
  } else {
    if (wanted !== 'Default') {
      toast.add({ severity: 'warn', summary: 'View not found', detail: wanted, life: 4000 })
    }
    await loadDefault()
  }
}

// Load a saved view by id into the canvas. No toast (used by the chooser,
// the initial route load, and after a delete).
const openIntoCanvas = async (id: string): Promise<boolean> => {
  const view = await store.openView(id)
  if (!view) {
    toast.add({ severity: 'error', summary: 'Open failed', detail: 'Could not load the view.', life: 5000 })
    return false
  }
  canvasRef.value?.loadView(view)
  syncRouteToView()
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
    syncRouteToView()
  }
}

const onSave = () => saveCurrent()

// View names are unique in the catalog. Catch a collision up front so New /
// Save As give a clear message instead of a doomed request -- and, for Save
// As, so we never mutate the open view before a save that will fail.
const nameInUse = (name: string): boolean => store.catalog.some((v) => v.name === name)

const warnNameInUse = (name: string) =>
  toast.add({
    severity: 'warn',
    summary: 'Name already in use',
    detail: `A view named "${name}" already exists. Choose a different name.`,
    life: 5000
  })

const onNew = async () => {
  const name = window.prompt('Name the new view:', '')
  if (!name || !name.trim()) return
  const trimmed = name.trim()
  if (nameInUse(trimmed)) {
    warnNameInUse(trimmed)
    return
  }
  store.newView()
  store.renameCurrent(trimmed)
  store.setEditMode(true)
  if (store.currentView) canvasRef.value?.loadView(store.currentView)
  await saveCurrent()
  syncRouteToView()
}

const onSaveAs = async () => {
  if (!store.currentView) return
  const name = window.prompt('Save view as:', store.currentView.name)
  if (!name || !name.trim()) return
  const trimmed = name.trim()
  // Up-front collision check: Save As must create a new entry, so an existing
  // name (including the current view's own) is always a conflict.
  if (nameInUse(trimmed)) {
    warnNameInUse(trimmed)
    return
  }
  const snapshot = canvasRef.value?.serialize()
  if (!snapshot) return
  // Non-destructive: the open view is replaced only if the save succeeds.
  const ok = await store.saveCurrentViewAs(trimmed, snapshot)
  toast.add(
    ok
      ? { severity: 'success', summary: 'View saved', detail: trimmed, life: 3000 }
      : {
          severity: 'error',
          summary: 'Save failed',
          detail: 'Could not save the view; the name may already be in use.',
          life: 5000
        }
  )
  if (ok) syncRouteToView()
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
  syncRouteToView()
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
  /* Fill the layout's main area (header + footer overhead ~104px). A residual
     ~16px page scrollbar comes from the app shell (side-menu rail / footer
     spacer), independent of this page -- tracked separately. */
  height: calc(100vh - 104px);
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

.source-button {
  white-space: nowrap;
}

.variant-chooser {
  min-width: 12rem;
}

/* Mark the active source in the menu. */
:deep(.source-item-active) > .p-tieredmenu-item-link,
:deep(.source-item-active) > .p-menuitem-link {
  font-weight: 700;
}

.view-chooser {
  min-width: 12rem;
}

.topology-search :deep(.p-autocomplete-input) {
  min-width: 11rem;
}

.node-size-control {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  color: #98a2b3;
}
.node-size-slider {
  width: 6rem;
}
.node-size-icon-sm {
  font-size: 0.5rem;
}
.node-size-icon-lg {
  font-size: 0.85rem;
}

.discovered-hint {
  font-size: 0.85rem;
  font-style: italic;
  color: #6b7280;
}

.toolbar-controls {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.szl-control {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.szl-value {
  min-width: 3.5rem;
  text-align: center;
  font-variant-numeric: tabular-nums;
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

/* Wraps the canvas so the discovered empty-state can overlay it. */
.topology-canvas-wrap {
  flex: 1 1 auto;
  min-width: 0;
  position: relative;
  display: flex;
}

.topology-canvas-pane {
  flex: 1 1 auto;
  min-width: 0;
}

.discovered-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  pointer-events: none;
  text-align: center;
  color: #6b7280;
}

.discovered-empty-hint {
  font-size: 0.85rem;
}

.topology-inspector-pane {
  flex: 0 0 auto;
}
</style>
