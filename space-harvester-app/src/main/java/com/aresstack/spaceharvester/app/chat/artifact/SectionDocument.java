package com.aresstack.spaceharvester.app.chat.artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Dokument als geordnete, flache Liste von Abschnitten (ADR 0045, Abschnitt 2). Ein Abschnitt reicht bis zur
 * nächsten Überschrift gleich welcher Ebene; Unterabschnitte sind eigene Abschnitte, der Text vor der ersten Überschrift
 * ist die Einleitung (Ebene 0, ohne Überschrift). Kennungen bleiben über alle Änderungen gleich und werden nie neu
 * vergeben: {@code nextId} zählt nur aufwärts. Überschriften in Codeblöcken zählen nicht.
 *
 * <p>Reine Logik, unveränderlich: Jede Operation liefert ein neues Dokument oder wirft {@link SectionException} mit
 * einer Meldung, die dem Modell sagt, was es tun soll.
 */
public record SectionDocument(List<Section> sections, long nextId) {

    /** Ein Abschnitt; Ebene 0 ist die Einleitung ohne Überschrift. */
    public record Section(String id, int level, String heading, String text) {
        public Section {
            heading = heading == null ? "" : heading.strip();
            // Nur leere Randzeilen und Leerraum am Ende fallen weg; Einrückung (Codeblock) bleibt.
            text = text == null ? "" : LEADING_BLANK_LINES.matcher(text).replaceFirst("").stripTrailing();
        }

        /** Grob gezählte Wörter des Texts (ohne Überschrift). */
        public int words() {
            String trimmed = text.strip();
            return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        }

        /** Das Markdown dieses Abschnitts mit Überschrift. */
        public String markdown() {
            StringBuilder out = new StringBuilder();
            if (level > 0) {
                out.append("#".repeat(level)).append(' ').append(heading).append("\n\n");
            }
            if (!text.isEmpty()) {
                out.append(text).append("\n\n");
            }
            return out.toString();
        }
    }

    /** Eine Meldung für das Modell: was falsch war und was es jetzt tun soll. */
    public static final class SectionException extends RuntimeException {
        public SectionException(String message) {
            super(message);
        }
    }

    /** Das Ergebnis einer Änderung: das neue Dokument und die Kennungen der berührten Abschnitte. */
    public record Change(SectionDocument document, List<String> touched) {
    }

    // Schließende # nur nach Leerraum, damit „# C#“ seine Raute behält.
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern LEADING_BLANK_LINES = Pattern.compile("^(?:[ \\t]*\\R)+");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("^ {0,3}\\[[^\\]]+]:[ \\t]*\\S.*$");
    private static final String PREFIX = "a";

    public SectionDocument {
        sections = List.copyOf(sections);
    }

    public static SectionDocument empty() {
        return new SectionDocument(List.of(), 1);
    }

    /** Zerlegt Markdown in Abschnitte mit neuen Kennungen. */
    public static SectionDocument parse(String markdown) {
        Change change = empty().split(markdown, 0, null);
        return change.document();
    }

    /** Das Markdown des Dokuments: die Abschnitte aneinandergereiht. */
    public String markdown() {
        StringBuilder out = new StringBuilder();
        for (Section section : sections) {
            out.append(section.markdown());
        }
        return out.toString().strip() + (sections.isEmpty() ? "" : "\n");
    }

    public int words() {
        return sections.stream().mapToInt(Section::words).sum();
    }

    /**
     * Die Linkdefinitionen ({@code [ref]: https://…}) aller Abschnitte außerhalb von Codeblöcken, damit ein Abschnitt,
     * der für sich gezeigt wird, auch Verweise auf Definitionen in anderen Abschnitten auflöst.
     */
    public List<String> referenceDefinitions() {
        List<String> definitions = new ArrayList<>();
        for (Section section : sections) {
            Fences fences = new Fences();
            for (String line : lines(section.text())) {
                if (!fences.inside(line) && REFERENCE_DEFINITION.matcher(line).matches()) {
                    definitions.add(line.strip());
                }
            }
        }
        return definitions;
    }

    /**
     * Verfolgt Codeblöcke Zeile für Zeile: Ein Block endet erst an einer Zaunzeile aus demselben Zeichen, mindestens
     * so lang wie die öffnende und ohne weiteren Text dahinter (CommonMark).
     */
    private static final class Fences {
        private char marker;
        private int length;

        /** Ob {@code line} in einem Codeblock liegt; die öffnende und die schließende Zaunzeile zählen dazu. */
        boolean inside(String line) {
            Matcher fence = FENCE.matcher(line);
            if (length == 0) {
                if (fence.matches()) {
                    marker = fence.group(1).charAt(0);
                    length = fence.group(1).length();
                    return true;
                }
                return false;
            }
            if (fence.matches() && fence.group(1).charAt(0) == marker && fence.group(1).length() >= length
                    && fence.group(2).isBlank()) {
                length = 0;
            }
            return true;
        }
    }

    /** Der Abschnitt mit dieser Kennung, sonst eine Meldung mit den vorhandenen Kennungen. */
    public Section section(String id) {
        return sections.get(index(id));
    }

    private int index(String id) {
        String wanted = id == null ? "" : id.strip();
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id().equalsIgnoreCase(wanted)) {
                return i;
            }
        }
        throw new SectionException("Abschnitt '" + wanted + "' gibt es nicht. Vorhandene Kennungen: " + ids()
                + ". Bei Zweifel zuerst dokument_gliederung aufrufen.");
    }

    private String ids() {
        if (sections.isEmpty()) {
            return "keine (das Dokument ist leer, mit abschnitt_einfuegen ohne 'nach' beginnen)";
        }
        return String.join(", ", sections.stream().map(Section::id).toList());
    }

    /**
     * Ersetzt den Text eines Abschnitts; die Überschrift bleibt. Beginnt der neue Text mit der Überschrift des
     * Abschnitts, wird sie herausgenommen. Weitere Überschriften im Text werden zu neuen Abschnitten danach.
     */
    public Change replaceText(String id, String text) {
        int index = index(id);
        Section old = sections.get(index);
        String body = withoutOwnHeading(old, text == null ? "" : text);
        Change split = split(body, index, old);
        return split;
    }

    /**
     * Ersetzt einen Abschnitt durch das Markdown, das der Benutzer bearbeitet hat (ADR 0045, Abschnitt 5): Beginnt es
     * mit einer Überschrift, gelten deren Text und Ebene; weitere Überschriften werden neue Abschnitte danach. Ohne
     * Überschrift am Anfang bleibt die alte.
     */
    public Change replaceSection(String id, String markdown) {
        int index = index(id);
        Section old = sections.get(index);
        String body = markdown == null ? "" : markdown;
        String stripped = body.stripLeading();
        int end = stripped.indexOf('\n');
        Matcher first = HEADING.matcher(end < 0 ? stripped : stripped.substring(0, end));
        Section head = old;
        if (first.matches()) {
            // Auch die Einleitung darf eine Überschrift bekommen; dann ist sie ein gewöhnlicher Abschnitt.
            head = new Section(old.id(), first.group(1).length(), singleLine(first.group(2)), "");
            body = end < 0 ? "" : stripped.substring(end + 1);
        }
        List<Section> list = new ArrayList<>(sections);
        list.set(index, head);
        return new SectionDocument(list, nextId).split(body, index, head);
    }

    /** Ersetzt eine wörtliche Textstelle im Text eines Abschnitts; sie muss genau einmal vorkommen. */
    public Change replaceLiteral(String id, String search, String replacement) {
        int index = index(id);
        Section old = sections.get(index);
        if (search == null || search.isEmpty()) {
            throw new SectionException("'suchen' ist leer. Die wörtliche Textstelle aus abschnitt_lesen angeben.");
        }
        int count = occurrences(old.text(), search);
        if (count == 0) {
            throw new SectionException("Die Textstelle steht nicht wörtlich in Abschnitt " + old.id()
                    + ". Abschnitt mit abschnitt_lesen neu lesen und die Stelle genau abschreiben.");
        }
        if (count > 1) {
            throw new SectionException("Die Textstelle kommt in Abschnitt " + old.id() + " " + count
                    + "-mal vor. Mehr Umgebung angeben, damit sie eindeutig ist.");
        }
        // Wie beim Ersetzen des Texts: neue Überschriften in der Ersetzung werden eigene Abschnitte.
        return split(old.text().replace(search, replacement == null ? "" : replacement), index, old);
    }

    /**
     * Fügt einen neuen Abschnitt nach {@code after} ein; ohne {@code after} an den Anfang. Weitere Überschriften im
     * Text werden zu weiteren neuen Abschnitten.
     */
    public Change insertAfter(String after, int level, String heading, String text) {
        int position = after == null || after.isBlank() ? 0 : index(after) + 1;
        if (heading == null || heading.isBlank()) {
            throw new SectionException("'ueberschrift' fehlt. Jeder neue Abschnitt braucht eine Überschrift.");
        }
        int depth = level <= 0 ? defaultLevel(position) : Math.min(level, 6);
        String body = text == null ? "" : text;
        Section fresh = new Section(PREFIX + nextId, depth, singleLine(heading), "");
        List<Section> list = new ArrayList<>(sections);
        list.add(position, fresh);
        SectionDocument withHeading = new SectionDocument(list, nextId + 1);
        return withHeading.split(withoutOwnHeading(fresh, body), position, fresh);
    }

    public Change delete(String id) {
        int index = index(id);
        List<Section> list = new ArrayList<>(sections);
        Section removed = list.remove(index);
        return new Change(new SectionDocument(list, nextId), List.of(removed.id()));
    }

    /** Ändert nur die Überschrift; Ebene 0 ist die Einleitung und hat keine. Ebene 0 lässt die Ebene unverändert. */
    public Change rename(String id, String heading, int level) {
        int index = index(id);
        Section old = sections.get(index);
        if (old.level() == 0) {
            throw new SectionException("Abschnitt " + old.id() + " ist die Einleitung und hat keine Überschrift. "
                    + "Für eine Überschrift davor abschnitt_einfuegen ohne 'nach' verwenden.");
        }
        if (heading == null || heading.isBlank()) {
            throw new SectionException("'ueberschrift' ist leer. Zum Entfernen des Abschnitts abschnitt_loeschen.");
        }
        int depth = level <= 0 ? old.level() : Math.min(level, 6);
        List<Section> list = new ArrayList<>(sections);
        list.set(index, new Section(old.id(), depth, singleLine(heading), old.text()));
        return new Change(new SectionDocument(list, nextId), List.of(old.id()));
    }

    /** Setzt einen Abschnitt hinter einen anderen; ohne {@code after} an den Anfang. */
    public Change move(String id, String after) {
        int index = index(id);
        Section moving = sections.get(index);
        if (after != null && !after.isBlank()) {
            index(after);
            if (moving.id().equalsIgnoreCase(after.strip())) {
                throw new SectionException("Ein Abschnitt kann nicht hinter sich selbst stehen.");
            }
        }
        List<Section> list = new ArrayList<>(sections);
        list.remove(index);
        SectionDocument without = new SectionDocument(list, nextId);
        int position = after == null || after.isBlank() ? 0 : without.index(after) + 1;
        list.add(position, moving);
        return new Change(new SectionDocument(list, nextId), List.of(moving.id()));
    }

    /**
     * Setzt {@code body} an die Stelle {@code index}: Der Text bis zur ersten Überschrift wird Text von {@code head}
     * (oder, ohne {@code head}, eine Einleitung), jede weitere Überschrift ein neuer Abschnitt danach.
     */
    private Change split(String body, int index, Section head) {
        List<Section> parsed = new ArrayList<>();
        long next = nextId;
        StringBuilder text = new StringBuilder();
        int level = head == null ? 0 : head.level();
        String heading = head == null ? "" : head.heading();
        String id = head == null ? null : head.id();
        Fences fences = new Fences();
        for (String line : lines(body)) {
            boolean fenced = fences.inside(line);
            Matcher match = fenced ? null : HEADING.matcher(line);
            if (match != null && match.matches()) {
                if (id != null || !text.toString().isBlank()) {
                    if (id == null) {
                        id = PREFIX + next++;
                    }
                    parsed.add(new Section(id, level, heading, text.toString()));
                }
                id = PREFIX + next++;
                level = match.group(1).length();
                heading = match.group(2);
                text.setLength(0);
                continue;
            }
            text.append(line).append('\n');
        }
        if (id != null || !text.toString().isBlank()) {
            if (id == null) {
                id = PREFIX + next++;
            }
            parsed.add(new Section(id, level, heading, text.toString()));
        }
        List<Section> list = new ArrayList<>(sections);
        if (head != null) {
            list.remove(index);
        }
        list.addAll(index, parsed);
        return new Change(new SectionDocument(list, next), parsed.stream().map(Section::id).toList());
    }

    /** Ebene für einen neuen Abschnitt ohne Angabe: wie der Abschnitt davor, sonst 2 (unter dem Titel). */
    private int defaultLevel(int position) {
        for (int i = position - 1; i >= 0; i--) {
            if (sections.get(i).level() > 0) {
                return sections.get(i).level();
            }
        }
        return 2;
    }

    /** Nimmt eine vorangestellte Überschrift heraus, wenn sie die des Abschnitts ist. */
    private static String withoutOwnHeading(Section section, String text) {
        if (section.level() == 0) {
            return text;
        }
        String stripped = text.stripLeading();
        int end = stripped.indexOf('\n');
        String first = end < 0 ? stripped : stripped.substring(0, end);
        Matcher match = HEADING.matcher(first);
        if (match.matches() && normalized(match.group(2)).equals(normalized(section.heading()))) {
            return end < 0 ? "" : stripped.substring(end + 1);
        }
        return text;
    }

    private static String normalized(String heading) {
        return heading.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String singleLine(String heading) {
        return heading.replaceAll("^\\s*#+\\s*", "").replaceAll("\\s+", " ").strip();
    }

    private static int occurrences(String text, String search) {
        int count = 0;
        for (int at = text.indexOf(search); at >= 0; at = text.indexOf(search, at + search.length())) {
            count++;
        }
        return count;
    }

    private static List<String> lines(String text) {
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }
}
