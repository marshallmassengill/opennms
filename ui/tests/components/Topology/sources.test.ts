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
import {
  buildSources,
  CUSTOM_SOURCE_SLUG,
  sourceForSlug,
  isDiscoveredSlug,
  variantForKey,
  graphSourceFor
} from '@/components/Topology/sources'
import type { GraphContainerMeta } from '@/services/topologyService'

const graph = (namespace: string, label: string) => ({ namespace, label })

/**
 * The five containers a stock 36.x instance reports from GET /api/v2/graphs,
 * labels included, so the derived entries are asserted against real strings
 * rather than invented ones.
 */
const LIVE_CONTAINERS: GraphContainerMeta[] = [
  { id: 'application', label: 'Application Graph', graphs: [graph('application', 'Application Graph')] },
  { id: 'bsm', label: 'Business Service Graph', graphs: [graph('bsm', 'Business Service Graph')] },
  {
    id: 'enlinkd',
    label: 'Enlinkd Graphs',
    graphs: [
      graph('nodes', 'All'),
      graph('nodes:Bridge', 'Bridge'),
      graph('nodes:Cdp', 'Cdp'),
      graph('nodes:Isis', 'Isis'),
      graph('nodes:Layer2', 'Layer2'),
      graph('nodes:Layer3', 'Layer3'),
      graph('nodes:Lldp', 'Lldp'),
      graph('nodes:NetworkRouter', 'NetworkRouter'),
      graph('nodes:Ospf', 'Ospf'),
      graph('nodes:OspfArea', 'OspfArea'),
      graph('nodes:UserDefined', 'UserDefined')
    ]
  },
  { id: 'pathoutage', label: 'Path Outage', graphs: [graph('pathoutage', 'Path Outage')] },
  { id: 'vmware', label: 'VMware Topology Provider', graphs: [graph('vmware', 'VMware Topology Provider')] }
]

// The offline fallback, which is also the curated table as declared.
const curated = buildSources([])

describe('buildSources', () => {
  it('falls back to the curated groups when nothing is known yet', () => {
    expect(curated.map(s => s.slug)).toEqual([CUSTOM_SOURCE_SLUG, 'layer2', 'layer3', 'pathoutage'])
  })

  it('keeps curated groups first and derives the rest, sorted by label', () => {
    expect(buildSources(LIVE_CONTAINERS).map(s => s.slug)).toEqual([
      CUSTOM_SOURCE_SLUG,
      'layer2',
      'layer3',
      'pathoutage',
      // Derived, alphabetical by the label the server reports.
      'application',
      'bsm',
      'enlinkd',
      'vmware'
    ])
  })

  it('labels derived sources from the server, not from the container id', () => {
    const sources = buildSources(LIVE_CONTAINERS)
    expect(sourceForSlug(sources, 'bsm')?.label).toBe('Business Service Graph')
    expect(sourceForSlug(sources, 'vmware')?.label).toBe('VMware Topology Provider')
  })

  // The point of deriving: a namespace no curated group claims is still
  // reachable, so one added by a future release cannot go silently missing.
  it('surfaces the enlinkd namespaces the curated groups do not claim', () => {
    const leftovers = sourceForSlug(buildSources(LIVE_CONTAINERS), 'enlinkd')
    expect(leftovers?.variants).toEqual([
      { key: 'nodes', label: 'All', namespace: 'nodes' },
      { key: 'userdefined', label: 'UserDefined', namespace: 'nodes:UserDefined' }
    ])
  })

  it('picks up a container it has never heard of', () => {
    const sources = buildSources([
      ...LIVE_CONTAINERS,
      {
        id: 'graphml:acme-sites',
        label: 'Acme Sites',
        graphs: [graph('acme:region', 'Regions'), graph('acme:site', 'Sites')]
      }
    ])
    const graphml = sourceForSlug(sources, 'graphml-acme-sites')
    expect(graphml?.label).toBe('Acme Sites')
    expect(graphml?.container).toBe('graphml:acme-sites')
    expect(graphml?.layout).toBe('force')
    expect(graphml?.variants?.map(v => v.key)).toEqual(['region', 'site'])
  })

  it('lays out business services and applications as hierarchies, others as force', () => {
    const sources = buildSources(LIVE_CONTAINERS)
    expect(sourceForSlug(sources, 'bsm')?.layout).toBe('hierarchy')
    expect(sourceForSlug(sources, 'application')?.layout).toBe('hierarchy')
    expect(sourceForSlug(sources, 'vmware')?.layout).toBe('force')
  })

  it('drops a curated group whose container is not installed', () => {
    const slugs = buildSources([LIVE_CONTAINERS[3]]).map(s => s.slug)
    expect(slugs).toEqual([CUSTOM_SOURCE_SLUG, 'pathoutage'])
  })

  it('reduces a curated group to the variants that exist', () => {
    const sources = buildSources([
      { id: 'enlinkd', label: 'Enlinkd Graphs', graphs: [graph('nodes:Lldp', 'Lldp')] }
    ])
    expect(sourceForSlug(sources, 'layer2')?.variants?.map(v => v.key)).toEqual(['lldp'])
    // Layer 3 had nothing left, so it is not offered at all.
    expect(sourceForSlug(sources, 'layer3')).toBeUndefined()
  })

  it('keeps slugs unique, since a slug addresses a route', () => {
    const sources = buildSources([
      // A container whose id collides with a curated slug.
      { id: 'layer2', label: 'Impostor', graphs: [graph('impostor', 'Impostor')] },
      ...LIVE_CONTAINERS
    ])
    const slugs = sources.map(s => s.slug)
    expect(new Set(slugs).size).toBe(slugs.length)
    expect(sourceForSlug(sources, 'layer2')?.label).toBe('Layer 2')
    expect(sources.find(s => s.label === 'Impostor')?.slug).toBe('layer2-2')
  })

  it('gives every discovered source a container and variants; custom has neither', () => {
    for (const s of buildSources(LIVE_CONTAINERS)) {
      if (s.kind === 'discovered') {
        expect(s.container).toBeTruthy()
        expect(s.variants?.length).toBeGreaterThan(0)
      } else {
        expect(s.container).toBeUndefined()
        expect(s.variants).toBeUndefined()
      }
    }
  })
})

describe('curated presentation', () => {
  it('path outage is a single-variant hierarchy-laid-out source', () => {
    const pathoutage = sourceForSlug(curated, 'pathoutage')!
    expect(pathoutage.kind).toBe('discovered')
    expect(pathoutage.container).toBe('pathoutage')
    expect(pathoutage.layout).toBe('hierarchy')
    // One variant -> the page renders no variant picker.
    expect(pathoutage.variants).toHaveLength(1)
    expect(pathoutage.variants![0].namespace).toBe('pathoutage')
  })

  it('Layer 2 / Layer 3 cover the expected enlinkd namespaces as variants', () => {
    const ns = (slug: string) => sourceForSlug(curated, slug)!.variants!.map(v => v.namespace)
    expect(ns('layer2')).toEqual(['nodes:Layer2', 'nodes:Lldp', 'nodes:Cdp', 'nodes:Bridge'])
    expect(ns('layer3')).toEqual([
      'nodes:Layer3',
      'nodes:Ospf',
      'nodes:OspfArea',
      'nodes:Isis',
      'nodes:NetworkRouter'
    ])
  })

  it('classifies slugs as discovered or not', () => {
    expect(isDiscoveredSlug(curated, 'layer2')).toBe(true)
    expect(isDiscoveredSlug(curated, CUSTOM_SOURCE_SLUG)).toBe(false)
    expect(isDiscoveredSlug(curated, 'nonexistent')).toBe(false)
    expect(isDiscoveredSlug(curated, undefined)).toBe(false)
  })
})

describe('variantForKey', () => {
  const layer2 = sourceForSlug(curated, 'layer2')

  it('resolves a known variant', () => {
    expect(variantForKey(layer2, 'lldp')?.namespace).toBe('nodes:Lldp')
  })

  it('falls back to the default (variants[0]) for a missing/unknown key', () => {
    expect(variantForKey(layer2, undefined)?.namespace).toBe('nodes:Layer2')
    expect(variantForKey(layer2, 'bogus')?.namespace).toBe('nodes:Layer2')
  })

  it('returns undefined for a non-discovered source', () => {
    expect(variantForKey(sourceForSlug(curated, CUSTOM_SOURCE_SLUG), 'x')).toBeUndefined()
  })
})

describe('graphSourceFor', () => {
  it('builds the Graph API source for a (group, variant)', () => {
    expect(graphSourceFor(sourceForSlug(curated, 'layer3'), 'ospf-area')).toEqual({
      container: 'enlinkd',
      namespace: 'nodes:OspfArea'
    })
  })

  it('uses the default variant when the key is absent', () => {
    expect(graphSourceFor(sourceForSlug(curated, 'layer3'), undefined)).toEqual({
      container: 'enlinkd',
      namespace: 'nodes:Layer3'
    })
  })

  it('returns undefined for the custom source', () => {
    expect(graphSourceFor(sourceForSlug(curated, CUSTOM_SOURCE_SLUG), undefined)).toBeUndefined()
  })

  it('carries the source layout preference through (hierarchy for path outage)', () => {
    expect(graphSourceFor(sourceForSlug(curated, 'pathoutage'), undefined)).toEqual({
      container: 'pathoutage',
      namespace: 'pathoutage',
      layout: 'hierarchy'
    })
    expect(graphSourceFor(sourceForSlug(curated, 'layer2'), undefined)?.layout).toBeUndefined()
  })

  it('carries a derived source through too', () => {
    const sources = buildSources(LIVE_CONTAINERS)
    expect(graphSourceFor(sourceForSlug(sources, 'bsm'), undefined)).toEqual({
      container: 'bsm',
      namespace: 'bsm',
      layout: 'hierarchy'
    })
  })
})
