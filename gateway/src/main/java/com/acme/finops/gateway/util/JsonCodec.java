package com.acme.finops.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared JSON codec for control/CI paths.
 */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    public static JsonNode readTree(String raw) throws JsonProcessingException {
        return MAPPER.readTree(raw);
    }

    public static String writeString(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }
}

