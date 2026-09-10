package com.coldchain.web;

import com.coldchain.common.ApiResponse;
import com.coldchain.common.BusinessException;
import com.coldchain.domain.dto.BoxListItem;
import com.coldchain.domain.dto.SamplePoint;
import com.coldchain.domain.dto.TimelineResponse;
import com.coldchain.service.BoxQueryService;
import com.coldchain.service.SampleQueryService;
import com.coldchain.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/boxes")
@RequiredArgsConstructor
public class BoxController {

    private final BoxQueryService boxQueryService;
    private final SampleQueryService sampleQueryService;
    private final TimelineService timelineService;

    /** 箱体列表，支持箱号/批次/温度区间/转运节点/状态筛选 */
    @GetMapping
    public ApiResponse<List<BoxListItem>> list(
            @RequestParam(required = false) String boxCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) BigDecimal tempFrom,
            @RequestParam(required = false) BigDecimal tempTo,
            @RequestParam(required = false) String nodeCode,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(boxQueryService.listBoxes(boxCode, batchNo, tempFrom, tempTo, nodeCode, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<BoxListItem> detail(@PathVariable Long id) {
        BoxListItem item = boxQueryService.getBox(id);
        if (item == null) {
            throw new BusinessException(404, "冷链箱不存在: " + id);
        }
        return ApiResponse.ok(item);
    }

    /** 温度采样曲线：from/to（ISO 时间）、timezone=UTC|LOCAL；空数据返回空数组 */
    @GetMapping("/{id}/samples")
    public ApiResponse<List<SamplePoint>> samples(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "UTC") String timezone) {
        return ApiResponse.ok(sampleQueryService.boxSamples(id, from, to, timezone));
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<TimelineResponse> timeline(@PathVariable Long id) {
        return ApiResponse.ok(timelineService.timeline(id));
    }
}
