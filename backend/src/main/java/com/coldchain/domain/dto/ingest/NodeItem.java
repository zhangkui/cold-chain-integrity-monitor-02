package com.coldchain.domain.dto.ingest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NodeItem {
    /** 转运单号；不存在则自动创建 */
    @NotBlank(message = "shipmentNo 不能为空")
    private String shipmentNo;

    /** 箱号 */
    @NotBlank(message = "boxCode 不能为空")
    private String boxCode;

    @NotNull(message = "seq 不能为空")
    @Min(value = 0, message = "seq 必须 >= 0")
    private Integer seq;

    @NotBlank(message = "nodeCode 不能为空")
    private String nodeCode;

    @NotBlank(message = "nodeName 不能为空")
    private String nodeName;

    /** DISPATCH / TRANSIT / ARRIVAL / SIGN，缺省 TRANSIT */
    private String nodeType;

    private String plannedTime;
    private String actualTime;
    private String timezone;
    private String operator;
    private String location;
}
