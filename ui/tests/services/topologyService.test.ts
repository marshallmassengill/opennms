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
import { listViews, getView, saveView, deleteView } from '@/services/topologyService'
import { v2 } from '@/services/axiosInstances'
import type { TopologyView } from '@/types/topology'

vi.mock('@/services/axiosInstances', () => ({
  v2: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

const flatView = (overrides: Partial<TopologyView> = {}): TopologyView => ({
  name: 'Core DC',
  nodes: [{ id: 'placed-7', nodeId: 7, label: 'core-sw1', x: 10, y: 20, color: '#1f5fb0' }],
  edges: [{ id: 'e1', sourceId: 'placed-7', targetId: 'placed-8', origin: 'user' }],
  labels: [{ id: 'label-1', text: 'DC core', x: 5, y: 5 }],
  viewport: { zoom: 1.5, panX: 3, panY: 4 },
  ...overrides
})

// The server's nested shape: canvas under `definition`, metadata as siblings.
const dtoFor = (id: number, view: TopologyView) => ({
  id,
  name: view.name,
  owner: 'admin',
  created: 1700000000000,
  lastModified: null,
  definition: {
    nodes: view.nodes,
    edges: view.edges,
    labels: view.labels,
    viewport: view.viewport
  }
})

describe('topologyService views catalog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('listViews', () => {
    it('maps server documents down to catalog summaries with string ids', async () => {
      vi.mocked(v2.get).mockResolvedValue({
        data: [dtoFor(12, flatView()), dtoFor(34, flatView({ name: 'Edge' }))]
      })

      const result = await listViews()

      expect(v2.get).toHaveBeenCalledWith('topology/views')
      expect(result).toEqual([
        { id: '12', name: 'Core DC' },
        { id: '34', name: 'Edge' }
      ])
    })

    it('returns false when the request fails', async () => {
      vi.mocked(v2.get).mockRejectedValue(new Error('boom'))
      expect(await listViews()).toBe(false)
    })
  })

  describe('getView', () => {
    it('unwraps the definition into the flat front-end shape with a string id', async () => {
      const view = flatView()
      vi.mocked(v2.get).mockResolvedValue({ data: dtoFor(99, view) })

      const result = await getView('99')

      expect(v2.get).toHaveBeenCalledWith('topology/views/99')
      expect(result).toMatchObject({
        id: '99',
        name: 'Core DC',
        nodes: view.nodes,
        edges: view.edges,
        labels: view.labels,
        viewport: view.viewport
      })
    })

    it('falls back to an empty canvas when definition fields are absent', async () => {
      vi.mocked(v2.get).mockResolvedValue({ data: { id: 1, name: 'Bare', definition: {} } })

      const result = await getView('1')

      expect(result).toMatchObject({
        id: '1',
        name: 'Bare',
        nodes: [],
        edges: [],
        labels: [],
        viewport: { zoom: 1, panX: 0, panY: 0 }
      })
    })
  })

  describe('saveView (create)', () => {
    it('POSTs the nested definition, reads the Location id, and re-fetches', async () => {
      const view = flatView()
      vi.mocked(v2.post).mockResolvedValue({
        status: 201,
        headers: { location: 'http://localhost:8980/opennms/api/v2/topology/views/55' },
        data: ''
      })
      vi.mocked(v2.get).mockResolvedValue({ data: dtoFor(55, view) })

      const result = await saveView(view)

      // POST body nests the canvas under `definition`, metadata at the top.
      expect(v2.post).toHaveBeenCalledWith('topology/views', {
        name: 'Core DC',
        definition: {
          nodes: view.nodes,
          edges: view.edges,
          labels: view.labels,
          viewport: view.viewport,
          background: undefined
        }
      })
      // Re-fetch uses the id parsed from the Location header.
      expect(v2.get).toHaveBeenCalledWith('topology/views/55')
      expect(result).toMatchObject({ id: '55', name: 'Core DC' })
    })

    it('returns false when no Location header comes back', async () => {
      vi.mocked(v2.post).mockResolvedValue({ status: 201, headers: {}, data: '' })
      expect(await saveView(flatView())).toBe(false)
      expect(v2.get).not.toHaveBeenCalled()
    })
  })

  describe('saveView (update)', () => {
    it('PUTs to the id and re-fetches the canonical record', async () => {
      const view = flatView({ id: '55', name: 'Renamed' })
      vi.mocked(v2.put).mockResolvedValue({ status: 204 })
      vi.mocked(v2.get).mockResolvedValue({ data: dtoFor(55, view) })

      const result = await saveView(view)

      expect(v2.put).toHaveBeenCalledWith(
        'topology/views/55',
        expect.objectContaining({ name: 'Renamed', definition: expect.any(Object) })
      )
      expect(v2.get).toHaveBeenCalledWith('topology/views/55')
      expect(result).toMatchObject({ id: '55', name: 'Renamed' })
    })
  })

  describe('deleteView', () => {
    it('DELETEs by id and returns true', async () => {
      vi.mocked(v2.delete).mockResolvedValue({ status: 204 })
      expect(await deleteView('7')).toBe(true)
      expect(v2.delete).toHaveBeenCalledWith('topology/views/7')
    })

    it('returns false on failure', async () => {
      vi.mocked(v2.delete).mockRejectedValue(new Error('nope'))
      expect(await deleteView('7')).toBe(false)
    })
  })
})
