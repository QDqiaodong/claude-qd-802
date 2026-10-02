package com.linen.laundry.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linen.laundry.common.BizException;
import com.linen.laundry.domain.ContaminationEvent;
import com.linen.laundry.domain.Machine;
import com.linen.laundry.domain.OperationLog;
import com.linen.laundry.domain.QuarantineHitStep;
import com.linen.laundry.domain.QuarantineRecord;
import com.linen.laundry.domain.RewashLink;
import com.linen.laundry.domain.WashBatch;
import com.linen.laundry.dto.ContaminationActionRequest;
import com.linen.laundry.dto.ContaminationEventRequest;
import com.linen.laundry.dto.RewashCreateRequest;
import com.linen.laundry.mapper.ContaminationEventMapper;
import com.linen.laundry.mapper.LinenCategoryMapper;
import com.linen.laundry.mapper.MachineMapper;
import com.linen.laundry.mapper.QuarantineHitStepMapper;
import com.linen.laundry.mapper.QuarantineRecordMapper;
import com.linen.laundry.mapper.RewashLinkMapper;
import com.linen.laundry.mapper.WashBatchMapper;
import com.linen.laundry.repository.OperationLogRepository;

/**
 * 医院布草污染事件：登记 → 确认命中 → 隔离批次 → 复洗 → 全部完成后复核放行。
 * 所有状态跃迁在数据库事务内使用行锁 + 条件 UPDATE，重复提交和并发请求只会得到同一份有效结果。
 */
@Service
public class ContaminationService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Set<String> POLLUTION_TYPES = Set.of("INFECTIOUS", "BLOOD", "CHEMICAL", "OTHER");

    private final ContaminationEventMapper eventMapper;
    private final QuarantineRecordMapper quarantineMapper;
    private final QuarantineHitStepMapper hitStepMapper;
    private final RewashLinkMapper rewashMapper;
    private final WashBatchMapper batchMapper;
    private final MachineMapper machineMapper;
    private final LinenCategoryMapper categoryMapper;
    private final WashBatchService batchService;
    private final OperationLogRepository logRepository;

    public ContaminationService(ContaminationEventMapper eventMapper,
                                QuarantineRecordMapper quarantineMapper,
                                QuarantineHitStepMapper hitStepMapper,
                                RewashLinkMapper rewashMapper,
                                WashBatchMapper batchMapper,
                                MachineMapper machineMapper,
                                LinenCategoryMapper categoryMapper,
                                WashBatchService batchService,
                                OperationLogRepository logRepository) {
        this.eventMapper = eventMapper;
        this.quarantineMapper = quarantineMapper;
        this.hitStepMapper = hitStepMapper;
        this.rewashMapper = rewashMapper;
        this.batchMapper = batchMapper;
        this.machineMapper = machineMapper;
        this.categoryMapper = categoryMapper;
        this.batchService = batchService;
        this.logRepository = logRepository;
    }

    public List<ContaminationEvent> list() {
        return eventMapper.selectAll();
    }

    public ContaminationEvent get(Long id) {
        ContaminationEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new BizException("污染事件不存在：id=" + id);
        }
        return event;
    }

    @Transactional
    public ContaminationEvent create(ContaminationEventRequest request) {
        validateRequest(request);
        Machine machine = machineMapper.selectById(request.getMachineId());
        if (machine == null) {
            throw new BizException("发生设备不存在：id=" + request.getMachineId());
        }

        ContaminationEvent event = new ContaminationEvent();
        event.setMachineId(machine.getId());
        event.setReporter(blankTo(request.getReporter(), "车间人员"));
        event.setFoundTime(request.getFoundTime());
        event.setImpactStartTime(request.getImpactStartTime());
        event.setImpactEndTime(request.getImpactEndTime());
        event.setPollutionType(request.getPollutionType());
        event.setDescription(request.getDescription());
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());
        insertWithUniqueNo(event);

        log("CONTAMINATION", event.getId(), "登记污染事件",
                event.getEventNo() + " / " + machine.getName() + " / " + typeName(event.getPollutionType()));
        return get(event.getId());
    }

    /**
     * 确认事件：锁定事件和命中的工序片段，按批次生成唯一隔离记录。
     */
    @Transactional
    public ContaminationEvent confirm(Long id, ContaminationActionRequest request) {
        return doConfirm(id, request);
    }

    private ContaminationEvent doConfirm(Long id, ContaminationActionRequest request) {
        ContaminationEvent event = eventMapper.selectByIdForUpdate(id);
        if (event == null) {
            throw new BizException("污染事件不存在：id=" + id);
        }
        if ("RELEASED".equals(event.getStatus())) {
            throw new BizException("污染事件【" + event.getEventNo() + "】已复核放行，不能重复确认");
        }
        if ("CONFIRMED".equals(event.getStatus())) {
            // 多人同时点击：第一份确认结果已存在，直接返回同一事件，不重复生成隔离记录。
            return get(id);
        }

        Machine eventMachine = machineMapper.selectByIdForUpdate(event.getMachineId());
        if (eventMachine == null) {
            throw new BizException("发生设备不存在或已删除：id=" + event.getMachineId());
        }

        String operator = operatorOf(request);
        List<QuarantineHitStep> candidateSteps = hitStepMapper.findOverlappingSteps(
                event.getMachineId(), event.getImpactStartTime(), event.getImpactEndTime());

        Map<Long, java.util.List<QuarantineHitStep>> grouped = new HashMap<>();
        for (QuarantineHitStep hit : candidateSteps) {
            Long batchId = batchMapper.findBatchIdByStepId(hit.getBatchStepId());
            if (batchId == null) {
                continue;
            }
            hit.setReason(buildHitReason(event, hit));
            grouped.computeIfAbsent(batchId, key -> new java.util.ArrayList<>()).add(hit);
        }

        for (Long batchId : grouped.keySet().stream().sorted().toList()) {
            WashBatch batch = batchMapper.selectByIdForUpdate(batchId);
            if (batch == null || "DELETED".equals(batch.getStatus())) {
                continue;
            }
            List<QuarantineHitStep> steps = grouped.get(batchId);
            String summaryReason = buildBatchReason(event, batch, steps);

            QuarantineRecord record = new QuarantineRecord();
            record.setEventId(event.getId());
            record.setBatchId(batchId);
            record.setStatus("ACTIVE");
            record.setHitReason(summaryReason);
            record.setCreatedBy(operator);
            record.setCreatedAt(LocalDateTime.now());
            quarantineMapper.insert(record);

            for (QuarantineHitStep hit : steps) {
                hit.setId(null);
                hit.setQuarantineId(record.getId());
                hitStepMapper.insert(hit);
            }

            batchMapper.markQuarantined(batchId);
            if ("DONE".equals(batch.getStatus())) {
                int pieces = batchService.piecesOf(batch);
                if (pieces > 0) {
                    categoryMapper.subtractStock(batch.getCategoryId(), pieces);
                }
            }
        }

        if (eventMapper.confirm(id, operator) == 0) {
            throw new BizException("污染事件状态已变化，请刷新后查看");
        }
        machineMapper.updateStatus(event.getMachineId(), "QUARANTINED");
        log("CONTAMINATION", id, "确认污染事件",
                event.getEventNo() + " 命中隔离 " + grouped.size() + " 个批次");
        return get(id);
    }

    /**
     * 发起复洗：同一隔离批次只允许一个在产复洗批次；并发下重复请求被行锁和唯一索引拒绝。
     */
    @Transactional
    public RewashLink startRewash(RewashCreateRequest request) {
        if (request == null || request.getQuarantineId() == null) {
            throw new BizException("请选择需要复洗的隔离记录");
        }
        Long eventId = quarantineMapper.selectEventIdForUpdate(request.getQuarantineId());
        if (eventId == null) {
            throw new BizException("隔离记录不存在：id=" + request.getQuarantineId());
        }
        ContaminationEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            throw new BizException("污染事件不存在：id=" + eventId);
        }
        QuarantineRecord quarantine = quarantineMapper.selectByIdForUpdate(request.getQuarantineId());
        if (quarantine == null) {
            throw new BizException("隔离记录不存在：id=" + request.getQuarantineId());
        }
        if (!eventId.equals(quarantine.getEventId())) {
            throw new BizException("隔离记录与污染事件不一致，请刷新后重试");
        }
        if (!"ACTIVE".equals(quarantine.getStatus()) || !"CONFIRMED".equals(event.getStatus())) {
            throw new BizException("污染事件或隔离记录已放行，不能再发起复洗");
        }

        WashBatch source = batchMapper.selectByIdForUpdate(quarantine.getBatchId());
        if (source == null || "DELETED".equals(source.getStatus())) {
            throw new BizException("原隔离批次不存在或已删除");
        }
        List<RewashLink> active = rewashMapper.selectActiveBySourceForUpdate(source.getId());
        if (!active.isEmpty()) {
            throw new BizException("该隔离批次已有在产复洗批次，不能重复发起");
        }

        WashBatch rewashBatch = batchService.createRewashBatchLocked(source, request.getMachineId(),
                request.getWeightKg(), request.getDetergentMl(), request.getWaterTemp(),
                blankTo(request.getOperator(), "处置员"), request.getRemark());

        RewashLink link = new RewashLink();
        link.setSourceBatchId(source.getId());
        link.setRewashBatchId(rewashBatch.getId());
        link.setOperatorName(blankTo(request.getOperator(), "处置员"));
        link.setStatus("RUNNING");
        link.setStartedAt(LocalDateTime.now());
        link.setCreatedAt(LocalDateTime.now());
        try {
            rewashMapper.insert(link);
        } catch (DuplicateKeyException duplicate) {
            // 并发点击下 uk_rewash_active 兜底，事务回滚刚插入的新批次，只保留已有在产复洗。
            List<RewashLink> existing = rewashMapper.selectActiveBySourceForUpdate(source.getId());
            if (!existing.isEmpty()) {
                throw new BizException("该隔离批次已有在产复洗批次："
                        + (existing.get(0).getRewashBatch() == null ? existing.get(0).getId()
                        : existing.get(0).getRewashBatch().getBatchNo()));
            }
            throw duplicate;
        }
        quarantineMapper.linkRewash(quarantine.getId(), link.getId());

        log("CONTAMINATION", event.getId(), "发起复洗",
                source.getBatchNo() + " → " + rewashBatch.getBatchNo());
        return enrichRewash(link);
    }

    /**
     * 复洗失败：复洗批次和链路同步关闭；没有成功复洗前事件保持隔离。
     */
    @Transactional
    public RewashLink failRewash(Long rewashBatchId, String reason) {
        WashBatch batch = batchMapper.selectByIdForUpdate(rewashBatchId);
        if (batch == null) {
            throw new BizException("复洗批次不存在");
        }
        RewashLink locked = rewashMapper.selectByRewashBatchId(rewashBatchId);
        if (locked == null) {
            throw new BizException("该批次不是污染复洗批次");
        }
        if ("FAILED".equals(locked.getStatus())) {
            return enrichRewash(locked);
        }
        if (!"RUNNING".equals(locked.getStatus())) {
            throw new BizException("当前复洗状态为【" + rewashStatusName(locked.getStatus()) + "】，不能登记失败");
        }
        String failureReason = reason == null || reason.isBlank() ? "复洗失败" : reason;
        if (rewashMapper.markFailed(locked.getId(), failureReason) == 0) {
            throw new BizException("复洗状态已变化，请刷新后查看");
        }
        batchMapper.markDeleted(rewashBatchId);
        log("CONTAMINATION", null, "复洗失败", batch.getBatchNo() + "：" + failureReason);
        return enrichRewash(rewashMapper.selectById(locked.getId()));
    }

    /**
     * 复核放行：每个命中批次都必须至少有一个当前关联且最终 DONE 的复洗批次。
     * 任一失败、删除、在产复洗都不能放行；重复点击返回已放行的同一份结果。
     */
    @Transactional
    public ContaminationEvent release(Long id, ContaminationActionRequest request) {
        ContaminationEvent event = eventMapper.selectByIdForUpdate(id);
        if (event == null) {
            throw new BizException("污染事件不存在：id=" + id);
        }
        if ("RELEASED".equals(event.getStatus())) {
            return get(id);
        }
        if (!"CONFIRMED".equals(event.getStatus())) {
            throw new BizException("污染事件尚未确认，不能复核放行");
        }
        Machine eventMachine = machineMapper.selectByIdForUpdate(event.getMachineId());
        if (eventMachine == null) {
            throw new BizException("发生设备不存在或已删除：id=" + event.getMachineId());
        }

        List<Long> quarantineIds = quarantineMapper.selectActiveIdsByEventForUpdate(id);
        if (quarantineIds.isEmpty()) {
            throw new BizException("事件没有有效隔离记录，请先确认污染事件");
        }
        for (Long quarantineId : quarantineIds) {
            QuarantineRecord quarantine = quarantineMapper.selectByIdForUpdate(quarantineId);
            WashBatch source = batchMapper.selectByIdForUpdate(quarantine.getBatchId());
            List<RewashLink> links = rewashMapper.selectByQuarantineIdForUpdate(quarantineId);
            if (links.isEmpty()) {
                throw new BizException("隔离批次【" + (source == null ? quarantineId : source.getBatchNo())
                        + "】尚未发起复洗");
            }
            boolean allDone = links.stream().allMatch(link -> "DONE".equals(link.getStatus()));
            if (!allDone) {
                RewashLink blocker = links.stream()
                        .filter(link -> !"DONE".equals(link.getStatus()))
                        .findFirst()
                        .orElse(links.get(0));
                throw new BizException("隔离批次【" + (source == null ? quarantineId : source.getBatchNo())
                        + "】存在" + rewashStatusName(blocker.getStatus())
                        + "的复洗批次，任一复洗失败、删除或在产都必须继续保持隔离");
            }
        }

        String operator = operatorOf(request);
        quarantineMapper.releaseByEvent(id);
        for (Long quarantineId : quarantineIds) {
            QuarantineRecord quarantine = quarantineMapper.selectByIdForUpdate(quarantineId);
            if (quarantineMapper.countActiveByBatch(quarantine.getBatchId()) == 0) {
                batchMapper.markDisposed(quarantine.getBatchId());
            }
        }
        if (eventMapper.release(id, operator) == 0) {
            throw new BizException("污染事件状态已变化，请刷新后查看");
        }
        if (contaminationEventRemaining(eventMachine)) {
            machineMapper.updateStatus(event.getMachineId(), "QUARANTINED");
        } else {
            long openRepairs = eventMachine.getRepairs() == null ? 0
                    : eventMachine.getRepairs().stream().filter(repair -> "OPEN".equals(repair.getStatus())).count();
            machineMapper.updateStatus(event.getMachineId(), openRepairs > 0 ? "FAULT" : "IDLE");
        }
        log("CONTAMINATION", id, "复核放行", event.getEventNo() + " 全部复洗批次已完成");
        return get(id);
    }

    private boolean contaminationEventRemaining(Machine machine) {
        return eventMapper.countActiveByMachine(machine.getId()) > 0;
    }

    private void validateRequest(ContaminationEventRequest request) {
        if (request == null) {
            throw new BizException("请填写污染事件");
        }
        if (request.getMachineId() == null) {
            throw new BizException("请选择发生设备");
        }
        if (request.getFoundTime() == null) {
            throw new BizException("请选择发现时间");
        }
        if (request.getImpactStartTime() == null || request.getImpactEndTime() == null) {
            throw new BizException("请选择污染影响起止时间");
        }
        if (request.getImpactEndTime().isBefore(request.getImpactStartTime())) {
            throw new BizException("影响结束时间不能早于开始时间");
        }
        if (request.getImpactEndTime().isBefore(request.getFoundTime())) {
            throw new BizException("影响结束时间不能早于发现时间");
        }
        if (Duration.between(request.getImpactStartTime(), request.getImpactEndTime()).toHours() > 72) {
            throw new BizException("单次污染影响窗口不能超过 72 小时");
        }
        if (request.getPollutionType() == null || !POLLUTION_TYPES.contains(request.getPollutionType())) {
            throw new BizException("请选择污染类型");
        }
    }

    private void insertWithUniqueNo(ContaminationEvent event) {
        for (int i = 0; i < 5; i++) {
            event.setEventNo(nextEventNo());
            try {
                eventMapper.insert(event);
                return;
            } catch (DuplicateKeyException ignored) {
                // 并发下极小概率撞号时换新编号重试。
            }
        }
        throw new BizException("污染事件编号生成失败，请重试");
    }

    private String buildBatchReason(ContaminationEvent event, WashBatch batch,
                                    java.util.List<QuarantineHitStep> hits) {
        QuarantineHitStep first = hits.get(0);
        return "批次【" + batch.getBatchNo() + "】在 " + first.getStepName()
                + (hits.size() > 1 ? " 等 " + hits.size() + " 道工序" : "")
                + " 实际占用设备【" + event.getMachineName() + "】，占用区间 "
                + format(first.getStepStartTime()) + "～" + format(first.getStepEndTime())
                + " 与污染窗口 " + format(event.getImpactStartTime()) + "～" + format(event.getImpactEndTime())
                + " 重叠，重叠 " + durationText(first.getOverlapSeconds()) + "。";
    }

    private String buildHitReason(ContaminationEvent event, QuarantineHitStep hit) {
        return hit.getStepName() + "设备占用与污染窗口重叠 " + durationText(hit.getOverlapSeconds());
    }

    private String nextEventNo() {
        return "CE" + LocalDateTime.now().format(NO_DATE) + "-"
                + String.format("%03d", (System.nanoTime() % 900) + 100);
    }

    private String operatorOf(ContaminationActionRequest request) {
        return request == null || blankTo(request.getOperator(), null) == null
                ? "车间人员" : request.getOperator().trim();
    }

    private RewashLink enrichRewash(RewashLink link) {
        if (link == null) {
            return null;
        }
        link.setSourceBatch(batchMapper.selectById(link.getSourceBatchId()));
        link.setRewashBatch(batchMapper.selectById(link.getRewashBatchId()));
        return link;
    }

    private String format(LocalDateTime time) {
        return time == null ? "当前" : time.format(TEXT_TIME);
    }

    private String durationText(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return "0分钟";
        }
        long minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes < 60) {
            return minutes + "分钟";
        }
        long hours = minutes / 60;
        long remain = minutes % 60;
        return remain == 0 ? hours + "小时" : hours + "小时" + remain + "分";
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String typeName(String type) {
        return switch (type) {
            case "INFECTIOUS" -> "感染性污染";
            case "BLOOD" -> "血液体液污染";
            case "CHEMICAL" -> "化学污染";
            default -> "其他污染";
        };
    }

    private String rewashStatusName(String status) {
        return switch (status) {
            case "RUNNING" -> "生产中";
            case "DONE" -> "已完成";
            case "FAILED" -> "失败";
            case "DELETED" -> "已删除";
            default -> status;
        };
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
