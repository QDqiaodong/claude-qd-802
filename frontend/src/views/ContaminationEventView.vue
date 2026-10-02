<template>
  <div class="ll-page">
    <div class="ll-panel" style="padding: 12px 14px; margin-bottom: 12px;">
      <div style="display: flex; flex-wrap: wrap; gap: 10px; align-items: center;">
        <span class="ll-section-title">医院布草污染事件追溯与隔离处置</span>
        <el-button type="primary" @click="openCreate">登记污染事件</el-button>
        <el-button @click="load">刷新</el-button>
        <el-tag type="warning" effect="plain">待确认 {{ counts.PENDING || 0 }}</el-tag>
        <el-tag type="danger" effect="plain">隔离中 {{ counts.CONFIRMED || 0 }}</el-tag>
        <el-tag type="success" effect="plain">已放行 {{ counts.RELEASED || 0 }}</el-tag>
        <span class="ll-sub">确认后按设备实际占用窗口锁定批次；隔离批次由后端拦截推进、完工和删除</span>
      </div>
    </div>

    <div class="ll-panel" style="padding: 0;">
      <el-table :data="events" size="small" @row-click="openDetail" style="cursor: pointer;">
        <el-table-column prop="eventNo" label="事件编号" width="150" />
        <el-table-column label="发生设备" width="170">
          <template #default="{ row }">{{ row.machineCode }} · {{ row.machineName }}</template>
        </el-table-column>
        <el-table-column label="发现 / 影响窗口" width="330">
          <template #default="{ row }">
            <div>发现：{{ fmt(row.foundTime) }}</div>
            <div class="ll-sub">{{ fmt(row.impactStartTime) }} ～ {{ fmt(row.impactEndTime) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="污染类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="pollutionTag(row.pollutionType)">{{ pollutionName(row.pollutionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态 / 待处置" width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="eventTag(row.status)">{{ eventStatusName(row.status) }}</el-tag>
            <div class="ll-sub">隔离 {{ row.quarantineCount || 0 }} · 待复洗 {{ row.pendingCount || 0 }} · 复洗单 {{ row.rewashCount || 0 }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="reporter" label="登记人" width="90" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">追溯链</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 登记事件 -->
    <el-dialog v-model="createVisible" title="登记医院布草污染事件" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="发生设备" required>
          <el-select v-model="form.machineId" placeholder="选择发生污染的设备" style="width: 100%">
            <el-option
              v-for="m in machines"
              :key="m.id"
              :label="`${m.code} · ${m.name}（${typeName(m.machineType)}）`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发现时间" required>
          <el-date-picker v-model="form.foundTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="影响开始时间" required>
          <el-date-picker v-model="form.impactStartTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="影响结束时间" required>
          <el-date-picker v-model="form.impactEndTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="污染类型" required>
          <el-radio-group v-model="form.pollutionType">
            <el-radio-button label="INFECTIOUS">感染性</el-radio-button>
            <el-radio-button label="BLOOD">血液体液</el-radio-button>
            <el-radio-button label="CHEMICAL">化学污染</el-radio-button>
            <el-radio-button label="OTHER">其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="登记人">
          <el-input v-model="form.reporter" placeholder="车间人员" style="width: 200px" />
        </el-form-item>
        <el-form-item label="污染描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="来源、现象、初步隔离范围" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 事件详情 / 追溯链 -->
    <el-drawer v-model="detailVisible" :title="`污染事件追溯链 · ${current?.eventNo || ''}`" size="78%">
      <template v-if="current">
        <el-alert
          :title="`${current.machineCode} · ${current.machineName} / ${pollutionName(current.pollutionType)} / ${eventStatusName(current.status)}`"
          :type="current.status === 'CONFIRMED' ? 'error' : current.status === 'RELEASED' ? 'success' : 'warning'"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
        />
        <div class="ll-event-meta">
          <div><b>发现时间：</b>{{ fmt(current.foundTime) }}</div>
          <div><b>影响窗口：</b>{{ fmt(current.impactStartTime) }} ～ {{ fmt(current.impactEndTime) }}</div>
          <div><b>登记人：</b>{{ current.reporter || '—' }}</div>
          <div><b>确认人：</b>{{ current.confirmedBy || '—' }} {{ current.confirmedAt ? fmt(current.confirmedAt) : '' }}</div>
          <div><b>放行人：</b>{{ current.releasedBy || '—' }} {{ current.releasedAt ? fmt(current.releasedAt) : '' }}</div>
          <div style="grid-column: 1 / -1;"><b>描述：</b>{{ current.description || '—' }}</div>
        </div>

        <div style="display: flex; gap: 8px; margin: 12px 0;">
          <el-button
            v-if="current.status === 'PENDING'"
            type="danger"
            :loading="acting === 'confirm'"
            @click="confirmEvent"
          >确认事件并生成隔离记录</el-button>
          <el-button
            v-if="current.status === 'CONFIRMED'"
            type="success"
            :loading="acting === 'release'"
            @click="releaseEvent"
          >复核放行（全部复洗完成）</el-button>
          <el-button @click="refreshDetail">刷新追溯链</el-button>
          <el-button @click="goTunnel">去批次隧道推进复洗批次</el-button>
        </div>

        <el-divider content-position="left">命中批次与命中原因（{{ current.quarantineRecords?.length || 0 }}）</el-divider>
        <el-empty v-if="!current.quarantineRecords?.length" description="窗口内未发现实际经过该设备的批次" :image-size="70" />

        <el-collapse v-else v-model="activeQuarantines">
          <el-collapse-item
            v-for="q in current.quarantineRecords"
            :key="q.id"
            :name="q.id"
            :title="`${q.batch?.batchNo || '批次#' + q.batchId} · ${q.batch?.categoryName || ''}`"
          >
            <template #title>
              <div class="ll-q-title">
                <el-tag size="small" :type="q.status === 'ACTIVE' ? 'danger' : 'success'">
                  {{ q.status === 'ACTIVE' ? '隔离待处置' : '已放行' }}
                </el-tag>
                <strong>{{ q.batch?.batchNo }}</strong>
                <span class="ll-sub">{{ batchStatusName(q.batch?.status) }} · {{ q.batch?.currentStep ? stepName(q.batch.currentStep) : '' }}</span>
              </div>
            </template>

            <el-alert :title="q.hitReason" type="error" :closable="false" show-icon style="margin-bottom: 8px;" />

            <el-table :data="q.hitSteps || []" size="small" border style="margin-bottom: 10px;">
              <el-table-column prop="stepOrder" label="#" width="45" />
              <el-table-column prop="stepName" label="命中工序" width="90" />
              <el-table-column label="设备占用区间" width="260">
                <template #default="{ row }">{{ fmt(row.stepStartTime) }} ～ {{ fmt(row.stepEndTime) }}</template>
              </el-table-column>
              <el-table-column label="窗口重叠区间" width="260">
                <template #default="{ row }">{{ fmt(row.overlapStartTime) }} ～ {{ fmt(row.overlapEndTime) }}</template>
              </el-table-column>
              <el-table-column label="重叠时长" width="90">
                <template #default="{ row }">{{ duration(row.overlapSeconds) }}</template>
              </el-table-column>
            </el-table>

            <div class="ll-section-title" style="margin: 8px 0;">复洗批次链路</div>
            <el-table :data="q.rewashLinks || []" size="small" border style="margin-bottom: 10px;">
              <el-table-column label="复洗批次" min-width="140">
                <template #default="{ row }">
                  <div>{{ row.rewashBatch?.batchNo || '—' }}</div>
                  <div class="ll-sub">来源 {{ row.sourceBatch?.batchNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="当前状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="rewashTag(row.status)">{{ rewashStatusName(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="operatorName" label="处置人" width="90" />
              <el-table-column label="工序 / 时间" width="210">
                <template #default="{ row }">
                  <div>{{ row.rewashBatch ? stepName(row.rewashBatch.currentStep) : '—' }}</div>
                  <div class="ll-sub">{{ fmt(row.startedAt) }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="failureReason" label="失败原因" min-width="130" show-overflow-tooltip />
              <el-table-column label="处置" width="95">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'RUNNING' && row.rewashBatch"
                    size="small"
                    type="danger"
                    @click="failRewash(row)"
                  >登记失败</el-button>
                  <span v-else class="ll-sub">—</span>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="current.status === 'CONFIRMED' && q.status === 'ACTIVE'" style="display:flex; gap:8px;">
              <el-button size="small" type="primary" @click="openRewash(q)">发起复洗（生成可追溯新批次）</el-button>
              <span class="ll-sub">并发重复点击只创建一个在产复洗批次；复洗批次需在批次隧道推进至完工</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-drawer>

    <!-- 发起复洗 -->
    <el-dialog v-model="rewashVisible" title="发起污染复洗" width="500px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px;"
                title="系统将生成新的 REWASH 批次并关联原批次；原批次持续隔离，不能推进或按正常完工处理。" />
      <el-form label-width="95px" size="small">
        <el-form-item label="复洗设备">
          <el-select v-model="rewashForm.machineId" placeholder="优先选择医感专区设备" style="width: 100%">
            <el-option
              v-for="m in rewashMachines"
              :key="m.id"
              :label="`${m.code} · ${m.name} · ${m.capacityKg}kg · ${machineStatusName(m.status)}`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="重量 kg">
          <el-input-number v-model="rewashForm.weightKg" :min="1" :max="100" :step="0.5" :precision="1" />
        </el-form-item>
        <el-form-item label="水温 ℃">
          <el-input-number v-model="rewashForm.waterTemp" :min="0" :max="180" />
        </el-form-item>
        <el-form-item label="洗涤剂 ml">
          <el-input-number v-model="rewashForm.detergentMl" :min="0" :max="5000" :step="50" />
        </el-form-item>
        <el-form-item label="处置人">
          <el-input v-model="rewashForm.operator" style="width: 180px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rewashVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRewash">创建复洗批次</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { contaminationApi, machineApi } from '../api'

const emit = defineEmits(['kpi-change'])
const router = useRouter()

const events = ref([])
const machines = ref([])
const createVisible = ref(false)
const detailVisible = ref(false)
const rewashVisible = ref(false)
const submitting = ref(false)
const acting = ref('')
const current = ref(null)
const currentQuarantine = ref(null)
const activeQuarantines = ref([])

const form = reactive({
  machineId: null,
  foundTime: null,
  impactStartTime: null,
  impactEndTime: null,
  pollutionType: 'INFECTIOUS',
  reporter: '',
  description: ''
})

const rewashForm = reactive({
  quarantineId: null,
  machineId: null,
  weightKg: 0,
  detergentMl: 900,
  waterTemp: 75,
  operator: '处置员'
})

const counts = computed(() => {
  const result = { PENDING: 0, CONFIRMED: 0, RELEASED: 0 }
  events.value.forEach((event) => { result[event.status] = (result[event.status] || 0) + 1 })
  return result
})

const rewashMachines = computed(() => machines.value
  .filter((m) => m.machineType === 'WASHER' && m.status !== 'FAULT')
  .sort((a, b) => Number(b.location?.includes('医感')) - Number(a.location?.includes('医感'))))

async function load() {
  const [eventList, machineList] = await Promise.all([contaminationApi.list(), machineApi.list()])
  events.value = eventList
  machines.value = machineList
}

async function refreshDetail() {
  if (!current.value) return
  current.value = await contaminationApi.detail(current.value.id)
  const index = events.value.findIndex((item) => item.id === current.value.id)
  if (index >= 0) events.value[index] = current.value
}

function openCreate() {
  Object.assign(form, {
    machineId: null,
    foundTime: null,
    impactStartTime: null,
    impactEndTime: null,
    pollutionType: 'INFECTIOUS',
    reporter: '',
    description: ''
  })
  createVisible.value = true
}

async function submitCreate() {
  submitting.value = true
  try {
    const event = await contaminationApi.create({ ...form })
    ElMessage.success('污染事件已登记，等待确认')
    createVisible.value = false
    await load()
    openDetail(event)
  } finally {
    submitting.value = false
  }
}

async function openDetail(row) {
  current.value = await contaminationApi.detail(row.id)
  activeQuarantines.value = (current.value.quarantineRecords || []).map((item) => item.id)
  detailVisible.value = true
}

async function confirmEvent() {
  acting.value = 'confirm'
  try {
    const { value } = await ElMessageBox.prompt('请输入确认人', '确认污染事件', {
      confirmButtonText: '确认并锁定命中批次',
      cancelButtonText: '取消',
      inputValue: '车间主管'
    })
    current.value = await contaminationApi.confirm(current.value.id, value)
    ElMessage.success(`已生成 ${current.value.quarantineRecords?.length || 0} 条不可重复隔离记录`)
    await load()
    emit('kpi-change')
  } catch (e) {
    if (e !== 'cancel') await refreshDetail()
  } finally {
    acting.value = ''
  }
}

async function releaseEvent() {
  acting.value = 'release'
  try {
    const { value } = await ElMessageBox.prompt('请输入复核放行人', '复核放行', {
      confirmButtonText: '全部复洗已完成，放行',
      cancelButtonText: '取消',
      inputValue: '院感复核员'
    })
    current.value = await contaminationApi.release(current.value.id, value)
    ElMessage.success('污染事件已复核放行，原始批次标记为处置完成')
    await load()
    emit('kpi-change')
  } catch (e) {
    if (e !== 'cancel') await refreshDetail()
  } finally {
    acting.value = ''
  }
}

function openRewash(q) {
  currentQuarantine.value = q
  const medical = rewashMachines.value.find((m) => m.location?.includes('医感'))
  Object.assign(rewashForm, {
    quarantineId: q.id,
    machineId: medical?.id || null,
    weightKg: q.batch?.weightKg || 0,
    detergentMl: q.batch?.detergentMl || 900,
    waterTemp: 75,
    operator: '处置员'
  })
  rewashVisible.value = true
}

async function submitRewash() {
  submitting.value = true
  try {
    const link = await contaminationApi.startRewash({ ...rewashForm })
    ElMessage.success(`复洗批次 ${link.rewashBatch?.batchNo || ''} 已创建，请在隧道推进至完工`)
    rewashVisible.value = false
    await refreshDetail()
    await load()
  } finally {
    submitting.value = false
  }
}

async function failRewash(link) {
  try {
    const { value } = await ElMessageBox.prompt('请输入复洗失败原因', '登记复洗失败', {
      confirmButtonText: '登记失败并保持隔离',
      cancelButtonText: '取消',
      inputValue: '复洗不合格'
    })
    await contaminationApi.failRewash(link.rewashBatch.id, value)
    ElMessage.warning('已登记复洗失败，原批次继续隔离')
    await refreshDetail()
    await load()
  } catch (e) {
    // 取消或后端业务提示
  }
}

function goTunnel() {
  detailVisible.value = false
  router.push('/batches')
}

function fmt(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function duration(seconds) {
  if (!seconds) return '0分钟'
  const minutes = Math.max(1, Math.round(seconds / 60))
  return minutes < 60 ? `${minutes}分钟` : `${Math.floor(minutes / 60)}小时${minutes % 60}分`
}

function pollutionName(type) {
  return { INFECTIOUS: '感染性污染', BLOOD: '血液体液', CHEMICAL: '化学污染', OTHER: '其他污染' }[type] || type
}

function pollutionTag(type) {
  return { INFECTIOUS: 'danger', BLOOD: 'warning', CHEMICAL: 'info', OTHER: 'info' }[type] || 'info'
}

function eventStatusName(status) {
  return { PENDING: '待确认', CONFIRMED: '已确认隔离', RELEASED: '已复核放行' }[status] || status
}

function eventTag(status) {
  return { PENDING: 'warning', CONFIRMED: 'danger', RELEASED: 'success' }[status] || 'info'
}

function batchStatusName(status) {
  return { RUNNING: '在产', DONE: '正常完工', HOLD: '挂起', QUARANTINED: '污染隔离', DISPOSED: '处置完成', DELETED: '已删除' }[status] || status
}

function rewashStatusName(status) {
  return { RUNNING: '生产中', DONE: '复洗完成', FAILED: '复洗失败', DELETED: '已删除' }[status] || status
}

function rewashTag(status) {
  return { RUNNING: 'warning', DONE: 'success', FAILED: 'danger', DELETED: 'info' }[status] || 'info'
}

function stepName(code) {
  return { PRE_WASH: '预洗', MAIN_WASH: '主洗', RINSE: '漂洗', NEUTRALIZE: '中和', EXTRACT: '脱水', DRY: '烘干', IRON: '烫平', FOLD: '折叠' }[code] || code
}

function typeName(type) {
  return { WASHER: '水洗机', DRYER: '烘干机', IRONER: '烫平机' }[type] || type
}

function machineStatusName(status) {
  return { RUNNING: '运行', IDLE: '待机', FAULT: '故障' }[status] || status
}

onMounted(load)
</script>
