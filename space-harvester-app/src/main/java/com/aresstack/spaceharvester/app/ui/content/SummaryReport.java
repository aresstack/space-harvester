package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.PictureStage;
import com.aresstack.spaceharvester.app.ui.controls.TextWithPicture;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.podcast.UpdatePodcasts;
import com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Die KI-Zusammenfassung eines Bereichs als Bericht (ADR 0034, Abschnitt 7, Paket 5a): oben der Überblick, darunter die
 * Themen je Space mit Details-Knopf, Seitenverweisen und dem Bild des Podcasts neben dem Thema; dazu die gelbe
 * Markierung der Themen, denen der Podcast gerade folgt. Gemeinsam für die Quellenseite ({@link SourceUpdatesView})
 * und später den Favoriten-Ordner; was vom Bereich abhängt (Zahlen je Space, welche Seite sich öffnen lässt), liefert
 * der {@link Host}. Zeichnet in eine gegebene Spalte und hält nur den Zustand der Markierung und der Bilder.
 */
final class SummaryReport {

    /** Was der Bericht vom Bereich wissen muss. */
    interface Host {

        /** Seiten dieses Space im Bereich, zum Ordnen der Spaces; 0 ohne. */
        int pages(String spaceKey);

        /** Die Zahlen eines Space klein, etwa „3 neu · 1 geändert“; leer ohne. */
        String counts(String spaceKey);

        /** Die Spaces mit Seiten im Bereich; die ohne Thema stehen aufklappbar am Ende. */
        Collection<String> spaces();

        /** Titel einer Seite, wenn die Zusammenfassung keinen kennt; {@code null} ohne. */
        String title(SummaryPage page);

        /** Öffnet die Seite; {@code null}, wenn sie sich nicht öffnen lässt. */
        Runnable opener(SummaryPage page);

        /** Öffnet einen Space, etwa aus der Überschrift seiner Themen. */
        void openSpace(String spaceKey);

        /** Der Hinweis, dass die Zusammenfassung nicht mehr alles kennt; {@code null} ohne. */
        String staleNote(UpdateSummary summary);

        /** Ein Zeitpunkt für „Erstellt am …“. */
        String when(Instant at);
    }

    /** Gelb wie die Treffer der Suche (ReaderFind). */
    static final Color PODCAST_MARK = new Color(0xFFF59D);
    /** Schlüssel der Gruppe „Über mehrere Spaces“; kein Space-Schlüssel kann so heißen. */
    static final String ACROSS_SPACES = "\u0000mehrere";
    /** Bis zu so vielen Seiten stehen die Verweise eines Themas offen da, darüber zugeklappt. */
    static final int OPEN_LINKS = 3;
    static final String DETAILS_TIP = "Ausführliche KI-Zusammenfassung dieses Themas";
    /** So breit wird ein Schaubild (SVG) für den Bericht gerastert: scharf auch auf Bildschirmen mit Skalierung. */
    static final int VECTOR_RASTER_WIDTH = 2 * TextWithPicture.MAX_PICTURE_WIDTH;

    /** Ein gelesenes Bild eines Themas des Podcasts. */
    record LoadedPicture(TopicPicture topic, PictureStage.Picture read) {
    }

    /**
     * Ein Bild an seiner Stelle im Bericht: in der Leseansicht in der Meldung ({@link TextWithPicture}), in der
     * Podcast-Ansicht rechts in der Zeile der ersten Seite des Themas.
     *
     * @param anchor wo das Bild steht
     * @param bounds wo genau, in Koordinaten von {@code anchor}
     */
    record PlacedPicture(int topic, JComponent anchor, Supplier<java.awt.Rectangle> bounds, String caption,
                         ImageViewer.Picture viewer) {
    }

    private final JPanel column;
    private final Host host;
    /** Die Teile jedes gezeigten Themas der Zusammenfassung (Nummer in {@link UpdateSummary#statements()}). */
    private final Map<Integer, List<JComponent>> topicParts = new java.util.HashMap<>();
    /** Nimmt die gelbe Markierung des Themas zurück, dem der Podcast gerade folgt. */
    private final List<Runnable> unmark = new ArrayList<>();
    /** Die Themen, von denen der Podcast gerade erzählt; bleiben markiert, auch wenn der Bericht neu gezeichnet wird. */
    private List<Integer> followed = List.of();
    /**
     * Die Themen, von denen der ganze Podcast erzählt; die übrigen sind grau hinterlegt (Angelo 27.11.2026).
     * {@code null} ohne Podcast: dann ist nichts grau.
     */
    private java.util.Set<Integer> covered;
    /** Der Text jedes gezeigten Themas (Nummer in {@link UpdateSummary#statements()}), in der Reihenfolge des Berichts. */
    private final Map<Integer, TextWithPicture> bodies = new java.util.LinkedHashMap<>();
    /** Die gerade gezeigte Zusammenfassung, für Space-Namen der Themen. */
    private UpdateSummary rendered;

    /**
     * Erklärend zeichnen (Podcast-Ansicht eines Bereichs, Angelo 24.11.2026): je Thema steht die ausführliche Fassung
     * direkt im Text statt hinter dem Details-Knopf, damit man mitlesen kann, was der Podcast erklärt.
     */
    private boolean explained;

    SummaryReport(JPanel column, Host host) {
        this.column = Objects.requireNonNull(column, "column");
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Die gezeigte Zusammenfassung, {@code null} vor der ersten. */
    /** Zeichnet ab jetzt erklärend ({@link #explained}). */
    SummaryReport explained(boolean value) {
        explained = value;
        return this;
    }

    UpdateSummary rendered() {
        return rendered;
    }

    /** Vergisst Teile, Markierungen und Bilder des vorigen Berichts; die Spalte leert der Aufrufer. */
    void clear() {
        topicParts.clear();
        unmark.clear();
        bodies.clear();
    }

    /**
     * Ein Teil, das zur Stelle {@code number} gehört, ohne Bericht (etwa ein Kapitel des Buchs): Gelb und Grau
     * markieren es wie die Teile eines Themas.
     */
    void topicPart(int number, JComponent part) {
        topicParts.computeIfAbsent(number, key -> new ArrayList<>()).add(part);
    }

    /** Ob Themen mit Text dastehen, neben die Bilder kommen können. */
    boolean hasBodies() {
        return !bodies.isEmpty();
    }

    /**
     * Die Zusammenfassung (Angelo, 21.11.2026): oben der Überblick, darunter die Themen je Space, Space mit Namen und
     * anklickbar, darunter klein die Zahlen. Themen über mehrere Spaces stehen für sich. Je Thema Titel, kurzer Text,
     * ein Knopf für die ausführliche KI-Zusammenfassung und die Seiten, ab {@link #OPEN_LINKS} zugeklappt. Spaces ohne
     * Thema (etwa ohne KI-Freigabe) stehen aufklappbar am Ende. Die Modellkennung steht nur im Tooltip. Scheitert die
     * Darstellung, steht der reine Text da, nie eine leere Seite.
     *
     * @param after was nach dem Zeichnen der Themen folgt (Markierung, Bilder); scheitert es, gilt auch das als
     *              gescheiterte Darstellung
     */
    void render(UpdateSummary summary, Runnable after) {
        int before = column.getComponentCount();
        try {
            renderTopics(summary);
            after.run();
        } catch (RuntimeException e) {
            // Nie eine leere Seite: was an der Darstellung scheitert, fällt auf den reinen Text zurück.
            clear();
            while (column.getComponentCount() > before) {
                column.remove(column.getComponentCount() - 1);
            }
            add(column, Comic.wrapped("Zusammenfassung", Comic.INK, 14f));
            add(column, Comic.wrapped(summary.text(), Comic.TEXT, 13.5f));
            for (SummaryStatement statement : summary.statements()) {
                add(column, Comic.wrapped((statement.title() == null ? "" : statement.title() + ": ")
                        + statement.text(), Comic.TEXT, 13.5f));
            }
        }
    }

    private void renderTopics(UpdateSummary summary) {
        rendered = summary;
        JLabel title = new JLabel("Zusammenfassung");
        title.setFont(Comic.semiBold(14f));
        title.setForeground(Comic.INK);
        add(column, title);
        WrappedText meta = Comic.wrapped(meta(summary), Comic.MUTED, 12f);
        meta.setName("summary-meta");
        meta.setToolTipText(modelTip(summary));
        add(column, meta);
        String stale = host.staleNote(summary);
        if (stale != null) {
            add(column, Comic.wrapped(stale, Comic.ORANGE, 12.5f));
        }
        // Ältere Zusammenfassungen (ohne Themen) haben als Text nur ihre Aussagen aneinandergereiht: dann nur diese.
        boolean topical = summary.statements().stream().anyMatch(statement -> statement.title() != null);
        if (summary.text() != null && !summary.text().isBlank() && (topical || summary.statements().isEmpty())) {
            column.add(Box.createVerticalStrut(6));
            WrappedText overview = Comic.wrapped(summary.text(), Comic.TEXT, 13.5f);
            overview.setName("summary-overview");
            add(column, overview);
            // Einstieg und Abschluss des Podcasts erzählen diesen Überblick: dann ist er gelb (Angelo 23.11.2026).
            topicParts.put(UpdatePodcasts.OVERVIEW, new ArrayList<>(List.of(overview)));
        }
        Map<String, List<SummaryStatement>> topics = topicsBySpace(summary.statements());
        Map<SummaryStatement, Integer> numbering = new java.util.IdentityHashMap<>();
        for (int i = 0; i < summary.statements().size(); i++) {
            numbering.put(summary.statements().get(i), i);
        }
        // Spaces mit Themen, die mit den meisten Seiten zuerst; danach was mehrere Spaces betrifft.
        List<String> spaces = new ArrayList<>(topics.keySet());
        spaces.remove(ACROSS_SPACES);
        spaces.sort(java.util.Comparator.comparingInt((String key) -> -host.pages(key))
                .thenComparing(key -> summary.spaceName(key), String.CASE_INSENSITIVE_ORDER));
        if (topics.containsKey(ACROSS_SPACES)) {
            spaces.add(0, ACROSS_SPACES);
        }
        boolean flat = summary.statements().stream().flatMap(statement -> statement.pages().stream())
                .allMatch(page -> page.spaceKey() == null);
        for (String space : spaces) {
            column.add(Box.createVerticalStrut(flat ? 0 : 12));
            if (!flat) {
                add(column, spaceHeading(space));
            }
            if (!ACROSS_SPACES.equals(space)) {
                String numbers = host.counts(space);
                if (!numbers.isEmpty()) {
                    add(column, Comic.wrapped(numbers, Comic.MUTED, 12f));
                }
            }
            for (SummaryStatement statement : topics.get(space)) {
                renderTopic(statement, ACROSS_SPACES.equals(space), numbering.get(statement));
            }
        }
        List<String> rest = host.spaces().stream().filter(key -> key != null && !topics.containsKey(key))
                .distinct()
                .sorted(java.util.Comparator.comparing(key -> summary.spaceName(key), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (!rest.isEmpty() && !flat) {
            column.add(Box.createVerticalStrut(12));
            JPanel list = new JPanel();
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setOpaque(false);
            for (String key : rest) {
                JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
                row.setOpaque(false);
                row.add(spaceLink(key, 12.5f));
                JLabel numbers = new JLabel("  " + host.counts(key));
                numbers.setFont(Comic.regular(12f));
                numbers.setForeground(Comic.MUTED);
                row.add(numbers);
                add(list, row);
            }
            add(column, toggle((rest.size() == 1 ? "Ein weiterer Space" : rest.size() + " weitere Spaces")
                    + " ohne Thema in der Zusammenfassung", list, false));
        }
        if (summary.unreadableChanges() > 0) {
            column.add(Box.createVerticalStrut(8));
            add(column, Comic.wrapped(unreadable(summary), Comic.MUTED, 12.5f));
        }
        column.add(Box.createVerticalStrut(12));
    }

    /** Der Hinweis auf Nichtgelesenes: bei Updates Änderungen, bei einem Bereich Seiten (ohne Freigabe, übers Budget). */
    static String unreadable(UpdateSummary summary) {
        int count = summary.unreadableChanges();
        if (summary.isArea()) {
            return count == 1 ? "Eine Seite hat die KI nicht gelesen (keine KI-Freigabe, nicht geladen oder über der "
                    + "Seitengrenze); sie fehlt im Überblick."
                    : count + " Seiten hat die KI nicht gelesen (keine KI-Freigabe, nicht geladen oder über der "
                    + "Seitengrenze); sie fehlen im Überblick.";
        }
        return count == 1 ? "Eine Änderung durfte die KI nicht lesen; sie fehlt in der Zusammenfassung."
                : count + " Änderungen durfte die KI nicht lesen; sie fehlen in der Zusammenfassung.";
    }

    /**
     * Die Themen je Space: ein Thema mit Seiten aus genau einem Space gehört zu ihm, eines aus mehreren Spaces zu
     * {@link #ACROSS_SPACES}, eines ohne bekannten Space (ältere Zusammenfassungen) ebenfalls dorthin, ohne Überschrift.
     */
    static Map<String, List<SummaryStatement>> topicsBySpace(List<SummaryStatement> statements) {
        Map<String, List<SummaryStatement>> grouped = new java.util.LinkedHashMap<>();
        for (SummaryStatement statement : statements) {
            java.util.Set<String> spaces = new java.util.TreeSet<>();
            statement.pages().stream().map(SummaryPage::spaceKey).filter(Objects::nonNull).forEach(spaces::add);
            String key = spaces.size() == 1 ? spaces.iterator().next() : ACROSS_SPACES;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement);
        }
        return grouped;
    }

    private JComponent spaceHeading(String space) {
        if (ACROSS_SPACES.equals(space)) {
            JLabel label = new JLabel("Über mehrere Spaces");
            label.setFont(Comic.semiBold(14f));
            label.setForeground(Comic.INK);
            return label;
        }
        return spaceLink(space, 14f);
    }

    /** Der Space mit Namen; ein Klick wählt ihn links im Baum. */
    private JLabel spaceLink(String space, float size) {
        JLabel link = new JLabel(rendered.spaceName(space));
        link.setName("summary-space");
        link.setFont(Comic.semiBold(size));
        link.setForeground(Comic.BLUE);
        String name = rendered.spaceName(space);
        clickable(() -> host.openSpace(space), name.equals(space) ? "Space öffnen" : "Space " + space + " öffnen",
                link);
        return link;
    }

    /** Ein Thema: Titel mit Details-Knopf, kurzer Text, Seiten. */
    private void renderTopic(SummaryStatement statement, boolean across, Integer number) {
        column.add(Box.createVerticalStrut(6));
        List<JComponent> parts = new ArrayList<>();
        if (number != null) {
            topicParts.put(number, parts);
        }
        if (statement.title() != null && !statement.title().isBlank()) {
            JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            JLabel topic = new JLabel(statement.title());
            topic.setName("summary-topic");
            topic.setFont(Comic.semiBold(13.5f));
            topic.setForeground(Comic.INK);
            head.add(topic);
            if (!explained && statement.details() != null && !statement.details().isBlank()) {
                com.aresstack.spaceharvester.app.ui.IconButton details =
                        new com.aresstack.spaceharvester.app.ui.IconButton(
                                com.aresstack.spaceharvester.app.ui.Glyph.SUMMARIZE, DETAILS_TIP).compact();
                details.setName("summary-details");
                details.setForeground(Comic.BLUE);
                details.addActionListener(e -> showDetails(statement, details));
                head.add(Box.createHorizontalStrut(4));
                head.add(details);
            }
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            add(column, head);
            parts.add(head);
        }
        String text = explained && statement.details() != null && !statement.details().isBlank()
                ? statement.details() : statement.text();
        if (across) {
            java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            statement.pages().stream().map(SummaryPage::spaceKey).filter(Objects::nonNull)
                    .forEach(key -> names.add(rendered.spaceName(key)));
            if (!names.isEmpty()) {
                text += " (" + String.join(", ", names) + ")";
            }
        }
        TextWithPicture body = new TextWithPicture(text, Comic.TEXT, 13.5f);
        body.setName("summary-text");
        add(column, body);
        parts.add(body);
        if (number != null) {
            bodies.put(number, body);
        }
        if (statement.pages().isEmpty()) {
            return;
        }
        JPanel links = new JPanel(new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(
                java.awt.FlowLayout.LEFT, 10, 0));
        links.setOpaque(false);
        for (SummaryPage page : statement.pages()) {
            links.add(pageLink(page));
        }
        // Die Verweise stehen direkt unter dem Text der Meldung, auch neben ihrem Bild (UX-Manager 22.11.2026).
        body.footer(statement.pages().size() <= OPEN_LINKS ? links
                : toggle(pages(statement.pages().size()), links, false));
    }

    /** Das Fenster mit der ausführlichen Zusammenfassung eines Themas; darunter die Seiten zum Öffnen. */
    private void showDetails(SummaryStatement statement, Component owner) {
        com.aresstack.spaceharvester.app.ui.controls.InfoPopup popup =
                new com.aresstack.spaceharvester.app.ui.controls.InfoPopup()
                        .text(statement.title() == null ? "Details" : statement.title(), statement.details());
        for (SummaryPage page : statement.pages()) {
            Runnable open = host.opener(page);
            if (open != null) {
                String title = page.title() != null && !page.title().isBlank() ? page.title() : page.pageId();
                popup.link("→ " + title, open);
            }
        }
        popup.showAt(owner, 0, owner.getHeight());
    }

    /** Verweis eines Themas auf eine Seite; anklickbar, wenn sie sich öffnen lässt. */
    private JLabel pageLink(SummaryPage page) {
        String known = host.title(page);
        String title = page.title() != null && !page.title().isBlank() ? page.title()
                : known != null ? known : page.pageId();
        JLabel link = new JLabel("→ " + title);
        link.setFont(Comic.semiBold(12.5f));
        link.setForeground(Comic.BLUE);
        link.setName("summary-link");
        Runnable open = host.opener(page);
        if (open != null) {
            clickable(open, "Seite öffnen", link);
        } else {
            link.setForeground(Comic.MUTED);
        }
        return link;
    }

    /** „Erstellt am …“ ohne Technik; das Modell steht im Tooltip ({@link #modelTip}). */
    String meta(UpdateSummary summary) {
        return "Erstellt am " + host.when(summary.createdAt());
    }

    static String modelTip(UpdateSummary summary) {
        return summary.model() == null || summary.model().isBlank() ? null : "Erstellt mit " + summary.model();
    }

    // --- Markierung des Podcasts ---------------------------------------------------------------------------------

    /** Ordnet einem Thema weitere Teile zu, etwa die Zeilen seiner Seiten in der Podcast-Ansicht. */
    void parts(int number, List<JComponent> parts) {
        topicParts.put(number, new ArrayList<>(parts));
    }

    /** Nimmt die Markierung zurück und vergisst die Zuordnung der Teile (vor dem Neuordnen). */
    void unmarkAll() {
        unmark.forEach(Runnable::run);
        unmark.clear();
        topicParts.clear();
    }

    /** Merkt sich die Themen, denen der Podcast folgt, markiert sie und liefert das erste markierte Teil oder null. */
    JComponent follow(List<Integer> statements) {
        followed = List.copyOf(statements);
        return markFollowed();
    }

    /**
     * Merkt sich, von welchen Themen der ganze Podcast erzählt, und hinterlegt die übrigen grau (Angelo 27.11.2026:
     * „damit man sofort sehen kann, was nicht abgedeckt war im Podcast“); leer = ohne Podcast, nichts grau.
     */
    void cover(java.util.Optional<List<Integer>> statements) {
        covered = statements.map(java.util.Set::copyOf).orElse(null);
        markFollowed();
    }

    /**
     * Markiert die Themen, denen der Podcast gerade folgt, gelb und nur diese, und hinterlegt grau, wovon der ganze
     * Podcast nie erzählt; das erste gelb markierte Teil oder null.
     */
    JComponent markFollowed() {
        unmark.forEach(Runnable::run);
        unmark.clear();
        JComponent first = null;
        // Mehrere Themen können dieselbe Seite nennen: jedes Teil nur einmal markieren, sonst hielte die zweite
        // Markierung Gelb für den Ausgangszustand und es bliebe nach dem Zurücknehmen stehen.
        java.util.Set<JComponent> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Integer number : followed) {
            for (JComponent part : topicParts.getOrDefault(number, List.of())) {
                if (!seen.add(part)) {
                    continue;
                }
                first = first == null ? part : first;
                mark(part, PODCAST_MARK);
            }
        }
        for (JComponent part : uncovered()) {
            if (seen.add(part)) {
                mark(part, Comic.PODCAST_GAP);
            }
        }
        return first;
    }

    /** Die Teile, die zu keinem Thema gehören, von dem der Podcast erzählt; ohne Podcast keine. */
    private List<JComponent> uncovered() {
        if (covered == null) {
            return List.of();
        }
        java.util.Set<JComponent> told = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        covered.forEach(number -> told.addAll(topicParts.getOrDefault(number, List.of())));
        List<JComponent> gaps = new ArrayList<>();
        java.util.Set<JComponent> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        topicParts.values().forEach(parts -> parts.stream().filter(part -> !told.contains(part) && seen.add(part))
                .forEach(gaps::add));
        return gaps;
    }

    private void mark(JComponent part, Color color) {
        if (part instanceof TextWithPicture body) {
            body.highlight(color);
            unmark.add(() -> body.highlight(null));
            return;
        }
        if (part instanceof javax.swing.text.JTextComponent text) {
            try {
                Object tag = text.getHighlighter().addHighlight(0, text.getDocument().getLength(),
                        new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(color));
                unmark.add(() -> text.getHighlighter().removeHighlight(tag));
            } catch (javax.swing.text.BadLocationException e) {
                // ein leerer Text braucht keine Markierung
            }
            return;
        }
        boolean opaque = part.isOpaque();
        Color background = part.getBackground();
        part.setOpaque(true);
        part.setBackground(color);
        part.repaint();
        unmark.add(() -> {
            part.setOpaque(opaque);
            part.setBackground(background);
            part.repaint();
        });
    }

    /** Die Teile der Themen, denen der Podcast gerade folgt (für Tests). */
    List<JComponent> followedParts() {
        List<JComponent> found = new ArrayList<>();
        for (Integer number : followed) {
            found.addAll(topicParts.getOrDefault(number, List.of()));
        }
        return found;
    }

    /** Gelb markierte Teile (für Tests). */
    List<String> marked() {
        return marked(PODCAST_MARK);
    }

    /** Grau hinterlegte Teile, von denen der Podcast nie erzählt (für Tests). */
    List<String> gaps() {
        return marked(Comic.PODCAST_GAP);
    }

    private List<String> marked(Color color) {
        List<String> marked = new ArrayList<>();
        for (List<JComponent> parts : topicParts.values()) {
            for (JComponent part : parts) {
                if (part instanceof TextWithPicture body) {
                    if (color.equals(body.highlightColor())) {
                        marked.add(body.text());
                    }
                } else if (part instanceof javax.swing.text.JTextComponent text) {
                    if (java.util.Arrays.stream(text.getHighlighter().getHighlights()).anyMatch(highlight ->
                            highlight.getPainter() instanceof javax.swing.text.DefaultHighlighter.DefaultHighlightPainter
                                    painter && color.equals(painter.getColor()))) {
                        marked.add(text.getText());
                    }
                } else if (part.isOpaque() && color.equals(part.getBackground())) {
                    // Leseansicht: Zeile mit Beschriftung vorn; Podcast-Ansicht: Überschrift oder Seitenzeile
                    marked.add(part instanceof JLabel label ? label.getText()
                            : part.getComponent(0) instanceof JLabel label ? label.getText()
                            : ((javax.swing.text.JTextComponent) part.getComponent(0)).getText());
                }
            }
        }
        return marked;
    }

    // --- Bilder des Podcasts -------------------------------------------------------------------------------------

    /**
     * Stellt die gelesenen Bilder neben ihre Themen (Leseansicht); was keine Stelle findet, fehlt.
     *
     * @param open öffnet das Bild an seiner Stelle in der Liste des Ergebnisses
     * @return die Bilder an ihrer Stelle, in der Reihenfolge des Berichts
     */
    List<PlacedPicture> showPictures(List<LoadedPicture> loaded, IntConsumer open) {
        Map<Integer, Integer> at = rendered == null ? Map.of() : placements(new ArrayList<>(bodies.keySet()),
                rendered.statements(), loaded.stream().map(LoadedPicture::topic).toList());
        List<PlacedPicture> now = new ArrayList<>();
        for (Map.Entry<Integer, TextWithPicture> entry : bodies.entrySet()) {
            TextWithPicture body = entry.getValue();
            Integer index = at.get(entry.getKey());
            java.awt.image.BufferedImage image = index == null ? null : inline(loaded.get(index).read());
            if (image == null) {
                body.picture(null, null);
                continue;
            }
            PictureStage.Picture read = loaded.get(index).read();
            int position = now.size();
            body.picture(image, read.caption());
            body.onOpen(() -> open.accept(position));
            now.add(new PlacedPicture(loaded.get(index).topic().topic(), body, body::pictureBounds, body.caption(),
                    new ImageViewer.Picture(image, read.caption(), read.vector())));
        }
        column.revalidate();
        column.repaint();
        return List.copyOf(now);
    }

    /**
     * Wo die Bilder des Podcasts im Bericht stehen (UX-Manager, 22.11.2026): jedes genau einmal, neben der ersten
     * Meldung in der Reihenfolge des Berichts, deren Seiten sich mit denen des Themas überschneiden; sonst neben der
     * ersten, deren Titel einem Meldungstitel des Themas gleicht; sonst nirgends, nie gesammelt am Ende. Eine Meldung
     * trägt höchstens ein Bild.
     *
     * @param order      die Nummern der gezeigten Meldungen in der Reihenfolge des Berichts
     * @param statements alle Meldungen der Zusammenfassung
     * @return Nummer der Meldung → Stelle des Bildes in {@code pictures}
     */
    static Map<Integer, Integer> placements(List<Integer> order, List<SummaryStatement> statements,
                                            List<TopicPicture> pictures) {
        Map<Integer, Integer> at = new java.util.HashMap<>();
        for (int p = 0; p < pictures.size(); p++) {
            TopicPicture picture = pictures.get(p);
            java.util.Set<String> ids = new java.util.HashSet<>(picture.pageIds());
            java.util.Set<String> titles = new java.util.HashSet<>();
            picture.storyTitles().stream().filter(Objects::nonNull).map(SummaryReport::comparable)
                    .forEach(titles::add);
            Integer found = null;
            for (Integer number : order) {
                if (!at.containsKey(number) && number >= 0 && number < statements.size() && statements.get(number)
                        .pages().stream().anyMatch(page -> ids.contains(page.pageId()))) {
                    found = number;
                    break;
                }
            }
            if (found == null) {
                for (Integer number : order) {
                    String title = number >= 0 && number < statements.size() ? statements.get(number).title() : null;
                    if (!at.containsKey(number) && title != null && titles.contains(comparable(title))) {
                        found = number;
                        break;
                    }
                }
            }
            if (found != null) {
                at.put(found, p);
            }
        }
        return at;
    }

    private static String comparable(String title) {
        return title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.GERMAN);
    }

    /** Ob beide Zusammenfassungen denselben Podcast haben (auch neu gelesen, also nicht dasselbe Objekt). */
    static boolean samePodcast(UpdateSummary a, UpdateSummary b) {
        return a != null && b != null && (a == b || UpdatePodcasts.key(a).equals(UpdatePodcasts.key(b)));
    }

    /** Das Bild als Raster für den Bericht; Schaubilder werden scharf gerastert. */
    static java.awt.image.BufferedImage inline(PictureStage.Picture read) {
        if (read.image() != null) {
            return read.image();
        }
        return read.vector() == null ? null : read.vector().raster(VECTOR_RASTER_WIDTH);
    }

    // --- Bausteine -----------------------------------------------------------------------------------------------

    /** Ein aufklappbarer Abschnitt: Pfeil und Beschriftung, darunter der Inhalt. */
    static JComponent toggle(String label, JComponent content, boolean open) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        // Pfeil gezeichnet wie bei „Erweitert“ (Comic.disclosure): die Schrift hat ▸/▾ nicht und zeigte ein Kästchen.
        javax.swing.JButton head = Comic.disclosure(label, "Auf- oder zuklappen");
        head.setName("summary-toggle");
        head.setForeground(Comic.BLUE);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        Comic.disclose(head, open);
        content.setVisible(open);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.addActionListener(e -> {
            content.setVisible(!content.isVisible());
            Comic.disclose(head, content.isVisible());
            box.revalidate();
            box.repaint();
        });
        add(box, head);
        add(box, content);
        return box;
    }

    static void add(JPanel column, JComponent part) {
        part.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(part);
    }

    static void clickable(Runnable action, String tip, Component... parts) {
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        };
        // Die Textfelder fangen Mausklicks selbst ab; der Klick gilt auf Zeile und Texten gleich.
        for (Component part : parts) {
            Cursors.set(part, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            part.addMouseListener(click);
            if (part instanceof JComponent component) {
                component.setToolTipText(tip);
            }
        }
    }

    static String pages(int count) {
        return count == 1 ? "1 Seite" : count + " Seiten";
    }
}
