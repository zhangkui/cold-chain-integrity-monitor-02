package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.common.HashUtils;
import com.coldchain.domain.dto.ReviewRequest;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.AnomalyReview;
import com.coldchain.domain.entity.EvidenceAttachment;
import com.coldchain.domain.enums.AnomalyStatus;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.AnomalyReviewMapper;
import com.coldchain.mapper.EvidenceAttachmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 异常复核 + 证据附件版本管理。
 *  - CONFIRMED / REJECTED 是终态语义：补传与重算不覆盖（见 DetectionService），
 *    只能由人工 REOPEN 后重新进入 OPEN；
 *  - 证据按 (anomalyId, version) 版本化存储，旧版本永不覆盖，全部可追溯。
 */
@Service
@RequiredArgsConstructor
public class AnomalyService {

    private final AnomalyMapper anomalyMapper;
    private final AnomalyReviewMapper reviewMapper;
    private final EvidenceAttachmentMapper evidenceMapper;
    private final AuditService auditService;

    @Value("${coldchain.evidence.dir}")
    private String evidenceDir;

    @Transactional
    public Anomaly review(Long anomalyId, ReviewRequest request, String operator) {
        Anomaly anomaly = anomalyMapper.selectById(anomalyId);
        if (anomaly == null) {
            throw new BusinessException(404, "异常不存在: " + anomalyId);
        }
        String action = request.getAction().trim().toUpperCase();
        String reviewer = request.getReviewer() != null && !request.getReviewer().isBlank()
                ? request.getReviewer() : operator;
        AnomalyStatus newStatus = switch (action) {
            case "CONFIRM" -> AnomalyStatus.CONFIRMED;
            case "REJECT" -> AnomalyStatus.REJECTED;
            case "REOPEN" -> AnomalyStatus.OPEN;
            default -> throw new BusinessException("action 只能是 CONFIRM / REJECT / REOPEN");
        };

        AnomalyReview review = new AnomalyReview();
        review.setAnomalyId(anomalyId);
        review.setAction(action);
        review.setComment(request.getComment());
        review.setReviewer(reviewer);
        reviewMapper.insert(review);

        String oldStatus = anomaly.getStatus();
        anomaly.setStatus(newStatus.name());
        anomaly.setConfirmedBy(reviewer);
        anomaly.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC));
        anomaly.setReviewComment(request.getComment());
        anomalyMapper.updateById(anomaly);

        auditService.log("ANOMALY", anomalyId, "REVIEW_" + action,
                Map.of("reviewer", reviewer,
                        "comment", request.getComment() == null ? "" : request.getComment(),
                        "fromStatus", oldStatus, "toStatus", newStatus.name()));
        return anomaly;
    }

    @Transactional
    public EvidenceAttachment addEvidence(Long anomalyId, MultipartFile file, String note, String operator)
            throws IOException {
        Anomaly anomaly = anomalyMapper.selectById(anomalyId);
        if (anomaly == null) {
            throw new BusinessException(404, "异常不存在: " + anomalyId);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("证据文件为空");
        }
        Integer maxVersion = evidenceMapper.selectList(Wrappers.<EvidenceAttachment>lambdaQuery()
                        .eq(EvidenceAttachment::getAnomalyId, anomalyId))
                .stream().map(EvidenceAttachment::getVersion).max(Comparator.naturalOrder()).orElse(0);
        int version = maxVersion + 1;

        // 计算内容指纹（二进制安全）
        byte[] bytes = file.getBytes();
        String fileHash = HashUtils.sha256(bytes);

        String safeName = Paths.get(file.getOriginalFilename() == null ? "evidence.bin"
                : file.getOriginalFilename()).getFileName().toString();
        String storedName = anomalyId + "-v" + version + "-" + fileHash.substring(0, 12) + "-" + safeName;
        Path dir = Paths.get(evidenceDir, String.valueOf(anomalyId));
        Files.createDirectories(dir);
        Path target = dir.resolve(storedName);
        file.transferTo(target.toFile());

        EvidenceAttachment attachment = new EvidenceAttachment();
        attachment.setAnomalyId(anomalyId);
        attachment.setVersion(version);
        attachment.setFileName(safeName);
        attachment.setStoredPath(target.toString());
        attachment.setFileHash(fileHash);
        attachment.setSizeBytes((long) bytes.length);
        attachment.setContentType(file.getContentType());
        attachment.setNote(note);
        attachment.setUploadedBy(operator);
        evidenceMapper.insert(attachment);

        auditService.log("EVIDENCE", attachment.getId(), "UPLOAD_V" + version,
                Map.of("anomalyId", anomalyId, "fileName", safeName,
                        "size", bytes.length, "hash", fileHash));
        return attachment;
    }

    public List<EvidenceAttachment> listEvidence(Long anomalyId) {
        return evidenceMapper.selectList(Wrappers.<EvidenceAttachment>lambdaQuery()
                .eq(EvidenceAttachment::getAnomalyId, anomalyId)
                .orderByAsc(EvidenceAttachment::getVersion));
    }

    public List<AnomalyReview> listReviews(Long anomalyId) {
        return reviewMapper.selectList(Wrappers.<AnomalyReview>lambdaQuery()
                .eq(AnomalyReview::getAnomalyId, anomalyId)
                .orderByAsc(AnomalyReview::getId));
    }
}
