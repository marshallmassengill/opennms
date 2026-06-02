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

import { v2 } from './axiosInstances'
import type { TopologyView, TopologyViewSummary } from '@/types/topology'

/**
 * Service stub for the topology views catalog.
 *
 * The backing REST resource (/api/v2/topology/views) is not implemented
 * yet -- it ships as a self-contained JAX-RS resource later in the build
 * sequence. Until then, these calls will fail with HTTP 404 and return
 * `false`, matching the pattern used by the other services in this
 * directory (return typed data or `false`).
 */

const viewsEndpoint = 'topology/views'

const listViews = async (): Promise<TopologyViewSummary[] | false> => {
  try {
    const resp = await v2.get(viewsEndpoint)
    return resp.data
  } catch (err) {
    return false
  }
}

const getView = async (id: string): Promise<TopologyView | false> => {
  try {
    const resp = await v2.get(`${viewsEndpoint}/${id}`)
    return resp.data
  } catch (err) {
    return false
  }
}

const saveView = async (view: TopologyView): Promise<TopologyView | false> => {
  try {
    if (view.id) {
      const resp = await v2.put(`${viewsEndpoint}/${view.id}`, view)
      return resp.data
    }
    const resp = await v2.post(viewsEndpoint, view)
    return resp.data
  } catch (err) {
    return false
  }
}

const deleteView = async (id: string): Promise<boolean> => {
  try {
    await v2.delete(`${viewsEndpoint}/${id}`)
    return true
  } catch (err) {
    return false
  }
}

export {
  listViews,
  getView,
  saveView,
  deleteView
}
