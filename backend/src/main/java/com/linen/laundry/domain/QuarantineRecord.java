package com.linen.laundry.domain;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 隔离记录：一个污染事件命中一个原始批次只允许有一条有效隔离记录。
 */
@Data
public class QuarantineRecord {

    private Long id;
    private Long eventId;
    private Long batchId;
    /** ACTIVE 待处置 / RELEASED 已随事件复核放行 */
    private String status;
    private String hitReason;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime releasedAt;

    // ------- 非表字段 -------
    private String eventNo;
    private String eventStatus;
    private String pollutionType;
    private Long machineId;
    private String machineCode;
    private String machineName;
    private LocalDateTime impactStartTime;
    private LocalDateTime impactEndTime;
    private WashBatch batch;
    private List<QuarantineHitStep> hitSteps;
    private List<RewashLink> rewashLinks;
}
