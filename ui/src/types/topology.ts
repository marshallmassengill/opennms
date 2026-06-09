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

/**
 * A node placed on the topology canvas. May reference a real OpenNMS node
 * by id; if it doesn't, it is a free-standing canvas node (label-only or
 * decorative). x/y are canvas coordinates owned by the view document.
 */
export interface CanvasNode {
  id: string
  nodeId?: number
  label: string
  x: number
  y: number
  icon?: string
  color?: string
}

/**
 * An edge between two CanvasNodes. The `origin` field is reserved for
 * Phase 2 (assisted composition): edges drawn by the user vs. surfaced
 * from discovered topology data.
 */
export interface CanvasLink {
  id: string
  sourceId: string
  targetId: string
  label?: string
  style?: Record<string, unknown>
  origin: 'user' | 'discovered'
}

/**
 * A free-standing text annotation placed directly on the canvas (not
 * attached to any node). Lives on the DOM overlay layer.
 */
export interface CanvasLabel {
  id: string
  text: string
  x: number
  y: number
  fontSize?: number
  color?: string
}

/**
 * Scaffolded for a roadmap item (NMS-7504): background images, racks,
 * floor plans. Only `type: 'none'` is supported in the MVP.
 */
export interface TopologyViewBackground {
  type: 'none' | 'image'
  ref?: string
}

/**
 * A complete custom topology view. This is the unit that the views
 * catalog REST resource will persist.
 */
export interface TopologyView {
  id?: string
  name: string
  nodes: CanvasNode[]
  links: CanvasLink[]
  labels: CanvasLabel[]
  viewport: {
    zoom: number
    panX: number
    panY: number
  }
  background?: TopologyViewBackground
}

/**
 * Lightweight catalog entry used by ViewManager (list/rename/delete).
 */
export interface TopologyViewSummary {
  id: string
  name: string
}

/**
 * The discovery protocol a link was learned from. Phase 2 (assisted
 * composition) treats all of these uniformly; the value is kept so the UI
 * can label/tooltip a discovered link by how it was found.
 */
export type DiscoveredLinkType = 'lldp' | 'cdp' | 'ospf' | 'isis' | 'bridge'

/**
 * A discovered neighbor of a node, normalized from the per-protocol link
 * lists returned by /api/v2/enlinkd/{nodeId}. The neighbor's node id is the
 * key the canvas needs to place it or match a ghost link; `label` and the
 * optional port fields are for display. Phase 2 uses these for the neighbor
 * tray and ghost-edge link hints.
 */
export interface DiscoveredNeighbor {
  neighborNodeId: number
  neighborLabel: string
  linkType: DiscoveredLinkType
  localPort?: string
  remotePort?: string
}

/**
 * Identifies a discovered (auto-generated) topology to load from the Graph
 * REST API: a container plus a namespace. E.g. enlinkd L2 is
 * { container: 'enlinkd', namespace: 'nodes:Layer2' }. This is the "view
 * source" dimension that sits above the custom Edit/View modes; new providers
 * (BSM, VMware, GraphML) are just other container/namespace pairs.
 */
export interface DiscoveredGraphSource {
  container: string
  namespace: string
}

/**
 * A discovered topology graph, normalized into the canvas model. Unlike a
 * custom view, the structure is read-only (it comes from discovery) and the
 * Graph API returns no meaningful positions (x/y = 0), so the front-end
 * auto-lays-out the nodes. Node `nodeId` carries the real OnmsNode id (for
 * status coloring + inspector); edges are all origin:'discovered'.
 */
export interface DiscoveredGraph {
  source: DiscoveredGraphSource
  label: string
  nodes: CanvasNode[]
  links: CanvasLink[]
}
