package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lesetext lokaler Dateien (Quellentyp {@code files}, #180) für den Volltextindex: Textdateien ({@code text/*}, JSON,
 * XML) als UTF-8, sonst nichts. Binärformate wie PDF oder Word werden nicht extrahiert, wie beim Quellentyp selbst.
 */
public final class LocalFilesTextProjector implements PageTextProjector {

    /** Projektionsregeln. */
    public static final String SCHEMA = "file-text/v1";

    private static final String NATIVE_FORMAT = "file";

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        if (!NATIVE_FORMAT.equals(snapshot.payload().nativeFormat()) || !textual(snapshot.payload().mediaType())) {
            return Optional.empty();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(snapshot.payload().bytes())).toString();
        } catch (CharacterCodingException e) {
            // Kein UTF-8: lieber kein Text als falsch dekodierter.
            return Optional.empty();
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return Optional.of(new PageText(SCHEMA, text.replace("\r\n", "\n"), List.of()));
    }

    private static boolean textual(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String type = mediaType.toLowerCase(Locale.ROOT);
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters).strip();
        }
        return type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.endsWith("+json") || type.endsWith("+xml");
    }
}
