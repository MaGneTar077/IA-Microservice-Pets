package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import com.myanimal.org.IA_service.domain.model.MessageRole;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class MessageRoleConverter implements AttributeConverter<MessageRole, String> {

    @Override
    public String convertToDatabaseColumn(MessageRole attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public MessageRole convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MessageRole.valueOf(dbData.toUpperCase());
    }
}
