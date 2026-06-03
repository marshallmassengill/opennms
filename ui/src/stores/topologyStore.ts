///
/// Licensed to The OpenNMS Group, Inc (TOG) under one or more
/// contributor license agreements.  See the LICENSE.md file
/// distributed with this work for additional information
/// regarding copyright ownership.
///
/// TOG licenses this file to You under the GNU Affero General
/// Public License Version 3 (the "License") or (at your option)
/// any later version.  You may not use this file except in
/// compliance with the License.  You may obtain a copy of the
/// License at:
///
///      https://www.gnu.org/licenses/agpl-3.0.txt
///
/// Unless required by applicable law or agreed to in writing,
/// software distributed under the License is distributed on an
/// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
/// either express or implied.  See the License for the specific
/// language governing permissions and limitations under the
/// License.
///

import { defineStore } from 'pinia'
import type { CanvasLabel, TopologyView, TopologyViewSummary } from '@/types/topology'
import { listViews, getView, saveView, deleteView, getNodeSeverities } from '@/services/topologyService'

/**
 * The live canvas geometry the canvas component hands back on save:
 * nodes/edges from the graphology graph plus the sigma camera viewport.
 * Labels are not included here -- they already live in the store.
 */
type CanvasSnapshot = Pick<TopologyView, 'nodes' | 'edges' | 'viewport'>

const emptyView = (): TopologyView => ({
  name: 'Untitled view',
  nodes: [],
  edges: [],
  labels: [],
  viewport: { zoom: 1, panX: 0, panY: 0 }
})

/**
 * State for the custom topology canvas. Holds the catalog of saved
 * views plus the currently-open view, selection state, and the
 * Edit/View mode flag. Service-backed actions (list/load/save/delete)
 * are wired in subsequent steps once the persistence REST resource
 * is in place.
 */
export const useTopologyStore = defineStore('topologyStore', () => {
  const catalog = ref<TopologyViewSummary[]>([])
  const currentView = ref<TopologyView | null>(null)
  const isEditMode = ref<boolean>(true)
  /**
   * Edge-draw mode: when true, clicks on nodes capture source/target
   * instead of selecting. Driven by the toolbar Draw Edge toggle.
   */
  const isEdgeDrawMode = ref<boolean>(false)
  const selectedIds = ref<string[]>([])
  /**
   * Palette node ids currently placed on the canvas. The palette uses
   * this to hide already-placed entries; the canvas writes it on
   * drop/delete. Reactive Set: any mutation reassigns the ref.
   */
  const placedNodeIds = ref<Set<string>>(new Set())
  /**
   * Free-standing text annotations on the canvas. Lives outside the
   * graphology graph so it doesn't interact with sigma node/edge
   * concepts. Persisted as part of TopologyView when save lands.
   */
  const labels = ref<CanvasLabel[]>([])

  /** True while a save request is in flight; drives toolbar disabled state. */
  const isSaving = ref<boolean>(false)

  /**
   * Latest alarm status for placed nodes, keyed by real OnmsNode id. The
   * canvas colors nodes from this map. Empty until a status refresh runs;
   * refreshes are driven by the page (interval in View mode, manual in
   * Edit mode -- see the Edit/View semantics in the plan).
   */
  const severities = ref<Record<number, string>>({})

  const setSeverities = (next: Record<number, string>) => {
    severities.value = next
  }

  /**
   * Fetch current severities for the placed nodes that map to real
   * OnmsNode ids. Placed-node palette ids are the node ids; non-numeric
   * ids (mock/decorative nodes) are skipped.
   */
  const refreshStatus = async (): Promise<void> => {
    const ids = Array.from(placedNodeIds.value)
      .map((id) => Number(id))
      .filter((n) => Number.isInteger(n))
    if (ids.length === 0) {
      severities.value = {}
      return
    }
    severities.value = await getNodeSeverities(ids)
  }

  const newView = () => {
    currentView.value = emptyView()
    selectedIds.value = []
    labels.value = []
    placedNodeIds.value = new Set()
  }

  /** Reload the catalog list (id + name + roleScope) from the server. */
  const refreshCatalog = async (): Promise<boolean> => {
    const res = await listViews()
    catalog.value = res === false ? [] : res
    return res !== false
  }

  /** Rename the open view (persisted on the next save). */
  const renameCurrent = (name: string) => {
    if (currentView.value) currentView.value = { ...currentView.value, name }
  }

  /**
   * Persist the open view. The canvas component supplies the live
   * nodes/edges/viewport; labels are merged from the store. On success the
   * server's canonical record (id, owner, timestamps) becomes the current
   * view and the catalog is refreshed.
   */
  const saveCurrentView = async (snapshot: CanvasSnapshot): Promise<boolean> => {
    if (!currentView.value) return false
    isSaving.value = true
    try {
      const view: TopologyView = {
        ...currentView.value,
        nodes: snapshot.nodes,
        edges: snapshot.edges,
        labels: labels.value.map((l) => ({ ...l })),
        viewport: snapshot.viewport
      }
      const saved = await saveView(view)
      if (saved === false) return false
      currentView.value = saved
      await refreshCatalog()
      return true
    } finally {
      isSaving.value = false
    }
  }

  /**
   * Load a saved view by id and make it current. Returns the view so the
   * caller (the page) can hand it to the canvas to render; the canvas, in
   * turn, repopulates labels and placed-node ids in this store.
   */
  const openView = async (id: string): Promise<TopologyView | false> => {
    const view = await getView(id)
    if (view === false) return false
    currentView.value = view
    return view
  }

  /** Delete a view; if it was the open one, reset to a blank canvas. */
  const removeView = async (id: string): Promise<boolean> => {
    const ok = await deleteView(id)
    if (!ok) return false
    if (currentView.value?.id === id) newView()
    await refreshCatalog()
    return true
  }

  const setEditMode = (value: boolean) => {
    isEditMode.value = value
  }

  const setEdgeDrawMode = (value: boolean) => {
    isEdgeDrawMode.value = value
  }

  const selectOnly = (id: string) => {
    selectedIds.value = [id]
  }

  const toggleSelection = (id: string) => {
    const idx = selectedIds.value.indexOf(id)
    if (idx >= 0) selectedIds.value.splice(idx, 1)
    else selectedIds.value.push(id)
  }

  const clearSelection = () => {
    selectedIds.value = []
  }

  const setSelection = (ids: string[]) => {
    selectedIds.value = [...ids]
  }

  const addToSelection = (ids: string[]) => {
    const merged = new Set(selectedIds.value)
    ids.forEach((id) => merged.add(id))
    selectedIds.value = Array.from(merged)
  }

  const isPlaced = (paletteId: string): boolean => placedNodeIds.value.has(paletteId)

  const markPlaced = (paletteId: string) => {
    if (placedNodeIds.value.has(paletteId)) return
    placedNodeIds.value = new Set(placedNodeIds.value).add(paletteId)
  }

  const markUnplaced = (paletteId: string) => {
    if (!placedNodeIds.value.has(paletteId)) return
    const next = new Set(placedNodeIds.value)
    next.delete(paletteId)
    placedNodeIds.value = next
  }

  const setPlacedNodeIds = (ids: Iterable<string>) => {
    placedNodeIds.value = new Set(ids)
  }

  const setLabels = (next: CanvasLabel[]) => {
    labels.value = next.map((l) => ({ ...l }))
  }

  const addLabel = (label: CanvasLabel) => {
    labels.value = [...labels.value, label]
  }

  const updateLabel = (id: string, patch: Partial<CanvasLabel>) => {
    labels.value = labels.value.map((l) => (l.id === id ? { ...l, ...patch } : l))
  }

  const removeLabel = (id: string) => {
    labels.value = labels.value.filter((l) => l.id !== id)
  }

  const getLabel = (id: string): CanvasLabel | undefined =>
    labels.value.find((l) => l.id === id)

  return {
    catalog,
    currentView,
    isEditMode,
    isEdgeDrawMode,
    isSaving,
    selectedIds,
    placedNodeIds,
    labels,
    newView,
    refreshCatalog,
    renameCurrent,
    saveCurrentView,
    openView,
    removeView,
    setPlacedNodeIds,
    setLabels,
    setEditMode,
    setEdgeDrawMode,
    selectOnly,
    toggleSelection,
    clearSelection,
    setSelection,
    addToSelection,
    isPlaced,
    markPlaced,
    markUnplaced,
    addLabel,
    updateLabel,
    removeLabel,
    getLabel
  }
})
