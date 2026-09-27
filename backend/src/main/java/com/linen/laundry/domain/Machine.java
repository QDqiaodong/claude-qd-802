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
    /** RUNNING 运行 / IDLE 待机 / FAULT 故障 */
    private String status;
    /** 累计运行时长（小时） */
    private BigDecimal runHours;
    /** 车间位置 */
    private String location;
    private LocalDate lastMaintainAt;

    // ------- 非表字段 -------
    /** 未完工维修单数 */
    private Integer openRepairCount;
    /** 维修记录（嵌套 @Many 带出） */
    private List<RepairOrder> repairs;
}
