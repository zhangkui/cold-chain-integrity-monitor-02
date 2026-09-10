package com.coldchain.domain.dto.ingest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SampleItem {
    /** 设备编号（与 deviceId 二选一，优先使用编号） */
    private String deviceCode;
    private Long deviceId;
    /** 箱号；可选，缺省用设备当前绑定箱体 */
    private String boxCode;

    @NotNull(message = "seq 不能为空")
    @PositiveOrZero(message = "seq 必须 >= 0")
    private Long seq;

    @NotBlank(message = "sampleTime 不能为空")
    private String sampleTime;

    /** 设备所在 IANA 时区，如 Asia/Shanghai；sampleTime 不带偏移量时必填 */
    private String timezone;

    @NotNull(message = "temperature 不能为空")
    @DecimalMin(value = "-273.15", message = "温度不能低于绝对零度")
    private BigDecimal temperature;

    /** REALTIME / BACKFILL，缺省 REALTIME */
    private String source;
}
