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
import { placedIdFor } from '@/components/Topology/nodeIds'
import type {
  CanvasLink,
  CanvasNode,
  DiscoveredGraph,
  DiscoveredGraphSource,
  DiscoveredLinkType,
  DiscoveredNeighbor,
  TopologyView,
  TopologyViewSummary
} from '@/types/topology'
import type { NodeApiResponse, QueryParameters } from '@/types'
import { aggregateNodeSeverities } from '@/components/Topology/severity'
import { deviceIconForSysObjectId, type DeviceIconId } from '@/components/Topology/deviceIcons'

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
  links: TopologyView['links']
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
    links: view.links,
    labels: view.labels,
    viewport: view.viewport,
    background: view.background
  }
})

const fromDto = (dto: TopologyViewDTO): TopologyView => ({
  id: dto.id != null ? String(dto.id) : undefined,
  name: dto.name,
  nodes: dto.definition?.nodes ?? [],
  links: dto.definition?.links ?? [],
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

/**
 * Discovered-topology neighbors for a node, from /api/v2/enlinkd/{nodeId}.
 *
 * That endpoint returns one array per discovery protocol (LLDP, CDP, OSPF,
 * IS-IS, bridge), each with protocol-specific field names. Phase 2 (assisted
 * composition) only needs, per neighbor: which node it is, a display label,
 * and how the link was found -- so this flattens all the protocol lists into
 * one normalized DiscoveredNeighbor[].
 *
 * The remote node id is not a first-class field on the wire; it is embedded
 * in the `...Url` fields (e.g. "element/linkednode.jsp?node=123"), so it is
 * parsed out of whichever Url field carries it. Links whose remote node can't
 * be resolved to an id -- or that point back at the node itself -- are
 * dropped (they can't be placed or matched on the canvas), and a neighbor
 * reached over several ports collapses to a single entry per protocol.
 */
const enlinkdEndpoint = 'enlinkd'

const PROTOCOL_LINK_FIELDS: Array<{ field: string; type: DiscoveredLinkType }> = [
  { field: 'lldpLinkNodes', type: 'lldp' },
  { field: 'cdpLinkNodes', type: 'cdp' },
  { field: 'ospfLinkNodes', type: 'ospf' },
  { field: 'isisLinkNodes', type: 'isis' },
  { field: 'bridgeLinkNodes', type: 'bridge' }
]

const NODE_URL_RE = /node=(\d+)/
// Field-name fragments (lowercased) that tend to carry a human-readable
// remote node name across the various protocol DTOs.
const REMOTE_LABEL_HINTS = ['reminfo', 'remsysname', 'cachedeviceid', 'remrouterid', 'neighsysid']
const LOCAL_PORT_HINT = 'localport'
const REMOTE_PORT_HINT = 'remport'

const firstStringField = (
  link: Record<string, unknown>,
  predicate: (lowerKey: string, value: string) => boolean
): string | undefined => {
  for (const [key, value] of Object.entries(link)) {
    if (typeof value === 'string' && value && predicate(key.toLowerCase(), value)) {
      return value
    }
  }
  return undefined
}

const parseNeighborNodeId = (link: Record<string, unknown>): number | undefined => {
  const urlValue = firstStringField(link, (k, v) => k.includes('url') && NODE_URL_RE.test(v))
  if (!urlValue) return undefined
  const match = NODE_URL_RE.exec(urlValue)
  return match ? Number(match[1]) : undefined
}

/**
 * Pure transform of an enlinkd response into normalized neighbors. Exported
 * so it can be unit-tested against captured payloads without HTTP.
 */
const parseEnlinkdNeighbors = (
  data: Record<string, unknown> | null | undefined,
  nodeId: number
): DiscoveredNeighbor[] => {
  if (!data) return []
  const neighbors: DiscoveredNeighbor[] = []
  const seen = new Set<number>()
  for (const { field, type } of PROTOCOL_LINK_FIELDS) {
    const links = data[field]
    if (!Array.isArray(links)) continue
    for (const raw of links) {
      if (!raw || typeof raw !== 'object') continue
      const link = raw as Record<string, unknown>
      const neighborNodeId = parseNeighborNodeId(link)
      if (neighborNodeId == null || neighborNodeId === nodeId || seen.has(neighborNodeId)) continue
      seen.add(neighborNodeId)
      neighbors.push({
        neighborNodeId,
        neighborLabel:
          firstStringField(link, (k) => REMOTE_LABEL_HINTS.some((h) => k.includes(h))) ??
          `Node ${neighborNodeId}`,
        linkType: type,
        localPort: firstStringField(link, (k) => k.includes(LOCAL_PORT_HINT)),
        remotePort: firstStringField(link, (k) => k.includes(REMOTE_PORT_HINT))
      })
    }
  }
  return neighbors
}

const getNodeNeighbors = async (nodeId: number): Promise<DiscoveredNeighbor[]> => {
  try {
    const resp = await v2.get<Record<string, unknown>>(`${enlinkdEndpoint}/${nodeId}`)
    return parseEnlinkdNeighbors(resp.data, nodeId)
  } catch (err) {
    return []
  }
}

/**
 * Discovered (auto-generated) topology graph from the Graph REST API
 * /api/v2/graphs/{container}/{namespace} (e.g. enlinkd L2 =
 * enlinkd/nodes:Layer2). This is the source for the *discovered* view type;
 * it's provider-agnostic, so BSM/VMware/GraphML are just other
 * container/namespace pairs.
 *
 * The wire shape: a vertex carries `id` (vertex id, = node id for node
 * vertices), `label`, `nodeID` (the real OnmsNode id), `iconKey`, and x/y that
 * are always "0" (no stored layout -- the front-end auto-lays-out). An edge
 * carries `id` plus `source`/`target` refs whose `id` is a vertex id. We map
 * vertices -> CanvasNode and edges -> CanvasLink (origin:'discovered'),
 * dropping any edge whose endpoints aren't present as vertices.
 */
const graphsEndpoint = 'graphs'

interface GraphApiVertex {
  id: string
  label?: string
  nodeID?: string
  iconKey?: string
  tooltipText?: string
}

interface GraphApiEdgeRef {
  namespace?: string
  id: string
}

interface GraphApiEdge {
  id: string
  label?: string
  source?: GraphApiEdgeRef
  target?: GraphApiEdgeRef
}

interface GraphApiResponse {
  vertices?: GraphApiVertex[]
  edges?: GraphApiEdge[]
  label?: string
  namespace?: string
}

/**
 * Canvas id for a discovered vertex. When the vertex maps to a real OnmsNode
 * we reuse the custom-view `placed-<nodeId>` convention, so discovered nodes
 * inherit severity coloring and the inspector's node detail for free. A vertex
 * without a numeric node id (e.g. a group/category vertex from another
 * provider) falls back to a `disc-` prefix so it can't collide or be mistaken
 * for a node.
 */
const discoveredNodeCanvasId = (vertex: GraphApiVertex): string =>
  vertex.nodeID != null && /^\d+$/.test(vertex.nodeID)
    ? placedIdFor(vertex.nodeID)
    : `disc-${vertex.id}`

/**
 * Pure transform of a Graph REST API response into a normalized
 * DiscoveredGraph. Exported for unit testing against captured payloads.
 * Positions are zeroed -- the caller auto-lays-out before rendering.
 */
const mapDiscoveredGraph = (
  data: GraphApiResponse,
  source: DiscoveredGraphSource
): DiscoveredGraph => {
  const vertices = data.vertices ?? []
  // vertex id (the id edges reference) -> canvas node id
  const canvasIdByVertexId = new Map(vertices.map((v) => [v.id, discoveredNodeCanvasId(v)]))
  const nodes: CanvasNode[] = vertices.map((v) => ({
    id: canvasIdByVertexId.get(v.id) as string,
    nodeId: v.nodeID != null && /^\d+$/.test(v.nodeID) ? Number(v.nodeID) : undefined,
    label: v.label ?? v.id,
    x: 0,
    y: 0,
    icon: v.iconKey
  }))
  const links: CanvasLink[] = (data.edges ?? [])
    .filter(
      (e) =>
        e.source &&
        e.target &&
        canvasIdByVertexId.has(e.source.id) &&
        canvasIdByVertexId.has(e.target.id)
    )
    .map((e) => ({
      id: e.id,
      sourceId: canvasIdByVertexId.get(e.source!.id) as string,
      targetId: canvasIdByVertexId.get(e.target!.id) as string,
      origin: 'discovered' as const
    }))
  return { source, label: data.label ?? source.namespace, nodes, links }
}

const loadDiscoveredGraph = async (
  source: DiscoveredGraphSource
): Promise<DiscoveredGraph | false> => {
  try {
    const resp = await v2.get<GraphApiResponse>(
      `${graphsEndpoint}/${source.container}/${source.namespace}`
    )
    if (!resp.data) return false
    return mapDiscoveredGraph(resp.data, source)
  } catch (err) {
    return false
  }
}

/**
 * One operator-configured info-panel item for a node: a titled HTML fragment
 * rendered server-side from an etc/infopanel Jinjava template. The HTML must be
 * sanitized before rendering (see the Inspector).
 */
export interface NodeInfoPanelItem {
  title: string
  order: number
  html: string
}

const infopanelEndpoint = 'topology/infopanel'

/**
 * Fetch the rendered info-panel items for a node (sorted by order server-side).
 * Returns [] on any error or when the install has no etc/infopanel templates --
 * the Inspector simply shows nothing extra.
 */
/**
 * Device-icon ids for a set of nodes, keyed by node id. Resolves each node's
 * sysObjectId to a device type the way the legacy map does (see deviceIcons).
 * Only recognized device types are returned -- unresolved nodes are omitted so
 * the canvas leaves them as plain circles. Mirrors getNodeSeverities' bulk FIQL
 * lookup; returns {} on error.
 */
const getNodeIconIds = async (nodeIds: number[]): Promise<Record<number, DeviceIconId>> => {
  if (nodeIds.length === 0) return {}
  // The /nodes endpoint filters on `id` (the /alarms endpoint uses `node.id`).
  const fiql = nodeIds.map((id) => `id==${id}`).join(',')
  try {
    const resp = await getNodes({ _s: fiql, limit: 1000 })
    if (!resp || !resp.node) return {}
    const out: Record<number, DeviceIconId> = {}
    for (const n of resp.node) {
      const icon = deviceIconForSysObjectId(n.sysObjectId)
      const id = Number(n.id)
      if (icon && Number.isFinite(id)) out[id] = icon
    }
    return out
  } catch (err) {
    return {}
  }
}

const getNodeInfoPanel = async (nodeId: number): Promise<NodeInfoPanelItem[]> => {
  try {
    const resp = await v2.get<NodeInfoPanelItem[]>(infopanelEndpoint, { params: { nodeId } })
    return Array.isArray(resp.data) ? resp.data : []
  } catch (err) {
    return []
  }
}

export {
  fetchPaletteNodes,
  getNodeSeverities,
  listViews,
  getView,
  saveView,
  deleteView,
  getNodeNeighbors,
  parseEnlinkdNeighbors,
  loadDiscoveredGraph,
  mapDiscoveredGraph,
  getNodeInfoPanel,
  getNodeIconIds
}
