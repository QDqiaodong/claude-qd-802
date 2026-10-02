package com.linen.laundry.domain;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 命中设备窗口的具体工序片段，用于在事件详情解释批次为什么被隔离。
 */
@Data
public class QuarantineHitStep {

    private Long id;
    private Long quarantineId;
    private Long batchStepId;
    private String stepCode;
    private String stepName;
    private Integer stepOrder;
    private LocalDateTime stepStartTime;
    private LocalDateTime stepEndTime;
    private LocalDateTime overlapStartTime;
    private LocalDateTime overlapEndTime;
    private Long overlapSeconds;
    private String reason;
}
