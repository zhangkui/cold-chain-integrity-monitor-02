package com.coldchain.domain.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CalibrationIngestRequest {
    @NotEmpty(message = "calibrations 不能为空")
    @Valid
    private List<CalibrationItem> calibrations;
}
