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

import { createTestingPinia } from '@pinia/testing'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TopologyBrowsePanel from '@/components/Topology/TopologyBrowsePanel.vue'
import { useTopologyStore } from '@/stores/topologyStore'

// vi.mock is hoisted above the module body, so the fixtures live inside the
// factories rather than as consts it would not be able to reach.
vi.mock('@/services/nodeService', () => ({
  getNodes: vi.fn().mockResolvedValue({
    node: [
      { id: 7, label: 'core-sw1', location: 'HQ' },
      { id: 8, label: 'edge-sw2', location: 'DC' }
    ]
  })
}))
vi.mock('@/services/alarmService', () => ({
  getAlarms: vi.fn().mockResolvedValue({
    alarm: [
      { id: 1, nodeId: 7, nodeLabel: 'core-sw1', severity: 'MAJOR', logMessage: 'link down', lastEventTime: 0 }
    ]
  })
}))

const mountPanel = async () => {
  const wrapper = mount(TopologyBrowsePanel, {
    global: { plugins: [PrimeVue, createTestingPinia({ stubActions: false })] }
  })
  const store = useTopologyStore()
  store.currentView = {
    name: 'v', nodes: [{ id: 'placed-7', nodeId: 7, label: 'core-sw1', x: 0, y: 0 }], links: [], labels: []
  } as never
  await flushPromises()
  // The panel opens collapsed, and the tabs and the fetch both hang off that.
  await wrapper.find('.tb-toggle').trigger('click')
  await flushPromises()
  return { wrapper, store }
}

const tabLabels = (wrapper: Awaited<ReturnType<typeof mountPanel>>['wrapper']) =>
  wrapper.findAll('.tb-tabs button').map(b => b.text().replace(/\s*\(\d+\)$/, ''))

describe('TopologyBrowsePanel tabs', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('puts Alarms before Nodes', async () => {
    const { wrapper } = await mountPanel()
    expect(tabLabels(wrapper)).toEqual(['Alarms', 'Nodes'])
  })

  // The first tab has to be the active one, or the panel looks broken on open.
  it('opens on Alarms', async () => {
    const { wrapper } = await mountPanel()
    const active = wrapper.findAll('.tb-tabs button').filter(b => b.classes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toContain('Alarms')
  })

  it('switches to Nodes when that tab is clicked', async () => {
    const { wrapper } = await mountPanel()
    const nodesTab = wrapper.findAll('.tb-tabs button').find(b => b.text().includes('Nodes'))!
    await nodesTab.trigger('click')

    const active = wrapper.findAll('.tb-tabs button').filter(b => b.classes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toContain('Nodes')
  })
})
