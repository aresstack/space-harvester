package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lesetext lokaler Dateien (Quellentyp {@code files}, #180) für Volltextindex und Anreicherungen: Textdateien
 * ({@code text/*}, JSON, XML) als UTF-8 (Schema {@value #SCHEMA}), Dokumente wie PDF, Word, Excel, PowerPoint,
 * OpenDocument und RTF über {@link DocumentText} (Schema {@value #DOCUMENT_SCHEMA}, #183), mit deren Größen-, Zeit- und
 * Längengrenzen. Bilder und unbekannte Formate haben keinen Text.
 *
 * <p>Ein Dokument ohne lesbaren Text (defekt, verschlüsselt, zu groß) ergibt keine Projektion. Läuft die Extraktion zu
 * lange oder sind zu viele gleichzeitig unterwegs, scheitert die Projektion mit {@link DocumentTextException}: Ein
 * vorhandener Indexeintrag bleibt dann stehen, statt zu verschwinden.
 */
public final class LocalFilesTextProjector implements PageTextProjector {

    /** Projektionsregeln für Textdateien. */
    public static final String SCHEMA = "file-text/v1";
    /** Projektionsregeln für Dokumente, die erst die Extraktion liest. */
    public static final String DOCUMENT_SCHEMA = "file-document-text/v1";

    private static final String NATIVE_FORMAT = "file";

    private final DocumentText documents;

    /** Mit der gemeinsamen Tika-Extraktion des Dienstes ({@link TikaDocumentText#shared()}). */
    public LocalFilesTextProjector() {
        this(TikaDocumentText.shared());
    }

    public LocalFilesTextProjector(DocumentText documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        if (!NATIVE_FORMAT.equals(snapshot.payload().nativeFormat())) {
            return Optional.empty();
        }
        String mediaType = snapshot.payload().mediaType();
        if (textual(mediaType)) {
            return utf8(snapshot.payload().bytes());
        }
        if (!document(mediaType)) {
            return Optional.empty();
        }
        DocumentText.Extracted extracted;
        try {
            extracted = documents.extract(snapshot.metadata().title(), mediaType, snapshot.payload().bytes());
        } catch (DocumentTextException e) {
            if (e.reason().transientFailure()) {
                throw e;
            }
            return Optional.empty();
        }
        if (extracted.text().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PageText(DOCUMENT_SCHEMA, extracted.text(), List.of()));
    }

    private static Optional<PageText> utf8(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // Kein UTF-8: lieber kein Text als falsch dekodierter.
            return Optional.empty();
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return Optional.of(new PageText(SCHEMA, text.replace("\r\n", "\n"), List.of()));
    }

    private static String base(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        String type = mediaType.toLowerCase(Locale.ROOT);
        int parameters = type.indexOf(';');
        return parameters >= 0 ? type.substring(0, parameters).strip() : type.strip();
    }

    private static boolean textual(String mediaType) {
        String type = base(mediaType);
        return type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.endsWith("+json") || type.endsWith("+xml");
    }

    /** Dokumentformate, aus denen die Extraktion Text liest; Bilder, Audio, Video und Unbekanntes nicht. */
    private static boolean document(String mediaType) {
        String type = base(mediaType);
        return type.equals("application/pdf") || type.equals("application/rtf") || type.equals("application/msword")
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.ms-") || type.startsWith("application/vnd.oasis.opendocument.")
                || type.equals("message/rfc822");
    }
}
