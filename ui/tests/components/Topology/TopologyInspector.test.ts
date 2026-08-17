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

import { OnmsColorPicker } from '@opennms/onms-ui'
import { createTestingPinia } from '@pinia/testing'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TopologyInspector from '@/components/Topology/TopologyInspector.vue'
import { useTopologyStore } from '@/stores/topologyStore'

vi.mock('@/services/nodeService', () => ({ getNodeById: vi.fn().mockResolvedValue(null) }))
vi.mock('@/services/topologyService', () => ({
  listAssets: vi.fn().mockResolvedValue([]),
  assetUrl: vi.fn(),
  uploadAsset: vi.fn(),
  getNodeInfoPanel: vi.fn().mockResolvedValue([]),
  getEdgeInfoPanel: vi.fn().mockResolvedValue([]),
  getDiscoveredNeighbors: vi.fn().mockResolvedValue([])
}))

const shapeA = { id: 'shape-a', type: 'rect', x: 0, y: 0, w: 10, h: 10, stroke: '#aaaaaa', fill: '#eeeeee' }
const shapeB = { id: 'shape-b', type: 'rect', x: 20, y: 0, w: 10, h: 10, stroke: '#bbbbbb', fill: '#dddddd' }

const mountInspector = async () => {
  const wrapper = mount(TopologyInspector, {
    props: { canvas: null, variant: 'props' },
    global: { plugins: [PrimeVue, createTestingPinia({ stubActions: false })] }
  })
  const store = useTopologyStore()
  store.shapes = [{ ...shapeA }, { ...shapeB }] as never
  store.isEditMode = true as never
  store.selectedIds = [shapeA.id] as never
  await flushPromises()
  return { wrapper, store }
}

// Each color field is a .ti-field with its label, so the picker is located by
// the label rather than by position among the section's pickers.
const picker = (wrapper: Awaited<ReturnType<typeof mountInspector>>['wrapper'], label: string) => {
  const field = wrapper.findAll('.ti-field').find(f => f.text().includes(label))
  expect(field, `no "${label}" field rendered`).toBeTruthy()
  const found = field!.findComponent(OnmsColorPicker)
  expect(found.exists(), `no color picker under "${label}"`).toBe(true)
  return found
}

describe('TopologyInspector color pickers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the selected shape\'s current colors', async () => {
    const { wrapper } = await mountInspector()
    expect(picker(wrapper, 'Border color').props('modelValue')).toBe('#aaaaaa')
    expect(picker(wrapper, 'Fill color').props('modelValue')).toBe('#eeeeee')
  })

  it('writes the border color to the selected shape', async () => {
    const { wrapper, store } = await mountInspector()
    picker(wrapper, 'Border color').vm.$emit('update:modelValue', '#123456')
    await flushPromises()

    expect(store.getShape('shape-a')?.stroke).toBe('#123456')
    expect(store.getShape('shape-b')?.stroke).toBe('#bbbbbb')
  })

  it('writes the fill color to the selected shape', async () => {
    const { wrapper, store } = await mountInspector()
    picker(wrapper, 'Fill color').vm.$emit('update:modelValue', '#654321')
    await flushPromises()

    expect(store.getShape('shape-a')?.fill).toBe('#654321')
    expect(store.getShape('shape-b')?.fill).toBe('#dddddd')
  })

  it('follows the selection to another shape', async () => {
    const { wrapper, store } = await mountInspector()

    store.selectedIds = [shapeB.id] as never
    await flushPromises()

    expect(picker(wrapper, 'Border color').props('modelValue')).toBe('#bbbbbb')
    picker(wrapper, 'Border color').vm.$emit('update:modelValue', '#0f0f0f')
    await flushPromises()

    expect(store.getShape('shape-b')?.stroke).toBe('#0f0f0f')
    expect(store.getShape('shape-a')?.stroke).toBe('#aaaaaa')
  })

})
