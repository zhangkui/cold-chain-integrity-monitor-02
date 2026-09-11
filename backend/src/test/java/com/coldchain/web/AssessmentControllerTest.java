package com.coldchain.web;

import com.coldchain.common.BusinessException;
import com.coldchain.common.GlobalExceptionHandler;
import com.coldchain.domain.dto.assessment.AssessmentView;
import com.coldchain.service.assessment.AssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评估接口 HTTP 契约：404 箱体不存在 / 422 数据不足（携带已落库结果）/ 500 内部失败，
 * 成功响应走统一信封 code=0。使用 standalone MockMvc，避免 @MapperScan 拉起数据源。
 */
class AssessmentControllerTest {

    private MockMvc mvc;
    private AssessmentService assessmentService;

    @BeforeEach
    void setUp() {
        assessmentService = mock(AssessmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AssessmentController(assessmentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AssessmentView view(String conclusion, int version) {
        AssessmentView v = new AssessmentView();
        v.setId(100L + version);
        v.setBoxId(1L);
        v.setBoxCode("BOX-1");
        v.setVersion(version);
        v.setConclusion(conclusion);
        v.setConclusionLabel("不可评估");
        v.setSummary("不可评估：箱体无任何温度采样");
        return v;
    }

    @Test
    @DisplayName("箱体不存在 => 404 BOX_NOT_FOUND")
    void boxNotFoundIs404WithErrorCode() throws Exception {
        when(assessmentService.generate(99L))
                .thenThrow(new BusinessException(404, "BOX_NOT_FOUND", "冷链箱不存在: 99"));
        mvc.perform(post("/api/boxes/99/assessments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.errorCode").value("BOX_NOT_FOUND"));
    }

    @Test
    @DisplayName("数据不足 => 422 DATA_INSUFFICIENT，且 data 携带已持久化的不可评估结果")
    void unassessableIs422AndCarriesPersistedView() throws Exception {
        when(assessmentService.generate(1L)).thenThrow(new BusinessException(
                422, "DATA_INSUFFICIENT", "数据不足", view("UNASSESSABLE", 1)));
        mvc.perform(post("/api/boxes/1/assessments"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.conclusion").value("UNASSESSABLE"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("内部失败 => 500 ASSESSMENT_FAILED")
    void internalFailureIs500() throws Exception {
        when(assessmentService.generate(1L))
                .thenThrow(new BusinessException(500, "ASSESSMENT_FAILED", "评估生成失败"));
        mvc.perform(post("/api/boxes/1/assessments"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.errorCode").value("ASSESSMENT_FAILED"));
    }

    @Test
    @DisplayName("查看成功 => 统一信封 code=0")
    void latestSuccessEnvelope() throws Exception {
        when(assessmentService.getLatest(eq(1L))).thenReturn(view("PASS", 2));
        mvc.perform(get("/api/boxes/1/assessments/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.conclusion").value("PASS"));
    }

    @Test
    @DisplayName("从未评估 => 404 ASSESSMENT_NOT_FOUND，与箱体不存在区分")
    void neverAssessedIs404DistinctCode() throws Exception {
        when(assessmentService.getLatest(any()))
                .thenThrow(new BusinessException(404, "ASSESSMENT_NOT_FOUND", "该箱体尚无评估记录: 1"));
        mvc.perform(get("/api/boxes/1/assessments/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ASSESSMENT_NOT_FOUND"));
    }
}
