package com.linen.laundry.domain;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 医院布草污染事件：设备在影响时间段内经过的批次必须进入隔离处置闭环。
 */
@Data
public class ContaminationEvent {

    private Long id;
    private String eventNo;
    private Long machineId;
    private String reporter;
    private LocalDateTime foundTime;
    private LocalDateTime impactStartTime;
    private LocalDateTime impactEndTime;
    /** INFECTIOUS 感染性 / BLOOD 血液体液 / CHEMICAL 化学污染 / OTHER 其他 */
    private String pollutionType;
    private String description;
    /** PENDING 待确认 / CONFIRMED 已确认隔离 / RELEASED 已复核放行 */
    private String status;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String releasedBy;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;

    // ------- 非表字段 -------
    private String machineCode;
    private String machineName;
    private Integer quarantineCount;
    private Integer pendingCount;
    private Integer rewashCount;
    private List<QuarantineRecord> quarantineRecords;
}
