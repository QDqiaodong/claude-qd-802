package com.linen.laundry.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 处置人员从隔离记录发起复洗。
 */
@Data
public class RewashCreateRequest {

    private Long quarantineId;
    private Long machineId;
    private BigDecimal weightKg;
    private Integer detergentMl;
    private Integer waterTemp;
    private String operator;
    private String remark;
}
