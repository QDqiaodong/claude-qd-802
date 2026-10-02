package com.linen.laundry.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linen.laundry.common.ApiResponse;
import com.linen.laundry.domain.ContaminationEvent;
import com.linen.laundry.domain.RewashLink;
import com.linen.laundry.dto.ContaminationActionRequest;
import com.linen.laundry.dto.ContaminationEventRequest;
import com.linen.laundry.dto.RewashCreateRequest;
import com.linen.laundry.dto.RewashFailureRequest;
import com.linen.laundry.service.ContaminationService;

/**
 * 医院布草污染事件追溯与隔离处置闭环。
 */
@RestController
@RequestMapping("/api/contamination/events")
public class ContaminationController {

    private final ContaminationService service;

    public ContaminationController(ContaminationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ContaminationEvent>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ContaminationEvent> detail(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<ContaminationEvent> create(@RequestBody ContaminationEventRequest request) {
        return ApiResponse.success("污染事件已登记", service.create(request));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<ContaminationEvent> confirm(@PathVariable Long id,
                                                   @RequestBody(required = false) ContaminationActionRequest request) {
        return ApiResponse.success("污染事件已确认，隔离记录已生成", service.confirm(id, request));
    }

    @PostMapping("/rewashes")
    public ApiResponse<RewashLink> startRewash(@RequestBody RewashCreateRequest request) {
        return ApiResponse.success("复洗批次已创建", service.startRewash(request));
    }

    @PostMapping("/rewashes/{rewashBatchId}/fail")
    public ApiResponse<RewashLink> failRewash(@PathVariable Long rewashBatchId,
                                              @RequestBody(required = false) RewashFailureRequest request) {
        String reason = request == null ? null : request.getReason();
        return ApiResponse.success("已登记复洗失败并保持隔离", service.failRewash(rewashBatchId, reason));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<ContaminationEvent> release(@PathVariable Long id,
                                                   @RequestBody(required = false) ContaminationActionRequest request) {
        return ApiResponse.success("全部复洗完成，污染事件已复核放行", service.release(id, request));
    }
}
