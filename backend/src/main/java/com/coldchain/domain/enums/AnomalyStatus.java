package com.coldchain.domain.enums;

/** 异常复核状态：CONFIRMED/REJECTED 一旦确认，补传与重算都不能覆盖 */
public enum AnomalyStatus {
    OPEN,
    CONFIRMED,
    REJECTED
}
