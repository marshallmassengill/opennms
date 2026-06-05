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
  TOPOLOGY_SOURCES,
  CUSTOM_SOURCE_SLUG,
  sourceForSlug,
  isDiscoveredSlug,
  variantForKey,
  graphSourceFor
} from '@/components/Topology/sources'

describe('topology sources registry', () => {
  it('has unique slugs and a short discovered menu (Custom + Layer 2 + Layer 3)', () => {
    const slugs = TOPOLOGY_SOURCES.map((s) => s.slug)
    expect(new Set(slugs).size).toBe(slugs.length)
    expect(slugs).toEqual([CUSTOM_SOURCE_SLUG, 'layer2', 'layer3'])
  })

  it('discovered groups carry a container + variants (variants[0] = default); custom does not', () => {
    for (const s of TOPOLOGY_SOURCES) {
      if (s.kind === 'discovered') {
        expect(s.container).toBe('enlinkd')
        expect(s.variants?.length).toBeGreaterThan(1)
        for (const v of s.variants!) expect(v.namespace).toMatch(/^nodes/)
      } else {
        expect(s.container).toBeUndefined()
        expect(s.variants).toBeUndefined()
      }
    }
  })

  it('Layer 2 / Layer 3 cover the expected enlinkd namespaces as variants', () => {
    const ns = (slug: string) => sourceForSlug(slug)!.variants!.map((v) => v.namespace)
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
    expect(isDiscoveredSlug('layer2')).toBe(true)
    expect(isDiscoveredSlug(CUSTOM_SOURCE_SLUG)).toBe(false)
    expect(isDiscoveredSlug('nonexistent')).toBe(false)
    expect(isDiscoveredSlug(undefined)).toBe(false)
  })

  describe('variantForKey', () => {
    const layer2 = sourceForSlug('layer2')

    it('resolves a known variant', () => {
      expect(variantForKey(layer2, 'lldp')?.namespace).toBe('nodes:Lldp')
    })

    it('falls back to the default (variants[0]) for a missing/unknown key', () => {
      expect(variantForKey(layer2, undefined)?.namespace).toBe('nodes:Layer2')
      expect(variantForKey(layer2, 'bogus')?.namespace).toBe('nodes:Layer2')
    })

    it('returns undefined for a non-discovered source', () => {
      expect(variantForKey(sourceForSlug(CUSTOM_SOURCE_SLUG), 'x')).toBeUndefined()
    })
  })

  describe('graphSourceFor', () => {
    it('builds the Graph API source for a (group, variant)', () => {
      expect(graphSourceFor(sourceForSlug('layer3'), 'ospf-area')).toEqual({
        container: 'enlinkd',
        namespace: 'nodes:OspfArea'
      })
    })

    it('uses the default variant when the key is absent', () => {
      expect(graphSourceFor(sourceForSlug('layer3'), undefined)).toEqual({
        container: 'enlinkd',
        namespace: 'nodes:Layer3'
      })
    })

    it('returns undefined for the custom source', () => {
      expect(graphSourceFor(sourceForSlug(CUSTOM_SOURCE_SLUG), undefined)).toBeUndefined()
    })
  })
})
