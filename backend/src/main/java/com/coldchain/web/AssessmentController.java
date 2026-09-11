package com.coldchain.web;

import com.coldchain.common.ApiResponse;
import com.coldchain.domain.dto.assessment.AssessmentSummary;
import com.coldchain.domain.dto.assessment.AssessmentView;
import com.coldchain.service.assessment.AssessmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 箱体综合评估。
 *  POST   /api/boxes/{id}/assessments                 触发生成（总是产生新版本，历史不覆盖）
 *  GET    /api/boxes/{id}/assessments/latest          查看最新评估
 *  GET    /api/boxes/{id}/assessments                 历史版本列表
 *  GET    /api/boxes/{id}/assessments/versions/{ver}  指定历史版本
 *
 * 错误区分：404 箱体/评估不存在；422 数据不足（结论 UNASSESSABLE，响应 data 携带已持久化结果）；
 * 500 评估内部失败。生成/查看/失败均写追加式审计日志，操作人取当前请求身份。
 */
@RestController
@RequestMapping("/api/boxes/{boxId}/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @PostMapping
    public ApiResponse<AssessmentView> generate(@PathVariable Long boxId) {
        return ApiResponse.ok(assessmentService.generate(boxId));
    }

    @GetMapping("/latest")
    public ApiResponse<AssessmentView> latest(@PathVariable Long boxId) {
        return ApiResponse.ok(assessmentService.getLatest(boxId));
    }

    @GetMapping
    public ApiResponse<List<AssessmentSummary>> versions(@PathVariable Long boxId) {
        return ApiResponse.ok(assessmentService.listVersions(boxId));
    }

    @GetMapping("/versions/{version}")
    public ApiResponse<AssessmentView> version(@PathVariable Long boxId,
                                               @PathVariable Integer version) {
        return ApiResponse.ok(assessmentService.getVersion(boxId, version));
    }
}
