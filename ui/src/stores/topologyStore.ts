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

import { defineStore } from 'pinia'
import type { TopologyView, TopologyViewSummary } from '@/types/topology'

const emptyView = (): TopologyView => ({
  name: 'Untitled view',
  nodes: [],
  edges: [],
  labels: [],
  viewport: { zoom: 1, panX: 0, panY: 0 }
})

/**
 * State for the custom topology canvas. Holds the catalog of saved
 * views plus the currently-open view, selection state, and the
 * Edit/View mode flag. Service-backed actions (list/load/save/delete)
 * are wired in subsequent steps once the persistence REST resource
 * is in place.
 */
export const useTopologyStore = defineStore('topologyStore', () => {
  const catalog = ref<TopologyViewSummary[]>([])
  const currentView = ref<TopologyView | null>(null)
  const isEditMode = ref<boolean>(true)
  const selectedIds = ref<string[]>([])

  const newView = () => {
    currentView.value = emptyView()
    selectedIds.value = []
  }

  const setEditMode = (value: boolean) => {
    isEditMode.value = value
  }

  return {
    catalog,
    currentView,
    isEditMode,
    selectedIds,
    newView,
    setEditMode
  }
})
