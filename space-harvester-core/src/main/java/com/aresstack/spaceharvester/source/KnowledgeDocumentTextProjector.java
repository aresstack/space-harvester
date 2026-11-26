package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Lesetext der Quellen ohne Seitenliste je Partition (#187) für Volltextindex und Anreicherungen: Abgelegt ist dort
 * schon der Text, den der Adapter beim Laden liefert ({@code KnowledgeDocument.text()}, Klartext mit leichtem Markdown,
 * NFC und {@code \n}), so wie ihn der Enterprise AI Client indexiert. Er wird unverändert übernommen.
 */
public final class KnowledgeDocumentTextProjector implements PageTextProjector {

    /** Projektionsregeln. */
    public static final String SCHEMA = "knowledge-document-text/v1";
    /** Format der abgelegten Payload: der Lesetext des Adapters als UTF-8. */
    public static final String NATIVE_FORMAT = NativePayload.DOCUMENT_TEXT;
    /** Medientyp der abgelegten Payload. */
    public static final String MEDIA_TYPE = "text/markdown";

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        var portable = com.aresstack.spaceharvester.application.snapshot.PortableLocalDocument.read(snapshot.payload());
        if (portable.isPresent()) {
            String text = portable.get().text();
            return text.isBlank() ? Optional.empty() : Optional.of(new PageText(
                    "portable-local-document/v1", text, List.of()));
        }
        // Auch der Textauszug einer Datei über der Größengrenze des Caches (LargeFiles): derselbe Lesetext.
        if (!NATIVE_FORMAT.equals(snapshot.payload().nativeFormat())
                && !com.aresstack.spaceharvester.application.snapshot.LargeFiles.TEXT_FORMAT
                .equals(snapshot.payload().nativeFormat())) {
            return Optional.empty();
        }
        String text = new String(snapshot.payload().bytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? Optional.empty() : Optional.of(new PageText(SCHEMA, text, List.of()));
    }
}
