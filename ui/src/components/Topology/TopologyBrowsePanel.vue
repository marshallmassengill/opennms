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
        <button type="button" :class="{ active: tab === 'alarms' }" @click="tab = 'alarms'">
          Alarms ({{ alarmRows.length }})
        </button>
        <button type="button" :class="{ active: tab === 'nodes' }" @click="tab = 'nodes'">
          Nodes ({{ nodeRows.length }})
        </button>
        <template v-if="isApplicationGraph">
          <button type="button" :class="{ active: tab === 'applications' }" @click="tab = 'applications'">
            Applications ({{ applicationRows.length }})
          </button>
          <button type="button" :class="{ active: tab === 'perspective' }" @click="tab = 'perspective'">
            Perspective Outages ({{ perspectiveRows.length }})
          </button>
        </template>
      </div>
      <span v-if="!collapsed && isFiltered && tab !== 'applications'" class="tb-filter">
        filtered to selection
        <a href="#" @click.prevent="$emit('select', null)">show all</a>
      </span>
    </header>

    <div v-if="!collapsed" class="tb-body">
      <p v-if="loading" class="tb-empty">Loading…</p>
      <p v-else-if="nodeRows.length === 0" class="tb-empty">No nodes in this view.</p>

      <OnmsTable
        v-else-if="tab === 'nodes'"
        :value="filteredNodeRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
        selection-mode="single"
        @row-click="onRowSelect($event.data.id)"
      >
        <OnmsColumn header="" :style="{ width: '2rem' }">
          <template #body="{ data }">
            <span class="tb-dot" :style="{ background: severityColor(data.severity) }" />
          </template>
        </OnmsColumn>
        <OnmsColumn field="label" header="Node" sortable />
        <OnmsColumn field="severity" header="Severity" sortable />
        <OnmsColumn field="location" header="Location" sortable />
      </OnmsTable>

      <OnmsTable
        v-else-if="tab === 'applications'"
        :value="applicationRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
      >
        <OnmsColumn field="name" header="Application" sortable />
        <OnmsColumn header="Services">
          <template #body="{ data }">{{ serviceCountFor(data.id) }}</template>
        </OnmsColumn>
        <OnmsColumn header="Perspectives">
          <template #body="{ data }">
            {{ data.perspectiveLocations.length ? data.perspectiveLocations.join(', ') : '—' }}
          </template>
        </OnmsColumn>
      </OnmsTable>

      <OnmsTable
        v-else-if="tab === 'perspective'"
        :value="filteredPerspectiveRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
        selection-mode="single"
        @row-click="onRowSelect(`placed-${$event.data.nodeId}`)"
      >
        <OnmsColumn field="nodeLabel" header="Node" sortable />
        <OnmsColumn field="serviceName" header="Service" sortable />
        <OnmsColumn field="perspective" header="Perspective" sortable />
        <OnmsColumn field="lostAt" header="Down since" sortable>
          <template #body="{ data }">{{ formatTime(data.lostAt) }}</template>
        </OnmsColumn>
      </OnmsTable>

      <OnmsTable
        v-else-if="tab === 'alarms'"
        :value="filteredAlarmRows"
        data-key="id"
        scrollable
        scroll-height="flex"
        size="small"
        selection-mode="single"
        @row-click="onRowSelect(`placed-${$event.data.nodeId}`)"
      >
        <OnmsColumn header="" :style="{ width: '2rem' }">
          <template #body="{ data }">
            <span class="tb-dot" :style="{ background: severityColor(data.severity) }" />
          </template>
        </OnmsColumn>
        <OnmsColumn field="nodeLabel" header="Node" sortable />
        <OnmsColumn field="logMessage" header="Message">
          <template #body="{ data }">
            <span class="tb-msg" v-text="stripHtml(data.logMessage)" />
          </template>
        </OnmsColumn>
        <OnmsColumn field="lastEventTime" header="Last event" sortable>
          <template #body="{ data }">{{ formatTime(data.lastEventTime) }}</template>
        </OnmsColumn>
      </OnmsTable>
      <p v-if="!loading && tab === 'alarms' && alarmRows.length === 0" class="tb-empty">
        No alarms for these nodes.
      </p>
      <p v-if="!loading && tab === 'applications' && applicationRows.length === 0" class="tb-empty">
        No applications defined.
      </p>
      <p v-if="!loading && tab === 'perspective' && filteredPerspectiveRows.length === 0" class="tb-empty">
        No perspective currently reports an outage for these nodes.
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { OnmsColumn, OnmsTable } from '@opennms/onms-ui'
import { useTopologyStore } from '@/stores/topologyStore'
import { severityColor } from '@/components/Topology/severity'
import { nodeIdFromPlacedId } from '@/components/Topology/nodeIds'
import { getNodes } from '@/services/nodeService'
import { getAlarms } from '@/services/alarmService'
import {
  getApplications,
  getPerspectiveOutages,
  type PerspectiveOutage,
  type TopologyApplication
} from '@/services/topologyService'


const emit = defineEmits<{ (e: 'select', placedId: string | null): void }>()

const store = useTopologyStore()

const collapsed = ref(true)
// Alarms first, and active by default: it is the tab an operator opens the
// panel for, and leaving Nodes selected would highlight the second tab.
type BrowseTab = 'alarms' | 'nodes' | 'applications' | 'perspective'
const tab = ref<BrowseTab>('alarms')
const loading = ref(false)

/**
 * Applications and perspective outages are only meaningful for the Application
 * graph, so those two tabs appear only while it is the loaded source.
 */
const isApplicationGraph = computed(() =>
  store.discoveredGraph?.source.container === 'application'
)

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
const applicationRows = ref<TopologyApplication[]>([])
const perspectiveRows = ref<PerspectiveOutage[]>([])

// The real OnmsNode ids of the placed nodes (bare-number palette ids).
const placedRealIds = computed<number[]>(() =>
  Array.from(store.placedNodeIds)
    .map(id => Number(id))
    .filter(n => Number.isInteger(n))
)

/**
 * The nodes behind the current selection, so the tables filter to all of them
 * rather than only to a single pick. A canvas id encodes its node id for placed
 * and one-vertex-per-node discovered graphs; where it cannot (several vertices
 * on one node) the vertex carries it instead, so the discovered graph is
 * consulted as a fallback.
 */
const selectedNodeIds = computed<number[]>(() => {
  const ids = new Set<number>()
  for (const selected of store.selectedIds) {
    const fromId = nodeIdFromPlacedId(selected)
    const nodeId = fromId ?? store.discoveredGraph?.nodes.find(n => n.id === selected)?.nodeId
    if (nodeId != null) {
      ids.add(nodeId)
    }
  }
  return Array.from(ids)
})

const isFiltered = computed(() => selectedNodeIds.value.length > 0)

/**
 * How many services an application watches, counted from the loaded graph's
 * application-to-service edges. The applications resource does not return them,
 * and the graph is already on hand.
 */
const serviceCountFor = (applicationId: number): number => {
  const graph = store.discoveredGraph
  if (!graph) {
    return 0
  }
  const vertex = graph.nodes.find(n => n.properties?.applicationId === String(applicationId))
  return vertex ? graph.links.filter(l => l.sourceId === vertex.id || l.targetId === vertex.id).length : 0
}

const matchesSelection = (nodeId: number | undefined): boolean =>
  !isFiltered.value || (nodeId != null && selectedNodeIds.value.includes(nodeId))

const filteredNodeRows = computed(() => nodeRows.value.filter(r => matchesSelection(r.nodeId)))
const filteredAlarmRows = computed(() => alarmRows.value.filter(r => matchesSelection(r.nodeId)))
const filteredPerspectiveRows = computed(() =>
  perspectiveRows.value.filter(r => matchesSelection(r.nodeId))
)

const onRowSelect = (placedId: string) => emit('select', placedId)

const stripHtml = (s?: string): string => (s ? s.replace(/<[^>]*>/g, '').trim() : '')
const formatTime = (ms?: number): string => {
  if (!ms) {
    return '—'
  }
  const d = new Date(ms)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString()}`
}

const fetchData = async (): Promise<void> => {
  const ids = placedRealIds.value
  if (ids.length === 0) {
    nodeRows.value = []
    alarmRows.value = []
    applicationRows.value = []
    perspectiveRows.value = []
    return
  }
  loading.value = true
  try {
    const nodeFiql = ids.map(id => `id==${id}`).join(',')
    const alarmFiql = ids.map(id => `node.id==${id}`).join(',')
    const [nodesResp, alarmsResp, applications, perspectives] = await Promise.all([
      getNodes({ _s: nodeFiql, limit: 1000 }),
      getAlarms({ _s: alarmFiql, limit: 1000 }),
      isApplicationGraph.value ? getApplications() : Promise.resolve([]),
      isApplicationGraph.value ? getPerspectiveOutages(ids) : Promise.resolve([])
    ])
    applicationRows.value = applications
    perspectiveRows.value = perspectives
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
        ? alarmsResp.alarm.map(a => ({
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

// Fetch when first expanded, and whenever the placed-node set or the loaded
// source changes while open.
watch([collapsed, placedRealIds, isApplicationGraph], ([isCollapsed]) => {
  if (!isCollapsed) {
    void fetchData()
  }
})

// Leaving the Application graph takes its two tabs with it, so fall back rather
// than leaving a tab selected that is no longer rendered.
watch(isApplicationGraph, (isApplication) => {
  if (!isApplication && (tab.value === 'applications' || tab.value === 'perspective')) {
    tab.value = 'alarms'
  }
})
</script>

<style scoped>
.topology-browse {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--onms-border-on-surface);
  border-radius: 6px;
  background: var(--onms-surface);
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
  border-bottom: 1px solid var(--onms-border-on-surface);
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
  color: var(--onms-primary-text-on-surface);
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
  color: var(--onms-primary-text-on-surface);
}
.tb-tabs button.active {
  background: rgba(31, 95, 176, 0.10);
  border-color: var(--onms-border-on-surface);
  color: #1f5fb0;
  font-weight: 600;
}
.tb-filter {
  margin-left: auto;
  font-size: 0.8rem;
  color: var(--onms-secondary-text-on-surface);
}
.tb-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 0.25rem;
}
.tb-empty {
  padding: 0.75rem;
  color: var(--onms-secondary-text-on-surface);
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
