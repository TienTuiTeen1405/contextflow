package com.contextflow.conversation.mapper;

import com.contextflow.conversation.dto.ContentBlockResponse;
import com.contextflow.conversation.dto.LocatorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * Converts the stored JSON/binary columns of committed Turns into public DTO values. Used by {@link
 * TurnMapper}; it only transforms data and never touches the database.
 */
@Component
public class TurnPayloadConverter {
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper objectMapper;
    private final ObjectReader locatorReader;

    public TurnPayloadConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.locatorReader =
                objectMapper
                        .readerFor(LocatorResponse.class)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Parses {@code turns.user_content_blocks}. The column is written by the Chat Worker, so a
     * malformed value is a data-integrity failure rather than a client error.
     */
    @Named("contentBlocks")
    public List<ContentBlockResponse> toContentBlocks(String json) {
        if (json == null) {
            return List.of();
        }
        JsonNode root = readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("turns.user_content_blocks must be a JSON array.");
        }
        List<ContentBlockResponse> blocks = new ArrayList<>(root.size());
        for (JsonNode block : root) {
            String type = block.path("type").asText("");
            switch (type) {
                case "TEXT" -> blocks.add(ContentBlockResponse.text(requireText(block, "text")));
                case "QUOTE_REF" ->
                        blocks.add(ContentBlockResponse.quoteRef(readQuoteRefId(block)));
                default ->
                        throw new IllegalStateException(
                                "Unsupported content block type in turns.user_content_blocks.");
            }
        }
        return blocks;
    }

    @Named("locator")
    public LocatorResponse toLocator(String json) {
        if (json == null) {
            return null;
        }
        try {
            return locatorReader.readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid locator JSON in turn_quote_references.", exception);
        }
    }

    @Named("sha256Hex")
    public String toHex(byte[] hash) {
        return hash == null ? null : HEX.formatHex(hash);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid JSON in turns.user_content_blocks.", exception);
        }
    }

    /** Accepts the public camelCase key and the internal snake_case key used by Core-AI DTOs. */
    private static String readQuoteRefId(JsonNode block) {
        if (block.hasNonNull("quoteRefId")) {
            return requireText(block, "quoteRefId");
        }
        return requireText(block, "quote_ref_id");
    }

    private static String requireText(JsonNode block, String field) {
        JsonNode value = block.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException(
                    "Content block in turns.user_content_blocks is missing '" + field + "'.");
        }
        return value.asText();
    }
}
