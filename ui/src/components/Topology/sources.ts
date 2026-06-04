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

import type { DiscoveredGraphSource } from '@/types/topology'

/**
 * The "view source" dimension that sits above the Edit/View mode: what am I
 * looking at? `custom` is the hand-composed catalog; the others are
 * discovered (auto-generated, read-only) topologies served by the Graph REST
 * API. The `slug` is the route param (`/topology/:source`) and the selector
 * value, so routing, the source selector, and loading all stay in lockstep.
 *
 * Adding a provider (BSM, VMware, GraphML, …) is just another entry here --
 * the load path is provider-agnostic (it's the same Graph REST API).
 */
export interface TopologySourceOption {
  slug: string
  label: string
  kind: 'custom' | 'discovered'
  /** Graph REST API container/namespace; present for discovered sources. */
  graph?: DiscoveredGraphSource
}

export const CUSTOM_SOURCE_SLUG = 'custom'

export const TOPOLOGY_SOURCES: TopologySourceOption[] = [
  { slug: CUSTOM_SOURCE_SLUG, label: 'Custom', kind: 'custom' },
  {
    slug: 'enlinkd-l2',
    label: 'Discovered · Layer 2',
    kind: 'discovered',
    graph: { container: 'enlinkd', namespace: 'nodes:Layer2' }
  },
  {
    slug: 'enlinkd-l3',
    label: 'Discovered · Layer 3',
    kind: 'discovered',
    graph: { container: 'enlinkd', namespace: 'nodes:Layer3' }
  },
  {
    slug: 'enlinkd-lldp',
    label: 'Discovered · LLDP',
    kind: 'discovered',
    graph: { container: 'enlinkd', namespace: 'nodes:Lldp' }
  },
  {
    slug: 'enlinkd-cdp',
    label: 'Discovered · CDP',
    kind: 'discovered',
    graph: { container: 'enlinkd', namespace: 'nodes:Cdp' }
  }
]

export const sourceForSlug = (slug: string | undefined): TopologySourceOption | undefined =>
  TOPOLOGY_SOURCES.find((s) => s.slug === slug)

export const isDiscoveredSlug = (slug: string | undefined): boolean =>
  sourceForSlug(slug)?.kind === 'discovered'
