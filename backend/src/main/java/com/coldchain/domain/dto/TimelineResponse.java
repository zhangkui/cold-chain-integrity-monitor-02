package com.coldchain.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 转运时间线：节点 / 开箱事件 / 异常按时间归并 */
@Data
public class TimelineResponse {
    private Long boxId;
    private String boxCode;
    private String shipmentNo;
    private String timezone;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        /** NODE / EVENT / ANOMALY */
        private String kind;
        private LocalDateTime timeUtc;
        private LocalDateTime timeLocal;
        private String title;
        private String detail;
        private String type;
        private String status;
    }
}
