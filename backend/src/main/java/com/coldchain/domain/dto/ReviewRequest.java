package com.coldchain.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReviewRequest {
    /** CONFIRM / REJECT / REOPEN */
    @NotBlank(message = "action 不能为空")
    private String action;

    private String comment;
    /** 复核人，缺省从 X-Operator 请求头取，再缺省 anonymous */
    private String reviewer;
}
