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

import { describe, it, expect } from 'vitest'
import { layoutDiscoveredGraph } from '@/components/Topology/layout'
import type { CanvasLink, CanvasNode } from '@/types/topology'

const node = (id: string): CanvasNode => ({ id, label: id, x: 0, y: 0 })
const edge = (s: string, t: string): CanvasLink => ({ id: `${s}-${t}`, sourceId: s, targetId: t, origin: 'discovered' })

describe('layoutDiscoveredGraph', () => {
  it('returns an empty array for no nodes', () => {
    expect(layoutDiscoveredGraph([], [])).toEqual([])
  })

  it('assigns finite, spread-out positions to every node', () => {
    const nodes = ['a', 'b', 'c', 'd', 'e'].map(node)
    const edges = [edge('a', 'b'), edge('a', 'c'), edge('a', 'd'), edge('a', 'e')]
    const out = layoutDiscoveredGraph(nodes, edges, { ticks: 100 })

    expect(out).toHaveLength(nodes.length)
    for (const n of out) {
      expect(Number.isFinite(n.x)).toBe(true)
      expect(Number.isFinite(n.y)).toBe(true)
    }
    // Not all stacked at the origin -- the layout produced spread.
    const distinct = new Set(out.map((n) => `${Math.round(n.x)},${Math.round(n.y)}`))
    expect(distinct.size).toBe(nodes.length)
  })

  it('preserves node identity and other fields, only setting x/y', () => {
    const nodes: CanvasNode[] = [{ id: 'placed-1', nodeId: 1, label: 'core', x: 0, y: 0, icon: 'linkd.system' }]
    const out = layoutDiscoveredGraph(nodes, [])
    expect(out[0].id).toBe('placed-1')
    expect(out[0].nodeId).toBe(1)
    expect(out[0].label).toBe('core')
    expect(out[0].icon).toBe('linkd.system')
  })

  it('does not mutate the input nodes', () => {
    const nodes = [node('a'), node('b')]
    layoutDiscoveredGraph(nodes, [edge('a', 'b')])
    expect(nodes.every((n) => n.x === 0 && n.y === 0)).toBe(true)
  })

  it('ignores edges that reference unknown nodes', () => {
    const out = layoutDiscoveredGraph([node('a')], [edge('a', 'ghost')])
    expect(out).toHaveLength(1)
    expect(Number.isFinite(out[0].x)).toBe(true)
  })
})
