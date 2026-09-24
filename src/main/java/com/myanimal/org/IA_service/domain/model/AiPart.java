package com.myanimal.org.IA_service.domain.model;

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

    public static AiPart ofText(String text) {
        return AiPart.builder().text(text).build();
    }

    public static AiPart ofBinary(String mimeType, byte[] data) {
        return AiPart.builder().mimeType(mimeType).data(data).build();
    }
}
