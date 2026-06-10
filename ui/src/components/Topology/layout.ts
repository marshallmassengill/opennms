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

import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, stratify, tree } from 'd3'
import type { SimulationLinkDatum, SimulationNodeDatum } from 'd3'
import type { CanvasLink, CanvasNode } from '@/types/topology'

/**
 * Auto-layout for discovered topologies. Unlike custom views, a discovered
 * graph carries no stored positions (the Graph REST API returns x/y = 0), so
 * we compute them with a d3-force simulation: nodes repel, linked nodes are
 * pulled together, and a centering force keeps the result around the origin
 * (sigma's camera then fits it).
 *
 * The simulation is run synchronously for a fixed number of ticks and stopped
 * -- we only want the final positions, not an animated layout. d3-force seeds
 * unset positions on a deterministic phyllotaxis spiral, so for a given graph
 * the layout is stable across runs (good for tests and for not having nodes
 * jump around on reload).
 */

interface SimNode extends SimulationNodeDatum {
  id: string
}
type SimLink = SimulationLinkDatum<SimNode>

interface LayoutOptions {
  /** Target distance between linked nodes. */
  linkDistance?: number
  /** Repulsion strength (more negative = more spread). */
  chargeStrength?: number
  /** Minimum separation so node glyphs don't overlap. */
  collideRadius?: number
  /** Simulation ticks to run before reading positions. */
  ticks?: number
}

const DEFAULTS: Required<LayoutOptions> = {
  linkDistance: 100,
  chargeStrength: -280,
  collideRadius: 30,
  ticks: 400
}

/**
 * Return a new node array with computed x/y positions. Input nodes are not
 * mutated; edges are read-only. Nodes referenced only by id in edges that
 * don't exist as nodes are ignored by the link force (we pass the node set as
 * the source of truth).
 */
export const layoutDiscoveredGraph = (
  nodes: CanvasNode[],
  links: CanvasLink[],
  options: LayoutOptions = {}
): CanvasNode[] => {
  const opts = { ...DEFAULTS, ...options }
  if (nodes.length === 0) {
    return []
  }

  const simNodes: SimNode[] = nodes.map(n => ({ id: n.id }))
  const ids = new Set(simNodes.map(n => n.id))
  const simLinks: SimLink[] = links
    .filter(e => ids.has(e.sourceId) && ids.has(e.targetId))
    .map(e => ({ source: e.sourceId, target: e.targetId }))

  const simulation = forceSimulation<SimNode>(simNodes)
    .force(
      'link',
      forceLink<SimNode, SimLink>(simLinks)
        .id(d => d.id)
        .distance(opts.linkDistance)
    )
    .force('charge', forceManyBody<SimNode>().strength(opts.chargeStrength))
    .force('center', forceCenter(0, 0))
    // iterations > 1 enforces non-overlap more strictly (important for dense
    // discovered graphs with many leaf nodes at the larger node size).
    .force('collide', forceCollide<SimNode>(opts.collideRadius).iterations(3))
    .stop()

  simulation.tick(opts.ticks)

  const posById = new Map(simNodes.map(n => [n.id, n]))
  return nodes.map((n) => {
    const p = posById.get(n.id)
    return { ...n, x: p?.x ?? 0, y: p?.y ?? 0 }
  })
}

interface HierarchyLayoutOptions {
  /** Vertical distance between tiers (parent row to child row). */
  levelSpacing?: number
  /** Horizontal distance between adjacent siblings. */
  siblingSpacing?: number
}

const HIERARCHY_DEFAULTS: Required<HierarchyLayoutOptions> = {
  levelSpacing: 110,
  siblingSpacing: 70
}

/**
 * Tiered top-down layout for rooted parent-child data (e.g. the path-outage
 * node-parent hierarchy), where the point is to *see the tiers* -- force
 * layout would render the same data as an undifferentiated blob. Links are
 * read as parent (source) -> child (target). The data is usually a forest,
 * so all roots are hung under a synthetic super-root, laid out with d3's
 * tidy-tree algorithm, and the synthetic level is stripped back off.
 *
 * Defensive cases: a node with several incoming links keeps its first parent
 * (the data model -- one nodeParentID -- can't produce this; another provider
 * could). If the links don't form a tree at all (a cycle), d3 stratify throws
 * and we fall back to the force layout rather than render nothing.
 */
export const layoutHierarchyGraph = (
  nodes: CanvasNode[],
  links: CanvasLink[],
  options: HierarchyLayoutOptions = {}
): CanvasNode[] => {
  const opts = { ...HIERARCHY_DEFAULTS, ...options }
  if (nodes.length === 0) {
    return []
  }

  const ids = new Set(nodes.map(n => n.id))
  const parentById = new Map<string, string>()
  for (const link of links) {
    if (!ids.has(link.sourceId) || !ids.has(link.targetId)) {
      continue
    }
    if (link.sourceId === link.targetId) {
      continue
    }
    if (!parentById.has(link.targetId)) {
      parentById.set(link.targetId, link.sourceId)
    }
  }

  const SUPER_ROOT = '__hierarchy_root__'
  type Datum = { id: string; parentId?: string }
  const data: Datum[] = [
    { id: SUPER_ROOT },
    ...nodes.map(n => ({ id: n.id, parentId: parentById.get(n.id) ?? SUPER_ROOT }))
  ]

  let root
  try {
    root = stratify<Datum>()
      .id(d => d.id)
      .parentId(d => d.parentId)(data)
  } catch {
    return layoutDiscoveredGraph(nodes, links)
  }

  // nodeSize (not size) so spacing stays constant regardless of tree breadth;
  // the canvas camera fits whatever extent results.
  const laidOut = tree<Datum>().nodeSize([opts.siblingSpacing, opts.levelSpacing])(root)

  const posById = new Map<string, { x: number; y: number }>()
  laidOut.each((n) => {
    if (n.data.id === SUPER_ROOT) {
      return
    }
    // depth 1 is the real top tier; pull it up to y=0 (the synthetic root's
    // row). Negative per tier because sigma's graph y-axis points up -- deeper
    // tiers must sit at smaller y to render below their parent. `|| 0`
    // normalizes the top tier's -0 to 0.
    posById.set(n.data.id, { x: n.x, y: -(n.depth - 1) * opts.levelSpacing || 0 })
  })

  return nodes.map((n) => {
    const p = posById.get(n.id)
    return { ...n, x: p?.x ?? 0, y: p?.y ?? 0 }
  })
}
