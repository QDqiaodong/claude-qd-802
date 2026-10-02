package com.linen.laundry.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 洗涤批次：一缸布草从预洗到折叠的流转主体。
 */
@Data
public class WashBatch {

    private Long id;
    private String batchNo;
    private Long categoryId;
    /** 重量 kg */
    private BigDecimal weightKg;
    /** 当前工序编码 */
    private String currentStep;
    /** 当前工序号 1~8 */
    private Integer currentStepOrder;
    /** 洗涤剂用量 ml */
    private Integer detergentMl;
    /** 水温 ℃ */
    private Integer waterTemp;
    private Long machineId;
    private String operator;
    /** RUNNING 在产 / DONE 已完成 / HOLD 挂起 / QUARANTINED 污染隔离 */
    private String status;
    /** NORMAL 正常批次 / REWASH 污染复洗批次 */
    private String batchType;
    private Long sourceBatchId;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private String remark;

    // ------- 非表字段 -------
    private String categoryName;
    private String machineName;
    private String machineCode;
    /** 进度百分比 */
    private Integer progress;
    /** 当前有效隔离数量；大于 0 时任何工序都不能推进、不能按正常完工处理 */
    private Integer activeQuarantineCount;
    /** 灯墙 / 隧道展示用污染来源摘要 */
    private String quarantineSource;
    /** 工序流转记录（嵌套 @Many 带出） */
    private List<BatchStep> steps;
    /** 品类（嵌套 @One 带出） */
    private LinenCategory category;
    /** 设备（嵌套 @One 带出） */
    private Machine machine;
}
