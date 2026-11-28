package com.contextflow.conversation.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.contextflow.conversation.dto.ContentBlockResponse;
import com.contextflow.conversation.dto.LocatorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnPayloadConverterTest {
    private final TurnPayloadConverter converter = new TurnPayloadConverter(new ObjectMapper());

    @Test
    void parsesTextAndQuoteBlocksInStoredOrder() {
        List<ContentBlockResponse> blocks =
                converter.toContentBlocks(
                        """
                        [
                          {"type": "TEXT", "text": "Đối chiếu "},
                          {"type": "QUOTE_REF", "quoteRefId": "q-1"},
                          {"type": "QUOTE_REF", "quote_ref_id": "q-2"},
                          {"type": "TEXT", "text": ""}
                        ]
                        """);

        assertEquals(
                List.of(
                        ContentBlockResponse.text("Đối chiếu "),
                        ContentBlockResponse.quoteRef("q-1"),
                        ContentBlockResponse.quoteRef("q-2"),
                        ContentBlockResponse.text("")),
                blocks);
    }

    @Test
    void rejectsMalformedStoredContentBlocks() {
        for (String json :
                List.of(
                        "{\"type\":\"TEXT\",\"text\":\"not an array\"}",
                        "[{\"type\":\"IMAGE\",\"url\":\"x\"}]",
                        "[{\"type\":\"TEXT\"}]",
                        "[{\"type\":\"QUOTE_REF\"}]",
                        "not json")) {
            assertThrows(IllegalStateException.class, () -> converter.toContentBlocks(json));
        }
    }

    @Test
    void readsLocatorIgnoringUnknownFieldsAndFormatsHashAsLowercaseHex() {
        LocatorResponse locator =
                converter.toLocator(
                        "{\"charStart\":0,\"charEnd\":22,\"prefixAnchor\":\"a\","
                                + "\"suffixAnchor\":\"b\",\"extra\":true}");

        assertEquals(new LocatorResponse(0L, 22L, "a", "b"), locator);
        assertEquals("00ff10", converter.toHex(new byte[] {0, (byte) 0xff, 0x10}));
        assertNull(converter.toHex(null));
        assertNull(converter.toLocator(null));
    }
}
