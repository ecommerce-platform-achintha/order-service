package com.achintha.orderservice.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A list of strings as a JSON array in a text column (attachment keys). */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        return JSON.writeValueAsString(values == null ? List.of() : values);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? List.of() : List.copyOf(JSON.readValue(json, TYPE));
    }
}
