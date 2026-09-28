package com.myanimal.org.IA_service.domain.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class AiPart {

    private String text;
    private String mimeType;
    private byte[] data;

    private String functionCallId;
    private String functionCallName;
    private Map<String, Object> functionCallArgs;
    private String thoughtSignature;

    private String functionResponseId;
    private String functionResponseName;
    private Map<String, Object> functionResponseData;

    public static AiPart ofText(String text) {
        return AiPart.builder().text(text).build();
    }

    public static AiPart ofBinary(String mimeType, byte[] data) {
        return AiPart.builder().mimeType(mimeType).data(data).build();
    }

    public static AiPart ofFunctionCall(String id, String name, Map<String, Object> args, String thoughtSignature) {
        return AiPart.builder()
                .functionCallId(id)
                .functionCallName(name)
                .functionCallArgs(args)
                .thoughtSignature(thoughtSignature)
                .build();
    }

    public static AiPart ofFunctionResponse(String id, String name, Map<String, Object> response) {
        return AiPart.builder()
                .functionResponseId(id)
                .functionResponseName(name)
                .functionResponseData(response)
                .build();
    }
}
