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
import com.linen.laundry.domain.WashBatch;
import com.linen.laundry.dto.BatchAdvanceRequest;
import com.linen.laundry.dto.BatchCreateRequest;
import com.linen.laundry.dto.StepStat;
import com.linen.laundry.mapper.LinenCategoryMapper;
import com.linen.laundry.mapper.MachineMapper;
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
    private final OperationLogRepository logRepository;

    public WashBatchService(WashBatchMapper batchMapper, LinenCategoryMapper categoryMapper,
                            MachineMapper machineMapper, OperationLogRepository logRepository) {
        this.batchMapper = batchMapper;
        this.categoryMapper = categoryMapper;
        this.machineMapper = machineMapper;
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
        batch.setStartTime(LocalDateTime.now());
        batch.setRemark(request.getRemark());
        batchMapper.insert(batch);

        batchMapper.insertStep(newStep(batch.getId(), WashProcess.codeAt(1), request.getOperator(),
                batch.getWaterTemp(), batch.getDetergentMl(), "CURRENT"));

        if ("IDLE".equals(machine.getStatus())) {
            machineMapper.updateStatus(machine.getId(), "RUNNING");
        }
        log("BATCH", batch.getId(), "新建批次", batch.getBatchNo() + " / " + category.getName()
                + " / " + weight + "kg");
        return get(batch.getId());
    }

    /**
     * 推进工序：必须按顺序，不能跳步；最后一道折叠完成后批次收口。
     */
    @Transactional
    public WashBatch advance(Long id, BatchAdvanceRequest request) {
        WashBatch batch = get(id);
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

        batchMapper.finishStep(id, current);

        if (next == null) {
            // 最后一道折叠完成：批次收口
            batch.setStatus("DONE");
            batch.setFinishTime(LocalDateTime.now());
            batchMapper.advance(batch);
            int pieces = batch.getWeightKg() == null ? 0
                    : batch.getWeightKg().divide(BigDecimal.valueOf(1.2), 0, RoundingMode.HALF_UP).intValue();
            if (pieces > 0 && batch.getCategoryId() != null) {
                categoryMapper.addStock(batch.getCategoryId(), pieces);
            }
            if (batch.getMachineId() != null) {
                machineMapper.addRunHours(batch.getMachineId(), BigDecimal.valueOf(0.8));
                Machine machine = machineMapper.selectById(batch.getMachineId());
                if (machine != null && "RUNNING".equals(machine.getStatus())) {
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
        batch.setStatus("RUNNING");
        batch.setFinishTime(null);
        batchMapper.advance(batch);
        batchMapper.insertStep(newStep(id, next, operator, waterTemp, detergent, "CURRENT"));

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
        WashBatch batch = get(id);
        batchMapper.deleteSteps(id);
        batchMapper.delete(id);
        log("BATCH", id, "删除批次", batch.getBatchNo());
    }

    private BatchStep newStep(Long batchId, String stepCode, String operator,
                              Integer waterTemp, Integer detergent, String status) {
        BatchStep step = new BatchStep();
        step.setBatchId(batchId);
        step.setStepCode(stepCode);
        step.setStepName(WashProcess.nameOf(stepCode));
        step.setStepOrder(WashProcess.orderOf(stepCode));
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
