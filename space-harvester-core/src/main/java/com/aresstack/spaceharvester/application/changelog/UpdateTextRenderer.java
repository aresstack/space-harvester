package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Schreibt den Update-Text {@value UpdateText#FORMAT} (ADR 0031, Abschnitt 2): ein Kopf mit Ebene, Zeitraum und
 * Zählern, dann je Seite Pfad und Titel, Space, Arten, Versionen, Zeitpunkt und die geänderten Absätze. Was nicht
 * bekannt ist, steht als Zustand da; geschätzt wird nichts. Seitentext erscheint nur in den Absätzen des Unterschieds,
 * gruppiert unter ihrem Überschriftenpfad. Die Anzeige schreibt Zeiten in der Zone des Lesers und nennt Bearbeiter;
 * das Material für ein Modell schreibt ISO-Zeiten und nennt Bearbeiter nur als Zahl.
 */
final class UpdateTextRenderer {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    private final ZoneId zone;

    private UpdateTextRenderer(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * @param zone   Zone der Anzeige; {@code null} für das Material eines Modells (ISO-Zeiten, keine Namen)
     * @param counts Seiten je Art in der ganzen Ebene (nicht nur im Ausschnitt)
     * @param total  Seiten der Ebene insgesamt
     */
    static Rendered render(ChangeUpdate update, String spaceKey, Map<ChangeKind, Integer> counts, int total,
                           int offset, List<ReportedPage> pages, ZoneId zone) {
        UpdateTextRenderer renderer = new UpdateTextRenderer(zone);
        StringBuilder text = new StringBuilder();
        if (offset == 0) {
            renderer.head(text, update, spaceKey, counts, total);
        }
        List<UpdateText.Section> sections = new ArrayList<>();
        for (ReportedPage page : pages) {
            int start = text.length();
            renderer.page(text, page, spaceKey == null);
            sections.add(new UpdateText.Section(page.pageId(), start, text.length() - start, page.kind()));
        }
        if (offset + pages.size() < total) {
            text.append("_… weitere Seiten ab Nr. ").append(offset + pages.size() + 1).append(" von ").append(total)
                    .append("_\n");
        }
        return new Rendered(text.toString(), sections);
    }

    record Rendered(String markdown, List<UpdateText.Section> sections) {
    }

    /** Seiten je Art aus einer Seitenliste. */
    static Map<ChangeKind, Integer> counts(List<ReportedPage> pages) {
        Map<ChangeKind, Integer> counts = new EnumMap<>(ChangeKind.class);
        pages.forEach(page -> counts.merge(page.kind(), 1, Integer::sum));
        return counts;
    }

    private void head(StringBuilder text, ChangeUpdate update, String spaceKey, Map<ChangeKind, Integer> counts,
                             int total) {
        text.append("# Update ").append(update.sourceId());
        if (spaceKey != null) {
            text.append(" · Space ").append(spaceKey);
        }
        text.append('\n').append('\n');
        text.append("Zeitraum: ").append(update.from() == null ? "Beginn" : time(update.from())).append(" bis ")
                .append(update.to() == null ? "jetzt (offen)" : time(update.to())).append('\n');
        if (update.initial()) {
            text.append("Erstbefüllung: ").append(update.pages()).append(" Seiten, nur gezählt\n\n");
            return;
        }
        text.append("Geänderte Seiten: ").append(total);
        if (!counts.isEmpty()) {
            text.append(" (").append(counts.entrySet().stream()
                    .map(entry -> label(entry.getKey()) + " " + entry.getValue())
                    .collect(Collectors.joining(", "))).append(')');
        }
        text.append('\n').append('\n');
    }

    private void page(StringBuilder text, ReportedPage page, boolean showSpace) {
        text.append("## ");
        page.path().forEach(ancestor -> text.append(line(ancestor)).append(" › "));
        text.append(page.title() == null ? "(ohne Titel)" : line(page.title())).append('\n');
        List<String> facts = new ArrayList<>();
        if (showSpace && page.spaceKey() != null) {
            facts.add("Space " + page.spaceKey());
        }
        facts.add(page.kinds().stream().map(UpdateTextRenderer::label).collect(Collectors.joining(", ")));
        if (page.oldVersion() != null || page.newVersion() != null) {
            facts.add("Version " + (page.oldVersion() == null ? "–" : page.oldVersion()) + " → "
                    + (page.newVersion() == null ? "–" : page.newVersion()));
        }
        if (page.at() != null) {
            facts.add(time(page.at()));
        }
        if (!page.editors().isEmpty()) {
            facts.add(zone == null ? "Bearbeiter: " + page.editors().size()
                    : "bearbeitet von " + page.editors().stream().map(UpdateTextRenderer::line)
                    .collect(Collectors.joining(", ")));
        }
        text.append(String.join(" · ", facts)).append('\n');
        if (page.kinds().contains(ChangeKind.RENAMED) && page.oldTitle() != null && page.newTitle() != null) {
            text.append("Titel: „").append(line(page.oldTitle())).append("“ → „").append(line(page.newTitle()))
                    .append("“\n");
        }
        if (page.kinds().contains(ChangeKind.LABELS_CHANGED)) {
            text.append("Labels: ").append(page.labels().isEmpty() ? "keine"
                    : page.labels().stream().map(UpdateTextRenderer::line).collect(Collectors.joining(", ")))
                    .append('\n');
        }
        if (page.kinds().contains(ChangeKind.MOVED_OUT) && page.newSpaceKey() != null) {
            text.append("Neuer Space: ").append(page.newSpaceKey()).append('\n');
        }
        if (page.contentPending() && page.diff() == null) {
            text.append("_Inhalt der neuen Version noch nicht geladen_\n");
        }
        if (page.diff() != null) {
            diff(text, page.diff());
        }
        text.append('\n');
    }

    private static void diff(StringBuilder text, ContentDiff diff) {
        switch (diff.status()) {
            case PREVIOUS_MISSING -> text.append("_Voriger Inhalt fehlt im Cache; kein Unterschied_\n");
            case NOT_PERMITTED -> text.append("_Kein Leserecht für den Inhalt_\n");
            case CONTENT_PENDING -> text.append("_Inhalt der neuen Version noch nicht geladen_\n");
            case AVAILABLE -> {
            }
        }
        boolean truncated = false;
        boolean any = false;
        // Absätze unter ihrem Überschriftenpfad, Pfade in der Reihenfolge ihres ersten Auftretens.
        Map<List<String>, List<ParagraphChange>> sections = new LinkedHashMap<>();
        for (ContentChange step : diff.steps()) {
            truncated |= step.truncated();
            for (ParagraphChange paragraph : step.paragraphs()) {
                any = true;
                sections.computeIfAbsent(paragraph.headings(), ignored -> new ArrayList<>()).add(paragraph);
            }
        }
        sections.forEach((headings, paragraphs) -> {
            if (!headings.isEmpty()) {
                text.append("### ").append(headings.stream().map(UpdateTextRenderer::line)
                        .collect(Collectors.joining(" › "))).append('\n');
            }
            for (ParagraphChange paragraph : paragraphs) {
                switch (paragraph.kind()) {
                    case ADDED -> text.append("+ ").append(block(paragraph.after())).append('\n');
                    case REMOVED -> text.append("− ").append(block(paragraph.before())).append('\n');
                    case CHANGED -> text.append("~ ").append(block(paragraph.before())).append('\n')
                            .append("  → ").append(block(paragraph.after())).append('\n');
                }
            }
        });
        if (diff.status() == ContentDiff.Status.AVAILABLE && !any) {
            text.append("_Nur unsichtbare Änderungen (Formatierung, Makros)_\n");
        }
        if (truncated) {
            text.append("_(gekürzt)_\n");
        }
    }

    /** Ein Absatz auf einer Zeile; Zeilenumbrüche werden zu Leerzeichen, damit die Zeichen +, − und ~ eindeutig bleiben. */
    private static String block(String paragraph) {
        return paragraph.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private static String line(String value) {
        return value.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private String time(Instant at) {
        return zone == null ? at.toString() : DISPLAY.format(at.atZone(zone));
    }

    static String label(ChangeKind kind) {
        return switch (kind) {
            case NEW -> "Neu";
            case CONTENT_CHANGED -> "Inhalt geändert";
            case RENAMED -> "Umbenannt";
            case MOVED -> "Verschoben";
            case LABELS_CHANGED -> "Labels geändert";
            case REMOVED -> "Entfernt";
            case WITHHELD -> "Gesperrt";
            case MOVED_OUT -> "In anderen Space verschoben";
            case LOADED -> "Erstmals geladen";
            case RELOADED -> "Nachgeladen";
        };
    }
}
