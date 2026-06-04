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

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTopologyStore } from '@/stores/topologyStore'
import { saveView, listViews } from '@/services/topologyService'
import type { TopologyView } from '@/types/topology'

vi.mock('@/services/topologyService', () => ({
  listViews: vi.fn(),
  getView: vi.fn(),
  saveView: vi.fn(),
  deleteView: vi.fn(),
  getNodeSeverities: vi.fn(),
  loadDiscoveredGraph: vi.fn()
}))

const snapshot = { nodes: [], edges: [], viewport: { zoom: 1, panX: 0, panY: 0 } }

const existingView = (): TopologyView => ({
  id: '5',
  name: 'Existing',
  nodes: [],
  edges: [],
  labels: [],
  viewport: { zoom: 1, panX: 0, panY: 0 }
})

describe('useTopologyStore - saveCurrentViewAs (Save As)', () => {
  let store: ReturnType<typeof useTopologyStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    store = useTopologyStore()
    vi.clearAllMocks()
  })

  it('on success, adopts the saved view as current and POSTs a new entry (no id)', async () => {
    store.currentView = existingView()
    const saved: TopologyView = { ...existingView(), id: '9', name: 'Copy' }
    vi.mocked(saveView).mockResolvedValue(saved)
    vi.mocked(listViews).mockResolvedValue([{ id: '9', name: 'Copy' }])

    const ok = await store.saveCurrentViewAs('Copy', snapshot)

    expect(ok).toBe(true)
    expect(store.currentView).toEqual(saved)
    // Save As must create a new catalog entry: candidate carries no id.
    expect(saveView).toHaveBeenCalledWith(expect.objectContaining({ id: undefined, name: 'Copy' }))
  })

  it('on failure (e.g. duplicate name -> 409), leaves the open view UNCHANGED', async () => {
    store.currentView = existingView()
    vi.mocked(saveView).mockResolvedValue(false)

    const ok = await store.saveCurrentViewAs('Taken', snapshot)

    expect(ok).toBe(false)
    // The regression: the open view must keep its id and name -- not get
    // detached (id dropped) and renamed to the conflicting name.
    expect(store.currentView?.id).toBe('5')
    expect(store.currentView?.name).toBe('Existing')
    expect(listViews).not.toHaveBeenCalled()
    expect(store.isSaving).toBe(false)
  })

  it('returns false when there is no open view', async () => {
    store.currentView = null
    expect(await store.saveCurrentViewAs('Whatever', snapshot)).toBe(false)
    expect(saveView).not.toHaveBeenCalled()
  })
})
