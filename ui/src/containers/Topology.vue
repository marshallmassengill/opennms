<!--
Licensed to The OpenNMS Group, Inc (TOG) under one or more
contributor license agreements.  See the LICENSE.md file
distributed with this work for additional information
regarding copyright ownership.

TOG licenses this file to You under the GNU Affero General
Public License Version 3 (the "License") or (at your option)
any later version.  You may not use this file except in
compliance with the License.  You may obtain a copy of the
License at:

     https://www.gnu.org/licenses/agpl-3.0.txt

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
-->

<template>
  <div class="topology-page">
    <PToolbar class="topology-toolbar">
      <template #start>
        <span class="topology-title">Topology (Preview)</span>
      </template>
    </PToolbar>

    <PCard class="topology-empty-state">
      <template #title>Coming soon</template>
      <template #content>
        <p>
          This is the new PrimeVue-based topology canvas. The full feature ships in
          subsequent steps.
        </p>
        <p>
          The legacy topology map remains available at
          <a href="/opennms/topology"><code>/opennms/topology</code></a>.
        </p>
      </template>
    </PCard>
  </div>
</template>

<script setup lang="ts">
import Toolbar from 'primevue/toolbar'
import Card from 'primevue/card'
import { useTopologyStore } from '@/stores/topologyStore'

const PToolbar = Toolbar
const PCard = Card

// Store wired but unused in the scaffold step; reserved for subsequent steps.
useTopologyStore()
</script>

<style scoped>
.topology-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem;
  height: 100%;
}

.topology-toolbar {
  flex: 0 0 auto;
}

.topology-title {
  font-size: 1.25rem;
  font-weight: 600;
}

.topology-empty-state {
  flex: 1 1 auto;
}
</style>
