package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.export.ExportStorageFullException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.RefreshUnavailableException;
import com.aresstack.spaceharvester.application.query.TreeTooLargeException;
import com.aresstack.spaceharvester.application.ranking.SemanticSearchUnavailableException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException;
import com.aresstack.spaceharvester.application.sync.SyncUnavailableException;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Übersetzt Ausnahmen der Core-Bibliothek in verständliche Meldungen mit einem Hinweis, was zu tun ist. Die
 * technische Zeile (Klasse und Meldung) erscheint nur in der aufklappbaren Detailansicht. Meldungen des Core nennen
 * keine Geheimnisse; Ursachen ({@code getCause}) werden nie angezeigt, weil sie Payload-Ausschnitte enthalten können.
 */
public final class Problems {

    private Problems() {
    }

    /**
     * @param title    kurze Überschrift
     * @param message  was passiert ist
     * @param hint     was der Bediener tun kann, oder {@code null}
     * @param technical Klasse und Meldung für die Detailansicht
     */
    public record Problem(String title, String message, String hint, String technical) {
    }

    /**
     * Welcher Schritt gescheitert ist. Die Spaces-Liste, die Seitenliste aus der Quelle, der Abruf einer einzelnen
     * Seite und der lokale Bestand sind getrennte Abrufe; dass einer klappt, sagt über die anderen nichts.
     */
    public enum Step {
        SPACES("Spaces nicht abrufbar"),
        PAGE_LIST("Seitenliste nicht abrufbar"),
        PAGE("Seite nicht geladen"),
        STOCK("Lokaler Bestand nicht lesbar");

        private final String heading;

        Step(String heading) {
            this.heading = heading;
        }

        public String heading() {
            return heading;
        }
    }

    /**
     * Wie {@link #describe(Throwable)}, mit dem gescheiterten Schritt in der Überschrift. Ist die Quelle bei einer
     * Seite oder Seitenliste nicht erreichbar, sagt der Hinweis, dass die Verbindungsprüfung (Spaces) etwas anderes
     * abfragt, und wo die genaue Ursache steht; die Meldung des Core (Zeitüberschreitung, TLS, HTTP-Status) erscheint
     * dann schon im Text, weil sie die eigentliche Auskunft ist.
     */
    public static Problem describe(Throwable error, Step step) {
        Problem base = describe(error);
        Throwable e = unwrap(error);
        String hint = base.hint();
        String message = base.message();
        if (e instanceof SourceAccessException source && (step == Step.PAGE || step == Step.PAGE_LIST)
                && source.reason() == SourceAccessException.Reason.UNAVAILABLE) {
            message = (step == Step.PAGE ? "Der Abruf dieser Seite aus " : "Der Abruf der Seitenliste aus ")
                    + source.sourceId() + " ist gescheitert"
                    + (e.getMessage() == null || e.getMessage().isBlank() ? "." : ": " + e.getMessage());
            hint = "Die Verbindungsprüfung fragt nur die Spaces ab; dieser Abruf geht an eine andere Adresse der "
                    + "Quelle. Erneut versuchen; bleibt es dabei, Proxy/PAC und Zertifikate prüfen.";
        }
        return new Problem(step.heading() + ": " + base.title(), message, hint, base.technical());
    }

    public static Problem describe(Throwable error) {
        Throwable e = unwrap(error);
        String technical = e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (e instanceof SourceAccessException source) {
            return switch (source.reason()) {
                case ACCESS_DENIED -> new Problem("Zugriff verweigert",
                        "Die Quelle " + source.sourceId() + " hat die Anmeldung abgelehnt oder die Rechte reichen nicht.",
                        "Benutzer, Passwort bzw. Token und Client-Zertifikat der Quelle prüfen.", technical);
                case UNAVAILABLE -> new Problem("Quelle nicht erreichbar",
                        "Die Quelle " + source.sourceId() + " antwortet nicht.",
                        "Adresse, Netzwerk, Proxy/PAC und Zertifikate prüfen; danach erneut versuchen.", technical);
                case RATE_LIMITED -> new Problem("Quelle drosselt Anfragen",
                        "Die Quelle " + source.sourceId() + " hat zu viele Anfragen gemeldet.",
                        "Etwas warten und erneut versuchen.", technical);
                case NOT_FOUND -> new Problem("Nicht gefunden",
                        "Die Quelle " + source.sourceId() + " kennt das Objekt nicht oder zeigt es nicht an.",
                        "Space-Key bzw. Seiten-ID prüfen.", technical);
                case INVALID_RESPONSE -> new Problem("Unerwartete Antwort",
                        "Die Quelle " + source.sourceId() + " hat unerwartet geantwortet.",
                        "Basis-URL prüfen (bei Confluence ohne /rest). Details zeigt die technische Ansicht.",
                        technical);
                case UNSUPPORTED -> new Problem("Nicht unterstützt",
                        "Die Quelle " + source.sourceId() + " kann diese Abfrage nicht beantworten.", null, technical);
            };
        }
        if (e instanceof UnknownSourceException) {
            return new Problem("Unbekannte Quelle", "Diese Quelle ist nicht (mehr) eingerichtet.",
                    "Konfiguration neu laden.", technical);
        }
        if (e instanceof AccessDeniedException) {
            return new Problem("Keine Freigabe", "Für diesen Inhalt besteht keine Freigabe.", null, technical);
        }
        if (e instanceof CacheMissException) {
            return new Problem("Nicht im Bestand",
                    "Die Seite bzw. der Space liegt noch nicht im lokalen Bestand.",
                    "Den Space synchronisieren; die Inhalte-Ansicht zeigt Seiten bis dahin live aus der Quelle.",
                    technical);
        }
        if (e instanceof CacheUnavailableException) {
            return new Problem("Bestand nicht verfügbar", "Der lokale Bestand (Cache) ist nicht geöffnet.",
                    "Datenverzeichnis in den Einstellungen prüfen.", technical);
        }
        if (e instanceof CacheGenerationChangedException) {
            return new Problem("Bestand wurde inzwischen aktualisiert",
                    "Während des Blätterns hat ein Abgleich den Bestand geändert.", "Liste neu laden.", technical);
        }
        if (e instanceof RefreshUnavailableException) {
            return new Problem("Aktualitätsprüfung nicht möglich", e.getMessage(), null, technical);
        }
        if (e instanceof TreeTooLargeException) {
            return new Problem("Struktur zu groß", e.getMessage(), "Die flache Liste verwenden.", technical);
        }
        if (e instanceof InvalidQueryException) {
            return new Problem("Ungültige Eingabe", e.getMessage(), null, technical);
        }
        if (e instanceof SyncAlreadyRunningException) {
            return new Problem("Abgleich läuft bereits", "Für diesen Space läuft schon ein Abgleich.",
                    "Den laufenden Abgleich unter Aufträge verfolgen.", technical);
        }
        if (e instanceof SyncUnavailableException) {
            return new Problem("Abgleich nicht möglich", e.getMessage(), null, technical);
        }
        if (e instanceof JobCapacityExceededException) {
            return new Problem("Zu viele Aufträge", e.getMessage(), "Warten, bis ein Auftrag fertig ist.", technical);
        }
        if (e instanceof UnknownJobException) {
            return new Problem("Auftrag unbekannt", "Diesen Auftrag gibt es nicht mehr.",
                    "Beendete Aufträge bleiben nur für die Aufbewahrungsfrist erhalten.", technical);
        }
        if (e instanceof SemanticSearchUnavailableException) {
            return new Problem("Semantische Suche nicht eingerichtet",
                    "Ohne Embedding-Modell sucht die App nur in Titeln.",
                    "space-harvester.embedding.provider: onnx mit model-dir einrichten (docs/EMBEDDINGS.md).",
                    technical);
        }
        if (e instanceof AssetTooLargeException || e instanceof AssetStoreFullException
                || e instanceof ExportStorageFullException) {
            return new Problem("Ablagegrenze erreicht", e.getMessage(), null, technical);
        }
        if (e instanceof InvalidSourceConfigurationException config) {
            return new Problem("Konfiguration fehlerhaft", String.join("\n", config.problems()),
                    "Konfigurationsdatei korrigieren und neu laden.", technical);
        }
        if (e instanceof InvalidNetworkConfigurationException) {
            return new Problem("Netzwerkeinstellungen fehlerhaft", e.getMessage(),
                    "space-harvester.network.proxy prüfen.", technical);
        }
        if (e.getClass().getSimpleName().equals("PageCacheException")) {
            return new Problem("Bestand kann nicht geöffnet werden", e.getMessage(),
                    "Läuft schon eine App oder ein Dienst mit demselben Datenverzeichnis? Dann dort beenden oder "
                            + "ein anderes Datenverzeichnis wählen.", technical);
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return new Problem("Nicht möglich", e.getMessage() == null ? "Die Aktion ist so nicht möglich." : e.getMessage(),
                    null, technical);
        }
        return new Problem("Unerwarteter Fehler", "Die Aktion ist fehlgeschlagen.",
                "Die technische Ansicht zeigt die Ursache.", technical);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable e = error;
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
