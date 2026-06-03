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
 * Topology views catalog, backed by the /api/v2/topology/views REST
 * resource. Calls return typed data or `false`, matching the convention
 * used by the other services in this directory.
 *
 * The server stores the canvas as an opaque JSON document under a
 * `definition` field, with the catalog metadata (name, role scope,
 * owner, timestamps) as siblings. The front-end model is flat -- nodes,
 * edges, labels, and viewport live at the top of TopologyView -- so this
 * service maps between the two shapes. Ids are integers on the wire and
 * strings in the UI.
 */

const viewsEndpoint = 'topology/views'

/** The canvas document as stored under the server's `definition` field. */
interface TopologyViewDefinition {
  nodes: TopologyView['nodes']
  edges: TopologyView['edges']
  labels: TopologyView['labels']
  viewport: TopologyView['viewport']
  background?: TopologyView['background']
}

/** Wire shape of a view as returned by /api/v2/topology/views. */
interface TopologyViewDTO {
  id?: number
  name: string
  definition: TopologyViewDefinition
  roleScope?: string
  owner?: string
  created?: number
  lastModified?: number
}

const toDto = (view: TopologyView): TopologyViewDTO => ({
  name: view.name,
  roleScope: view.roleScope,
  definition: {
    nodes: view.nodes,
    edges: view.edges,
    labels: view.labels,
    viewport: view.viewport,
    background: view.background
  }
})

const fromDto = (dto: TopologyViewDTO): TopologyView => ({
  id: dto.id != null ? String(dto.id) : undefined,
  name: dto.name,
  roleScope: dto.roleScope,
  nodes: dto.definition?.nodes ?? [],
  edges: dto.definition?.edges ?? [],
  labels: dto.definition?.labels ?? [],
  viewport: dto.definition?.viewport ?? { zoom: 1, panX: 0, panY: 0 },
  background: dto.definition?.background
})

const listViews = async (): Promise<TopologyViewSummary[] | false> => {
  try {
    const resp = await v2.get<TopologyViewDTO[]>(viewsEndpoint)
    return (resp.data ?? []).map((dto) => ({
      id: dto.id != null ? String(dto.id) : '',
      name: dto.name,
      roleScope: dto.roleScope
    }))
  } catch (err) {
    return false
  }
}

const getView = async (id: string): Promise<TopologyView | false> => {
  try {
    const resp = await v2.get<TopologyViewDTO>(`${viewsEndpoint}/${id}`)
    return fromDto(resp.data)
  } catch (err) {
    return false
  }
}

/**
 * Create (POST) or update (PUT) a view. The server replies 201 with a
 * Location header on create and 204 with no body on update, so in both
 * cases the saved document is re-fetched to return the canonical record
 * (server-assigned id, owner, timestamps).
 */
const saveView = async (view: TopologyView): Promise<TopologyView | false> => {
  try {
    if (view.id) {
      await v2.put(`${viewsEndpoint}/${view.id}`, toDto(view))
      return await getView(view.id)
    }
    const resp = await v2.post(viewsEndpoint, toDto(view))
    const location: string | undefined = resp.headers?.location
    const newId = location ? location.substring(location.lastIndexOf('/') + 1) : undefined
    if (!newId) return false
    return await getView(newId)
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
