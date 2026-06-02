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

import { v2 } from './axiosInstances'
import type { TopologyView, TopologyViewSummary } from '@/types/topology'
import type { Node, NodeApiResponse, QueryParameters } from '@/types'

/**
 * Palette node fetch.
 *
 * Step 3 spike: returns a realistic mock list. The shape matches
 * `nodeService.getNodes`'s `NodeApiResponse` so swapping the mock for the
 * real call (during Step 8 or sooner, when we point the SPA at a live
 * OpenNMS) is a one-line change in the implementation, with no consumer
 * impact.
 */

const MOCK_LOCATIONS = ['Default', 'branch-east', 'branch-west', 'dc-primary', 'dc-secondary']
const MOCK_FOREIGN_SOURCES = ['selfmonitor', 'network', 'manual', 'vcsa-r36-test']

interface MockArchetype {
  prefix: string
  count: number
  categories: string[]
}

const MOCK_ARCHETYPES: MockArchetype[] = [
  { prefix: 'rt-core', count: 6, categories: ['Routers'] },
  { prefix: 'rt-edge', count: 8, categories: ['Routers'] },
  { prefix: 'sw-dist', count: 10, categories: ['Switches'] },
  { prefix: 'sw-access', count: 30, categories: ['Switches'] },
  { prefix: 'fw-edge', count: 4, categories: ['Firewalls'] },
  { prefix: 'lb-app', count: 4, categories: ['LoadBalancers'] },
  { prefix: 'web-prd', count: 20, categories: ['Servers', 'Linux'] },
  { prefix: 'web-stg', count: 6, categories: ['Servers', 'Linux'] },
  { prefix: 'db-prd', count: 8, categories: ['Servers', 'Linux'] },
  { prefix: 'app-prd', count: 16, categories: ['Servers', 'Linux'] },
  { prefix: 'win-prd', count: 12, categories: ['Servers', 'Windows'] },
  { prefix: 'esxi-mgmt', count: 6, categories: ['VMware8'] },
  { prefix: 'bsm-service', count: 8, categories: ['BSM'] }
]

const generateMockNodes = (): Node[] => {
  const nodes: Node[] = []
  let id = 1
  let categoryId = 1
  const categoryIdByName = new Map<string, number>()

  for (const arch of MOCK_ARCHETYPES) {
    for (let i = 1; i <= arch.count; i++) {
      const label = `${arch.prefix}-${i.toString().padStart(2, '0')}`
      const location = MOCK_LOCATIONS[id % MOCK_LOCATIONS.length]
      const foreignSource = MOCK_FOREIGN_SOURCES[id % MOCK_FOREIGN_SOURCES.length]
      const categories = arch.categories.map((name) => {
        if (!categoryIdByName.has(name)) {
          categoryIdByName.set(name, categoryId++)
        }
        return {
          id: categoryIdByName.get(name) as number,
          name,
          authorizedGroups: []
        }
      })
      nodes.push({
        id: String(id),
        label,
        location,
        type: 'A',
        foreignSource,
        foreignId: `mock-${id}`,
        categories,
        createTime: Date.now() - id * 60_000,
        assetRecord: {
          longitude: '',
          latitude: '',
          category: '',
          description: '',
          maintcontract: ''
        },
        lastEgressFlow: 0,
        lastIngressFlow: 0,
        labelSource: 'U',
        lastCapabilitiesScan: '',
        primaryInterface: 0,
        sysObjectId: '',
        sysDescription: '',
        sysName: label,
        sysContact: '',
        sysLocation: location
      })
      id++
    }
  }
  return nodes
}

const mockNodes = generateMockNodes()

/**
 * Returns nodes for the palette. Mirrors `nodeService.getNodes`'s response
 * shape; switching to the real call is a single-line change in this body.
 */
const fetchPaletteNodes = async (
  queryParameters?: QueryParameters
): Promise<NodeApiResponse | false> => {
  const limit = queryParameters?.limit ?? 200
  const offset = queryParameters?.offset ?? 0
  const slice = mockNodes.slice(offset, offset + limit)
  return {
    node: slice,
    count: slice.length,
    offset,
    totalCount: mockNodes.length
  }
}

/**
 * Service stub for the topology views catalog.
 *
 * The backing REST resource (/api/v2/topology/views) is not implemented
 * yet -- it ships as a self-contained JAX-RS resource later in the build
 * sequence. Until then, these calls will fail with HTTP 404 and return
 * `false`, matching the pattern used by the other services in this
 * directory (return typed data or `false`).
 */

const viewsEndpoint = 'topology/views'

const listViews = async (): Promise<TopologyViewSummary[] | false> => {
  try {
    const resp = await v2.get(viewsEndpoint)
    return resp.data
  } catch (err) {
    return false
  }
}

const getView = async (id: string): Promise<TopologyView | false> => {
  try {
    const resp = await v2.get(`${viewsEndpoint}/${id}`)
    return resp.data
  } catch (err) {
    return false
  }
}

const saveView = async (view: TopologyView): Promise<TopologyView | false> => {
  try {
    if (view.id) {
      const resp = await v2.put(`${viewsEndpoint}/${view.id}`, view)
      return resp.data
    }
    const resp = await v2.post(viewsEndpoint, view)
    return resp.data
  } catch (err) {
    return false
  }
}

const deleteView = async (id: string): Promise<boolean> => {
  try {
    await v2.delete(`${viewsEndpoint}/${id}`)
    return true
  } catch (err) {
    return false
  }
}

export {
  fetchPaletteNodes,
  listViews,
  getView,
  saveView,
  deleteView
}
