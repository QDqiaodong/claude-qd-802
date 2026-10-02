package com.linen.laundry.dto;

import lombok.Data;

/**
 * 复洗批次无法继续生产时登记失败原因。
 */
@Data
public class RewashFailureRequest {

    private String reason;
}
