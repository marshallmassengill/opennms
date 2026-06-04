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
  isDiscoveredSlug
} from '@/components/Topology/sources'

describe('topology sources registry', () => {
  it('has unique slugs', () => {
    const slugs = TOPOLOGY_SOURCES.map((s) => s.slug)
    expect(new Set(slugs).size).toBe(slugs.length)
  })

  it('every discovered source carries a Graph API container + namespace; custom does not', () => {
    for (const s of TOPOLOGY_SOURCES) {
      if (s.kind === 'discovered') {
        expect(s.graph?.container).toBeTruthy()
        expect(s.graph?.namespace).toBeTruthy()
      } else {
        expect(s.graph).toBeUndefined()
      }
    }
  })

  it('includes the custom source and the enlinkd Layer 2 map', () => {
    expect(sourceForSlug(CUSTOM_SOURCE_SLUG)?.kind).toBe('custom')
    expect(sourceForSlug('enlinkd-l2')?.graph).toEqual({ container: 'enlinkd', namespace: 'nodes:Layer2' })
  })

  it('classifies slugs as discovered or not', () => {
    expect(isDiscoveredSlug('enlinkd-l2')).toBe(true)
    expect(isDiscoveredSlug(CUSTOM_SOURCE_SLUG)).toBe(false)
    expect(isDiscoveredSlug('nonexistent')).toBe(false)
    expect(isDiscoveredSlug(undefined)).toBe(false)
  })

  it('covers the enlinkd namespaces exposed by the Graph API', () => {
    const namespaces = TOPOLOGY_SOURCES.filter((s) => s.kind === 'discovered').map((s) => s.graph!.namespace)
    for (const ns of ['nodes:Layer2', 'nodes:Layer3', 'nodes:Lldp', 'nodes:Cdp', 'nodes:Ospf', 'nodes:OspfArea', 'nodes:Isis', 'nodes:Bridge', 'nodes:NetworkRouter']) {
      expect(namespaces).toContain(ns)
    }
  })
})
