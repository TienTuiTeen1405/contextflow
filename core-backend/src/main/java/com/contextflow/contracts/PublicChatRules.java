package com.contextflow.contracts;

import com.contextflow.contracts.generated.PublicApi.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

public final class PublicChatRules {
    private PublicChatRules() {}

    /**
     * Wire-level invariants only. Ownership, source revision/anchors and budget need Core services.
     */
    public static void validate(ChatInput input) {
        List<QuoteInput> quotes = input.quotes();
        var aliases = new HashSet<String>();
        for (QuoteInput quote : quotes) {
            if (!aliases.add(quote.quoteRefId())) throw invalid();
            Locator locator = quote.locator();
            String snapshot = quote.snapshotText();
            if (snapshot.indexOf('\r') >= 0
                    || locator.charEnd() <= locator.charStart()
                    || locator.charEnd() - locator.charStart()
                            != snapshot.codePointCount(0, snapshot.length())
                    || !hash(snapshot).equals(quote.contentHashSha256Hex())) throw invalid();
        }
        var blockAliases =
                input.userContentBlocks().stream()
                        .filter(QuoteRefBlock.class::isInstance)
                        .map(QuoteRefBlock.class::cast)
                        .map(QuoteRefBlock::quoteRefId)
                        .toList();
        if (blockAliases.size() != aliases.size() || !aliases.equals(new HashSet<>(blockAliases)))
            throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(ErrorCode.INVALID_EXPLICIT_REFERENCE.name());
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
