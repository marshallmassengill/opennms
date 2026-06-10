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
 * API.
 *
 * Discovered sources are grouped (Layer 2, Layer 3, …) and each carries a list
 * of **variants** — different representations of the same data (e.g. the
 * combined map vs. a single protocol like LLDP, or OSPF adjacencies vs. OSPF
 * areas). The group is the route param (`/topology/:source`); the chosen
 * variant is a `?variant=<key>` query param, so both stay bookmarkable and the
 * menu stays short. variants[0] is the default (used when no ?variant is set).
 *
 * Adding a provider (BSM, VMware, GraphML, …) is just another group here — the
 * load path is provider-agnostic (it's the same Graph REST API).
 */
export interface SourceVariant {
  key: string
  label: string
  namespace: string
}

export interface TopologySourceOption {
  slug: string
  label: string
  kind: 'custom' | 'discovered'
  /** Graph REST API container; present for discovered sources. */
  container?: string
  /** Representations of this source; variants[0] is the default. */
  variants?: SourceVariant[]
  /**
   * Auto-layout suited to the data's shape: 'force' (default) for mesh-like
   * graphs, 'hierarchy' for rooted parent-child trees.
   */
  layout?: 'force' | 'hierarchy'
}

export const CUSTOM_SOURCE_SLUG = 'custom'

const enlinkdGroup = (
  slug: string,
  label: string,
  variants: SourceVariant[]
): TopologySourceOption => ({ slug, label, kind: 'discovered', container: 'enlinkd', variants })

export const TOPOLOGY_SOURCES: TopologySourceOption[] = [
  { slug: CUSTOM_SOURCE_SLUG, label: 'Custom', kind: 'custom' },
  enlinkdGroup('layer2', 'Layer 2', [
    { key: 'combined', label: 'Combined (LLDP + CDP)', namespace: 'nodes:Layer2' },
    { key: 'lldp', label: 'LLDP', namespace: 'nodes:Lldp' },
    { key: 'cdp', label: 'CDP', namespace: 'nodes:Cdp' },
    { key: 'bridge', label: 'Bridge', namespace: 'nodes:Bridge' }
  ]),
  enlinkdGroup('layer3', 'Layer 3', [
    { key: 'combined', label: 'Combined (OSPF + IS-IS)', namespace: 'nodes:Layer3' },
    { key: 'ospf', label: 'OSPF — adjacencies', namespace: 'nodes:Ospf' },
    { key: 'ospf-area', label: 'OSPF — by area', namespace: 'nodes:OspfArea' },
    { key: 'isis', label: 'IS-IS', namespace: 'nodes:Isis' },
    { key: 'routers', label: 'Routers & Subnets', namespace: 'nodes:NetworkRouter' }
  ]),
  {
    // The node parent / critical-path hierarchy (nodeParentID). A rooted
    // forest, so it lays out as top-down tiers rather than force-directed.
    slug: 'pathoutage',
    label: 'Path Outage',
    kind: 'discovered',
    container: 'pathoutage',
    layout: 'hierarchy',
    variants: [{ key: 'default', label: 'Path Outage', namespace: 'pathoutage' }]
  }
]

export const sourceForSlug = (slug: string | undefined): TopologySourceOption | undefined =>
  TOPOLOGY_SOURCES.find(s => s.slug === slug)

export const isDiscoveredSlug = (slug: string | undefined): boolean =>
  sourceForSlug(slug)?.kind === 'discovered'

/**
 * Resolve a (source, variant key) pair to the variant to display. Falls back
 * to the source's default variant when the key is missing or unknown, so a
 * bare `/topology/layer2` or a stale `?variant=` still lands somewhere valid.
 */
export const variantForKey = (
  source: TopologySourceOption | undefined,
  key: string | undefined
): SourceVariant | undefined => {
  if (!source?.variants?.length) {
    return undefined
  }
  return source.variants.find(v => v.key === key) ?? source.variants[0]
}

/** The Graph REST API graph source for a discovered (source, variant key). */
export const graphSourceFor = (
  source: TopologySourceOption | undefined,
  key: string | undefined
): DiscoveredGraphSource | undefined => {
  const variant = variantForKey(source, key)
  if (!source?.container || !variant) {
    return undefined
  }
  return { container: source.container, namespace: variant.namespace, layout: source.layout }
}
