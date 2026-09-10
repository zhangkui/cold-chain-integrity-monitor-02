package com.coldchain.domain.dto.ingest;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EventItem {
    private String deviceCode;
    private Long deviceId;
    private String boxCode;

    @NotBlank(message = "eventType 不能为空（OPEN/CLOSE）")
    private String eventType;

    @NotBlank(message = "eventTime 不能为空")
    private String eventTime;

    private String timezone;
    private String note;
}
