package com.coldchain.domain.dto.ingest;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CalibrationItem {
    private String deviceCode;
    private Long deviceId;

    @NotBlank(message = "calibrateTime 不能为空")
    private String calibrateTime;
    private String timezone;

    @NotBlank(message = "agency 不能为空")
    private String agency;

    private BigDecimal offsetBefore;
    private BigDecimal offsetAfter;

    @NotBlank(message = "result 不能为空（PASS/FAIL）")
    private String result;

    @NotBlank(message = "certNo 不能为空")
    private String certNo;

    private String operator;
}
