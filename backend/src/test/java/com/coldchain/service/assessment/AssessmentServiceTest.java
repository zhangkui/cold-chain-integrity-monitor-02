package com.coldchain.service.assessment;

import com.coldchain.common.BusinessException;
import com.coldchain.domain.entity.BoxAssessment;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.BoxAssessmentMapper;
import com.coldchain.mapper.BoxEventMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import com.coldchain.mapper.TransportNodeMapper;
import com.coldchain.service.AuditService;
import com.coldchain.service.RedisLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static com.coldchain.service.assessment.AssessmentFixtures.box;
import static com.coldchain.service.assessment.AssessmentFixtures.chain;
import static com.coldchain.service.assessment.AssessmentFixtures.device;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估服务编排测试：版本化持久化（不覆盖）、404/422/500 错误区分、
 * 追加式审计、操作人沿用请求身份。
 */
class AssessmentServiceTest {

    private ColdBoxMapper boxMapper;
    private DeviceMapper deviceMapper;
    private TemperatureSampleMapper sampleMapper;
    private AnomalyMapper anomalyMapper;
    private ShipmentMapper shipmentMapper;
    private TransportNodeMapper nodeMapper;
    private BoxEventMapper eventMapper;
    private BoxAssessmentMapper assessmentMapper;
    private AuditService auditService;
    private RedisLockService lockService;
    private ObjectMapper objectMapper;

    private AssessmentService service;
    private final List<BoxAssessment> stored = new ArrayList<>();
    private long idSeq = 1000;

    @BeforeEach
    void setUp() {
        boxMapper = mock(ColdBoxMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        sampleMapper = mock(TemperatureSampleMapper.class);
        anomalyMapper = mock(AnomalyMapper.class);
        shipmentMapper = mock(ShipmentMapper.class);
        nodeMapper = mock(TransportNodeMapper.class);
        eventMapper = mock(BoxEventMapper.class);
        assessmentMapper = mock(BoxAssessmentMapper.class);
        auditService = mock(AuditService.class);
        lockService = mock(RedisLockService.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AssessmentService(boxMapper, deviceMapper, sampleMapper, anomalyMapper,
                shipmentMapper, nodeMapper, eventMapper, assessmentMapper, auditService,
                lockService, objectMapper);

        when(lockService.tryLock(any())).thenReturn(() -> { });
        when(anomalyMapper.selectList(any())).thenReturn(List.of());
        when(shipmentMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(eventMapper.selectList(any())).thenReturn(List.of());
        // 版本号取自已持久化记录；insert 模拟自增 id
        when(assessmentMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(stored));
        doAnswer(inv -> {
            BoxAssessment row = inv.getArgument(0);
            row.setId(idSeq++);
            stored.add(row);
            return 1;
        }).when(assessmentMapper).insert(any(BoxAssessment.class));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ColdBox givenBoxWithSamples(List<TemperatureSample> samples) {
        ColdBox b = box(1, 2, 8);
        Device d = device(b.getDeviceId());
        when(boxMapper.selectById(1L)).thenReturn(b);
        when(deviceMapper.selectById(b.getDeviceId())).thenReturn(d);
        when(sampleMapper.selectList(any())).thenReturn(samples);
        return b;
    }

    @Test
    @DisplayName("箱体不存在 => 404 BOX_NOT_FOUND，并写 ASSESS_GENERATE_FAILED 审计")
    void missingBox() {
        when(boxMapper.selectById(404L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.generate(404L));
        assertEquals(404, ex.getCode());
        assertEquals("BOX_NOT_FOUND", ex.getErrorCode());
        verify(auditService).log(eq("BOX_ASSESSMENT"), eq(404L), eq("ASSESS_GENERATE_FAILED"), any());
        verify(assessmentMapper, never()).insert(any(BoxAssessment.class));
    }

    @Test
    @DisplayName("空箱：不可评估版本持久化后抛 422 DATA_INSUFFICIENT，错误携带已落库结果")
    void emptyBoxUnassessablePersistsAnd422() {
        givenBoxWithSamples(List.of());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.generate(1L));
        assertEquals(422, ex.getCode());
        assertEquals("DATA_INSUFFICIENT", ex.getErrorCode());
        assertEquals(1, stored.size());
        assertEquals("UNASSESSABLE", stored.get(0).getConclusion());
        var view = assertInstanceOf(com.coldchain.domain.dto.assessment.AssessmentView.class, ex.getData());
        assertEquals(1, view.getVersion());
        // 不可评估也写了生成审计（审计事实先于异常抛出）
        verify(auditService).log(eq("BOX_ASSESSMENT"), any(), eq("ASSESS_GENERATE"), any());
    }

    @Test
    @DisplayName("重复触发：版本递增、历史不覆盖，GET 返回最新版本")
    void repeatedGenerateKeepsVersions() {
        givenBoxWithSamples(chain(101L, 1L, 12, 5.0));

        var v1 = service.generate(1L);
        var v2 = service.generate(1L);

        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());
        assertEquals(2, stored.size());
        assertEquals("PASS", stored.get(0).getConclusion());
        assertEquals("PASS", stored.get(1).getConclusion());
        // 历史行对象各自独立，未被覆盖
        assertEquals(1, stored.get(0).getVersion());
        assertNotNull(stored.get(0).getRuleSnapshotJson());
        assertNotNull(stored.get(0).getDataBoundaryJson());
        assertNotNull(stored.get(0).getMetricsJson());

        when(assessmentMapper.selectOne(any())).thenReturn(stored.get(1));
        var latest = service.getLatest(1L);
        assertEquals(2, latest.getVersion());
    }

    @Test
    @DisplayName("篡改采样导致链断裂 => NEEDS_REVIEW/BROKEN，且评估过程不写异常表")
    void tamperedChainNeedsReview() {
        List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
        AssessmentFixtures.tamper(samples, 6, 99.0);
        givenBoxWithSamples(samples);

        var view = service.generate(1L);
        assertEquals("NEEDS_REVIEW", view.getConclusion());
        assertEquals("BROKEN", view.getChainStatus());
        assertEquals(12, view.getChainChecked());
        // 只读链校验：评估绝不插入 HASH_BROKEN 异常
        verify(anomalyMapper, never()).insert(any(com.coldchain.domain.entity.Anomaly.class));
        // 规则快照与数据边界已持久化
        BoxAssessment row = stored.get(0);
        assertEquals(AssessmentEngine.RULE_VERSION, row.getRuleVersion());
        assertTrue(row.getDataBoundaryJson().contains("samplesFromUtc"));
    }

    @Test
    @DisplayName("从未评估时查看 => 404 ASSESSMENT_NOT_FOUND，并写失败审计")
    void latestMissing() {
        givenBoxWithSamples(List.of());
        when(assessmentMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getLatest(1L));
        assertEquals(404, ex.getCode());
        assertEquals("ASSESSMENT_NOT_FOUND", ex.getErrorCode());
        verify(auditService).log(eq("BOX_ASSESSMENT"), eq(1L), eq("ASSESS_VIEW_FAILED"), any());
    }

    @Test
    @DisplayName("内部失败（锁服务异常）=> 500 ASSESSMENT_FAILED，并写 GENERATE_FAILED 审计")
    void internalFailureAudited() {
        givenBoxWithSamples(chain(101L, 1L, 6, 5.0));
        when(lockService.tryLock(any())).thenThrow(new IllegalStateException("redis down"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.generate(1L));
        assertEquals(500, ex.getCode());
        assertEquals("ASSESSMENT_FAILED", ex.getErrorCode());
        verify(auditService).log(eq("BOX_ASSESSMENT"), eq(1L), eq("ASSESS_GENERATE_FAILED"), any());
    }

    @Test
    @DisplayName("操作人取当前请求身份（MDC X-Operator），并随评估持久化")
    void operatorFromRequestIdentity() {
        MDC.put("operator", "auditor-li");
        givenBoxWithSamples(chain(101L, 1L, 6, 5.0));
        var view = service.generate(1L);
        assertEquals("auditor-li", view.getGeneratedBy());
        assertEquals("auditor-li", stored.get(0).getGeneratedBy());
    }

    @Test
    @DisplayName("查看历史版本成功 => 写 ASSESS_VIEW 追加式审计")
    void viewVersionAudited() {
        givenBoxWithSamples(chain(101L, 1L, 6, 5.0));
        service.generate(1L);
        when(assessmentMapper.selectOne(any())).thenReturn(stored.get(0));
        service.getVersion(1L, 1);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        // generate 与 view 都以评估记录 id 为实体，共两条追加审计
        verify(auditService, org.mockito.Mockito.times(2))
                .log(eq("BOX_ASSESSMENT"), eq(stored.get(0).getId()), action.capture(), any());
        assertEquals(List.of("ASSESS_GENERATE", "ASSESS_VIEW"), action.getAllValues());
    }
}
