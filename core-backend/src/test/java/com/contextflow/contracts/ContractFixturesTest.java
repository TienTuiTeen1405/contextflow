package com.contextflow.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.contracts.generated.InternalAi;
import com.contextflow.contracts.generated.PublicApi;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class ContractFixturesTest {
    private static final Path CONTRACTS = findContracts();
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static ValidatorFactory validators;

    private static Path findContracts() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("contracts/fixtures/manifest.json")))
                return path.resolve("contracts");
            path = path.getParent();
        }
        throw new IllegalStateException("Shared contract fixtures not found");
    }

    @BeforeAll
    static void validators() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidators() {
        validators.close();
    }

    @TestFactory
    Stream<DynamicTest> sharedFixturesRoundTripThroughJavaDtos() throws Exception {
        JsonNode manifest = MAPPER.readTree(CONTRACTS.resolve("fixtures/manifest.json").toFile());
        return StreamSupport.stream(manifest.spliterator(), false)
                .map(test ->
                        DynamicTest.dynamicTest(
                                test.get("file").asText(),
                                () -> {
                                    JsonNode input = fixture(test.get("file").asText());
                                    String module =
                                            test.get("document").asText().equals("public")
                                                    ? "PublicApi"
                                                    : "InternalAi";
                                    Class<?> model =
                                            Class.forName(
                                                    "com.contextflow.contracts.generated."
                                                            + module
                                                            + "$"
                                                            + test.get("schema").asText());
                                    Object dto = MAPPER.treeToValue(input, model);
                                    assertTrue(
                                            validators.getValidator().validate(dto).isEmpty(),
                                            () ->
                                                    validators
                                                            .getValidator()
                                                            .validate(dto)
                                                            .toString());
                                    assertProvidedValues(input, MAPPER.valueToTree(dto));
                                }));
    }

    private static JsonNode fixture(String file) throws Exception {
        return MAPPER.readTree(CONTRACTS.resolve("fixtures").resolve(file).toFile());
    }

    private static void assertProvidedValues(JsonNode expected, JsonNode actual) {
        if (expected.isNull()) {
            // Optional nullable fields may be omitted; required usage nulls are checked separately.
            assertTrue(actual == null || actual.isNull());
        } else if (expected.isNumber()) {
            assertNotNull(actual);
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()));
        } else if (expected.isObject()) {
            assertNotNull(actual);
            expected.fields()
                    .forEachRemaining(field ->
                            assertProvidedValues(field.getValue(), actual.get(field.getKey())));
        } else if (expected.isArray()) {
            assertNotNull(actual);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++)
                assertProvidedValues(expected.get(i), actual.get(i));
        } else assertEquals(expected, actual);
    }

    @Test
    void publicQuoteTransportPreservesBlockOrderAndHash() throws Exception {
        var input =
                MAPPER.treeToValue(fixture("public/chat-input.json"), PublicApi.ChatInput.class);
        assertDoesNotThrow(() -> PublicChatRules.validate(input));
        assertInstanceOf(PublicApi.QuoteRefBlock.class, input.userContentBlocks().get(1));
        ObjectNode broken = fixture("public/chat-input.json").deepCopy();
        ((ObjectNode) broken.get("quotes").get(0)).put("contentHashSha256Hex", "0".repeat(64));
        var invalid = MAPPER.treeToValue(broken, PublicApi.ChatInput.class);
        assertThrows(IllegalArgumentException.class, () -> PublicChatRules.validate(invalid));
        broken = fixture("public/chat-input.json").deepCopy();
        ((ObjectNode) broken.get("quotes").get(0)).put("quoteRefId", "orphan");
        var orphan = MAPPER.treeToValue(broken, PublicApi.ChatInput.class);
        assertThrows(IllegalArgumentException.class, () -> PublicChatRules.validate(orphan));
    }

    @Test
    void quoteAnchorsUseUnicodeCodePointsInsteadOfUtf16Units() throws Exception {
        ObjectNode input = fixture("public/chat-input.json").deepCopy();
        ObjectNode locator = (ObjectNode) input.get("quotes").get(0).get("locator");
        locator.put("prefixAnchor", "🧠".repeat(32));
        assertTrue(
                validators
                        .getValidator()
                        .validate(MAPPER.treeToValue(input, PublicApi.ChatInput.class))
                        .isEmpty());
        locator.put("prefixAnchor", "🧠".repeat(33));
        assertFalse(
                validators
                        .getValidator()
                        .validate(MAPPER.treeToValue(input, PublicApi.ChatInput.class))
                        .isEmpty());
    }

    @Test
    void contentBlocksRejectClientControlledExtraFields() throws Exception {
        ObjectNode input = fixture("public/chat-input.json").deepCopy();
        ((ObjectNode) input.get("userContentBlocks").get(0)).put("excludeFromAutoKb", true);
        assertThrows(
                JsonMappingException.class,
                () -> MAPPER.treeToValue(input, PublicApi.ChatInput.class));
    }

    @Test
    void missingUsageStaysNullAndRequiredFieldsCannotDisappear() throws Exception {
        JsonNode before = fixture("internal/provider.completed.json");
        var dto = MAPPER.treeToValue(before, InternalAi.ProviderCompleted.class);
        JsonNode after = MAPPER.valueToTree(dto);
        for (String field :
                List.of(
                        "input_tokens",
                        "output_tokens",
                        "total_tokens",
                        "cached_tokens",
                        "reasoning_tokens")) {
            assertTrue(after.get("usage").has(field));
            assertTrue(after.get("usage").get(field).isNull());
        }
        assertEquals(
                MAPPER.writeValueAsString(before.get("native_output_steps")),
                MAPPER.writeValueAsString(after.get("native_output_steps")));
        ObjectNode invalid = before.deepCopy();
        invalid.remove("usage");
        assertThrows(
                JsonMappingException.class,
                () -> MAPPER.treeToValue(invalid, InternalAi.ProviderCompleted.class));
    }

    @Test
    void errorRegistryAndEventRegistriesMatchTheIndependentContracts() throws Exception {
        JsonNode catalog =
                MAPPER.readTree(CONTRACTS.resolve("error-codes.json").toFile()).get("errors");
        assertEquals(25, PublicApi.ErrorCode.values().length);
        for (JsonNode entry : catalog) {
            var code = PublicApi.ErrorCode.valueOf(entry.get("code").asText());
            assertEquals(entry.get("retryable").asBoolean(), code.retryable());
            assertEquals(
                    entry.get("httpStatus").isNull() ? null : entry.get("httpStatus").intValue(),
                    code.httpStatus());
        }
        JsonNode publicEvents =
                MAPPER.readTree(CONTRACTS.resolve("public.openapi.json").toFile())
                        .get("x-sse-events");
        assertEquals(publicEvents.size(), SseEvents.Public.values().length);
        for (var event : SseEvents.Public.values())
            assertEquals(
                    event.payloadType().getSimpleName(),
                    publicEvents.get(event.wireName()).asText());
        JsonNode internalEvents =
                MAPPER.readTree(CONTRACTS.resolve("internal.openapi.json").toFile())
                        .get("x-sse-events");
        assertEquals(internalEvents.size(), SseEvents.Internal.values().length);
        for (var event : SseEvents.Internal.values())
            assertEquals(
                    event.payloadType().getSimpleName(),
                    internalEvents.get(event.wireName()).asText());
    }
}
