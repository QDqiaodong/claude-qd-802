package com.linen.laundry.dto;

import lombok.Data;

/**
 * 推进工序请求：目标工序必须正好是当前工序的下一道，不允许跳步。
 */
@Data
public class BatchAdvanceRequest {

    /** 目标工序编码；留空表示推进到下一道 */
    private String targetStep;
    private String operator;
    private Integer waterTemp;
    private Integer detergentMl;
    /** 进入烘干 / 烫平等后续工序时实际占用的设备 */
    private Long machineId;
}
