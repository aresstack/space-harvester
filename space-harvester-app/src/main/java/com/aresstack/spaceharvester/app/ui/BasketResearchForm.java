package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.research.Abbreviations;
import com.aresstack.spaceharvester.app.research.BookFieldFill;
import com.aresstack.spaceharvester.app.research.BookFields;
import com.aresstack.spaceharvester.app.research.BookNotes;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.ui.controls.LengthField;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Eingabe zu „Korb füllen“ (Buchfunktion Phase 3) und „Buch schreiben“ (Phase 4, Arbeitszweig): sichtbar Idee und
 * Stichpunkte, der Auftrag und beim Buch die Fragen an das Buch; der KI-Knopf im Feld „Idee und Stichpunkte“ füllt auf
 * Klick alle Felder daraus (je Feld eine Anfrage, parallel, gestreamt, abbrechbar; Abkürzungen löst vorher das
 * Fachglossar auf, nie das Modell; gefüllte Felder ersetzt er nur nach „wirklich ersetzen?“). Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist (beim
 * Buch auch die Länge), stehen unter „Erweitert“. Daraus wird der Weidezaun. Was zum Ordner schon gespeichert ist,
 * steht vorab in den Feldern ({@link #prefill}). Die Eingabe ist ein Overlay wie die übrigen Formulare
 * ({@link Dialogs#formOverlay}).
 */
final class BasketResearchForm {

    static final String TITLE = "Korb füllen";
    static final String ACTION = "Recherche starten";
    static final String BOOK_TITLE = "Buch schreiben";
    static final String BOOK_ACTION = "Vorbereiten";

    /** Merkt sich die gezogene Fenstergröße je Formular ({@link Dialogs#formOverlaySized}). */
    static final String SIZE_KEY = "basket-research";
    static final String BOOK_SIZE_KEY = "book-research";
    /** Ohne gemerkte Größe so viel des nutzbaren Bildschirms. */
    static final double SCREEN_SHARE = 0.6;
    /** Aufgeklappt bekommen die Felder unter „Erweitert“ zusammen so viel Höhe wie der Auftrag mal diesen Faktor. */
    private static final double MORE_WEIGHT = 2;
    private static final int COLUMNS = 50;
    static final String FILL_TIP = "Felder mit KI aus Idee und Stichpunkten füllen";
    static final String CANCEL_TIP = "KI abbrechen";

    /**
     * Was der KI-Knopf braucht: das Chat-Modell des KI-Dienstes, die Parallelität und das Fachglossar zum Auflösen der
     * Abkürzungen ({@code null}: keins, dann bleiben alle Abkürzungen unaufgelöst).
     */
    record AiAccess(AiChat chat, String model, int capacity, GlossaryAccess glossary) {
    }

    final JTextArea ideas = area(6);
    final JTextArea mission = area(6);
    /** Nur beim Buch: Fragen an das Buch, eine je Zeile (Pflichtfragen neben dem Weidezaun). */
    final JTextArea questions = area(5);
    final IconButton fill = new IconButton(Glyph.SPARKLE, FILL_TIP).compact();
    /** Der eine Statusbereich des Formulars: Stand der KI, unbekannte Abkürzungen, Fehler je Feld. */
    final JTextArea status = Comic.note("");
    private AiAccess ai;
    private BookFieldFill running;
    private final Map<BookFields.Field, String> problems = new EnumMap<>(BookFields.Field.class);
    private List<String> unresolved = List.of();
    private String state = "";
    final JTextArea edge = area(3);
    final JTextArea included = area(4);
    final JTextArea excluded = area(4);
    private final JButton advanced = Comic.disclosure("Erweitert",
            "Rand des Themas, was ausdrücklich dazugehört und was ausgeschlossen ist");
    /**
     * Wörter je DIN-A4-Seite, mit denen „Seiten“ umgerechnet werden, solange der Leser nichts anderes einstellt: eine
     * einseitig beschriebene Seite Fließtext in 11 bis 12 Punkt. Die Zahl im Feld ist änderbar und bleibt gemerkt.
     */
    static final int DEFAULT_WORDS_PER_PAGE = 400;
    static final String WORDS_PER_PAGE_KEY = "book.words-per-page";
    /** Nur beim Buch: gewünschte Länge als Zahl mit Einheit (Wörter oder Seiten), intern Wörter; 0 = nach den Belegen. */
    final LengthField length =
            new LengthField(
                    WindowSizes.recallInt(WORDS_PER_PAGE_KEY, DEFAULT_WORDS_PER_PAGE));
    private final boolean book;

    BasketResearchForm() {
        this(false);
    }

    private BasketResearchForm(boolean book) {
        this.book = book;
    }

    /** Die Eingabe zu „Buch schreiben“. */
    static BasketResearchForm book() {
        return new BasketResearchForm(true);
    }

    /** Mit KI-Knopf; ohne ({@code null}) sagt der Knopf, dass ein KI-Dienst fehlt. */
    BasketResearchForm withAi(AiAccess access) {
        this.ai = access;
        return this;
    }

    /** Setzt alles vorab, was am Ordner gespeichert ist: Weidezaun, Länge, Stichpunkte und Fragen. */
    BasketResearchForm prefill(BookNotes notes) {
        prefill(notes.fence(), notes.targetWords());
        if (notes.lengthAmount() > 0) {
            LengthField.Unit unit = LengthField.Unit.parse(notes.lengthUnit());
            length.value(notes.lengthAmount(), unit);
            if (unit == LengthField.Unit.PAGES && notes.targetWords() > 0) {
                // Die Umrechnung dieses Buchs, nicht die zuletzt bei einem anderen Buch gemerkte.
                length.wordsPerPage(Math.round((float) notes.targetWords() / notes.lengthAmount()));
            }
        }
        ideas.setText(notes.ideas());
        questions.setText(String.join("\n", notes.questions()));
        return this;
    }

    /** Setzt Auftrag, Rand, Ein- und Ausschlüsse und die Länge (0 = leer) vorab. */
    BasketResearchForm prefill(Fence fence, int targetWords) {
        mission.setText(fence.getMission());
        edge.setText(fence.getEdge());
        included.setText(String.join("\n", fence.getIncluded()));
        excluded.setText(String.join("\n", fence.getExcluded()));
        length.value(Math.max(0, targetWords), LengthField.Unit.WORDS);
        return this;
    }

    /**
     * Fragt nach Auftrag und Weidezaun; leer, wenn der Benutzer abbricht oder keinen Auftrag nennt. Das Fenster ist
     * vergrößerbar und merkt sich die gezogene Größe; die Felder wachsen mit.
     */
    Optional<Fence> ask(Component parent, String folderName) {
        boolean start = Dialogs.formOverlaySized(parent, (book ? BOOK_TITLE : TITLE) + " – " + folderName,
                body(folderName), book ? BOOK_ACTION : ACTION, book ? BOOK_SIZE_KEY : SIZE_KEY, SCREEN_SHARE);
        cancelFill();
        if (start && book && length.unit() == LengthField.Unit.PAGES) {
            WindowSizes.rememberInt(WORDS_PER_PAGE_KEY, length.wordsPerPage());
        }
        return start ? fence() : Optional.empty();
    }

    /** Die gewünschte Länge in Wörtern; 0 = keine Vorgabe (dann nach den Belegen). */
    int targetWords() {
        return book ? length.words() : 0;
    }

    JComponent body(String folderName) {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        int row = 0;
        row = add(body, row, Comic.note(book ? "Aus den Seiten im Ordner „" + folderName + "“ entsteht ein Buch zum "
                + "Auftrag. Zuerst beschreibt die KI die Quellen und siebt die Begriffe; danach wählst du die Begriffe, "
                + "die eigene Fragen bekommen, und startest das Schreiben. Buch und Missionsbericht kommen in den "
                + "Ausgabeordner. Fortschritt unter Aufträge."
                : "Die Recherche sucht im Wissensbestand Seiten zum Auftrag, prüft jede am "
                + "Weidezaun und legt passende als Favoriten in den Ordner „" + folderName + "“. Seiten, die schon im "
                + "Ordner liegen, liest sie nicht noch einmal. Fortschritt unter Aufträge."), 0);
        row = add(body, row, label("Idee und Stichpunkte (grobe Idee, Fragestellung, Stichpunkte)"), 0);
        fill.addActionListener(e -> fillClicked(body));
        row = add(body, row, withButton(field(ideas), fill), 1);
        status.setForeground(Comic.MUTED);
        row = add(body, row, status, 0);
        row = add(body, row, label("Auftrag"), 0);
        row = add(body, row, field(mission), 1);
        if (book) {
            row = add(body, row, label("Fragen an das Buch (eine je Zeile)"), 0);
            row = add(body, row, field(questions), 1);
        }
        row = add(body, row, advanced, 0);
        JPanel more = new JPanel(new GridBagLayout());
        more.setOpaque(false);
        int inner = 0;
        inner = add(more, inner, label("Rand des Themas"), 0);
        inner = add(more, inner, field(edge), 1);
        inner = add(more, inner, label("Dazu gehört ausdrücklich (eine Angabe je Zeile)"), 0);
        inner = add(more, inner, field(included), 1);
        inner = add(more, inner, label("Ausgeschlossen (eine Angabe je Zeile)"), 0);
        inner = add(more, inner, field(excluded), 1);
        if (book) {
            inner = add(more, inner, label("Länge"), 0);
            add(more, inner, length, 0);
        }
        more.setVisible(false);
        GridBagConstraints place = constraints(row, 0);
        body.add(more, place);
        advanced.addActionListener(e -> {
            boolean open = !Comic.disclosed(advanced) || Boolean.TRUE.equals(advanced.getClientProperty(OPEN_ONLY));
            advanced.putClientProperty(OPEN_ONLY, null);
            Comic.disclose(advanced, open);
            more.setVisible(open);
            // Aufgeklappt teilen sich die Felder die Höhe; Auftrag und Erweitert wachsen gleich mit.
            body.remove(more);
            body.add(more, constraints(place.gridy, open ? MORE_WEIGHT : 0));
            grow(body);
            body.revalidate();
            body.repaint();
        });
        return body;
    }

    private static final String OPEN_ONLY = "basket-research.open-only";

    /** Ob es die Eingabe zu „Buch schreiben“ ist (mit Fragen und Länge). */
    boolean isBook() {
        return book;
    }

    /** Die Fragen an das Buch, eine je Zeile; leer beim Korb. */
    List<String> questions() {
        return book ? lines(questions) : List.of();
    }

    /** Idee und Stichpunkte, wie der Leser sie geschrieben hat. */
    String ideas() {
        return ideas.getText().strip();
    }

    // ------------------------------------------------------------------ KI füllt die Felder

    /** Die Felder, die die KI füllt, in der Reihenfolge der Anfragen. */
    Map<BookFields.Field, JTextArea> targets() {
        Map<BookFields.Field, JTextArea> fields = new LinkedHashMap<>();
        fields.put(BookFields.Field.MISSION, mission);
        if (book) {
            fields.put(BookFields.Field.QUESTIONS, questions);
        }
        fields.put(BookFields.Field.EDGE, edge);
        fields.put(BookFields.Field.INCLUDED, included);
        fields.put(BookFields.Field.EXCLUDED, excluded);
        return fields;
    }

    private void fillClicked(JComponent body) {
        if (running != null) {
            cancelFill();
            return;
        }
        if (ai == null) {
            show("Dafür braucht es einen KI-Dienst mit Chat-Modell (Einstellungen › KI-Dienst).");
            return;
        }
        if (ideas().isEmpty()) {
            show("Schreib zuerst eine Idee, eine Fragestellung oder Stichpunkte ins Feld darüber.");
            return;
        }
        Map<BookFields.Field, JTextArea> all = targets();
        List<String> filled = new ArrayList<>();
        all.forEach((field, area) -> {
            if (!area.getText().isBlank()) {
                filled.add(field.label());
            }
        });
        boolean replace = filled.isEmpty() || Dialogs.confirmOverlay(body, "Gefüllte Felder wirklich ersetzen?",
                Comic.note("Schon gefüllt: " + String.join(", ", filled) + ". Bei „Nein“ füllt die KI nur die "
                        + "leeren Felder."));
        Map<BookFields.Field, JTextArea> chosen = new LinkedHashMap<>();
        Map<BookFields.Field, String> fixed = new EnumMap<>(BookFields.Field.class);
        all.forEach((field, area) -> {
            if (replace || area.getText().isBlank()) {
                chosen.put(field, area);
            } else {
                fixed.put(field, area.getText());
            }
        });
        if (chosen.isEmpty()) {
            show("Alle Felder sind gefüllt; die KI hat nichts ersetzt.");
            return;
        }
        if (chosen.keySet().stream().anyMatch(f -> f == BookFields.Field.EDGE || f == BookFields.Field.INCLUDED
                || f == BookFields.Field.EXCLUDED) && !Comic.disclosed(advanced)) {
            advanced.putClientProperty(OPEN_ONLY, Boolean.TRUE);
            advanced.doClick();
        }
        String text = ideas();
        AiAccess access = ai;
        problems.clear();
        unresolved = List.of();
        running = new BookFieldFill(access.chat(), access.model(), access.capacity());
        BookFieldFill fill = running;
        busy(true);
        show("Abkürzungen im Fachglossar nachschlagen …");
        Function<String, Optional<String>> lookup = access.glossary() == null ? acronym -> Optional.empty()
                : Abbreviations.glossary(access.glossary());
        CompletableFuture.supplyAsync(() -> Abbreviations.resolve(text, lookup)).whenComplete((abbreviations,
                failed) -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (running != fill) {
                return;
            }
            Abbreviations.Result known = abbreviations == null ? Abbreviations.resolve(text, a -> Optional.empty())
                    : abbreviations;
            unresolved = known.unresolved();
            Map<BookFields.Field, List<AiChat.Message>> requests = new LinkedHashMap<>();
            chosen.keySet().forEach(field -> requests.put(field, BookFields.messages(field, text, known, fixed)));
            show("Die KI füllt " + chosen.size() + (chosen.size() == 1 ? " Feld" : " Felder") + " …");
            fill.start(requests, onEdt(new BookFieldFill.Listener() {
                @Override
                public void started(BookFields.Field field) {
                    chosen.get(field).setText("");
                }

                @Override
                public void delta(BookFields.Field field, String piece) {
                    chosen.get(field).append(piece);
                }

                @Override
                public void completed(BookFields.Field field, String result) {
                    chosen.get(field).setText(result);
                    chosen.get(field).setCaretPosition(0);
                }

                @Override
                public void failed(BookFields.Field field, String reason) {
                    problems.put(field, reason);
                    show(state);
                }

                @Override
                public void finished() {
                    if (running == fill) {
                        running = null;
                        busy(false);
                        show(problems.isEmpty() ? "Die KI hat die Felder gefüllt. Prüfe sie und ändere, was nicht "
                                + "passt." : "Die KI ist fertig; nicht alle Felder sind gefüllt.");
                    }
                }
            }, fill));
        }));
    }

    /** Leitet die Meldungen auf den EDT um; die eines abgebrochenen Laufs zählen nicht mehr. */
    private BookFieldFill.Listener onEdt(BookFieldFill.Listener inner, BookFieldFill fill) {
        return new BookFieldFill.Listener() {
            @Override
            public void started(BookFields.Field field) {
                later(() -> inner.started(field));
            }

            @Override
            public void delta(BookFields.Field field, String text) {
                later(() -> inner.delta(field, text));
            }

            @Override
            public void completed(BookFields.Field field, String text) {
                later(() -> inner.completed(field, text));
            }

            @Override
            public void failed(BookFields.Field field, String reason) {
                later(() -> inner.failed(field, reason));
            }

            @Override
            public void finished() {
                later(inner::finished);
            }

            private void later(Runnable work) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (running == fill) {
                        work.run();
                    }
                });
            }
        };
    }

    /** Bricht die KI ab; was schon in den Feldern steht, bleibt. */
    void cancelFill() {
        BookFieldFill fill = running;
        running = null;
        if (fill != null) {
            fill.cancel();
            busy(false);
            show("Abgebrochen; was schon in den Feldern steht, bleibt.");
        }
    }

    private void busy(boolean busy) {
        fill.setIcon(busy ? Glyph.CANCEL : Glyph.SPARKLE);
        fill.setToolTipText(busy ? CANCEL_TIP : FILL_TIP);
    }

    /** Der Statusbereich: Stand, nicht aufgelöste Abkürzungen und Fehler je Feld. */
    private void show(String text) {
        state = text == null ? "" : text;
        StringBuilder all = new StringBuilder(state);
        if (!unresolved.isEmpty()) {
            all.append(all.isEmpty() ? "" : "\n").append("Nicht im Glossar (bleiben unverändert): ")
                    .append(String.join(", ", unresolved));
        }
        problems.forEach((field, reason) -> all.append(all.isEmpty() ? "" : "\n").append(field.label())
                .append(": nicht gefüllt (").append(reason).append(")"));
        status.setText(all.toString());
        status.setForeground(problems.isEmpty() ? Comic.MUTED : Comic.RED);
        status.revalidate();
    }

    /** Ein mehrzeiliges Feld mit einem Knopf oben rechts im Feldrahmen (Feldknöpfe im Feld). */
    private static JComponent withButton(JComponent field, JComponent button) {
        javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane() {
            @Override
            public void doLayout() {
                field.setBounds(0, 0, getWidth(), getHeight());
                Dimension size = button.getPreferredSize();
                Insets frame = field.getInsets();
                button.setBounds(getWidth() - size.width - frame.right - BUTTON_GAP, frame.top + BUTTON_GAP,
                        size.width, size.height);
            }

            @Override
            public Dimension getPreferredSize() {
                return field.getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return field.getMinimumSize();
            }
        };
        layers.add(field, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(button, javax.swing.JLayeredPane.PALETTE_LAYER);
        return layers;
    }

    /** Abstand des Feldknopfs vom Feldrahmen. */
    private static final int BUTTON_GAP = 2;

    /**
     * Ist das Fenster für die aufgeklappten Felder zu niedrig, wird es so hoch, dass jedes Feld seine Zeilen zeigt
     * (höchstens der Bildschirm, {@link Dialogs#present}); kleiner wird es nie von selbst.
     */
    private static void grow(JComponent body) {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(body);
        if (window == null) {
            return;
        }
        int missing = body.getPreferredSize().height - body.getHeight();
        if (missing <= 0) {
            return;
        }
        java.awt.Rectangle screen = Dialogs.usableScreen(window);
        int height = window.getHeight() + missing;
        if (screen != null) {
            height = Math.min(height, screen.height);
        }
        window.setSize(window.getWidth(), height);
        if (screen != null && window.getY() + height > screen.y + screen.height) {
            window.setLocation(window.getX(), Math.max(screen.y, screen.y + screen.height - height));
        }
    }

    private static int add(JPanel panel, int row, JComponent component, double weight) {
        panel.add(component, constraints(row, weight));
        return row + 1;
    }

    private static GridBagConstraints constraints(int row, double weight) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.weighty = weight;
        c.fill = weight > 0 ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(weight > 0 ? 2 : 6, 0, 0, 0);
        return c;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(12.5f));
        label.setForeground(Comic.INK);
        return label;
    }

    /** Ein mehrzeiliges Feld mit Feldrahmen; es zeigt immer mindestens seine Zeilen und wächst mit dem Fenster. */
    private static JComponent field(JTextArea area) {
        javax.swing.JScrollPane scroll = Comic.scroll(area);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(Comic.fieldBorder());
        scroll.getViewport().setBackground(AppColors.of("app.BasketResearchForm.field"));
        Dimension rows = area.getPreferredScrollableViewportSize();
        Insets frame = scroll.getInsets();
        Dimension least = new Dimension(0, rows.height + frame.top + frame.bottom);
        scroll.setMinimumSize(least);
        scroll.setPreferredSize(new Dimension(rows.width, least.height));
        return scroll;
    }

    /** Der Weidezaun aus den Feldern; leer ohne Auftrag. */
    Optional<Fence> fence() {
        String text = mission.getText().strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Fence(text, edge.getText().strip(), lines(included), lines(excluded), "", ""));
    }

    private static List<String> lines(JTextArea area) {
        return Arrays.stream(area.getText().split("\\R")).map(String::strip).filter(line -> !line.isEmpty())
                .toList();
    }

    private static JTextArea area(int rows) {
        JTextArea area = new JTextArea(rows, COLUMNS);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Comic.style(area);
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return area;
    }
}
