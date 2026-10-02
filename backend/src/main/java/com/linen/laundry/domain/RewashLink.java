package com.linen.laundry.domain;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 复洗链路：每次发起复洗形成一条可追溯记录和一个新的复洗批次。
 */
@Data
public class RewashLink {

    private Long id;
    private Long sourceBatchId;
    private Long rewashBatchId;
    private String operatorName;
    /** RUNNING 复洗在产 / DONE 复洗完成 / FAILED 复洗失败 / DELETED 批次已删除 */
    private String status;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    // ------- 非表字段 -------
    private WashBatch sourceBatch;
    private WashBatch rewashBatch;
}
