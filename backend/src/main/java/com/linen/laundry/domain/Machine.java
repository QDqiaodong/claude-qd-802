package com.linen.laundry.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 水洗机 / 烘干机 / 烫平机设备台账。
 */
@Data
public class Machine {

    private Long id;
    private String code;
    private String name;
    private String model;
    /** WASHER 水洗机 / DRYER 烘干机 / IRONER 烫平机 */
    private String machineType;
    /** 容量 kg */
    private BigDecimal capacityKg;
    /** RUNNING 运行 / IDLE 待机 / FAULT 故障 / QUARANTINED 污染隔离 */
    private String status;
    /** 累计运行时长（小时） */
    private BigDecimal runHours;
    /** 车间位置 */
    private String location;
    private LocalDate lastMaintainAt;

    // ------- 非表字段 -------
    /** 未完工维修单数 */
    private Integer openRepairCount;
    /** 已确认未放行的污染事件数 */
    private Integer activeContaminationCount;
    /** 该设备尚未完成复洗处置的隔离批次数 */
    private Integer pendingQuarantineCount;
    /** 灯墙展示的污染来源 */
    private String contaminationSources;
    /** 维修记录（嵌套 @Many 带出） */
    private List<RepairOrder> repairs;
}
