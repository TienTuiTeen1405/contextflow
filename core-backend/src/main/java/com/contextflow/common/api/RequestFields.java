package com.contextflow.common.api;

import com.contextflow.contracts.NonNullIfPresent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.Set;

/** Keeps field presence separate from nullable values; PATCH absent never means clear. */
public record RequestFields<T>(T value, Set<String> fields) {
    public RequestFields {
        fields = Set.copyOf(fields);
    }

    public boolean has(String field) {
        return fields.contains(field);
    }

    public static <T> RequestFields<T> read(
            ObjectNode body, Class<T> type, ObjectMapper mapper, Validator validator) {
        if (body == null || body.isEmpty())
            throw ApiProblem.invalid("At least one field is required.");
        try {
            for (var component : type.getRecordComponents()) {
                if (!component.isAnnotationPresent(NonNullIfPresent.class)) continue;
                var property =
                        type.getDeclaredField(component.getName())
                                .getAnnotation(JsonProperty.class);
                String name = property == null ? component.getName() : property.value();
                if (body.has(name) && body.get(name).isNull())
                    throw ApiProblem.invalid("Field cannot be null: " + name);
            }
            T value = mapper.treeToValue(body, type);
            if (!validator.validate(value).isEmpty())
                throw ApiProblem.invalid("Invalid request fields.");
            var fields = new HashSet<String>();
            body.fieldNames().forEachRemaining(fields::add);
            return new RequestFields<>(value, fields);
        } catch (JsonProcessingException exception) {
            throw ApiProblem.invalid("Invalid request fields.");
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Generated DTO metadata is inconsistent", exception);
        }
    }
}
