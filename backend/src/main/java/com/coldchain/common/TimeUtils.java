package com.coldchain.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间纠正工具：设备上报的“本地时间 + IANA 时区”统一转换为 UTC 存储。
 */
public final class TimeUtils {
    /** 参与哈希计算的规范时间格式（毫秒精度、UTC、无偏移歧义） */
    public static final DateTimeFormatter HASH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private TimeUtils() {
    }

    public static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (java.time.DateTimeException e) {
            throw new BusinessException("无法识别的时区: " + timezone);
        }
    }

    /**
     * 解析上报时间。
     * 支持：带偏移量的 ISO（2026-09-09T08:00:00+08:00，以偏移量为准）
     *      不带偏移量的本地时间（结合 timezone 参数解释）
     */
    public static Instant parseToInstant(String raw, String timezone) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("采样时间不能为空");
        }
        String text = raw.trim().replace(' ', 'T');
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续按本地时间解析
        }
        // LocalDateTime（可选秒以下精度）
        try {
            LocalDateTime local = LocalDateTime.parse(text);
            return local.atZone(zoneOf(timezone)).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试宽松格式
        }
        try {
            LocalDateTime local = LocalDateTime.parse(text,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            return local.atZone(zoneOf(timezone)).toInstant();
        } catch (DateTimeParseException e) {
            throw new BusinessException("无法解析的时间格式: " + raw);
        }
    }

    public static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static LocalDateTime toLocal(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant, zone);
    }

    public static String hashFormat(Instant instant) {
        return HASH_FORMAT.format(instant);
    }
}
