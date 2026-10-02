package com.linen.laundry.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 车间人员登记污染事件。
 */
@Data
public class ContaminationEventRequest {

    private Long machineId;
    private String reporter;
    private LocalDateTime foundTime;
    private LocalDateTime impactStartTime;
    private LocalDateTime impactEndTime;
    private String pollutionType;
    private String description;
}
