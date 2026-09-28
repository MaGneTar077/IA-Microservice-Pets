package com.myanimal.org.IA_service.domain.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class AiRequest {

    private String systemInstruction;
    private List<AiMessage> messages;
    private List<Map<String, Object>> tools;
}
