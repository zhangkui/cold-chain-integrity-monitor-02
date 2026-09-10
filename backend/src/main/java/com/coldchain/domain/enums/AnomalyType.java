package com.coldchain.domain.enums;

/** 异常类型 */
public enum AnomalyType {
    TEMP_EXCURSION, // 连续超温
    OFFLINE,        // 传感器离线
    INTERVAL,       // 采样间隔异常
    HASH_BROKEN     // 哈希链断裂（疑似篡改）
}
