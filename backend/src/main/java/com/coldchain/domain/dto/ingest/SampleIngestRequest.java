package com.coldchain.domain.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SampleIngestRequest {
    @NotEmpty(message = "samples 不能为空")
    @Valid
    private List<SampleItem> samples;
}
