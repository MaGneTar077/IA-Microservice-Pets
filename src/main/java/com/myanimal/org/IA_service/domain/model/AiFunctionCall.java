package com.myanimal.org.IA_service.domain.model;

import java.util.Map;

public record AiFunctionCall(String id, String name, Map<String, Object> args, String thoughtSignature) {
}
