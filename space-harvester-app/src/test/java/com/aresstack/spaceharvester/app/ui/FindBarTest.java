package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.Highlighter;
import org.junit.jupiter.api.Test;

/** Die Suche über der Statuszeile: markiert wie im Browser, zählt, schaltet durch und folgt dem Bereich. */
class FindBarTest {

    /**
     * Angelo 22.11.2026: Das Kopieren-Icon saß höher als die Lupe. Ein Werkzeug neben der Lupe steht immer auf ihrer
     * Höhe, gleich wie seine Zeile gebaut ist (FlowLayout, BorderLayout, verschachtelt), auch mit Linie darüber.
     */
    @Test
    void everyToolNextToTheMagnifierSitsAtItsHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean line : new boolean[] {true, false}) {
                for (int kind = 0; kind < 3; kind++) {
                    FindBar bar = new FindBar(JPanel::new);
                    bar.line(line);
                    IconButton tool = new IconButton(Glyph.TRASH,
                            "Kopieren");
                    JPanel row = kind == 1 ? new JPanel(new java.awt.BorderLayout())
                            : new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
                    row.add(tool, kind == 1 ? java.awt.BorderLayout.WEST : null);
                    JComponent shown = row;
                    if (kind == 2) {
                        // wie die Seitenansicht: ein Halter, der seine Zeile über die ganze Leiste zieht
                        JPanel holder = new JPanel(new java.awt.BorderLayout());
                        holder.putClientProperty(View.TOOLS_FILL, Boolean.TRUE);
                        holder.add(row, java.awt.BorderLayout.CENTER);
                        shown = holder;
                    }
                    bar.tools(shown);
                    bar.setSize(600, bar.getPreferredSize().height);
                    layoutAll(bar);
                    int magnifier = centerY(bar.findButton(), bar);
                    assertThat(centerY(tool, bar)).as("Linie " + line + ", Zeile " + kind)
                            .isBetween(magnifier - 1, magnifier + 1);
                }
            }
        });
    }

    /**
     * Angelo 28.11.2026: Alle Fußleisten leiten von derselben Klasse ab und sind dadurch gleich hoch. Die Höhe steht
     * nur in {@link com.aresstack.spaceharvester.app.ui.controls.FootBar}; kein Layout und keine Leiste setzt sie
     * selbst, und nichts auf der Leiste macht sie höher.
     */
    @Test
    void everyFootBarDerivesFromTheSameClassAndOnlyThatClassSetsTheHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel tall = new JPanel();
            tall.setPreferredSize(new java.awt.Dimension(40, 200));
            FindBar find = new FindBar(JPanel::new);
            find.tools(tall);
            com.aresstack.spaceharvester.app.ui.controls.SideFoot side =
                    new com.aresstack.spaceharvester.app.ui.controls.SideFoot(null, null, null);
            java.util.List<JComponent> bars = java.util.List.of(find, side,
                    com.aresstack.spaceharvester.app.ui.deck.DeckBars.foot(null, tall));
            for (JComponent bar : bars) {
                assertThat(bar).isInstanceOf(com.aresstack.spaceharvester.app.ui.controls.FootBar.class);
                assertThat(bar.getPreferredSize().height).isEqualTo(Comic.FOOT_HEIGHT);
                assertThat(bar.getMinimumSize().height).isEqualTo(Comic.FOOT_HEIGHT);
                assertThat(bar.getMaximumSize().height).isEqualTo(Comic.FOOT_HEIGHT);
            }
        });
        // Kein zweiter Ort, der die Höhe einer Fußleiste festlegt.
        java.nio.file.Path sources = java.nio.file.Path.of("src/main/java");
        java.util.List<String> setters = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(sources)) {
            for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("FootBar.java")) {
                    continue;
                }
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    if (line.contains("FOOT_HEIGHT") && line.contains("Dimension(")) {
                        setters.add(file.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertThat(setters).as("Höhe einer Fußleiste außerhalb von FootBar gesetzt").isEmpty();
    }

    /** Angelo 28.11.2026: Das Fragezeichen hing oben in der Ecke. Die Hilfe steht auf der Höhe der Lupe. */
    @Test
    void theHelpSitsAtTheHeightOfTheMagnifier() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FindBar bar = new FindBar(JPanel::new);
            com.aresstack.spaceharvester.app.ui.controls.HelpButton first =
                    new com.aresstack.spaceharvester.app.ui.controls.HelpButton(22, "Hilfe", "Text");
            com.aresstack.spaceharvester.app.ui.controls.HelpButton second =
                    new com.aresstack.spaceharvester.app.ui.controls.HelpButton(22, "Hilfe", "Text");
            second.setVisible(false);
            bar.help(Comic.row(first, second));
            bar.setSize(320, bar.getPreferredSize().height);
            layoutAll(bar);
            int magnifier = centerY(bar.findButton(), bar);
            assertThat(centerY(first, bar)).isBetween(magnifier - 1, magnifier + 1);
            assertThat(SwingUtilities.convertPoint(first.getParent(), first.getLocation(), bar).x + first.getWidth())
                    .as("ganz rechts, in der Leiste").isLessThanOrEqualTo(bar.getWidth());
        });
    }

    /**
     * Angelo 28.11.2026: Kein Suchfeld oben in den Reitern. Hat die gezeigte Ansicht ein Filterfeld hinter der
     * Schleuse, zeigt das Feld der Fußleiste das Filtersymbol; damit wird abwechselnd gesucht oder gefiltert, nie
     * beides. Beim Filtern steht der Text im Filterfeld der Ansicht, Enter geht an sie weiter; zurück auf Suchen,
     * beim Schließen und beim Wechsel der Ansicht ist ihr Filter wieder leer.
     */
    @Test
    void theFieldEitherSearchesOrFiltersTheShownView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel area = new JPanel(new java.awt.CardLayout());
            JPanel list = new JPanel(new java.awt.BorderLayout());
            javax.swing.JTextField filter = new javax.swing.JTextField();
            java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
            filter.addActionListener(event -> entered.incrementAndGet());
            com.aresstack.spaceharvester.app.ui.controls.InputFrame frame =
                    com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(filter);
            list.add(frame, java.awt.BorderLayout.NORTH);
            JTextArea text = new JTextArea("Alpha Beta Alpha");
            list.add(text, java.awt.BorderLayout.CENTER);
            JPanel plain = new JPanel(new java.awt.BorderLayout());
            plain.add(new JTextArea("Alpha"), java.awt.BorderLayout.CENTER);
            area.add(list, "list");
            area.add(plain, "plain");
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.gate(area, false);
            assertThat(frame.isVisible()).as("oben steht kein Suchfeld").isFalse();

            FindBar bar = new FindBar(() -> area);
            bar.search("Alpha");
            assertThat(bar.foldToggle().isVisible()).isTrue();
            assertThat(bar.foldToggle().getToolTipText()).isEqualTo(FindBar.FILTER_TOOLTIP);
            assertThat(bar.filtering()).isFalse();
            assertThat(bar.counterText()).isEqualTo("1 / 2");
            assertThat(filter.getText()).isEmpty();

            bar.foldToggle().doClick();
            assertThat(bar.filtering()).isTrue();
            assertThat(filter.getText()).as("der Text filtert die Ansicht").isEqualTo("Alpha");
            assertThat(bar.counterText()).as("gefiltert wird ohne Markierungen").isEmpty();
            assertThat(text.getHighlighter().getHighlights()).isEmpty();
            assertThat(frame.isVisible()).as("das Feld oben bleibt weg").isFalse();
            bar.field().setText("Beta");
            assertThat(filter.getText()).isEqualTo("Beta");
            bar.next();
            assertThat(entered.get()).as("Enter geht an die Ansicht").isEqualTo(1);

            // Eine Ansicht ohne Filterfeld: Der alte Filter ist leer, das Symbol weg, es wird gesucht.
            ((java.awt.CardLayout) area.getLayout()).show(area, "plain");
            bar.viewChanged();
            assertThat(filter.getText()).isEmpty();
            assertThat(bar.foldToggle().isVisible()).isFalse();
            assertThat(bar.filtering()).isFalse();

            // Zurück: Die gewählte Betriebsart gilt wieder; zurück auf Suchen räumt den Filter und markiert.
            ((java.awt.CardLayout) area.getLayout()).show(area, "list");
            bar.viewChanged();
            assertThat(filter.getText()).isEqualTo("Beta");
            bar.foldToggle().doClick();
            assertThat(filter.getText()).isEmpty();
            assertThat(bar.counterText()).isEqualTo("1 / 1");
            bar.foldToggle().doClick();
            assertThat(filter.getText()).isEqualTo("Beta");
            bar.close();
            assertThat(filter.getText()).as("zugeklappt wirkt kein unsichtbarer Filter").isEmpty();
        });
    }

    /**
     * Beim Filtern steht das Feld der Fußleiste für das unsichtbare Filterfeld der Ansicht ein: Was an dem Feld hängt
     * (Vorschläge des Begriffsgraphen), richtet sich an das Feld unten, und was die Ansicht selbst in ihr Feld setzt
     * (ein gewählter Vorschlag), steht danach auch unten. Ohne Filtern steht wieder das Feld selbst.
     */
    @Test
    void whileFilteringTheFootFieldStandsInForTheHiddenFilterField() throws Exception {
        JPanel area = new JPanel(new java.awt.BorderLayout());
        javax.swing.JTextField filter = new javax.swing.JTextField();
        FindBar[] bar = new FindBar[1];
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            area.add(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(filter),
                    java.awt.BorderLayout.NORTH);
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.gate(area, false);
            bar[0] = new FindBar(() -> area);
            ((com.aresstack.spaceharvester.app.ui.controls.InputFrame) SwingUtilities.getAncestorOfClass(
                    com.aresstack.spaceharvester.app.ui.controls.InputFrame.class, filter))
                    .onLeft(left::incrementAndGet);
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent event) {
                    writes.incrementAndGet();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent event) {
                    writes.incrementAndGet();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent event) {
                }
            });
            bar[0].search("Zah");
            assertThat(com.aresstack.spaceharvester.app.ui.controls.InputFrame.typedAt(filter)).isSameAs(filter);
            bar[0].filtering(true);
            assertThat(com.aresstack.spaceharvester.app.ui.controls.InputFrame.typedAt(filter))
                    .isSameAs(bar[0].field());
            // Verlässt der Nutzer das Feld unten, erfährt es das Suchfeld der Ansicht.
            for (java.awt.event.FocusListener listener : bar[0].field().getFocusListeners()) {
                listener.focusLost(new java.awt.event.FocusEvent(bar[0].field(), java.awt.event.FocusEvent.FOCUS_LOST));
            }
            assertThat(left.get()).isEqualTo(1);
            // Die Ansicht übernimmt einen Vorschlag in ihr Feld.
            writes.set(0);
            filter.setText("Zahlungsverkehr");
            assertThat(writes.get()).isEqualTo(2);
        });
        SwingUtilities.invokeAndWait(() -> {
            assertThat(writes.get()).as("das Feld unten schreibt den übernommenen Text nicht zurück").isEqualTo(2);
            assertThat(bar[0].query()).isEqualTo("Zahlungsverkehr");
            assertThat(filter.getText()).isEqualTo("Zahlungsverkehr");
            bar[0].filtering(false);
            assertThat(filter.getText()).isEmpty();
        });
        SwingUtilities.invokeAndWait(() -> {
            assertThat(bar[0].query()).as("der Text unten bleibt beim Zurückschalten stehen")
                    .isEqualTo("Zahlungsverkehr");
            assertThat(com.aresstack.spaceharvester.app.ui.controls.InputFrame.typedAt(filter)).isSameAs(filter);
        });
    }

    private static int centerY(java.awt.Component part, java.awt.Component root) {
        return SwingUtilities.convertPoint(part.getParent(), 0, part.getY(), root).y + part.getHeight() / 2;
    }

    private static void layoutAll(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof java.awt.Container container) {
                layoutAll(container);
            }
        }
    }

    /** Eine selbst suchende Ansicht wird gezeigt: die Leiste darüber erfährt es über ihren Träger. */
    @Test
    void aShownViewReachesTheBarOverItsHost() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        JPanel host = new JPanel();
        JPanel inner = new JPanel();
        JLabel view = new JLabel("Editor");
        host.add(inner);
        inner.add(view);
        host.putClientProperty(FindBar.VIEW_HOST, (Runnable) calls::incrementAndGet);
        SwingUtilities.invokeAndWait(() -> FindBar.viewChanged(view));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * Im Command Deck steht die Leiste im durchsuchten Bereich (DeckBars.FindSlot). Tippen darf den Cursor im Suchfeld
     * nicht an den Anfang setzen, und das Feld selbst ist kein Treffer (Angelo 24.11.2026).
     */
    @Test
    void typingInsideTheSearchedAreaKeepsTheCaretAtTheEnd() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("Erlass zur Zuweisung von Frachtraum 7");
            JPanel page = new JPanel(new java.awt.BorderLayout());
            page.add(text, java.awt.BorderLayout.CENTER);
            FindBar bar = new FindBar(() -> page);
            page.add(bar, java.awt.BorderLayout.SOUTH);
            bar.focus();
            for (char typed : "Fracht".toCharArray()) {
                PlaceholderField field = bar.field();
                field.replaceSelection(String.valueOf(typed));
            }
            assertThat(bar.field().getText()).isEqualTo("Fracht");
            assertThat(bar.field().getCaretPosition()).isEqualTo("Fracht".length());
            assertThat(bar.counterText()).isEqualTo("1 / 1");
            assertThat(bar.field().getHighlighter().getHighlights()).isEmpty();
        });
    }

    @Test
    void highlightsCountsAndCyclesThroughHits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("Neustart mit systemctl. Danach erneut Neustart prüfen.");
            JLabel property = new JLabel("Titel: Neustart");
            JPanel page = new JPanel();
            page.add(text);
            page.add(property);
            JComponent[] area = {page};
            FindBar bar = new FindBar(() -> area[0]);

            assertThat(bar.search("neustart")).isEqualTo(3);
            assertThat(bar.counterText()).isEqualTo("1 / 3");
            Highlighter.Highlight[] marks = text.getHighlighter().getHighlights();
            assertThat(marks).hasSize(2);
            assertThat(property.isOpaque()).isTrue();

            bar.next();
            assertThat(bar.counterText()).isEqualTo("2 / 3");
            bar.next();
            bar.next();
            assertThat(bar.counterText()).isEqualTo("1 / 3");
            bar.previous();
            assertThat(bar.counterText()).isEqualTo("3 / 3");

            assertThat(bar.search("gibt es nicht")).isZero();
            assertThat(bar.counterText()).isEqualTo("Keine Treffer");
            assertThat(text.getHighlighter().getHighlights()).isEmpty();

            bar.search("systemctl");
            bar.clear();
            assertThat(bar.counterText()).isEmpty();
            assertThat(text.getHighlighter().getHighlights()).isEmpty();

            // Anderer Bereich: alte Markierungen weg, der stehende Text sucht dort neu.
            JTextArea other = new JTextArea("systemctl restart");
            JPanel second = new JPanel();
            second.add(other);
            area[0] = second;
            bar.areaChanged();
            assertThat(bar.counterText()).isEqualTo("1 / 1");
            assertThat(other.getHighlighter().getHighlights()).hasSize(1);
            assertThat(text.getHighlighter().getHighlights()).isEmpty();
        });
    }

    /** Datei-Aktionen der Vorschau (Angelo, 19.11.2026): direkt neben der Lupe, die Werkzeuge füllen die Zeile. */
    /** Angelo (2026-11-20): „Holz“ muss die Pillen (Bezeichner) „Holzapfel-Hüttenrauch“ und „Holzmann-Kaiser“ finden. */
    @Test
    void pillsOfTheTermsViewAreFound() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel page = new JPanel();
            FindablePill first =
                    new FindablePill("Holzapfel-Hüttenrauch", 26, 26,
                            10);
            FindablePill second =
                    new FindablePill("Holzmann-Kaiser", 26, 26, 10);
            FindablePill other =
                    new FindablePill("Workshop", 26, 26, 10);
            page.add(first);
            page.add(second);
            page.add(other);
            FindBar bar = new FindBar(() -> page);

            assertThat(bar.search("Holz")).isEqualTo(2);
            assertThat(bar.counterText()).isEqualTo("1 / 2");
            assertThat(first.getMark()).isEqualTo(ReaderFind.CURRENT_HIT);
            assertThat(second.getMark()).isEqualTo(ReaderFind.HIT);
            assertThat(other.getMark()).isNull();

            bar.next();
            assertThat(second.getMark()).isEqualTo(ReaderFind.CURRENT_HIT);

            bar.clear();
            assertThat(first.getMark()).isNull();
            assertThat(second.getMark()).isNull();
        });
    }

    @Test
    void fillingToolsStartRightOfTheMagnifierAndReachTheRightEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FindBar bar = new FindBar(JPanel::new);
            JPanel actions = new JPanel(new java.awt.BorderLayout());
            actions.add(new javax.swing.JButton("S"), java.awt.BorderLayout.WEST);
            actions.add(new javax.swing.JLabel("Word · 4,0 MB"), java.awt.BorderLayout.EAST);
            actions.putClientProperty(View.TOOLS_FILL, Boolean.TRUE);
            bar.tools(actions);
            bar.setSize(800, 36);
            bar.doLayout();
            java.awt.Rectangle magnifier = bar.findButton().getBounds();
            java.awt.Rectangle tools = bar.toolsArea().getBounds();
            assertThat(tools.x).isGreaterThan(magnifier.x + magnifier.width - 1)
                    .isLessThanOrEqualTo(magnifier.x + magnifier.width + 8);
            assertThat(tools.x + tools.width).isEqualTo(800 - bar.getInsets().right);
            // Zurück ohne Eigenschaft: wieder mittig.
            actions.putClientProperty(View.TOOLS_FILL, Boolean.FALSE);
            bar.doLayout();
            assertThat(bar.toolsArea().getX()).isGreaterThan(tools.x);
        });
    }

    @Test
    void theMagnifierUnfoldsTheSearchOverTheToolsAndEscapeFoldsItAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("eins zwei eins");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            JPanel switches = new JPanel();
            switches.add(new javax.swing.JToggleButton("A"));
            bar.tools(switches);
            bar.setSize(800, 36);
            bar.doLayout();
            // Zu: nur die Lupe links, die Umschalter mittig.
            assertThat(bar.isOpen()).isFalse();
            assertThat(bar.searchArea().isVisible()).isFalse();
            assertThat(bar.toolsArea().isVisible()).isTrue();
            assertThat(bar.findButton().getX()).isLessThan(bar.toolsArea().getX());
            int middle = bar.toolsArea().getX() + bar.toolsArea().getWidth() / 2;
            assertThat(Math.abs(middle - 400)).isLessThanOrEqualTo(2);

            // Klick auf die Lupe: die Suche klappt nach rechts auf und überdeckt die Umschalter.
            bar.findButton().doClick();
            bar.doLayout();
            assertThat(bar.isOpen()).isTrue();
            assertThat(bar.searchArea().isVisible()).isTrue();
            assertThat(bar.toolsArea().isVisible()).isFalse();
            assertThat(bar.searchArea().getX()).isGreaterThan(bar.findButton().getX());
            assertThat(bar.search("eins")).isEqualTo(2);

            // Esc: Markierungen weg, zugeklappt, der Text bleibt für das nächste Aufklappen.
            bar.close();
            assertThat(bar.isOpen()).isFalse();
            assertThat(bar.toolsArea().isVisible()).isTrue();
            assertThat(text.getHighlighter().getHighlights()).isEmpty();
            assertThat(bar.field().getText()).isEqualTo("eins");
            bar.focus();
            assertThat(bar.counterText()).isEqualTo("1 / 2");
        });
    }

    @Test
    void expandingLowercaseDoesNotShiftOffsets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("İİİ Neustart");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("neustart")).isEqualTo(1);
            Highlighter.Highlight mark = text.getHighlighter().getHighlights()[0];
            assertThat(text.getText().substring(mark.getStartOffset(), mark.getEndOffset())).isEqualTo("Neustart");
        });
    }

    @Test
    void tablesAreSearchedByTheirDisplayedText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = new javax.swing.JTable(new Object[][] {{Thread.State.RUNNABLE}},
                    new Object[] {"Zustand"});
            table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object value) {
                    setText(value == Thread.State.RUNNABLE ? "Läuft" : String.valueOf(value));
                }
            });
            JPanel page = new JPanel();
            page.add(table);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("läuft")).isEqualTo(1);
            assertThat(bar.search("RUNNABLE")).isZero();
        });
    }

    @Test
    void changedContentIsSearchedAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("eins zwei eins zwei eins");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("eins")).isEqualTo(3);
            text.setText("eins");
            bar.next();
            assertThat(bar.counterText()).isEqualTo("1 / 1");
        });
    }

    @Test
    void longStatusTextLeavesRoomForTheJobLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel status = new JLabel("Space Harvester · Bestand " + "C:\\sehr\\langer\\Pfad".repeat(30));
            JLabel cancel = new JLabel("Abbrechen");
            StatusLine line = new StatusLine(new javax.swing.JProgressBar(), status, cancel, new JLabel("© AresStack"));
            line.setSize(960, 30);
            line.doLayout();
            line.setSize(960, 30);
            line.doLayout();
            assertThat(cancel.getWidth()).isGreaterThanOrEqualTo(cancel.getPreferredSize().width);
        });
    }
}
