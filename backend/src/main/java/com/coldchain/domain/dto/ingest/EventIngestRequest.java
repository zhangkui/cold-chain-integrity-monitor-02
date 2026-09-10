package com.coldchain.domain.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class EventIngestRequest {
    @NotEmpty(message = "events 不能为空")
    @Valid
    private List<EventItem> events;
}
