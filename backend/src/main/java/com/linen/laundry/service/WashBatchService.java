package com.linen.laundry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linen.laundry.common.BizException;
import com.linen.laundry.common.WashProcess;
import com.linen.laundry.domain.BatchStep;
import com.linen.laundry.domain.LinenCategory;
import com.linen.laundry.domain.Machine;
import com.linen.laundry.domain.OperationLog;
import com.linen.laundry.domain.RewashLink;
import com.linen.laundry.domain.WashBatch;
import com.linen.laundry.dto.BatchAdvanceRequest;
import com.linen.laundry.dto.BatchCreateRequest;
import com.linen.laundry.dto.StepStat;
import com.linen.laundry.mapper.ContaminationEventMapper;
import com.linen.laundry.mapper.LinenCategoryMapper;
import com.linen.laundry.mapper.MachineMapper;
import com.linen.laundry.mapper.QuarantineRecordMapper;
import com.linen.laundry.mapper.RewashLinkMapper;
import com.linen.laundry.mapper.WashBatchMapper;
import com.linen.laundry.repository.OperationLogRepository;

/**
 * 洗涤批次与工序流转：预洗→主洗→漂洗→中和→脱水→烘干→烫平→折叠，必须按顺序推进。
 */
@Service
public class WashBatchService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WashBatchMapper batchMapper;
    private final LinenCategoryMapper categoryMapper;
    private final MachineMapper machineMapper;
    private final ContaminationEventMapper contaminationEventMapper;
    private final QuarantineRecordMapper quarantineMapper;
    private final RewashLinkMapper rewashMapper;
    private final OperationLogRepository logRepository;

    public WashBatchService(WashBatchMapper batchMapper, LinenCategoryMapper categoryMapper,
                            MachineMapper machineMapper, ContaminationEventMapper contaminationEventMapper,
                            QuarantineRecordMapper quarantineMapper,
                            RewashLinkMapper rewashMapper, OperationLogRepository logRepository) {
        this.batchMapper = batchMapper;
        this.categoryMapper = categoryMapper;
        this.machineMapper = machineMapper;
        this.contaminationEventMapper = contaminationEventMapper;
        this.quarantineMapper = quarantineMapper;
        this.rewashMapper = rewashMapper;
        this.logRepository = logRepository;
    }

    public List<WashBatch> list(String status, String stepCode, Long categoryId) {
        if ((status == null || status.isBlank()) && (stepCode == null || stepCode.isBlank()) && categoryId == null) {
            return batchMapper.selectAll();
        }
        return batchMapper.tunnel(status, stepCode, categoryId);
    }

    public WashBatch get(Long id) {
        WashBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BizException("洗涤批次不存在：id=" + id);
        }
        return batch;
    }

    /** 排产：设备故障状态不允许排产 */
    @Transactional
    public WashBatch create(BatchCreateRequest request) {
        if (request.getCategoryId() == null) {
            throw new BizException("请选择布草品类");
        }
        LinenCategory category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BizException("布草品类不存在：id=" + request.getCategoryId());
        }
        if (request.getMachineId() == null) {
            throw new BizException("请选择水洗机");
        }
        Machine machine = machineMapper.selectById(request.getMachineId());
        if (machine == null) {
            throw new BizException("设备不存在：id=" + request.getMachineId());
        }
        if ("FAULT".equals(machine.getStatus())) {
            throw new BizException("设备【" + machine.getName() + "】处于故障状态，不允许排产，请先报修并完工");
        }
        if (contaminationEventMapper.countActiveByMachine(machine.getId()) > 0) {
            throw new BizException("设备【" + machine.getName() + "】存在已确认未放行的污染事件，禁止继续排产");
        }
        BigDecimal weight = request.getWeightKg();
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("批次重量必须大于 0");
        }
        if (machine.getCapacityKg() != null && weight.compareTo(machine.getCapacityKg()) > 0) {
            throw new BizException("批次重量 " + weight + "kg 超出设备【" + machine.getName() + "】容量 "
                    + machine.getCapacityKg() + "kg");
        }
        if (request.getWaterTemp() != null && (request.getWaterTemp() < 0 || request.getWaterTemp() > 180)) {
            throw new BizException("水温必须在 0~180℃ 之间");
        }

        WashBatch batch = new WashBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setCategoryId(category.getId());
        batch.setWeightKg(weight);
        batch.setCurrentStep(WashProcess.codeAt(1));
        batch.setCurrentStepOrder(1);
        batch.setDetergentMl(request.getDetergentMl() == null ? 800 : request.getDetergentMl());
        batch.setWaterTemp(request.getWaterTemp() == null
                ? WashProcess.defaultTemp(WashProcess.codeAt(1)) : request.getWaterTemp());
        batch.setMachineId(machine.getId());
        batch.setOperator(request.getOperator());
        batch.setStatus("RUNNING");
        batch.setBatchType("NORMAL");
        batch.setSourceBatchId(null);
        batch.setStartTime(LocalDateTime.now());
        batch.setRemark(request.getRemark());
        batchMapper.insert(batch);

        batchMapper.insertStep(newStep(batch.getId(), WashProcess.codeAt(1), machine.getId(),
                request.getOperator(), batch.getWaterTemp(), batch.getDetergentMl(), "CURRENT"));

        if ("IDLE".equals(machine.getStatus())) {
            machineMapper.updateStatus(machine.getId(), "RUNNING");
        }
        log("BATCH", batch.getId(), "新建批次", batch.getBatchNo() + " / " + category.getName()
                + " / " + weight + "kg");
        return get(batch.getId());
    }

    /**
     * 污染处置复洗：复制原批次形成独立新批次，调用方负责先锁定原批次并写入 rewash_link。
     */
    @Transactional
    public WashBatch createRewashBatch(WashBatch source, Long machineId, java.math.BigDecimal weight,
                                       Integer detergentMl, Integer waterTemp, String operator, String remark) {
        return createRewashBatch(source, machineId, weight, detergentMl, waterTemp, operator, remark, false);
    }

    /** 调用方已经锁定原批次时使用，避免嵌套事务再次取锁。 */
    public WashBatch createRewashBatchLocked(WashBatch source, Long machineId, java.math.BigDecimal weight,
                                             Integer detergentMl, Integer waterTemp, String operator, String remark) {
        return createRewashBatch(source, machineId, weight, detergentMl, waterTemp, operator, remark, true);
    }

    private WashBatch createRewashBatch(WashBatch source, Long machineId, java.math.BigDecimal weight,
                                        Integer detergentMl, Integer waterTemp, String operator, String remark,
                                        boolean sourceAlreadyLocked) {
        WashBatch lockedSource = sourceAlreadyLocked ? source : batchMapper.selectByIdForUpdate(source.getId());
        if (lockedSource == null || "DELETED".equals(lockedSource.getStatus())) {
            throw new BizException("原隔离批次不存在或已删除");
        }
        Long targetMachineId = machineId;
        List<Machine> candidates = null;
        if (targetMachineId == null) {
            candidates = machineMapper.selectAvailable("WASHER").stream()
                    .filter(item -> !item.getId().equals(source.getMachineId()))
                    .toList();
        }
        if (targetMachineId == null && candidates.isEmpty()) {
            throw new BizException("没有可用的医感复洗水洗机");
        }
        Machine machine;
        if (candidates == null) {
            machine = machineMapper.selectByIdForUpdate(targetMachineId);
            if (machine == null) {
                throw new BizException("复洗设备不存在：id=" + targetMachineId);
            }
            if ("FAULT".equals(machine.getStatus())) {
                throw new BizException("设备【" + machine.getName() + "】处于故障状态，不能发起复洗");
            }
            if ("QUARANTINED".equals(machine.getStatus())
                    || contaminationEventMapper.countActiveByMachine(machine.getId()) > 0) {
                throw new BizException("设备【" + machine.getName() + "】仍有未放行污染事件，请改用隔离专区设备复洗");
            }
        } else {
            Machine picked = null;
            for (Machine item : candidates) {
                Machine locked = machineMapper.selectByIdForUpdate(item.getId());
                if (locked == null || "FAULT".equals(locked.getStatus())
                        || "QUARANTINED".equals(locked.getStatus())
                        || contaminationEventMapper.countActiveByMachine(locked.getId()) > 0) {
                    continue;
                }
                picked = locked;
                break;
            }
            if (picked == null) {
                throw new BizException("暂无可用于污染复洗的水洗机，请先选择或释放设备");
            }
            machine = picked;
            targetMachineId = machine.getId();
        }
        java.math.BigDecimal actualWeight = weight == null ? source.getWeightKg() : weight;
        if (actualWeight == null || actualWeight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("复洗重量必须大于 0");
        }
        if (machine.getCapacityKg() != null
                && actualWeight.compareTo(machine.getCapacityKg()) > 0) {
            throw new BizException("复洗重量 " + actualWeight + "kg 超出设备容量 " + machine.getCapacityKg() + "kg");
        }
        int temp = waterTemp == null ? WashProcess.defaultTemp(WashProcess.codeAt(1)) : waterTemp;
        int detergent = detergentMl == null ? source.getDetergentMl() : detergentMl;
        String actualOperator = operator == null || operator.isBlank() ? source.getOperator() : operator;

        WashBatch batch = new WashBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setCategoryId(source.getCategoryId());
        batch.setWeightKg(actualWeight);
        batch.setCurrentStep(WashProcess.codeAt(1));
        batch.setCurrentStepOrder(1);
        batch.setDetergentMl(detergent);
        batch.setWaterTemp(temp);
        batch.setMachineId(targetMachineId);
        batch.setOperator(actualOperator);
        batch.setStatus("RUNNING");
        batch.setBatchType("REWASH");
        batch.setSourceBatchId(source.getId());
        batch.setStartTime(LocalDateTime.now());
        batch.setRemark(remark == null || remark.isBlank() ? "污染复洗批次，来源 " + source.getBatchNo() : remark);
        batchMapper.insert(batch);
        batchMapper.insertStep(newStep(batch.getId(), WashProcess.codeAt(1), targetMachineId,
                actualOperator, temp, detergent, "CURRENT"));
        if ("IDLE".equals(machine.getStatus())) {
            machineMapper.updateStatus(machine.getId(), "RUNNING");
        }
        return get(batch.getId());
    }

    /**
     * 推进工序：必须按顺序，不能跳步；最后一道折叠完成后批次收口。
     */
    @Transactional
    public WashBatch advance(Long id, BatchAdvanceRequest request) {
        WashBatch batch = batchMapper.selectByIdForUpdate(id);
        if (batch == null) {
            throw new BizException("洗涤批次不存在：id=" + id);
        }
        if ("QUARANTINED".equals(batch.getStatus()) || quarantineMapper.countActiveByBatch(id) > 0) {
            throw new BizException("批次【" + batch.getBatchNo() + "】存在有效污染隔离，不能继续推进或按正常完工处理");
        }
        if ("DELETED".equals(batch.getStatus())) {
            throw new BizException("批次【" + batch.getBatchNo() + "】已删除，不能继续推进");
        }
        if ("DISPOSED".equals(batch.getStatus())) {
            throw new BizException("批次【" + batch.getBatchNo() + "】污染处置已闭环，不能继续推进");
        }
        if ("DONE".equals(batch.getStatus())) {
            throw new BizException("批次【" + batch.getBatchNo() + "】已完成，不能再推进工序");
        }
        String current = batch.getCurrentStep();
        int currentOrder = WashProcess.orderOf(current);
        if (currentOrder <= 0) {
            throw new BizException("当前工序编码非法：" + current);
        }
        String next = WashProcess.nextOf(current);
        String target = request == null || request.getTargetStep() == null || request.getTargetStep().isBlank()
                ? next
                : request.getTargetStep();
        if (next != null && !next.equals(target)) {
            throw new BizException("工序必须按顺序推进，不能跳步：当前【"
                    + WashProcess.nameOf(current) + "】，下一道应为【" + WashProcess.nameOf(next)
                    + "】，不能直接跳到【" + WashProcess.nameOf(target) + "】");
        }

        Long nextMachineId = resolveNextMachineId(batch, next, request);
        Long oldMachineId = batch.getMachineId();
        batchMapper.finishStep(id, current);

        if (next == null) {
            // 最后一道折叠完成：批次收口
            batch.setStatus("DONE");
            batch.setFinishTime(LocalDateTime.now());
            if (batchMapper.advance(batch) == 0) {
                throw new BizException("批次【" + batch.getBatchNo() + "】已被隔离，不能完工入库");
            }
            int pieces = piecesOf(batch);
            if (pieces > 0 && batch.getCategoryId() != null) {
                categoryMapper.addStock(batch.getCategoryId(), pieces);
            }
            RewashLink completedRewash = rewashMapper.selectByRewashBatchId(id);
            if (completedRewash != null) {
                if (rewashMapper.markDone(completedRewash.getId()) == 0) {
                    throw new BizException("复洗链路已关闭，不能重复完工");
                }
            }
            if (batch.getMachineId() != null) {
                machineMapper.addRunHours(batch.getMachineId(), BigDecimal.valueOf(0.8));
                Machine machine = machineMapper.selectById(batch.getMachineId());
                if (machine != null && "RUNNING".equals(machine.getStatus())
                        && contaminationEventMapper.countActiveByMachine(machine.getId()) == 0) {
                    machineMapper.updateStatus(machine.getId(), "IDLE");
                }
            }
            log("BATCH", id, "批次完工", batch.getBatchNo() + " 折叠完成，入库 " + pieces + " 件");
            return get(id);
        }

        int nextOrder = WashProcess.orderOf(next);
        Integer waterTemp = request != null && request.getWaterTemp() != null
                ? request.getWaterTemp()
                : WashProcess.defaultTemp(next);
        Integer detergent = request != null && request.getDetergentMl() != null
                ? request.getDetergentMl()
                : batch.getDetergentMl();
        String operator = request != null && request.getOperator() != null
                ? request.getOperator()
                : batch.getOperator();

        batch.setCurrentStep(next);
        batch.setCurrentStepOrder(nextOrder);
        batch.setWaterTemp(waterTemp);
        batch.setDetergentMl(detergent);
        batch.setOperator(operator);
        batch.setMachineId(nextMachineId);
        batch.setStatus("RUNNING");
        batch.setFinishTime(null);
        if (batchMapper.advance(batch) == 0) {
            throw new BizException("批次【" + batch.getBatchNo() + "】已被隔离，不能推进");
        }
        batchMapper.insertStep(newStep(id, next, nextMachineId, operator, waterTemp, detergent, "CURRENT"));
        updateOccupiedMachines(oldMachineId, nextMachineId);

        log("BATCH", id, "推进工序", batch.getBatchNo() + " → " + WashProcess.nameOf(next));
        return get(id);
    }

    public List<StepStat> stepStats() {
        List<StepStat> stats = batchMapper.stepStats();
        List<StepStat> full = new ArrayList<>();
        for (int order = 1; order <= WashProcess.size(); order++) {
            String code = WashProcess.codeAt(order);
            StepStat stat = new StepStat();
            stat.setStepCode(code);
            stat.setStepName(WashProcess.nameOf(code));
            stat.setStepOrder(order);
            stat.setBatchCount(0);
            stat.setTotalWeight(0);
            for (StepStat s : stats) {
                if (code.equals(s.getStepCode())) {
                    stat.setBatchCount(s.getBatchCount());
                    stat.setTotalWeight(s.getTotalWeight() == null ? 0 : s.getTotalWeight().intValue());
                }
            }
            full.add(stat);
        }
        return full;
    }

    public Map<String, Object> summary() {
        return batchMapper.summary();
    }

    @Transactional
    public void delete(Long id) {
        WashBatch batch = batchMapper.selectByIdForUpdate(id);
        if (batch == null) {
            throw new BizException("洗涤批次不存在：id=" + id);
        }
        if ("QUARANTINED".equals(batch.getStatus()) || quarantineMapper.countActiveByBatch(id) > 0) {
            throw new BizException("批次【" + batch.getBatchNo() + "】仍在污染隔离中，不能删除");
        }
        RewashLink link = rewashMapper.selectByRewashBatchId(id);
        if (link != null) {
            if (quarantineMapper.countReleasedQuarantineByRewash(link.getId()) > 0
                    && quarantineMapper.countActiveQuarantineByRewash(link.getId()) == 0) {
                throw new BizException("复洗批次已用于污染事件复核放行，删除会破坏追溯闭环");
            }
            rewashMapper.markDeleted(link.getId());
            batchMapper.markDeleted(id);
            log("BATCH", id, "删除复洗批次", batch.getBatchNo() + " 保留追溯链路并标记删除");
            return;
        }
        batchMapper.deleteSteps(id);
        batchMapper.delete(id);
        log("BATCH", id, "删除批次", batch.getBatchNo());
    }

    public int piecesOf(WashBatch batch) {
        return batch.getWeightKg() == null ? 0
                : batch.getWeightKg().divide(BigDecimal.valueOf(1.2), 0, RoundingMode.HALF_UP).intValue();
    }

    private Long resolveNextMachineId(WashBatch batch, String next, BatchAdvanceRequest request) {
        if (next == null) {
            return batch.getMachineId();
        }
        int order = WashProcess.orderOf(next);
        if (order <= WashProcess.orderOf("EXTRACT")) {
            return batch.getMachineId();
        }
        Long requested = request == null ? null : request.getMachineId();
        String expectedType = "DRY".equals(next) ? "DRYER" : "IRONER";
        Long targetMachineId;
        if (requested == null) {
            List<Machine> available = machineMapper.selectAvailable(expectedType);
            available = available.stream()
                    .filter(item -> contaminationEventMapper.countActiveByMachine(item.getId()) == 0)
                    .toList();
            if (available.isEmpty()) {
                throw new BizException("没有可用的" + ("DRYER".equals(expectedType) ? "烘干机" : "烫平机")
                        + "，请先处理设备污染或故障");
            }
            targetMachineId = available.get(0).getId();
        } else {
            Machine machine = machineMapper.selectById(requested);
            if (machine == null) {
                throw new BizException("设备不存在：id=" + requested);
            }
            if (!expectedType.equals(machine.getMachineType())) {
                throw new BizException(WashProcess.nameOf(next) + "必须选择"
                        + ("DRYER".equals(expectedType) ? "烘干机" : "烫平机"));
            }
            if ("FAULT".equals(machine.getStatus())) {
                throw new BizException("设备【" + machine.getName() + "】故障，不能占用");
            }
            if (contaminationEventMapper.countActiveByMachine(machine.getId()) > 0) {
                throw new BizException("设备【" + machine.getName() + "】存在已确认未放行的污染事件，不能占用");
            }
            if (batch.getWeightKg() != null && machine.getCapacityKg() != null
                    && batch.getWeightKg().compareTo(machine.getCapacityKg()) > 0) {
                throw new BizException("批次重量超过设备【" + machine.getName() + "】容量");
            }
            targetMachineId = requested;
        }
        return targetMachineId;
    }

    private void updateOccupiedMachines(Long previousMachineId, Long nextMachineId) {
        if (previousMachineId != null && !previousMachineId.equals(nextMachineId)
                && contaminationEventMapper.countActiveByMachine(previousMachineId) == 0) {
            machineMapper.updateStatus(previousMachineId, "IDLE");
        }
        if (nextMachineId != null) {
            machineMapper.updateStatus(nextMachineId, "RUNNING");
        }
    }

    private BatchStep newStep(Long batchId, String stepCode, Long machineId, String operator,
                              Integer waterTemp, Integer detergent, String status) {
        BatchStep step = new BatchStep();
        step.setBatchId(batchId);
        step.setStepCode(stepCode);
        step.setStepName(WashProcess.nameOf(stepCode));
        step.setStepOrder(WashProcess.orderOf(stepCode));
        step.setMachineId(machineId);
        step.setWaterTemp(waterTemp);
        step.setDetergentMl(detergent);
        step.setOperator(operator);
        step.setStatus(status);
        step.setStartTime(LocalDateTime.now());
        return step;
    }

    private String nextBatchNo() {
        return "PC" + LocalDateTime.now().format(NO_DATE) + "-"
                + String.format("%03d", (int) (System.currentTimeMillis() % 900) + 100);
    }

    private void log(String bizType, Long bizId, String action, String detail) {
        OperationLog entity = new OperationLog();
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        entity.setActionName(action);
        entity.setDetail(detail);
        entity.setCreatedAt(LocalDateTime.now());
        logRepository.save(entity);
    }
}
