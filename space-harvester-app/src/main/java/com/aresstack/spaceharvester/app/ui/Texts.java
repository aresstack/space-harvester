package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Fachliche Bezeichnungen und Formate der Oberfläche, auf Deutsch. */
public final class Texts {

    /**
     * Warum Seiten „veraltet“ sind: Die Quelle hat eine neuere Version, der Inhalt ist noch nicht geladen, meist weil
     * die Betriebsart der Quelle „Nur katalogisieren“ ist.
     */
    public static final String OUTDATED_WHY = "Veraltet: In der Quelle gibt es eine neuere Version, der Inhalt ist noch "
            + "nicht geladen, meist wegen der Betriebsart „Nur katalogisieren“. Ändern unter Quellen › „Diese Quelle "
            + "anpassen …“ › Betriebsart.";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm",
            Locale.GERMANY).withZone(ZoneId.systemDefault());

    private Texts() {
    }

    public static String time(Instant instant) {
        return instant == null ? "–" : TIME.format(instant);
    }

    /** Wie {@link #time}, kurz für schmale Listen: Tag, Monat und Uhrzeit ohne Jahr und Sekunden. */
    public static String shortTime(Instant instant) {
        return instant == null ? "–" : SHORT_TIME.format(instant);
    }

    /** Eine Größengrenze: 0 (oder weniger) heißt „ohne Grenze“ (#571), sonst wie {@link #bytes}. */
    public static String sizeLimit(long bytes) {
        return bytes <= 0 ? "ohne Grenze" : bytes(bytes);
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.GERMANY, "%.1f %s", value, units[unit]);
    }

    public static String duration(Instant from, Instant to) {
        if (from == null) {
            return "–";
        }
        Duration d = Duration.between(from, to == null ? Instant.now() : to);
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min " + seconds % 60 + " s";
        }
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    public static String state(JobState state) {
        return switch (state) {
            case QUEUED -> "Wartet";
            case RUNNING -> "Läuft";
            case COMPLETED -> "Fertig";
            case FAILED -> "Fehlgeschlagen";
            case CANCELLED -> "Abgebrochen";
            case PAUSED -> "Angehalten";
        };
    }

    /**
     * Zustand nach dem Zustandsobjekt des Core ({@link JobLifecycleState#key()}): unterscheidet „Fertig“ von „Fertig,
     * aber unvollständig“, angefordertes Anhalten von angefordertem Abbruch und nennt, warum sich ein Auftrag selbst
     * angehalten hat (fehlende Anmeldung, Verbindung). Unbekannte Schlüssel fallen auf {@link #state(JobState)}.
     */
    public static String state(JobStatus status) {
        return switch (status.lifecycle().key()) {
            case "PAUSE_REQUESTED" -> "Anhalten angefordert";
            case "CANCEL_REQUESTED" -> "Abbruch angefordert";
            case "RESTARTING" -> "Startet neu";
            case "COMPLETED_WITH_ERRORS" -> "Fertig, unvollständig";
            // Das Ende der App hat den Auftrag unterbrochen: kein Fehler der Arbeit, Fortsetzen macht weiter.
            case "INTERRUPTED" -> "Unterbrochen";
            case "PAUSED_SIGN_IN_REQUIRED" -> "Angehalten – wartet auf Anmeldung";
            case "PAUSED_CONNECTION_LOST" -> "Angehalten – Verbindung unterbrochen";
            case "PAUSED_RESUME_BY_HAND" -> "Angehalten – von Hand fortsetzen";
            case "PREPARING" -> "Vorbereitung";
            case "PREPARED" -> "Vorbereitet";
            case "AWAITING_EXPORT" -> "Wartet auf Text";
            // Kein Fehler: Der Auftrag macht im selben Eintrag weiter, sobald das Netz zurück ist (Angelo 22.11.2026).
            case "AWAITING_NETWORK" -> "Wartet auf Netzverbindung";
            default -> state(status.state());
        };
    }

    /** Farbe zum Zustandsobjekt ({@link #state(JobStatus)}): unvollständig und selbst angehalten als Warnung. */
    public static Comic.Tone tone(JobStatus status) {
        return switch (status.lifecycle().key()) {
            case "COMPLETED_WITH_ERRORS", "PAUSED_SIGN_IN_REQUIRED", "PAUSED_CONNECTION_LOST", "PAUSED_RESUME_BY_HAND",
                 "INTERRUPTED" ->
                    Comic.Tone.WARNING;
            case "PREPARING" -> Comic.Tone.RUNNING;
            // Vorbereitet und wartend tun gerade nichts: ruhig wie „Angehalten“, bis ein Platz frei ist.
            case "PREPARED", "AWAITING_EXPORT", "AWAITING_NETWORK" -> Comic.Tone.NEUTRAL;
            default -> tone(status.state());
        };
    }

    public static Comic.Tone tone(JobState state) {
        return switch (state) {
            case QUEUED, RUNNING -> Comic.Tone.RUNNING;
            case COMPLETED -> Comic.Tone.GOOD;
            case FAILED -> Comic.Tone.PROBLEM;
            case CANCELLED -> Comic.Tone.WARNING;
            case PAUSED -> Comic.Tone.NEUTRAL;
        };
    }

    public static String type(JobType type) {
        return switch (type) {
            case HARVEST -> "Ernte";
            case SYNC -> "Abgleich";
            case EXPORT -> "Export";
            case ENRICHMENT -> "Anreicherung";
            case ASSETS -> "Bilder beschaffen";
            case ATTACHMENTS -> "Anhänge beschaffen";
            case STATEMENT_ANALYSIS -> "Aussagenanalyse";
            case PURGE -> "Daten bereinigen";
            case KNOWLEDGE_GRAPH_EXPORT -> "Graph-Export";
            case GLOSSARY -> "Fachbegriffe ermitteln";
            case IMPORT -> "Import";
            case REREAD -> "Neu lesen";
            case CONVERSION -> "Datei erzeugen";
            case METADATA_EXPORT -> "Confluence-Metadaten";
            case ANALYTICS_EXPORT -> "Analytics";
            case UPDATE_SUMMARY -> "Zusammenfassen";
            case STATEMENT_CHECK -> "Aussagen prüfen";
            case VIDEO_TRANSCRIPT -> "Transkript ziehen";
            case VIDEO_SLIDES -> "Folien beschreiben";
            case TRANSCRIPT_CORRECTION -> "Transkript korrigieren";
            case SLIDE_NOTES -> "Folienkommentare";
            case PRESENTATION_EXPORT -> "PPTX erzeugen";
            case SEARCH_QUALITY -> "Suchqualität messen";
            case UPDATE_PODCAST -> "Podcast erzeugen";
            case READING_PREFETCH -> "Lese-Ansichten vorladen";
            case DOCUMENT_ANALYSIS -> "Dokument analysieren";
            case RESEARCH -> "Korb füllen";
            case BOOK -> "Buch schreiben";
            case PODCAST_EXPORT -> "Podcast exportieren";
            case BACKGROUND -> "Hintergrund";
        };
    }

    /**
     * Die Art, nach der Zähler, Fortschritt und Neuladen einen Auftrag behandeln: ein Unterauftrag der Hintergrundart
     * „Aussagen analysieren“ (Fahrplan D3) wie {@link JobType#STATEMENT_ANALYSIS}, sonst sein Typ.
     */
    public static JobType kind(JobStatus status) {
        return status.type() == JobType.BACKGROUND
                && com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.isAnalysis(status)
                ? JobType.STATEMENT_ANALYSIS : status.type();
    }

    /**
     * Wie {@link #type(JobType)}, für „PPTX erzeugen“ mit dem Format des Auftrags: Schreibt er ein PDF
     * ({@code VideoPresentations.FORMAT_PARAMETER}), heißt er „PDF erzeugen“.
     */
    public static String type(JobStatus status) {
        String child = status.parameters() == null ? null
                : status.parameters().get(com.aresstack.spaceharvester.application.jobs.ChildJobs.STEP_PARAMETER);
        if (child != null && !child.isBlank()) {
            // Unterauftrag unter einer Klammer (ChildJobs), etwa eine Quelle unter „Buch schreiben“: sein Titel.
            return child;
        }
        if (status.type() == JobType.UPDATE_SUMMARY) {
            // Unterauftrag der Zusammenfassung: sein Schritt, etwa „Teil 2 von 5“ oder „Zusammenführen 1 von 2“.
            String step = status.parameters().get(com.aresstack.spaceharvester.application.changelog.ChangeLog.STEP_PARAMETER);
            if (step != null && !step.isBlank()) {
                return step;
            }
        }
        if (status.type() == JobType.ENRICHMENT
                && com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions.TASK.equals(
                status.parameters().get(com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions
                        .TASK_PARAMETER))) {
            return "Bilder beschreiben";
        }
        if (status.type() == JobType.ENRICHMENT
                && com.aresstack.spaceharvester.application.terms.SynonymDiscovery.TASK.equals(
                status.parameters().get(com.aresstack.spaceharvester.application.terms.SynonymDiscovery
                        .TASK_PARAMETER))) {
            return backgroundTitle("Synonyme erkennen");
        }
        if (status.type() == JobType.SYNC && status.partitionKey() == null && status.parameters() != null
                && status.parameters().get(com.aresstack.spaceharvester.application.sync.SpaceSyncJobs.GROUP) != null) {
            // Die Zeile über den Abgleichen einer ganzen Quelle (Angelo 22.11.2026); ihre Zeilen tragen je einen Space.
            return "Abgleich der Quelle";
        }
        if (status.type() == JobType.BACKGROUND) {
            // Hintergrundaufträge (ADR 0025 Nachtrag): der Titel ihrer Gruppe, etwa „Nachbarseiten laden“; ein Teil
            // einer gemischten Gruppe nennt seine Stufe, damit die Zeile unter der Klammer sagt, was sie tut.
            String step = status.parameters().get(
                    com.aresstack.spaceharvester.application.background.BackgroundJobs.STEP_TITLE);
            if (step != null && !step.isBlank()) {
                return backgroundTitle(step);
            }
            String title = status.parameters().get(
                    com.aresstack.spaceharvester.application.background.BackgroundJobs.TITLE);
            return title == null || title.isBlank() ? type(status.type()) : backgroundTitle(title);
        }
        if (status.type() == JobType.PODCAST_EXPORT) {
            return com.aresstack.spaceharvester.app.podcast.PodcastExport.Format.parse(status.parameters().get(
                    com.aresstack.spaceharvester.app.podcast.PodcastExport.FORMAT_PARAMETER)).label() + " exportieren";
        }
        if (status.type() == JobType.PRESENTATION_EXPORT) {
            return com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format.parse(
                    status.parameters().get(com.aresstack.spaceharvester.application.presentation.VideoPresentations
                            .FORMAT_PARAMETER)).label() + " erzeugen";
        }
        return type(status.type());
    }

    /**
     * Was ein Hintergrundauftrag tut, in Worten (Angelo 22.11.2026: „Hintergrundfüllung“ sagt nicht, was passiert). Der
     * Core legt den kurzen Titel ab; auch Einträge von früher heißen so verständlich. Unbekannte Titel bleiben.
     */
    static final Map<String, String> BACKGROUND_TITLES = Map.ofEntries(
            Map.entry("Hintergrundfüllung", "Fehlende und veraltete Seiten laden"),
            Map.entry("KI-Anreicherung", "Seiten für die KI-Suche aufbereiten"),
            Map.entry("Anhänge analysieren", "Anhänge für die KI-Suche aufbereiten"),
            Map.entry("Suchtreffer laden", "Seiten der Suchtreffer laden"),
            Map.entry("Funde katalogisieren", "Gefundene Seiten in den Katalog aufnehmen"),
            Map.entry("Verweise abgleichen", "Verweise zwischen Seiten abgleichen"),
            Map.entry("Analysen nach Update nachrechnen", "Analysen nach einem Programm-Update neu berechnen"),
            Map.entry("Verbindung prüfen", "Verbindung zur Quelle prüfen"),
            Map.entry("Synonyme erkennen", "Synonyme der Fachbegriffe erkennen"),
            Map.entry("Anhänge vorladen", "Weitere Anhänge vorladen"));

    /** {@link #BACKGROUND_TITLES} für den abgelegten Titel eines Hintergrundauftrags. */
    public static String backgroundTitle(String stored) {
        return BACKGROUND_TITLES.getOrDefault(stored, stored);
    }

    public static String cacheState(CacheState state) {
        return switch (state) {
            case CONFIRMED -> "Bestätigt";
            case INCOMPLETE -> "Unvollständig";
            case STALE -> "Veraltet";
        };
    }

    /** Zustand einer Generation in einem Satz. */
    public static String generation(CacheGeneration generation) {
        if (generation == null) {
            return "Kein Bestand";
        }
        return cacheState(generation.state()) + ", abgeglichen " + time(generation.syncedAt()) + ", zuletzt geprüft "
                + time(generation.lastCheckedAt());
    }

    public static String capability(SourceCapability capability) {
        return switch (capability) {
            case PARTITIONS -> "Spaces auflisten";
            case PAGE_LISTING -> "Seitenlisten";
            case PAGE_METADATA -> "Seitenmetadaten";
            case STORED_PAGE -> "Originalseiten";
            case ASYNC_STORED_PAGE -> "Originalseiten asynchron";
            case PAGE_ATTACHMENTS -> "Anhänge";
            case LIVE_SEARCH -> "Volltextsuche in der Quelle";
            case TITLE_SEARCH -> "Seiten über den Titel finden";
            case NAVIGATION -> "Seitenbaum und Querverweise";
            case ADDRESSES -> "Adressen und Titel auflösen";
            case CHANGES -> "Änderungen und Papierkorb abfragen";
            case ATTACHMENT_PREVIEWS -> "Vorschau von Anhängen";
        };
    }

    public static String sourceType(String type) {
        return switch (type) {
            case "confluence" -> "Confluence";
            case "files" -> "Lokale Dateien";
            default -> type;
        };
    }

    public static String auth(SourceSettingsConfig.Auth auth) {
        return switch (auth.type()) {
            case NONE -> "Ohne Anmeldung";
            case BASIC -> "Benutzer und Passwort (" + (auth.username() == null ? "ohne Benutzer" : auth.username()) + ")";
            case PAT -> "Personal Access Token";
        };
    }

    public static String shortHash(String hash) {
        if (hash == null) {
            return "–";
        }
        int colon = hash.indexOf(':');
        String bare = colon >= 0 ? hash.substring(colon + 1) : hash;
        return bare.length() > 12 ? bare.substring(0, 12) + "…" : bare;
    }

    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
