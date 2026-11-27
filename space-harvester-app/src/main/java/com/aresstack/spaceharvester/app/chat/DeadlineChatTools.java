package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.ui.extraction.ExtractionRows;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Die Fristen-Auswertung (Zeile „Fristen“ unter Einstellungen › KI-Dienst › Modelle) als Werkzeug des E-Akte-Chats
 * (Angelo 27.11.2026: „Der Chat soll die Einstellungen ruhig alle nutzen“). Das Werkzeug startet dieselbe Analyse wie
 * der Knopf in der Ansicht „Fristen“ des offenen Dokuments, wartet auf ihr Ende und gibt die Tabelle als Text zurück;
 * ein fertiges Ergebnis wird ohne neuen Lauf gelesen.
 */
public final class DeadlineChatTools implements ChatToolset {

    public static final String DEADLINES = "fristen_auswerten";

    static final String DESCRIPTION = "Wertet das offene E-Akte-Dokument mit der Fristen-Analyse der App aus "
            + "(Fristen, Termine, Aktenzeichen, Beteiligte; mit Stand und Fundstelle) und liefert die Tabelle als Text. "
            + "Ein vorhandenes Ergebnis wird gelesen; mit neu=true läuft die Analyse erneut.";
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"neu\":{\"type\":\"boolean\",\"description\":\"true: Analyse neu starten statt vorhandenes Ergebnis\"}"
            + "}}";
    static final String NO_DOCUMENT = "Kein Dokument geöffnet. Bitte den Benutzer, in der E-Akte ein Dokument zu "
            + "öffnen.";
    static final String NOT_ANALYZABLE = "Für dieses Dokument ist die Fristen-Analyse nicht möglich (keine Ablage "
            + "angeschlossen oder Dokumentart ohne Text).";

    private final Supplier<DocumentRef> open;
    private final DocumentAnalysisProvider analyses;
    private final Semaphore changed = new Semaphore(0);
    private volatile boolean cancelled;

    /**
     * @param open     das Dokument, das die E-Akte gerade zeigt; {@code null} ohne
     * @param analyses die Fristen-Analyse ({@code DeadlineAnalysisService})
     */
    public DeadlineChatTools(Supplier<DocumentRef> open, DocumentAnalysisProvider analyses) {
        this.open = Objects.requireNonNull(open, "open");
        this.analyses = Objects.requireNonNull(analyses, "analyses");
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return analyses == DocumentAnalysisProvider.NONE ? List.of()
                : List.of(new AiChat.Tool(DEADLINES, DESCRIPTION, SCHEMA));
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return DEADLINES.equals(call.name()) ? "Fristen werden ausgewertet …" : "Werkzeug";
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (!DEADLINES.equals(call.name())) {
            return "Unbekanntes Werkzeug.";
        }
        boolean again;
        try {
            again = DeckChatTools.arguments(call).path("neu").asBoolean(false);
        } catch (RuntimeException e) {
            again = false;
        }
        DocumentRef document = open.get();
        if (document == null) {
            return NO_DOCUMENT;
        }
        try {
            if (!analyses.canAnalyze(document)) {
                return NOT_ANALYZABLE;
            }
            DocumentAnalysis result = analyses.result(document);
            if (!again && finished(result)) {
                return answer(result);
            }
            return answer(await(document, again || result == null
                    || result.state() != AnalysisState.IN_PROGRESS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Abgebrochen.";
        } catch (Exception e) {
            ChatTools.diagnosis.accept("Fristen-Analyse gescheitert: " + e.getClass().getSimpleName());
            return "Fehler: Die Fristen-Analyse ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
    }

    /** Startet die Analyse bei Bedarf und wartet, bis sie fertig oder gescheitert ist; Stopp im Chat bricht ab. */
    private DocumentAnalysis await(DocumentRef document, boolean start) throws Exception {
        changed.drainPermits();
        try (Subscription ignored = analyses.observe(ref -> {
            if (document.equals(ref)) {
                changed.release();
            }
        })) {
            if (start) {
                analyses.startAnalysis(document);
            }
            DocumentAnalysis result = analyses.result(document);
            while (!finished(result)) {
                if (cancelled) {
                    throw new InterruptedException();
                }
                changed.acquire();
                result = analyses.result(document);
            }
            return result;
        }
    }

    private static boolean finished(DocumentAnalysis result) {
        return result != null && (result.state() == AnalysisState.COMPLETED || result.state() == AnalysisState.FAILED);
    }

    static String answer(DocumentAnalysis result) {
        return "Fristen-Analyse des offenen Dokuments (Modellzeile „Fristen“):\n" + ExtractionRows.text(result);
    }

    @Override
    public void cancel() {
        cancelled = true;
        changed.release();
    }
}
