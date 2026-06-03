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

<!--
  Saved-views catalog dialog. Lists the shared topology views from the
  /api/v2/topology/views resource and lets the user open or delete one.
  Opening emits an event the page handles by loading the view into the
  canvas; deleting goes straight through the store.
-->

<template>
  <PDialog
    :visible="visible"
    modal
    header="Saved views"
    :style="{ width: '42rem' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div v-if="loading" class="tv-empty">Loading views&hellip;</div>
    <div v-else-if="loadError" class="tv-empty">
      <p>Couldn't load saved views.</p>
      <PButton label="Retry" size="small" text @click="refresh" />
    </div>
    <div v-else-if="store.catalog.length === 0" class="tv-empty">
      No saved views yet. Compose a canvas and use <strong>Save</strong> to create one.
    </div>
    <PDataTable v-else :value="store.catalog" dataKey="id" :rows="10" paginator>
      <PColumn field="name" header="Name" sortable />
      <PColumn header="" :style="{ width: '12rem' }">
        <template #body="{ data }">
          <div class="tv-row-actions">
            <PButton label="Open" size="small" text @click="onOpen(data.id)" />
            <PButton
              label="Delete"
              size="small"
              text
              severity="danger"
              @click="onDelete(data.id, data.name)"
            />
          </div>
        </template>
      </PColumn>
    </PDataTable>
  </PDialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useTopologyStore } from '@/stores/topologyStore'

const PDialog = Dialog
const PDataTable = DataTable
const PColumn = Column
const PButton = Button

const toast = useToast()

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'open', id: string): void
}>()

const store = useTopologyStore()

const loading = ref(false)
const loadError = ref(false)

const refresh = async () => {
  loading.value = true
  loadError.value = false
  const ok = await store.refreshCatalog()
  loading.value = false
  loadError.value = !ok
}

// Refresh the catalog from the server each time the dialog opens.
watch(
  () => props.visible,
  (open) => {
    if (open) refresh()
  }
)

const onOpen = (id: string) => {
  emit('open', id)
  emit('update:visible', false)
}

const onDelete = async (id: string, name: string) => {
  if (!window.confirm(`Delete view "${name}"? This cannot be undone.`)) return
  const ok = await store.removeView(id)
  toast.add(
    ok
      ? { severity: 'success', summary: 'View deleted', detail: name, life: 3000 }
      : { severity: 'error', summary: 'Delete failed', detail: name, life: 5000 }
  )
}
</script>

<style scoped>
.tv-empty {
  padding: 1rem 0.25rem;
  color: #475467;
}

.tv-row-actions {
  display: flex;
  gap: 0.25rem;
}
</style>
