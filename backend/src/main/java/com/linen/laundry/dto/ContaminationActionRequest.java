package com.linen.laundry.dto;

import lombok.Data;

/**
 * 多人同时确认 / 复核时记录操作人。
 */
@Data
public class ContaminationActionRequest {

    private String operator;
}
