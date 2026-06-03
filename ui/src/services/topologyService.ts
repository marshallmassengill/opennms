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
import { getNodes } from './nodeService'
import type { TopologyView, TopologyViewSummary } from '@/types/topology'
import type { NodeApiResponse, QueryParameters } from '@/types'
import { aggregateNodeSeverities } from '@/components/Topology/severity'

/**
 * Palette node source: the real OpenNMS node inventory from
 * /api/v2/nodes. Returns the same NodeApiResponse the palette already
 * consumes (or `false` on error). A default page size is applied when the
 * caller doesn't specify one.
 */
const fetchPaletteNodes = async (
  queryParameters?: QueryParameters
): Promise<NodeApiResponse | false> => {
  return getNodes({ limit: 200, ...queryParameters })
}

/**
 * Current alarm status for a set of nodes, as a map of node id -> highest
 * severity. Used to color placed canvas nodes. Returns an empty map (never
 * `false`) so a status refresh failure leaves the canvas uncolored rather
 * than tearing down the view. Node ids match the real OnmsNode ids carried
 * by placed palette nodes; a hand-composed view holds few, so a single
 * FIQL "node.id==a,node.id==b" query covers them.
 */
const alarmsEndpoint = '/alarms'

const getNodeSeverities = async (nodeIds: number[]): Promise<Record<number, string>> => {
  if (nodeIds.length === 0) return {}
  const fiql = nodeIds.map((id) => `node.id==${id}`).join(',')
  try {
    const resp = await v2.get<{ alarm?: Array<{ nodeId?: number; severity?: string }> }>(
      alarmsEndpoint,
      { params: { _s: fiql, limit: 1000 } }
    )
    if (resp.status === 204 || !resp.data) return {}
    return aggregateNodeSeverities(resp.data.alarm ?? [])
  } catch (err) {
    return {}
  }
}

/**
 * Topology views catalog, backed by the /api/v2/topology/views REST
 * resource. Calls return typed data or `false`, matching the convention
 * used by the other services in this directory.
 *
 * The server stores the canvas as an opaque JSON document under a
 * `definition` field, with the catalog metadata (name, owner, timestamps)
 * as siblings. The front-end model is flat -- nodes, edges, labels, and
 * viewport live at the top of TopologyView -- so this service maps between
 * the two shapes. Ids are integers on the wire and strings in the UI.
 *
 * Access control is the standard /api/v2 RBAC (any authenticated user can
 * read; ROLE_REST or ROLE_ADMIN can write); the catalog is shared, so there
 * is no per-view role field.
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
  owner?: string
  created?: number
  lastModified?: number
}

const toDto = (view: TopologyView): TopologyViewDTO => ({
  name: view.name,
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
      name: dto.name
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
  getNodeSeverities,
  listViews,
  getView,
  saveView,
  deleteView
}
