package com.aresstack.spaceharvester.app.deck;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentHash;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Vorschau von Dokumenten (PDF, Word, Excel, PowerPoint, OpenDocument, Mail) als Text im gemeinsamen Editor, wie die
 * Vorschau mit Strg+Doppelklick in MainframeMate ({@code previewOpener}); der Text kommt aus dem Tika-Adapter des Core.
 * Nur lesen: Bearbeiten und Speichern gibt es hier nicht.
 */
public final class DocumentPreviewStore implements DocumentStore {

    /** Endungen, die als Dokument gelten (wie {@code BINARY_DOCUMENT_EXTENSIONS} in MainframeMate, dazu Mail/RTF). */
    public static final Set<String> DOCUMENTS = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
            "ods", "odp", "rtf", "msg", "eml", "one");
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final DocumentText text;

    public DocumentPreviewStore() {
        this(TikaDocumentText.shared());
    }

    DocumentPreviewStore(DocumentText text) {
        this.text = text;
    }

    /** Ob die Datei eine Vorschau als Dokument bekommt. */
    public static boolean isDocument(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && DOCUMENTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    @Override
    public String id() {
        return "vorschau";
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public EditorDocument load(String location) throws DocumentException {
        Path path = Path.of(location);
        byte[] bytes;
        try {
            if (Files.size(path) > MAX_BYTES) {
                throw new DocumentException("Die Datei ist größer als 50 MB.");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DocumentException("Datei nicht lesbar (" + e.getClass().getSimpleName() + ")", e);
        }
        String name = path.getFileName() == null ? location : path.getFileName().toString();
        try {
            Optional<String> type = text.detect(name, bytes);
            DocumentText.Extracted extracted = text.extract(name, type.orElse(null), bytes);
            String content = extracted.text().replace("\r\n", "\n").replace('\r', '\n');
            if (extracted.truncated()) {
                content += "\n\n… (gekürzt)";
            }
            return new EditorDocument(location, name, content, "UTF-8", "\n", DocumentHash.of(bytes));
        } catch (RuntimeException e) {
            throw new DocumentException("Kein Text in diesem Dokument (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    @Override
    public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
        throw new DocumentException("Die Vorschau ist schreibgeschützt.");
    }
}
