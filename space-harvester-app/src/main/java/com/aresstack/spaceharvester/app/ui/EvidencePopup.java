package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.EscCircle;
import com.aresstack.spaceharvester.app.ui.controls.ReviewToggle;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.LockedPageException;
import com.aresstack.spaceharvester.application.standing.SentenceText;
import com.aresstack.spaceharvester.application.standing.StandingConflictException;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StatusQuo;
import com.aresstack.spaceharvester.application.standing.UnknownStandingTargetException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Overlay „Belege“ einer Verb-Kante der Fachsicht (Paket W1): Kopf „Antragsteller stellt Antrag“ mit Zahl, je Beleg der
 * Satz und darunter die Stufe des Belegs als stille Pille (Paket W2) und der Seitentitel als Link, der die Seite an der
 * Stelle öffnet und das Overlay schließt.
 * Keine Standardknöpfe; Schließen mit dem Kreis „Esc“, der Esc-Taste oder einem Klick daneben. Nur auf dem EDT.
 *
 * <p>Prüfen von Hand (ADR 0032, W3): Mit {@link #reviewing} stehen rechts in der Fußzeile jeder Zeile ✓ und ✕
 * ({@link ReviewToggle}), immer an derselben Stelle; der Seitentitel kürzt sich mit „…“. Ein Klick schaltet sofort um,
 * gespeichert wird abseits des EDT; ✓ und ✕ setzen den festen Wert ohne Revisionsprüfung, Zurücknehmen übergibt die
 * Revision der Entscheidung, die das Overlay gezeigt hat (beim Füllen abseits des EDT gelesen, nach eigenem Speichern
 * die zurückgegebene). Passt sie nicht mehr, gibt es keine Rückfrage: Die Zeile zeigt den aktuellen Wert, der eine
 * Statusbereich oben gelb „inzwischen geändert“. Lehnt der Core ab, springt der Knopf zurück, und der Statusbereich
 * nennt rot die Aussage. Ohne Recht (sync, gesperrte Seite) sind beide Knöpfe gesperrt sichtbar. Zeilen bleiben, wo
 * sie sind, solange das Overlay offen ist: Eine verworfene Zeile bleibt mit gedämpftem Satz stehen, die Pille zeigt
 * „verworfen“; nur ein neues {@link #fill} baut die Liste neu.
 */
public final class EvidencePopup extends JPopupMenu {

    private static final System.Logger LOG = System.getLogger(EvidencePopup.class.getName());

    /** Höchstens so breit. */
    static final int WIDTH = 460;
    /** Höchstens so hoch, die Liste scrollt darin. */
    static final int MAX_HEIGHT = 420;
    /** Satz, wenn der Lesetext nicht mehr zur Analyse passt und keine Vorstufe vorliegt. */
    static final String NO_SENTENCE = "Satz nicht mehr im Lesetext – die Seite hat sich seit der Analyse geändert.";
    /** Satz, wenn die Sätze nicht gelesen werden konnten. */
    static final String UNREADABLE = "Der Satz konnte nicht gelesen werden.";
    /** Satz, wenn der Aufrufer den Wortlaut der Seite nicht lesen darf. */
    static final String NO_ACCESS = "Kein Zugriff auf den Wortlaut dieser Seite.";
    /** Tooltip an ✓ und ✕ ohne Recht (UX W3). */
    static final String NO_RIGHT = "Kein Recht, Aussagen dieser Seite zu prüfen";
    /** Tooltip an ✓ und ✕ auf einer im Bestand gesperrten Seite (ADR 0014). */
    static final String LOCKED_PAGE = "Seite ist gesperrt; Aussagen lassen sich nicht prüfen";
    /** Tooltip an ✓ und ✕, solange der Prüfstand gelesen wird. */
    static final String READING = "Prüfstand wird gelesen …";
    /** Tooltip an ✓ und ✕, wenn der Prüfstand nicht gelesen werden konnte. */
    static final String REVIEWS_UNREADABLE = "Prüfstand nicht lesbar – Overlay schließen und erneut öffnen";
    /** Pille einer verworfenen Aussage (neutral dunkel). */
    static final String REJECTED_STAGE = "verworfen";
    /** Kern der gelben Meldung, wenn sich die Prüfung inzwischen geändert hat (AT W3). */
    static final String CHANGED = "inzwischen geändert";
    /** So viele Zeichen des Satzes nennt eine Meldung höchstens. */
    static final int BRIEF = 48;
    /** Link-Tooltip. */
    private static final String OPEN_TIP = "Seite an dieser Stelle öffnen";

    /** Innenbreite des Overlays: {@link #WIDTH} ohne Rahmen (2 + 12 je Seite). */
    private static final int INNER = WIDTH - 28;
    /** Kopf, Abstände und Rahmen über und unter der Liste. */
    private static final int CHROME = 64;

    /** Wohin ein Link springt: Seite und Stelle im Lesetext ({@code start} negativ: nur die Seite). */
    public record Target(String sourceId, String pageId, int start, int end) {
    }

    /** Was das Overlay zum Prüfen von Hand braucht (W3); in Tests ersetzt. Nie auf dem EDT gerufen. */
    public interface Reviewing {

        /**
         * Prüfstand (Recht und Entscheidungen samt Revision) je Seite ({@code StatusQuo#reviews}), in der Ablage des
         * Analysators, dessen Analyse die Belege zeigen.
         */
        Map<PageKey, StatusQuo.Reviews> reviews(AnalyzerChoice analyzer, Map<PageKey, List<String>> statements);

        /**
         * Setzt den festen Wert ohne Revisionsprüfung ({@code StatusQuo#review} mit {@code null}), in der Ablage genau
         * dieses Analysators, gleich welcher gerade eingestellt ist.
         */
        StandingDecision review(PageKey page, String statementId, AnalyzerChoice analyzer,
                                ParsedStatements.Review review);

        /** Nimmt die Prüfung zurück, nur wenn die Entscheidung noch {@code expectedRevision} hat. */
        boolean withdraw(PageKey page, String statementId, AnalyzerChoice analyzer, long expectedRevision);

        /** Über die laufende App; ohne Laufzeit scheitert jeder Aufruf. */
        static Reviewing of(Supplier<AppRuntime> runtime) {
            Objects.requireNonNull(runtime, "runtime");
            return new Reviewing() {
                private com.aresstack.spaceharvester.app.runtime.Workspace workspace() {
                    AppRuntime current = runtime.get();
                    if (current == null) {
                        throw new IllegalStateException(TermDirectory.NO_RUNTIME);
                    }
                    return current.workspace();
                }

                @Override
                public Map<PageKey, StatusQuo.Reviews> reviews(AnalyzerChoice analyzer,
                                                               Map<PageKey, List<String>> statements) {
                    return workspace().statementReviews(analyzer, statements);
                }

                @Override
                public StandingDecision review(PageKey page, String statementId, AnalyzerChoice analyzer,
                                               ParsedStatements.Review review) {
                    return workspace().reviewStatement(page, statementId, analyzer, review);
                }

                @Override
                public boolean withdraw(PageKey page, String statementId, AnalyzerChoice analyzer,
                                        long expectedRevision) {
                    return workspace().withdrawStatementReview(page, statementId, analyzer, expectedRevision);
                }
            };
        }
    }

    /**
     * Ein Schreiben scheiterte, nachdem das Overlay schon zu (oder neu gefüllt) war (AT W3: nichts scheitert still).
     * Die Statuszeile des Reiters zeigt {@link #note}: rot mit Kreispfeil, der dasselbe Schreiben wiederholt; bei einem
     * Konflikt beim Zurücknehmen gelb und ohne Wiederholen.
     *
     * @param brief    die Aussage kurz, etwa „„Der Plan beschreibt …““
     * @param conflict ob sich die Entscheidung inzwischen geändert hat (Zurücknehmen mit überholter Revision)
     * @param retry    wiederholt dasselbe Schreiben (derselbe Wert, beim Zurücknehmen dieselbe Revision); {@code null}
     *                 bei einem Konflikt
     */
    public record ClosedFailure(String brief, boolean conflict, Runnable retry) {

        public ClosedFailure {
            Objects.requireNonNull(brief, "brief");
        }

        /** Der Text der Statuszeile. */
        public String text() {
            return "Entscheidung zu " + brief + (conflict ? " " + CHANGED : " nicht gespeichert");
        }

        /**
         * Die Meldung für die Statuszeile: gelb bei Konflikt, sonst rot mit Kreispfeil; ein Klick darauf ruft erst
         * {@code beforeRetry} (etwa die Meldung entfernen), dann {@link #retry}.
         */
        public StatusNote note(Runnable beforeRetry) {
            Objects.requireNonNull(beforeRetry, "beforeRetry");
            String text = text();
            StatusNote note = new StatusNote(text, conflict ? StatusNote.Tone.WARNING : StatusNote.Tone.ERROR, null,
                    null);
            return retry == null ? note : note.withIcon(new StatusNote.IconLink(Glyph.REFRESH, text, RETRY_TIP, () -> {
                beforeRetry.run();
                retry.run();
            }));
        }
    }

    /** Tooltip des Kreispfeils hinter einer gescheiterten Entscheidung. */
    public static final String RETRY_TIP = "Entscheidung erneut speichern";

    /** Führt Arbeit abseits des EDT aus und meldet auf dem EDT zurück (Tests: sofort). */
    public interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final JLabel heading = new JLabel(" ");
    private final CountChip count = new CountChip("Belege", Comic.MUTED);
    private final com.aresstack.spaceharvester.app.ui.controls.ScrollColumn rows =
            new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(null);
    private final JScrollPane scroll = Comic.scroll(rows);
    /** Der eine Statusbereich des Overlays (UX W3): oben, mit ✕, unsichtbar ohne Meldung. */
    private final JPanel status = new JPanel(new BorderLayout(6, 0));
    private final WrappedText statusText = Comic.wrapped(" ", Comic.RED, 12.5f);
    private final IconButton statusClose = new IconButton(Glyph.CANCEL, "Meldung schließen").compact();
    private StatusNote.Tone statusTone = StatusNote.Tone.PLAIN;
    private final List<Row> shown = new ArrayList<>();
    private final List<WrappedText> texts = new ArrayList<>();
    private final List<String> otherTexts = new ArrayList<>();
    private Consumer<Target> onOpen = target -> { };
    /** Wohin ein Fehler nach dem Schließen geht (Statuszeile des Reiters); ohne Besitzer nur ins Log. */
    private Consumer<ClosedFailure> onClosedFailure = failure -> LOG.log(System.Logger.Level.WARNING,
            "Entscheidung nach dem Schließen nicht gespeichert, keine Statuszeile angemeldet");
    /** Ob das Overlay offen ist; Tests setzen es fest. */
    private java.util.function.BooleanSupplier open = this::isVisible;
    private Reviewing reviewing;
    private Runner runner;
    /** Zählt {@link #fill}: Antworten für eine frühere Füllung ändern nichts mehr. */
    private int generation;
    /**
     * Aussagen, für die gerade geschrieben wird, auch aus einer früheren Füllung (Overlay zu und gleich wieder auf):
     * Solange springt ein Klick auf sie zurück, damit sich zwei Schreibvorgänge nicht überholen.
     */
    private final java.util.Set<String> writing = new java.util.HashSet<>();
    /** Zähler beendeter Schreibvorgänge; je Aussage sein Stand beim letzten (seit der letzten Füllung). */
    private int writes;
    private final Map<String, Integer> lastWrite = new java.util.HashMap<>();

    public EvidencePopup() {
        setOpaque(true);
        setBackground(AppColors.of("comic.fieldBg"));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.INK, 2),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        setLayout(new BorderLayout(0, 8));
        heading.setFont(Comic.semiBold(14f));
        heading.setForeground(Comic.INK);
        count.setFocusable(false);
        count.setEnabled(true);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
        title.add(heading);
        title.add(Box.createHorizontalStrut(8));
        title.add(count);
        head.add(title, BorderLayout.CENTER);
        head.add(new EscCircle(() -> setVisible(false), "Schließen (Esc)"), BorderLayout.EAST);
        status.setOpaque(false);
        status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        statusText.setAlignmentX(LEFT_ALIGNMENT);
        status.add(statusText, BorderLayout.CENTER);
        JPanel closeBox = new JPanel(new BorderLayout());
        closeBox.setOpaque(false);
        closeBox.add(statusClose, BorderLayout.NORTH);
        status.add(closeBox, BorderLayout.EAST);
        status.setVisible(false);
        statusClose.addActionListener(e -> clearStatus());
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(status, BorderLayout.CENTER);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setVisible(false);
            }
        });
    }

    /** {@code listener} öffnet die Seite an der Stelle (im Reiter „Text“). */
    public EvidencePopup onOpen(Consumer<Target> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Prüfen von Hand (W3): ✓ und ✕ je Zeile, gelesen und gespeichert über {@code reviewing} mit {@code runner}. Ohne
     * diesen Aufruf zeigt das Overlay keine Knöpfe. Gilt ab der nächsten Füllung.
     */
    /**
     * {@code listener} zeigt ein Schreiben, das erst nach dem Schließen (oder neuen Füllen) scheiterte, in der
     * Statuszeile des Reiters ({@link ClosedFailure#note}). Auf dem EDT.
     */
    public EvidencePopup onClosedFailure(Consumer<ClosedFailure> listener) {
        onClosedFailure = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public EvidencePopup reviewing(Reviewing reviewing, Runner runner) {
        this.reviewing = Objects.requireNonNull(reviewing, "reviewing");
        this.runner = Objects.requireNonNull(runner, "runner");
        return this;
    }

    /**
     * Füllt das Overlay.
     *
     * @param title     Kopf, etwa „Antragsteller stellt Antrag“
     * @param total     Zahl der Belege der Kante
     * @param evidence  alle Belege; die Liste scrollt bei mehr als {@link #MAX_HEIGHT}
     * @param sentences Wortlaut je Aussage-ID ({@link SentenceText}); fehlt einer, darf der Aufrufer die Seite nicht
     *                  lesen
     * @param failed    ob die Sätze nicht gelesen werden konnten
     */
    public EvidencePopup fill(String title, int total, List<DomainTermGraph.Evidence> evidence,
                       Map<String, SentenceText> sentences, boolean failed) {
        int mine = ++generation;
        heading.setText(title);
        count.count(total);
        rows.removeAll();
        shown.clear();
        texts.clear();
        otherTexts.clear();
        sections.clear();
        hideStatus();
        lastWrite.clear();
        for (DomainTermGraph.Evidence item : evidence) {
            Row row = new Row(item, sentences.get(item.statement().id()), failed, mine);
            shown.add(row);
            rows.add(row.panel);
            rows.add(Box.createVerticalStrut(10));
        }
        relayout();
        if (reviewing != null && !shown.isEmpty()) {
            loadReviews(mine);
        }
        return this;
    }

    /**
     * Eine Aussage im Overlay (UX W9): Wortlaut wie an der Kante, etwa „nicht prüfen“, Zahl der Belege, Stufe der
     * Kante und ihre Belege.
     */
    public record Statement(String heading, int total, String stage, List<DomainTermGraph.Evidence> evidence) {
        public Statement {
            Objects.requireNonNull(heading, "heading");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Füllt das Overlay mit den Aussagen eines Knotenpaars (UX W9). Eine Aussage allein zeigt es wie {@link #fill};
     * mehrere stehen je unter eigener Überschrift mit Zahl und Stufe in der gegebenen Reihenfolge, nur die erste
     * aufgeklappt; ein Klick auf eine Überschrift (oder Enter/Leertaste) klappt sie auf oder zu.
     */
    public EvidencePopup fillStatements(String title, int total, List<Statement> statements,
                                        Map<String, SentenceText> sentences, boolean failed) {
        if (statements.size() == 1) {
            return fill(title, total, statements.getFirst().evidence(), sentences, failed);
        }
        int mine = ++generation;
        heading.setText(title);
        count.count(total);
        rows.removeAll();
        shown.clear();
        texts.clear();
        otherTexts.clear();
        sections.clear();
        hideStatus();
        lastWrite.clear();
        for (Statement statement : statements) {
            Section section = new Section(statement, sections.isEmpty());
            sections.add(section);
            rows.add(section.head);
            rows.add(Box.createVerticalStrut(6));
            for (DomainTermGraph.Evidence item : statement.evidence()) {
                Row row = new Row(item, sentences.get(item.statement().id()), failed, mine);
                shown.add(row);
                section.body.add(row.panel);
                section.body.add(Box.createVerticalStrut(10));
            }
            section.body.setVisible(section.expanded());
            rows.add(section.body);
            rows.add(Box.createVerticalStrut(8));
        }
        relayout();
        if (reviewing != null && !shown.isEmpty()) {
            loadReviews(mine);
        }
        return this;
    }

    /** Die Überschriften der Aussagen; leer, wenn das Overlay nur eine zeigt. */
    private final List<Section> sections = new ArrayList<>();

    /** Überschrift einer Aussage samt ihren Belegen (UX W9). */
    private final class Section {
        final Statement statement;
        final JPanel head = new JPanel();
        /** Aufklappbare Überschrift der Bibliothek: Fokusring, Maus, Leertaste/Enter, zugänglicher Name. */
        final com.aresstack.spaceharvester.app.ui.controls.SectionHeader header;
        final JPanel body = new JPanel();

        Section(Statement statement, boolean expanded) {
            this.statement = statement;
            header = new com.aresstack.spaceharvester.app.ui.controls.SectionHeader(statement.heading(), expanded,
                    this::toggled);
            head.setOpaque(false);
            head.setAlignmentX(LEFT_ALIGNMENT);
            head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
            header.setMaximumSize(header.getPreferredSize());
            head.add(header);
            head.add(Box.createHorizontalStrut(8));
            CountChip number = new CountChip("Belege", Comic.MUTED);
            number.setFocusable(false);
            number.count(statement.total());
            head.add(number);
            if (statement.stage() != null && !statement.stage().isBlank()) {
                head.add(Box.createHorizontalStrut(6));
                CountChip stage = CountChip.tag(statement.stage(), Comic.MUTED, "Stufe der Aussage");
                stage.setFocusable(false);
                head.add(stage);
            }
            head.add(Box.createHorizontalGlue());
            body.setOpaque(false);
            body.setAlignmentX(LEFT_ALIGNMENT);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        }

        boolean expanded() {
            return header.isOpen();
        }

        private void toggled() {
            body.setVisible(header.isOpen());
            relayout();
        }
    }

    /** Zeigt das Overlay an der Mausposition; der erste Link bekommt den Fokus. */
    public void showAt(Component owner, int x, int y) {
        if (!owner.isShowing()) {
            return;
        }
        show(owner, x + 8, y + 8);
        if (!shown.isEmpty()) {
            shown.getFirst().link.requestFocusInWindow();
        }
    }

    /**
     * Misst die Sätze an der echten Breite (sonst schätzen sie schmaler, und unter einem Satz bleibt eine Leerzeile
     * stehen; mit Scrollbalken wird die Spalte um dessen Breite schmaler) und setzt die Höhe samt Statusbereich. Ein
     * offenes Overlay passt seine Größe an.
     */
    private void relayout() {
        // pack() baut ein offenes Overlay neu auf; danach stünde der Fokus außerhalb. Wer ihn hatte (etwa ✓ nach Enter),
        // bekommt ihn zurück, mit gelbem Ring, wenn er über die Tastatur kam.
        Component owner = focus.owner();
        boolean inside = owner != null && SwingUtilities.isDescendingFrom(owner, this);
        boolean keyboard = inside && keyboardFocused(owner);
        int statusHeight = 0;
        if (status.isVisible()) {
            int room = INNER - statusClose.getPreferredSize().width - 6;
            statusText.setSize(room, statusText.heightFor(room));
            statusText.invalidate();
            statusHeight = status.getPreferredSize().height;
        }
        width(texts, INNER);
        if (rows.getPreferredSize().height + CHROME + statusHeight > MAX_HEIGHT) {
            width(texts, INNER - scroll.getVerticalScrollBar().getPreferredSize().width);
        }
        rows.revalidate();
        int height = Math.min(MAX_HEIGHT, rows.getPreferredSize().height + CHROME + statusHeight);
        setPreferredSize(new Dimension(WIDTH, Math.max(140, height)));
        if (isVisible()) {
            pack();
            revalidate();
            repaint();
        }
        if (inside) {
            Component target = visibleWithin(owner) ? owner : shown.isEmpty() ? null : shown.getFirst().link;
            if (target != null) {
                focus.request(target, keyboard ? FocusEvent.Cause.TRAVERSAL_FORWARD : FocusEvent.Cause.ACTIVATION);
            }
        }
    }

    /** Ob {@code component} im Overlay liegt und samt allen Eltern bis dorthin sichtbar ist. */
    private boolean visibleWithin(Component component) {
        for (Component at = component; at != null; at = at.getParent()) {
            if (!at.isVisible() && at != this) {
                return false;
            }
            if (at == this) {
                return true;
            }
        }
        return false;
    }

    /** Ob der Fokus von {@code owner} über die Tastatur kam (dann zeigt er den gelben Ring). */
    private static boolean keyboardFocused(Component owner) {
        if (owner instanceof TitleLink link) {
            return link.showsKeyboardFocus();
        }
        ReviewToggle toggle = (ReviewToggle) SwingUtilities.getAncestorOfClass(ReviewToggle.class, owner);
        return toggle != null && toggle.keyboardFocused();
    }

    /** Wer den Fokus hat und wie er zurückgegeben wird; Tests ohne Bildschirm ersetzen es. */
    interface Focus {

        Focus SYSTEM = new Focus() {
            @Override
            public Component owner() {
                return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            }

            @Override
            public void request(Component target, FocusEvent.Cause cause) {
                target.requestFocusInWindow(cause);
            }
        };

        Component owner();

        void request(Component target, FocusEvent.Cause cause);
    }

    private Focus focus = Focus.SYSTEM;

    /** Ersetzt die Fokusverwaltung (Tests ohne Bildschirm). */
    void focusForTests(Focus value) {
        focus = Objects.requireNonNull(value, "value");
    }

    private static void width(List<WrappedText> texts, int width) {
        for (WrappedText text : texts) {
            text.setSize(width, text.heightFor(width));
            text.invalidate();
        }
    }

    // ---- Statusbereich

    /** Eine Meldung im Statusbereich oben; ersetzt die vorige (ein Statusbereich je Overlay). */
    private void message(String text, StatusNote.Tone tone) {
        statusTone = tone;
        statusText.setText(text);
        statusText.setForeground(tone == StatusNote.Tone.ERROR ? Comic.RED
                : tone == StatusNote.Tone.WARNING ? StatusLabel.WARNING_COLOR : Comic.MUTED);
        statusText.getAccessibleContext().setAccessibleName(text);
        status.setVisible(true);
        relayout();
    }

    /** Das ✕ des Statusbereichs. */
    private void clearStatus() {
        hideStatus();
        relayout();
    }

    private void hideStatus() {
        status.setVisible(false);
        statusText.setText(" ");
        statusTone = StatusNote.Tone.PLAIN;
    }

    // ---- Prüfen von Hand (W3)

    /** Liest Recht und Entscheidungen der gezeigten Aussagen abseits des EDT; bis dahin sind die Knöpfe gesperrt. */
    private void loadReviews(int mine) {
        // Je Analysator der Belege ein Lesen in dessen Ablage, gleich welcher gerade eingestellt ist.
        Map<AnalyzerChoice, Map<PageKey, List<String>>> wanted = new java.util.EnumMap<>(AnalyzerChoice.class);
        for (Row row : shown) {
            row.toggle.locked(READING);
            wanted.computeIfAbsent(row.analyzer, unused -> new LinkedHashMap<>())
                    .computeIfAbsent(row.page(), unused -> new ArrayList<>()).add(row.statementId());
        }
        Reviewing current = reviewing;
        int since = writes;
        runner.run(() -> {
            Map<AnalyzerChoice, Map<PageKey, StatusQuo.Reviews>> read = new java.util.EnumMap<>(AnalyzerChoice.class);
            wanted.forEach((analyzer, statements) -> read.put(analyzer, current.reviews(analyzer, statements)));
            return read;
        }, found -> {
            if (mine != generation) {
                return;
            }
            for (Row row : shown) {
                // Hat ein Schreiben aus der vorigen Füllung erst nach dem Start dieses Lesens geendet, gilt sein Wert.
                row.loaded(found.getOrDefault(row.analyzer, Map.of()).getOrDefault(row.page(),
                        StatusQuo.Reviews.NONE), lastWrite.getOrDefault(row.key(), Integer.MIN_VALUE) > since);
            }
        }, failure -> {
            if (mine != generation) {
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "Prüfstand der Belege nicht lesbar: {0}",
                    failure.getClass().getName());
            for (Row row : shown) {
                row.toggle.locked(REVIEWS_UNREADABLE);
            }
        });
    }

    /** Der Zustand des Knopfs zu einer gespeicherten Entscheidung: nur eine Prüfung von Hand zählt. */
    static ReviewToggle.State stateOf(StandingDecision decision) {
        if (decision == null || decision.review() == null) {
            return ReviewToggle.State.OPEN;
        }
        return switch (decision.review()) {
            case CONFIRMED -> ReviewToggle.State.CONFIRMED;
            case REJECTED -> ReviewToggle.State.REJECTED;
            case UNREVIEWED -> ReviewToggle.State.OPEN;
        };
    }

    private static ReviewToggle.State stateOf(ParsedStatements.Review review) {
        return review == ParsedStatements.Review.CONFIRMED ? ReviewToggle.State.CONFIRMED
                : review == ParsedStatements.Review.REJECTED ? ReviewToggle.State.REJECTED : ReviewToggle.State.OPEN;
    }

    /** Die Stufe ohne Prüfung von Hand: nach dem Zurücknehmen die vorherige Stufe. */
    static String openStage(DomainTermGraph.Evidence item) {
        if (item.statement().review() != ParsedStatements.Review.CONFIRMED) {
            return stageWord(item);
        }
        // Von Hand bestätigt: Verglichen wurde dann nicht; es bleibt der Prüfschritt oder nur der Analysator.
        if (item.statement().check() != null
                && item.statement().check().verdict() == ParsedStatements.Verdict.STIMMT) {
            return GraphSource.STAGE_BY_CHECK;
        }
        String name = item.analyzerId() == null ? null
                : com.aresstack.spaceharvester.app.ui.content.StatementProvenance.analyzer(item.analyzerId());
        return "nur " + (name == null ? "Analysator" : name);
    }

    /** Ob die Meldung zu einer Zeile noch im Overlay erscheinen kann: offen und mit dieser Füllung. */
    private boolean showing(int mine) {
        return mine == generation && open.getAsBoolean();
    }

    /**
     * Meldet ein gescheitertes Schreiben an den Reiter; der Kreispfeil wiederholt es mit demselben Wert ({@code review})
     * bzw. beim Zurücknehmen ({@code review == null}) mit derselben Revision.
     */
    private void closedFailure(PageKey page, String id, AnalyzerChoice analyzer, String brief,
                               ParsedStatements.Review review, long expected,
                               Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, "Entscheidung nach dem Schließen nicht gespeichert: {0}",
                failure.getClass().getName());
        boolean conflict = review == null && failure instanceof StandingConflictException;
        onClosedFailure.accept(new ClosedFailure(brief, conflict, conflict ? null
                : () -> retry(page, id, analyzer, brief, review, expected)));
    }

    /** Wiederholt ein Schreiben aus der Statuszeile; zeigt das Overlay die Aussage gerade, zieht die Zeile mit. */
    private void retry(PageKey page, String id, AnalyzerChoice analyzer, String brief, ParsedStatements.Review review,
                       long expected) {
        Reviewing current = reviewing;
        String key = key(page, id, analyzer);
        if (current == null || writing.contains(key)) {
            return;
        }
        writing.add(key);
        runner.run(() -> review == null ? current.withdraw(page, id, analyzer, expected)
                        : current.review(page, id, analyzer, review),
                result -> {
                    writing.remove(key);
                    Long kept = result instanceof StandingDecision decision ? decision.revision() : null;
                    lastWrite.put(key, ++writes);
                    for (Row row : shown) {
                        if (row.key().equals(key) && !row.pending) {
                            row.revision = kept;
                            row.show(review == null ? ReviewToggle.State.OPEN : stateOf(review));
                        }
                    }
                }, failure -> {
                    writing.remove(key);
                    closedFailure(page, id, analyzer, brief, review, expected, failure);
                });
    }

    private static String key(PageKey page, String statementId, AnalyzerChoice analyzer) {
        return page.sourceId() + '\u0000' + page.pageId() + '\u0000' + statementId + '\u0000' + analyzer;
    }

    /** Der Satz kurz für eine Meldung, sonst die Seite. */
    static String brief(String sentence, String pageTitle) {
        if (sentence == null || sentence.isBlank()) {
            return "Aussage auf „" + pageTitle + "“";
        }
        String text = sentence.strip().replaceAll("\\s+", " ");
        if (text.length() > BRIEF) {
            // An einer Wortgrenze kürzen; ein einziges langes Wort hart.
            String cut = text.substring(0, BRIEF + 1);
            int space = cut.lastIndexOf(' ');
            text = (space > BRIEF / 2 ? cut.substring(0, space) : text.substring(0, BRIEF)).stripTrailing() + " …";
        }
        return "„" + text + "“";
    }

    /** Eine Zeile: Satz, Fußzeile mit Pille, Link und ✓/✕; hält, was die Zeile vom Prüfstand weiß. */
    private final class Row {

        final DomainTermGraph.Evidence item;
        /** Der Analysator, in dessen Analyse der Beleg steht: dessen Ablage wird gelesen und geschrieben. */
        final AnalyzerChoice analyzer;
        final int mine;
        final JPanel panel = new JPanel();
        final WrappedText text;
        final Color textColor;
        final JPanel stageBox = new JPanel(new GridBagLayout());
        CountChip stage;
        final TitleLink link;
        final ReviewToggle toggle = new ReviewToggle();
        final String brief;
        /** Die Revision der gezeigten Entscheidung (0 ohne); {@code null}, solange unbekannt. */
        Long revision;
        boolean loaded;
        /** Ein Speichern oder neues Lesen läuft; weitere Klicks springen zurück. */
        boolean pending;

        Row(DomainTermGraph.Evidence item, SentenceText sentence, boolean failed, int mine) {
            this.item = item;
            this.analyzer = AnalyzerChoice.of(item.analyzerId());
            this.mine = mine;
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setAlignmentX(LEFT_ALIGNMENT);
            boolean program = com.aresstack.spaceharvester.app.ui.content.StatementProvenance.program(item.statement());
            String said = program ? hypothesisText(sentence, failed) : sentenceText(sentence, failed);
            String found = program ? said : sentenceOf(sentence);
            textColor = said.equals(found) ? Comic.INK : Comic.MUTED;
            text = Comic.wrapped(said, textColor, 13f);
            text.setAlignmentX(LEFT_ALIGNMENT);
            texts.add(text);
            panel.add(text);
            String other = otherText(item);
            if (other != null) {
                // Bei abweichenden Belegen: wie die andere Ablage den Satz liest, nur was abweicht (K2).
                WrappedText otherLine = Comic.wrapped(other, Comic.MUTED, 12f);
                otherLine.setAlignmentX(LEFT_ALIGNMENT);
                texts.add(otherLine);
                otherTexts.add(other);
                panel.add(Box.createVerticalStrut(2));
                panel.add(otherLine);
            }
            String title = pageTitle(item);
            brief = brief(found, item.page().title() == null || item.page().title().isBlank()
                    ? item.page().key().pageId() : item.page().title());
            link = new TitleLink(title);
            JPanel foot = new JPanel(new BorderLayout(8, 0));
            foot.setOpaque(false);
            foot.setAlignmentX(LEFT_ALIGNMENT);
            stageBox.setOpaque(false);
            foot.add(stageBox, BorderLayout.WEST);
            // Klickfläche nur so breit wie der Titel: Daneben (bis zu ✓/✕) öffnet ein Klick nichts. Reicht der Platz
            // nicht, kürzt sich der Titel mit „…“, ✓/✕ bleiben rechts an ihrer Stelle.
            JPanel linkBox = new JPanel();
            linkBox.setLayout(new BoxLayout(linkBox, BoxLayout.X_AXIS));
            linkBox.setOpaque(false);
            linkBox.add(link);
            linkBox.add(Box.createHorizontalGlue());
            foot.add(linkBox, BorderLayout.CENTER);
            if (reviewing != null) {
                // Rechtsbündig, in jeder Zeile an derselben Stelle; der Titel davor kürzt sich.
                JPanel toggleBox = new JPanel(new GridBagLayout());
                toggleBox.setOpaque(false);
                toggleBox.add(toggle);
                foot.add(toggleBox, BorderLayout.EAST);
                toggle.onChange(this::changed);
            }
            ReviewToggle.State initial = stateOf(item.statement().review());
            toggle.state(initial);
            if (initial == ReviewToggle.State.OPEN) {
                stage(stageWord(item), false, stageTip(item));
            } else {
                show(initial);
            }
            Target target = new Target(item.page().key().sourceId(), item.page().key().pageId(),
                    sentence == null || sentence.stale() ? -1 : sentence.sentenceStart(),
                    sentence == null || sentence.stale() ? -1 : sentence.sentenceEnd());
            Runnable open = () -> {
                setVisible(false);
                onOpen.accept(target);
            };
            link.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    open.run();
                }
            });
            link.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                        open.run();
                    }
                }
            });
            panel.add(Box.createVerticalStrut(4));
            panel.add(foot);
        }

        PageKey page() {
            return item.page().key();
        }

        String statementId() {
            return item.statement().id();
        }

        String key() {
            return EvidencePopup.key(page(), statementId(), analyzer);
        }

        /**
         * Der gelesene Prüfstand: Revision, Wert der Entscheidung und Recht. {@code newer}: Ein Schreiben hat danach
         * geendet und Wert und Revision schon gesetzt ({@link #finished}); dann gilt nur das Recht.
         */
        void loaded(StatusQuo.Reviews reviews, boolean newer) {
            loaded = true;
            if (!newer) {
                StandingDecision decision = reviews.decisions().get(statementId());
                revision = decision == null ? 0L : decision.revision();
                ReviewToggle.State state = stateOf(decision);
                if (state != toggle.state()) {
                    show(state);
                }
            }
            toggle.locked(reviews.writable() ? null : reviews.locked() ? LOCKED_PAGE : NO_RIGHT);
        }

        /**
         * Ein Schreiben ist gespeichert. Ist das Overlay inzwischen neu gefüllt und zeigt dieselbe Aussage, übernimmt
         * deren Zeile Wert und Revision, denn ihr Lesen kann älter sein.
         */
        private void finished(ReviewToggle.State state, Long kept) {
            writing.remove(key());
            pending = false;
            revision = kept;
            lastWrite.put(key(), ++writes);
            if (mine != generation) {
                for (Row next : shown) {
                    if (next.key().equals(key())) {
                        next.revision = kept;
                        next.show(state);
                    }
                }
            }
        }

        /** Pille und Satz zum Zustand; der Knopf zeigt ihn mit. Die Zeile bleibt an ihrem Platz. */
        void show(ReviewToggle.State state) {
            toggle.state(state);
            switch (state) {
                case CONFIRMED -> stage(GraphSource.STAGE_BY_HAND, false, "Ein Mensch hat diese Aussage bestätigt");
                case REJECTED -> stage(REJECTED_STAGE, true,
                        "Ein Mensch hat diese Aussage verworfen; sie zählt nicht mehr");
                case OPEN -> stage(openStage(item), false, item.statement().review()
                        == ParsedStatements.Review.CONFIRMED ? "Nicht von Hand geprüft" : stageTip(item));
            }
            // Verworfen: gedämpft, nicht durchgestrichen (UX W3).
            text.setForeground(state == ReviewToggle.State.REJECTED ? Comic.MUTED : textColor);
        }

        private void stage(String word, boolean dark, String tip) {
            CountChip chip = CountChip.tag(word, dark ? Comic.INK : Comic.MUTED, tip);
            chip.setSelected(dark);
            stage = chip;
            stageBox.removeAll();
            stageBox.add(chip);
            stageBox.revalidate();
            stageBox.repaint();
        }

        /** Ein Klick auf ✓ oder ✕: sofort zeigen, abseits des EDT speichern. */
        private void changed(ReviewToggle.State from, ReviewToggle.State to) {
            if (mine != generation || !loaded || pending || reviewing == null || writing.contains(key())) {
                toggle.state(from);
                return;
            }
            pending = true;
            writing.add(key());
            show(to);
            Reviewing current = reviewing;
            PageKey page = page();
            String id = statementId();
            if (to == ReviewToggle.State.OPEN) {
                long expected = revision == null ? 0L : revision;
                // Danach steht der Eintrag mit neuer Revision (Stand von Hand) oder fehlt; ✓/✕ prüfen sie nicht.
                runner.run(() -> current.withdraw(page, id, analyzer, expected),
                        withdrawn -> finished(ReviewToggle.State.OPEN, null), failure -> {
                    if (!showing(mine)) {
                        // Das Overlay ist zu: nicht still scheitern, sondern in der Statuszeile des Reiters.
                        writing.remove(key());
                        pending = false;
                        show(from);
                        closedFailure(page, id, analyzer, brief, null, expected, failure);
                    } else if (failure instanceof StandingConflictException) {
                        changedMeanwhile();
                    } else {
                        refused(from, to, failure);
                    }
                });
            } else {
                ParsedStatements.Review review = to == ReviewToggle.State.CONFIRMED ? ParsedStatements.Review.CONFIRMED
                        : ParsedStatements.Review.REJECTED;
                runner.run(() -> current.review(page, id, analyzer, review),
                        decision -> finished(to, decision == null ? null : decision.revision()), failure -> {
                    if (!showing(mine)) {
                        writing.remove(key());
                        pending = false;
                        show(from);
                        closedFailure(page, id, analyzer, brief, review, 0L, failure);
                    } else {
                        refused(from, to, failure);
                    }
                });
            }
        }

        /** Zurücknehmen mit überholter Revision: keine Rückfrage, die Zeile zeigt den aktuellen Wert. */
        private void changedMeanwhile() {
            Reviewing current = reviewing;
            PageKey page = page();
            String id = statementId();
            runner.run(() -> current.reviews(analyzer, Map.of(page, List.of(id))), found -> {
                writing.remove(key());
                pending = false;
                if (mine != generation) {
                    return;
                }
                loaded(found.getOrDefault(page, StatusQuo.Reviews.NONE), false);
                message(brief + " wurde " + CHANGED + "; die Zeile zeigt den aktuellen Stand.",
                        StatusNote.Tone.WARNING);
            }, failure -> {
                writing.remove(key());
                pending = false;
                if (mine != generation) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "Prüfstand eines Belegs nicht lesbar: {0}",
                        failure.getClass().getName());
                revision = null;
                loaded = false;
                toggle.locked(REVIEWS_UNREADABLE);
                message(brief + " wurde " + CHANGED + "; der aktuelle Stand ist nicht lesbar.",
                        StatusNote.Tone.WARNING);
            });
        }

        /** Der Core lehnt ab: der Knopf springt zurück, eine rote Meldung nennt die Aussage. */
        private void refused(ReviewToggle.State from, ReviewToggle.State to, Throwable failure) {
            writing.remove(key());
            pending = false;
            if (mine != generation) {
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "Prüfung eines Belegs nicht gespeichert: {0}",
                    failure.getClass().getName());
            show(from);
            String what = switch (to) {
                case CONFIRMED -> brief + " nicht bestätigt";
                case REJECTED -> brief + " nicht verworfen";
                case OPEN -> "Prüfung von " + brief + " nicht zurückgenommen";
            };
            if (failure instanceof LockedPageException) {
                toggle.locked(LOCKED_PAGE);
            }
            String why = failure instanceof AccessDeniedException ? ": " + NO_RIGHT + "."
                    : failure instanceof LockedPageException ? ": Die Seite ist gesperrt."
                    : failure instanceof UnknownStandingTargetException
                    ? ": Die Aussage gehört nicht mehr zur aktuellen Analyse der Seite."
                    : ". Bitte erneut versuchen.";
            message(what + why, StatusNote.Tone.ERROR);
        }
    }

    /** Der Seitentitel als Link; zu lang kürzt er sich mit „…“ (der ganze Titel im Tooltip). */
    static final class TitleLink extends JLabel {

        private final String full;

        TitleLink(String full) {
            super(full);
            this.full = full;
            putClientProperty("html.disable", Boolean.TRUE);
            Font base = Comic.regular(12f);
            setFont(base.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)));
            setForeground(Comic.BLUE);
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            setToolTipText(OPEN_TIP);
            getAccessibleContext().setAccessibleName(full);
            // Platz für den Fokusring; der Link bleibt immer blau (UX W3).
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    // Wie KeyboardFocusRing: nur über die Tastatur (Tab), nicht beim ersten Fokus des Overlays.
                    keyboard = switch (e.getCause()) {
                        case TRAVERSAL, TRAVERSAL_FORWARD, TRAVERSAL_BACKWARD, TRAVERSAL_UP, TRAVERSAL_DOWN -> true;
                        default -> false;
                    };
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    keyboard = false;
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    keyboard = false;
                    repaint();
                }
            });
        }

        /** Ob der Fokus zuletzt über die Tastatur kam; dann steht der gelbe Ring um den Link (für Tests). */
        private boolean keyboard;

        boolean showsKeyboardFocus() {
            return keyboard;
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            if (keyboard && hasFocus()) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Comic.YELLOW);
                    g.setStroke(new java.awt.BasicStroke(2f));
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
                } finally {
                    g.dispose();
                }
            }
        }

        /** Der ganze Titel, auch wenn er gekürzt gezeigt wird. */
        String full() {
            return full;
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }

        /** So breit wie der ganze Titel, auch wenn er gerade gekürzt steht; nie breiter (Klickfläche). */
        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            return new Dimension(getFontMetrics(getFont()).stringWidth(full) + insets.left + insets.right,
                    super.getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            fit(width);
        }

        private void fit(int width) {
            FontMetrics metrics = getFontMetrics(getFont());
            Insets insets = getInsets();
            int room = width - insets.left - insets.right;
            String text = full;
            if (room > 0 && metrics.stringWidth(full) > room) {
                int end = full.length();
                while (end > 0 && metrics.stringWidth(full.substring(0, end).stripTrailing() + "…") > room) {
                    end--;
                }
                text = full.substring(0, end).stripTrailing() + "…";
            }
            if (!text.equals(getText())) {
                setText(text);
            }
            setToolTipText(text.equals(full) ? OPEN_TIP : full + " – " + OPEN_TIP);
        }
    }

    /** Genaue Stufe eines Belegs für die Pille, im Wortlaut des Kanten-Tooltips (Paket W2). */
    static String stageWord(DomainTermGraph.Evidence item) {
        return GraphSource.stage(item, com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer);
    }

    private static String stageTip(DomainTermGraph.Evidence item) {
        return switch (item.trust()) {
            case GEPRUEFT -> item.statement().review()
                    == com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review.CONFIRMED
                    ? "Ein Mensch hat diese Aussage bestätigt" : "Der Prüfschritt hat diese Aussage als stimmig bewertet";
            case EINIG -> "Parser und KI lesen diesen Satz gleich, nicht geprüft";
            case UNGEPRUEFT -> "Nicht mit der anderen Lesart verglichen, nicht geprüft";
            case ABWEICHEND -> "Parser und KI lesen diesen Satz verschieden, nicht geprüft";
        };
    }

    /**
     * Die Lesart der anderen Ablage bei einem abweichenden Beleg, nur in dem, was abweicht: „Sprachmodell (KI) liest
     * anders: Verb „senden“ · Handelnde „kunde““; sonst {@code null}. Die Wortlaute kommen normalisiert aus dem Abgleich;
     * gleiche Wörter an anderer Stelle heißen so.
     */
    static String otherText(DomainTermGraph.Evidence item) {
        com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Reading other = item.otherReading();
        if (other == null) {
            return null;
        }
        java.util.Set<com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference> differences =
                item.comparison().differences();
        List<String> parts = new ArrayList<>();
        com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Reading own = item.comparison().own();
        if (differences.contains(com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.VERB)) {
            parts.add(part("Verb", "kein Verb", other.verb() == null ? java.util.Set.of() : java.util.Set.of(other.verb()),
                    own == null || own.verb() == null ? java.util.Set.of() : java.util.Set.of(own.verb())));
        }
        if (differences.contains(com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.ACTOR)) {
            parts.add(part("Handelnde", "keine Handelnden", other.actors(), own == null ? java.util.Set.of()
                    : own.actors()));
        }
        if (differences.contains(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.AFFECTED_OBJECT)) {
            parts.add(part("Betroffene", "keine Betroffenen", other.affected(), own == null ? java.util.Set.of()
                    : own.affected()));
        }
        String name = other.analyzer() == com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL
                ? com.aresstack.spaceharvester.app.ui.content.StatementProvenance.LANGUAGE_MODEL
                : com.aresstack.spaceharvester.app.ui.content.StatementProvenance.MALTPARSER;
        return name + " liest anders" + (parts.isEmpty() ? "" : ": " + String.join(" · ", parts));
    }

    private static String part(String label, String none, java.util.Set<String> theirs, java.util.Set<String> mine) {
        java.util.List<String> words = theirs.stream().filter(word -> word != null && !word.isBlank()).toList();
        if (words.isEmpty()) {
            return none;
        }
        String quoted = label + " " + words.stream().map(word -> "„" + word + "“")
                .collect(java.util.stream.Collectors.joining(", "));
        // Abgeglichen wird über Zeichenspannen: gleiche Wörter können an anderer Stelle stehen.
        return new java.util.HashSet<>(words).equals(mine) ? quoted + " an anderer Stelle" : quoted;
    }

    /**
     * Der Originalsatz, sonst der vereinfachte Satz der Vorstufe; fehlt beides, warum: nicht lesbar, kein Zugriff oder
     * die Seite hat sich geändert. Nie ein Ersatz aus Erwähnungen und Prädikat.
     */
    static String sentenceText(SentenceText sentence, boolean failed) {
        if (failed) {
            return UNREADABLE;
        }
        if (sentence == null) {
            return NO_ACCESS;
        }
        String found = sentenceOf(sentence);
        return found == null ? NO_SENTENCE : found;
    }

    /**
     * Der Wortlaut einer Programmaussage: die Hypothese der Programmanalyse, als solche gekennzeichnet (ADR 0032,
     * Fortschreibung 26.11.2026); sie steht nicht im Lesetext, ihre Stelle ist die Codestelle.
     */
    static String hypothesisText(SentenceText sentence, boolean failed) {
        if (failed) {
            return UNREADABLE;
        }
        if (sentence == null) {
            return NO_ACCESS;
        }
        return sentence.simplified() == null || sentence.simplified().isBlank() ? NO_SENTENCE
                : sentence.simplified().strip() + " (" + PROGRAM_HYPOTHESIS + ")";
    }

    /** Kennzeichen hinter dem Wortlaut einer Programmaussage. */
    static final String PROGRAM_HYPOTHESIS = "Hypothese der Programmanalyse, Ist-Zustand";

    private static String sentenceOf(SentenceText sentence) {
        if (sentence != null && sentence.original() != null && !sentence.original().isBlank()) {
            return sentence.original();
        }
        if (sentence != null && sentence.simplified() != null && !sentence.simplified().isBlank()) {
            return sentence.simplified() + " (vereinfacht)";
        }
        return null;
    }

    /** Seitentitel mit Quelle und Space; veraltete Analysen gekennzeichnet. */
    static String pageTitle(DomainTermGraph.Evidence item) {
        String title = item.page().title() == null || item.page().title().isBlank() ? item.page().key().pageId()
                : item.page().title();
        String where = item.page().key().sourceId() + (item.page().spaceKey() == null ? "" : " / "
                + item.page().spaceKey());
        return title + " (" + where + ")" + (item.page().state()
                == com.aresstack.spaceharvester.application.knowledge.PageStatements.State.STALE ? " · veraltet" : "");
    }

    // ---- für Tests

    /** Die Überschriften der Aussagen, davor „auf: “ (aufgeklappt) oder „zu: “; leer bei nur einer Aussage. */
    public List<String> statementHeadings() {
        return sections.stream().map(section -> (section.expanded() ? "auf: " : "zu: ") + section.header.title())
                .toList();
    }

    /** Ein Klick auf die Überschrift der Aussage {@code index}. */
    void clickStatement(int index) {
        sections.get(index).header.toggle();
    }

    /** Die Überschrift (auch für Tests der Reiter in anderen Paketen). */
    public String headingText() {
        return heading.getText();
    }

    public int countValue() {
        return count.count();
    }

    List<String> otherTexts() {
        return List.copyOf(otherTexts);
    }

    List<String> stageTexts() {
        return shown.stream().map(row -> row.stage.word()).toList();
    }

    List<String> linkTexts() {
        return shown.stream().map(row -> row.link.full()).toList();
    }

    void clickLink(int index) {
        JLabel link = shown.get(index).link;
        MouseEvent click = new MouseEvent(link, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                1, 1, 1, false);
        for (java.awt.event.MouseListener listener : link.getMouseListeners()) {
            listener.mouseClicked(click);
        }
    }

    /** ✓/✕ der Zeile {@code index} (auch für Tests der Reiter in anderen Paketen). */
    public ReviewToggle toggle(int index) {
        return shown.get(index).toggle;
    }

    /** Ob die Pille der Zeile dunkel gefüllt ist (verworfen). */
    boolean stageDark(int index) {
        return shown.get(index).stage.isSelected();
    }

    /** Farbe des Satzes der Zeile. */
    Color sentenceColor(int index) {
        return shown.get(index).text.getForeground();
    }

    /** Der gezeigte (womöglich gekürzte) Seitentitel der Zeile. */
    String shownLinkText(int index) {
        return shown.get(index).link.getText();
    }

    /** Der Link der Zeile (Lage, Breite). */
    JLabel link(int index) {
        return shown.get(index).link;
    }

    /** Der Text im Statusbereich, leer ohne Meldung. */
    String statusText() {
        return status.isVisible() ? statusText.getText() : "";
    }

    /** Ton der Meldung im Statusbereich. */
    StatusNote.Tone statusTone() {
        return status.isVisible() ? statusTone : StatusNote.Tone.PLAIN;
    }

    /** Ob das Overlay als offen gilt (Tests ohne Bildschirm). */
    void openForTests(boolean value) {
        open = () -> value;
    }

    /** Der Link der Zeile als {@link TitleLink}. */
    TitleLink titleLink(int index) {
        return shown.get(index).link;
    }

    /** Das ✕ des Statusbereichs. */
    java.awt.Component statusCloseForTests() {
        return statusClose;
    }

    /** Ein Klick auf das ✕ des Statusbereichs. */
    void closeStatus() {
        statusClose.doClick(0);
    }
}
