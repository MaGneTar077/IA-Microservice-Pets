package com.myanimal.org.IA_service.domain.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class AiResponse {

    private String text;
    private String finishReason;
    private String modelUsed;
    private AiUsage usage;
    private List<AiFunctionCall> functionCalls;
}
