<template>
  <div class="ll-page">
    <div class="ll-panel" style="padding: 12px 14px; margin-bottom: 12px;">
      <div style="display: flex; flex-wrap: wrap; gap: 10px; align-items: center;">
        <span class="ll-section-title">洗涤批次隧道式流转</span>
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 120px" @change="load">
          <el-option label="在产" value="RUNNING" />
          <el-option label="已完成" value="DONE" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建批次（排产）</el-button>
        <el-button @click="load">刷新</el-button>
        <span class="ll-sub">点卡片底部「下一工序」推进；工序必须按顺序，不能跳步；已完成工序变灰打勾</span>
      </div>
      <div style="display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px;">
        <el-tag
          v-for="s in stepStats"
          :key="s.stepCode"
          size="small"
          :type="s.batchCount > 0 ? 'primary' : 'info'"
          effect="plain"
        >
          {{ s.stepName }} {{ s.batchCount }} 缸 / {{ s.totalWeight }}kg
        </el-tag>
        <span class="ll-sub">在产 {{ summary.runningBatch || 0 }} 缸 · 在产重量 {{ summary.runningWeight || 0 }}kg · 已完工 {{ summary.doneBatch || 0 }} 缸</span>
      </div>
    </div>

    <div class="ll-tunnel-viewport" ref="viewportRef">
      <div class="ll-tunnel-track" :style="{ transform: `translateX(${offset}px)` }">
        <div v-for="(lane, index) in lanes" :key="lane.stepCode" class="ll-tunnel-lane">
          <div class="ll-lane-head" :class="{ 'is-focus': focusIndex === index }">
            <span>{{ index + 1 }}. {{ lane.stepName }}</span>
            <span class="ll-lane-count">{{ lane.batches.length }} 缸</span>
          </div>

          <div v-if="!lane.batches.length" class="ll-sub" style="padding: 10px 6px;">— 无批次停留 —</div>

          <div
            v-for="b in lane.batches"
            :key="b.id"
            class="ll-batch-card"
            :class="{ 'is-done': b.status === 'DONE' }"
          >
            <div class="ll-batch-no">
              <span>{{ b.batchNo }}</span>
              <el-tag size="small" :type="b.status === 'DONE' ? 'info' : 'success'">
                {{ b.status === 'DONE' ? '已完工' : '在产' }}
              </el-tag>
            </div>
            <div class="ll-batch-meta">
              {{ b.categoryName }} · {{ b.weightKg }}kg · {{ b.waterTemp }}℃<br />
              洗涤剂 {{ b.detergentMl }}ml · {{ b.machineName || '未指定设备' }} · {{ b.operator || '未指派' }}
            </div>

            <div class="ll-step-dots">
              <span
                v-for="(step, i) in stepNames"
                :key="step"
                class="ll-step-dot"
                :class="{
                  'is-done': i + 1 < b.currentStepOrder || b.status === 'DONE',
                  'is-current': i + 1 === b.currentStepOrder && b.status !== 'DONE'
                }"
                :title="step"
              >
                {{ i + 1 < b.currentStepOrder || b.status === 'DONE' ? '✓' : step.charAt(0) }}
              </span>
            </div>

            <el-button
              v-if="b.status !== 'DONE'"
              type="primary"
              size="small"
              style="width: 100%"
              :loading="advancingId === b.id"
              @click="advance(b)"
            >
              下一工序 → {{ nextStepName(b) }}
            </el-button>
            <el-button v-else size="small" style="width: 100%" disabled>折叠完成，已入库</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建批次 -->
    <el-dialog v-model="createVisible" title="新建洗涤批次（排产）" width="460px">
      <el-form label-width="92px" size="small">
        <el-form-item label="布草品类">
          <el-select v-model="createForm.categoryId" placeholder="选择品类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name + '（' + c.spec + '）'" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="重量 kg">
          <el-input-number v-model="createForm.weightKg" :min="1" :max="100" :step="0.5" :precision="1" />
        </el-form-item>
        <el-form-item label="水洗机">
          <el-select v-model="createForm.machineId" placeholder="选择设备" style="width: 100%">
            <el-option
              v-for="m in washers"
              :key="m.id"
              :label="m.name + ' · 容量 ' + m.capacityKg + 'kg · ' + machineStatusName(m.status)"
              :value="m.id"
              :disabled="m.status === 'FAULT'"
            />
          </el-select>
          <div class="ll-sub">故障设备不允许排产</div>
        </el-form-item>
        <el-form-item label="水温 ℃">
          <el-input-number v-model="createForm.waterTemp" :min="0" :max="180" />
        </el-form-item>
        <el-form-item label="洗涤剂 ml">
          <el-input-number v-model="createForm.detergentMl" :min="0" :max="5000" :step="50" />
        </el-form-item>
        <el-form-item label="操作员">
          <el-input v-model="createForm.operator" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">排产</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { batchApi, categoryApi, machineApi } from '../api'

const emit = defineEmits(['kpi-change'])

const LANE_WIDTH = 216

const batches = ref([])
const stepNames = ref([])
const stepStats = ref([])
const summary = ref({})
const categories = ref([])
const machines = ref([])
const statusFilter = ref('')
const focusIndex = ref(0)
const advancingId = ref(null)
const viewportRef = ref(null)
const viewportWidth = ref(1000)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  categoryId: null,
  weightKg: 60,
  machineId: null,
  waterTemp: 35,
  detergentMl: 800,
  operator: '王建国'
})

const washers = computed(() => machines.value.filter((m) => m.machineType === 'WASHER'))

const lanes = computed(() => {
  const names = stepNames.value.length
    ? stepNames.value
    : ['预洗', '主洗', '漂洗', '中和', '脱水', '烘干', '烫平', '折叠']
  const codes = ['PRE_WASH', 'MAIN_WASH', 'RINSE', 'NEUTRALIZE', 'EXTRACT', 'DRY', 'IRON', 'FOLD']
  return names.map((stepName, index) => ({
    stepName,
    stepCode: codes[index],
    batches: batches.value.filter((b) => b.currentStepOrder === index + 1)
  }))
})

const visibleLanes = computed(() => Math.max(1, Math.floor(viewportWidth.value / LANE_WIDTH)))

const offset = computed(() => {
  const maxIndex = Math.max(0, lanes.value.length - visibleLanes.value)
  const centered = focusIndex.value - Math.floor((visibleLanes.value - 1) / 2)
  return -Math.min(maxIndex, Math.max(0, centered)) * LANE_WIDTH
})

function nextStepName(batch) {
  const names = stepNames.value.length ? stepNames.value : []
  return names[batch.currentStepOrder] || '折叠完工'
}

function machineStatusName(status) {
  return { RUNNING: '运行', IDLE: '待机', FAULT: '故障' }[status] || status
}

async function load(keepFocus = false) {
  const params = statusFilter.value ? { status: statusFilter.value } : {}
  const [list, names, stats, sum] = await Promise.all([
    batchApi.list(params),
    batchApi.steps(),
    batchApi.stepStats(),
    batchApi.summary()
  ])
  batches.value = list
  stepNames.value = names
  stepStats.value = stats
  summary.value = sum
  if (!keepFocus) {
    focusToDefault()
  }
}

function focusToDefault() {
  if (!batches.value.length) {
    focusIndex.value = 0
    return
  }
  const running = batches.value.filter((b) => b.status === 'RUNNING')
  const target = running.length ? running : batches.value
  const maxOrder = Math.max(...target.map((b) => b.currentStepOrder || 1))
  focusIndex.value = maxOrder - 1
}

async function advance(batch) {
  advancingId.value = batch.id
  try {
    const updated = await batchApi.advance(batch.id, {
      operator: batch.operator,
      waterTemp: batch.waterTemp,
      detergentMl: batch.detergentMl
    })
    ElMessage.success(`${batch.batchNo} 已推进到「${stepNames.value[(updated.currentStepOrder || 1) - 1] || '完工'}」`)
    focusIndex.value = Math.min(lanes.value.length - 1, (updated.currentStepOrder || 1) - 1)
    await load(true)
    emit('kpi-change')
  } finally {
    advancingId.value = null
  }
}

async function openCreate() {
  const [cats, machs] = await Promise.all([categoryApi.list(), machineApi.list()])
  categories.value = cats
  machines.value = machs
  createVisible.value = true
}

async function submitCreate() {
  creating.value = true
  try {
    await batchApi.create({ ...createForm })
    ElMessage.success('排产成功，批次已进入预洗工序')
    createVisible.value = false
    focusIndex.value = 0
    await load()
    emit('kpi-change')
  } finally {
    creating.value = false
  }
}

function measure() {
  if (viewportRef.value) {
    viewportWidth.value = viewportRef.value.clientWidth
  }
}

onMounted(async () => {
  await load()
  await nextTick(measure)
  window.addEventListener('resize', measure)
})
onUnmounted(() => {
  window.removeEventListener('resize', measure)
})
</script>
