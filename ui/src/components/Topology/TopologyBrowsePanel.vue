<!--
  Bottom browse panel: Nodes / Alarms tables for the nodes in the current view,
  tied to the canvas selection (legacy AlarmTable/NodeTable). Collapsible.
  Selecting a row selects that node on the canvas; selecting a single node on
  the canvas filters the tables to it.
-->
<template>
  <section class="topology-browse" :class="{ collapsed }">
    <header class="tb-header">
      <button class="tb-toggle" type="button" @click="collapsed = !collapsed">
        <span class="tb-caret">{{ collapsed ? '▸' : '▾' }}</span> Browse
      </button>
      <div v-if="!collapsed" class="tb-tabs">
        <button type="button" :class="{ active: tab === 'nodes' }" @click="tab = 'nodes'">
          Nodes ({{ nodeRows.length }})
        </button>
        <button type="button" :class="{ active: tab === 'alarms' }" @click="tab = 'alarms'">
          Alarms ({{ alarmRows.length }})
        </button>
      </div>
      <span v-if="!collapsed && selectedNodeId !== null" class="tb-filter">
        filtered to selection
        <a href="#" @click.prevent="$emit('select', null)">show all</a>
      </span>
    </header>

    <div v-if="!collapsed" class="tb-body">
      <p v-if="loading" class="tb-empty">Loading…</p>
      <p v-else-if="nodeRows.length === 0" class="tb-empty">No nodes in this view.</p>

      <PDataTable
        v-else-if="tab === 'nodes'"
        :value="filteredNodeRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
        selection-mode="single"
        @row-click="onRowSelect($event.data.id)"
      >
        <PColumn header="" :style="{ width: '2rem' }">
          <template #body="{ data }">
            <span class="tb-dot" :style="{ background: severityColor(data.severity) }" />
          </template>
        </PColumn>
        <PColumn field="label" header="Node" sortable />
        <PColumn field="severity" header="Severity" sortable />
        <PColumn field="location" header="Location" sortable />
      </PDataTable>

      <PDataTable
        v-else
        :value="filteredAlarmRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
        selection-mode="single"
        @row-click="onRowSelect(`placed-${$event.data.nodeId}`)"
      >
        <PColumn header="" :style="{ width: '2rem' }">
          <template #body="{ data }">
            <span class="tb-dot" :style="{ background: severityColor(data.severity) }" />
          </template>
        </PColumn>
        <PColumn field="nodeLabel" header="Node" sortable />
        <PColumn field="logMessage" header="Message">
          <template #body="{ data }">
            <span class="tb-msg" v-text="stripHtml(data.logMessage)" />
          </template>
        </PColumn>
        <PColumn field="lastEventTime" header="Last event" sortable>
          <template #body="{ data }">{{ formatTime(data.lastEventTime) }}</template>
        </PColumn>
      </PDataTable>
      <p v-if="!loading && tab === 'alarms' && alarmRows.length === 0" class="tb-empty">
        No alarms for these nodes.
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useTopologyStore } from '@/stores/topologyStore'
import { severityColor } from '@/components/Topology/severity'
import { nodeIdFromPlacedId } from '@/components/Topology/nodeIds'
import { getNodes } from '@/services/nodeService'
import { getAlarms } from '@/services/alarmService'

const PDataTable = DataTable
const PColumn = Column

const emit = defineEmits<{ (e: 'select', placedId: string | null): void }>()

const store = useTopologyStore()

const collapsed = ref(true)
const tab = ref<'nodes' | 'alarms'>('nodes')
const loading = ref(false)

interface NodeRow {
  id: string // placed canvas id
  nodeId: number
  label: string
  location: string
  severity: string
}
interface AlarmRow {
  id: number
  nodeId: number
  nodeLabel: string
  severity: string
  logMessage: string
  lastEventTime: number
}

const nodeRows = ref<NodeRow[]>([])
const alarmRows = ref<AlarmRow[]>([])

// The real OnmsNode ids of the placed nodes (bare-number palette ids).
const placedRealIds = computed<number[]>(() =>
  Array.from(store.placedNodeIds)
    .map((id) => Number(id))
    .filter((n) => Number.isInteger(n))
)

// When exactly one placed node is selected, the tables filter to it.
const selectedNodeId = computed<number | null>(() => {
  if (store.selectedIds.length !== 1) return null
  return nodeIdFromPlacedId(store.selectedIds[0])
})

const filteredNodeRows = computed(() =>
  selectedNodeId.value === null
    ? nodeRows.value
    : nodeRows.value.filter((r) => r.nodeId === selectedNodeId.value)
)
const filteredAlarmRows = computed(() =>
  selectedNodeId.value === null
    ? alarmRows.value
    : alarmRows.value.filter((r) => r.nodeId === selectedNodeId.value)
)

const onRowSelect = (placedId: string) => emit('select', placedId)

const stripHtml = (s?: string): string => (s ? s.replace(/<[^>]*>/g, '').trim() : '')
const formatTime = (ms?: number): string => {
  if (!ms) return '—'
  const d = new Date(ms)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}

const fetchData = async (): Promise<void> => {
  const ids = placedRealIds.value
  if (ids.length === 0) {
    nodeRows.value = []
    alarmRows.value = []
    return
  }
  loading.value = true
  try {
    const nodeFiql = ids.map((id) => `id==${id}`).join(',')
    const alarmFiql = ids.map((id) => `node.id==${id}`).join(',')
    const [nodesResp, alarmsResp] = await Promise.all([
      getNodes({ _s: nodeFiql, limit: 1000 }),
      getAlarms({ _s: alarmFiql, limit: 1000 })
    ])
    nodeRows.value =
      nodesResp && nodesResp.node
        ? nodesResp.node.map((n) => {
            const nid = Number(n.id)
            return {
              id: `placed-${nid}`,
              nodeId: nid,
              label: n.label ?? String(nid),
              location: n.location ?? '',
              severity: store.severities[nid] ?? 'NORMAL'
            }
          })
        : []
    alarmRows.value =
      alarmsResp && alarmsResp.alarm
        ? alarmsResp.alarm.map((a) => ({
            id: Number(a.id),
            nodeId: a.nodeId,
            nodeLabel: a.nodeLabel ?? String(a.nodeId),
            severity: a.severity ?? 'NORMAL',
            logMessage: a.logMessage ?? '',
            lastEventTime: a.lastEventTime
          }))
        : []
  } finally {
    loading.value = false
  }
}

// Fetch when first expanded and whenever the placed-node set changes while open.
watch([collapsed, placedRealIds], ([isCollapsed]) => {
  if (!isCollapsed) void fetchData()
})
</script>

<style scoped>
.topology-browse {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--feather-border-on-surface);
  border-radius: 6px;
  background: var(--feather-surface);
  overflow: hidden;
  max-height: 38vh;
}
.topology-browse.collapsed {
  max-height: none;
}
.tb-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.35rem 0.6rem;
  background: rgba(31, 95, 176, 0.10);
  border-bottom: 1px solid var(--feather-border-on-surface);
}
.topology-browse.collapsed .tb-header {
  border-bottom: none;
}
.tb-toggle {
  border: none;
  background: none;
  font-weight: 600;
  font-size: 0.9rem;
  cursor: pointer;
  color: var(--feather-primary-text-on-surface);
}
.tb-caret {
  display: inline-block;
  width: 1em;
}
.tb-tabs {
  display: flex;
  gap: 0.25rem;
}
.tb-tabs button {
  border: 1px solid transparent;
  background: none;
  padding: 0.2rem 0.6rem;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.85rem;
  color: var(--feather-primary-text-on-surface);
}
.tb-tabs button.active {
  background: rgba(31, 95, 176, 0.10);
  border-color: var(--feather-border-on-surface);
  color: #1f5fb0;
  font-weight: 600;
}
.tb-filter {
  margin-left: auto;
  font-size: 0.8rem;
  color: var(--feather-secondary-text-on-surface);
}
.tb-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 0.25rem;
}
.tb-empty {
  padding: 0.75rem;
  color: var(--feather-secondary-text-on-surface);
  font-size: 0.85rem;
}
.tb-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.tb-msg {
  display: inline-block;
  max-width: 48ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
