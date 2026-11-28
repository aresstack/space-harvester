package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.chat.artifact.SvgEditing;
import com.aresstack.spaceharvester.app.chat.artifact.SvgElements;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.controls.FlatIconButton;
import com.aresstack.spaceharvester.app.ui.controls.TextContextMenu;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.UpdateManager;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.swing.JSVGCanvas;
import org.apache.batik.swing.svg.GVTTreeBuilderAdapter;
import org.apache.batik.swing.svg.GVTTreeBuilderEvent;
import org.apache.batik.util.ParsedURL;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.svg.SVGDocument;
import org.w3c.dom.svg.SVGLocatable;
import org.w3c.dom.svg.SVGMatrix;
import org.w3c.dom.svg.SVGRect;

import javax.swing.BorderFactory;
import javax.swing.JColorChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Zeichenfläche einer Grafik (ADR 0045 §6, Angelo 27.11.): das SVG auf Apache Batik ({@link JSVGCanvas}, Apache
 * 2.0), zum selbst Malen. Auswählen, Verschieben, Größe ändern (Griff unten rechts), Löschen (Entf), Rechteck, Ellipse,
 * Linie und Text anlegen, Füll- und Linienfarbe. Jede fertige Änderung wird eine Revision des Benutzers auf den Stand,
 * den er gesehen hat; Rückgängig am Reiter nimmt sie zurück. Hat die KI inzwischen geändert, lädt die Fläche den neuen
 * Stand und die Statuszeile sagt es. Das SVG darf nichts nachladen und kein Skript ausführen.
 *
 * <p>Batik ändert sein DOM nur im eigenen Aktualisierungs-Thread; die Maus wird dorthin weitergereicht.
 */
class GraphicCanvas extends JPanel {

    /** Was ein Ziehen auf der Fläche tut. */
    enum Tool { SELECT, RECTANGLE, ELLIPSE, LINE, TEXT }

    static final Map<Tool, String> TIPS = Map.of(
            Tool.SELECT, "Auswählen und verschieben, Griff unten rechts ändert die Größe (Entf löscht)",
            Tool.RECTANGLE, "Rechteck zeichnen", Tool.ELLIPSE, "Ellipse zeichnen", Tool.LINE, "Linie zeichnen",
            Tool.TEXT, "Text setzen");
    static final String FILL = "Füllfarbe";
    static final String STROKE = "Linienfarbe";
    static final String DELETE = "Auswahl löschen (Entf)";
    static final String DONE = "Fertig (Esc)";
    static final String TEXT_PROMPT = "Text:";
    static final String SAVED = "selbst gezeichnet";
    /** Strichstärke neuer Linien (SVG-Benutzereinheiten). */
    private static final String LINE_WIDTH = "2";
    /** Für Tests: wie oft {@link #settle} nachsieht, ob die Fläche inzwischen neu geladen wurde (Millisekunden). */
    private static final long SETTLE_SLICE_MS = 50;
    /** Kantenlänge des Größengriffs in Pixeln. */
    private static final int HANDLE = 8;

    private final ChatArtifactStore store;
    private final Path folder;
    private final String chatId;
    private final String graphicId;
    private final Consumer<String> status;
    private final Canvas canvas = new Canvas();
    private final Map<Tool, FlatIconButton> tools = new EnumMap<>(Tool.class);
    private final FlatIconButton fillButton = new FlatIconButton(Glyph.FILL, FILL);
    private final FlatIconButton strokeButton = new FlatIconButton(Glyph.STROKE, STROKE);
    private Tool tool = Tool.SELECT;
    private Color fill = Comic.BLUE;
    private Color stroke = Comic.INK;
    /** Die Revision, auf der die Fläche steht; nur im Aktualisierungs-Thread geschrieben. */
    private volatile long revision;
    private volatile String title;

    // Zustand im Aktualisierungs-Thread von Batik
    private SVGDocument document;
    private Element selected;
    private Element creating;
    private Point2D start;
    private String originalTransform;
    private Rectangle2D startBox;
    private boolean resizing;
    private boolean changed;
    /** Fällt, sobald Batik die Grafik bearbeitbar zeigt (Aktualisierungs-Thread läuft). */
    private volatile java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    /** Die Auswahl in Pixeln der Fläche, für das Zeichnen des Rahmens. */
    private volatile Rectangle2D selection;

    GraphicCanvas(ChatArtifactStore store, Path folder, String chatId, ChatArtifact graphic, Runnable done,
                  Consumer<String> status) {
        super(new BorderLayout(0, 4));
        this.store = store;
        this.folder = folder;
        this.chatId = chatId;
        this.graphicId = graphic.id();
        this.status = status;
        setOpaque(false);
        canvas.setBackground(AppColors.of("app.GraphicCanvas.init"));
        canvas.setBorder(BorderFactory.createLineBorder(Comic.LINE));
        canvas.setDocumentState(JSVGCanvas.ALWAYS_DYNAMIC);
        canvas.setEnableRotateInteractor(false);
        canvas.setEnableImageZoomInteractor(false);
        canvas.addGVTTreeBuilderListener(new GVTTreeBuilderAdapter() {
            @Override
            public void gvtBuildCompleted(GVTTreeBuilderEvent event) {
                queue(GraphicCanvas.this::measure);
            }
        });
        canvas.addUpdateManagerListener(new org.apache.batik.bridge.UpdateManagerAdapter() {
            @Override
            public void managerStarted(org.apache.batik.bridge.UpdateManagerEvent event) {
                // Erst jetzt gehört das Dokument dem neuen Aktualisierungs-Thread.
                java.util.concurrent.CountDownLatch ready = started;
                // Auf die Warteschlange genau dieses Threads, nicht auf die, die die Fläche gerade meldet.
                ((UpdateManager) event.getSource()).getUpdateRunnableQueue().invokeLater(() -> {
                    document = canvas.getSVGDocument();
                    selected = null;
                    selection = null;
                    ready.countDown();
                });
            }
        });
        canvas.getOverlays().add((org.apache.batik.swing.gvt.Overlay) this::paintSelection);
        Mouse mouse = new Mouse();
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.setFocusable(true);
        canvas.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "loeschen");
        canvas.getActionMap().put("loeschen", action(() -> queue(this::deleteSelected)));
        canvas.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "fertig");
        canvas.getActionMap().put("fertig", action(done));
        add(canvas, BorderLayout.CENTER);
        add(toolbar(done), BorderLayout.SOUTH);
        load(graphic);
    }

    private JPanel toolbar(Runnable done) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bar.setOpaque(false);
        Map<Tool, Glyph> glyphs = Map.of(Tool.SELECT, Glyph.POINTER, Tool.RECTANGLE, Glyph.RECTANGLE,
                Tool.ELLIPSE, Glyph.ELLIPSE, Tool.LINE, Glyph.LINE, Tool.TEXT, Glyph.TEXT);
        for (Tool each : Tool.values()) {
            FlatIconButton button = new FlatIconButton(glyphs.get(each), TIPS.get(each));
            button.addActionListener(event -> tool(each));
            tools.put(each, button);
            bar.add(button);
        }
        fillButton.setForeground(fill);
        strokeButton.setForeground(stroke);
        fillButton.addActionListener(event -> chooseColor(true));
        strokeButton.addActionListener(event -> chooseColor(false));
        FlatIconButton delete = new FlatIconButton(Glyph.TRASH, DELETE);
        delete.addActionListener(event -> queue(this::deleteSelected));
        FlatIconButton finish = new FlatIconButton(Glyph.ACCEPT, DONE);
        finish.addActionListener(event -> done.run());
        bar.add(fillButton);
        bar.add(strokeButton);
        bar.add(delete);
        bar.add(finish);
        tool(Tool.SELECT);
        return bar;
    }

    /** Wählt das Werkzeug; das aktive ist gelb. */
    void tool(Tool next) {
        tool = next;
        tools.forEach((each, button) -> button.setForeground(each == next ? Comic.ORANGE : Comic.INK));
    }

    Tool tool() {
        return tool;
    }

    long revision() {
        return revision;
    }

    /** Für Tests: die Batik-Fläche. */
    JSVGCanvas canvas() {
        return canvas;
    }

    /**
     * Für Tests: wartet, bis die Fläche bearbeitbar ist und alles bis hierher Eingereihte erledigt hat. Lädt die Fläche
     * währenddessen einen neuen Stand (der alte Aktualisierungs-Thread endet dann, ohne die Marke abzuarbeiten), wartet
     * sie auf den neuen; so hängt das Ergebnis nicht davon ab, wie schnell der Rechner ist.
     */
    boolean settle(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            java.util.concurrent.CountDownLatch ready = started;
            if (!ready.await(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                return false;
            }
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            queue(done::countDown);
            while (ready == started) {
                long left = remaining(deadline);
                if (left <= 0) {
                    return false;
                }
                if (done.await(Math.min(left, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SETTLE_SLICE_MS)),
                        java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    return true;
                }
            }
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** Zeigt einen Stand der Grafik; die Auswahl geht verloren. */
    void load(ChatArtifact graphic) {
        started = new java.util.concurrent.CountDownLatch(1);
        revision = graphic.revision();
        title = graphic.title();
        try {
            SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName());
            SVGDocument parsed = factory.createSVGDocument("file:///" + graphic.id() + ".svg",
                    new StringReader(graphic.current().svg().strip()));
            canvas.setDocument(parsed);
        } catch (Exception unreadable) {
            status.accept("Die Grafik lässt sich nicht auf der Zeichenfläche öffnen ("
                    + unreadable.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Ein neuer Stand von außen (KI oder Rückgängig): laden, wenn er nicht von hier kommt. Geprüft wird im
     * Aktualisierungs-Thread, denn dort speichert die Fläche; so ist die eigene Revision schon bekannt.
     */
    void external(ChatArtifact graphic) {
        queueOrNow(() -> {
            if (graphic.revision() > revision) {
                SwingUtilities.invokeLater(() -> {
                    if (graphic.revision() > revision) {
                        load(graphic);
                    }
                });
            }
        });
    }

    private void chooseColor(boolean forFill) {
        Color chosen = JColorChooser.showDialog(this, forFill ? FILL : STROKE, forFill ? fill : stroke);
        if (chosen == null) {
            return;
        }
        if (forFill) {
            fill = chosen;
            fillButton.setForeground(chosen);
        } else {
            stroke = chosen;
            strokeButton.setForeground(chosen);
        }
        String value = SvgEditing.color(chosen);
        queue(() -> {
            if (selected != null) {
                SvgEditing.set(selected, forFill ? "fill" : "stroke", value);
                save(forFill ? "Füllfarbe" : "Linienfarbe");
            }
        });
    }

    // ------------------------------------------------------------------ Aktualisierungs-Thread

    private void press(double x, double y) {
        if (document == null) {
            return;
        }
        changed = false;
        start = new Point2D.Double(x, y);
        Rectangle2D box = selection;
        if (tool == Tool.SELECT) {
            if (selected != null && box != null && handle(box).contains(x, y)) {
                resizing = true;
                startBox = box;
                originalTransform = selected.getAttribute("transform");
                return;
            }
            resizing = false;
            selected = hit(x, y);
            originalTransform = selected == null ? null : selected.getAttribute("transform");
            measure();
            return;
        }
        Element root = document.getDocumentElement();
        Point2D at = toUser(root, x, y);
        String tag = switch (tool) {
            case RECTANGLE -> "rect";
            case ELLIPSE -> "ellipse";
            default -> "line";
        };
        creating = SvgEditing.add(document, root, tag);
        if (tool == Tool.LINE) {
            SvgEditing.set(creating, "stroke", SvgEditing.color(stroke));
            SvgEditing.set(creating, "stroke-width", LINE_WIDTH);
        } else {
            SvgEditing.set(creating, "fill", SvgEditing.color(fill));
            SvgEditing.set(creating, "stroke", SvgEditing.color(stroke));
        }
        SvgEditing.span(creating, at.getX(), at.getY(), at.getX(), at.getY());
    }

    private void drag(double x, double y) {
        if (document == null || start == null) {
            return;
        }
        if (x == start.getX() && y == start.getY() && !changed) {
            return; // ein Klick ohne Bewegung ändert nichts
        }
        if (creating != null) {
            Point2D from = toUser(document.getDocumentElement(), start.getX(), start.getY());
            Point2D to = toUser(document.getDocumentElement(), x, y);
            SvgEditing.span(creating, from.getX(), from.getY(), to.getX(), to.getY());
            changed = true;
            return;
        }
        if (selected == null) {
            return;
        }
        Element parent = (Element) selected.getParentNode();
        if (resizing && startBox != null && startBox.getWidth() > 0 && startBox.getHeight() > 0) {
            double sx = Math.max(x - startBox.getX(), 1) / startBox.getWidth();
            double sy = Math.max(y - startBox.getY(), 1) / startBox.getHeight();
            Point2D anchor = toUser(parent, startBox.getX(), startBox.getY());
            SvgEditing.scale(selected, originalTransform, anchor.getX(), anchor.getY(), sx, sy);
        } else {
            Point2D from = toUser(parent, start.getX(), start.getY());
            Point2D to = toUser(parent, x, y);
            SvgEditing.translate(selected, originalTransform, to.getX() - from.getX(), to.getY() - from.getY());
        }
        changed = true;
        measure();
    }

    private void release() {
        if (creating != null) {
            Element made = creating;
            creating = null;
            if (!changed) {
                made.getParentNode().removeChild(made);
            } else {
                selected = made;
                save(TIPS.get(tool));
            }
        } else if (changed && selected != null) {
            save(resizing ? "Größe geändert" : "verschoben");
        }
        start = null;
        resizing = false;
        changed = false;
        measure();
    }

    private void addText(double x, double y, String text) {
        if (document == null) {
            return;
        }
        Element root = document.getDocumentElement();
        Point2D at = toUser(root, x, y);
        Element element = SvgEditing.add(document, root, "text");
        SvgEditing.span(element, at.getX(), at.getY(), at.getX(), at.getY());
        SvgEditing.set(element, "fill", SvgEditing.color(stroke));
        element.setTextContent(text);
        selected = element;
        save("Text gesetzt");
        measure();
    }

    private void deleteSelected() {
        if (selected == null || selected == document.getDocumentElement()) {
            return;
        }
        selected.getParentNode().removeChild(selected);
        selected = null;
        save("gelöscht");
        measure();
    }

    /** Schreibt den Stand als Revision des Benutzers; ist die Grafik inzwischen anders, wird sie neu geladen. */
    private void save(String what) {
        SvgElements.assignIds(document);
        String svg = SvgElements.writeSpecified(document);
        if (svg == null) {
            return;
        }
        ChatArtifactStore.WriteResult result = store.replaceSvg(folder, chatId, graphicId, revision,
                ChatArtifact.Origin.USER, SAVED + ": " + what, svg);
        if (result.success()) {
            revision = result.artifact().revision();
            return;
        }
        ChatArtifact latest = result.artifact();
        SwingUtilities.invokeLater(() -> {
            status.accept("Die Grafik wurde inzwischen geändert; die Zeichenfläche zeigt jetzt den neuen Stand.");
            if (latest != null) {
                load(latest);
            }
        });
    }

    /** Das oberste Element unter dem Punkt (Kasten des Elements), Teile vor ihrer Gruppe; sonst {@code null}. */
    private Element hit(double x, double y) {
        List<Element> shapes = SvgElements.shapes(document);
        for (int i = shapes.size() - 1; i >= 0; i--) {
            Rectangle2D box = screenBox(shapes.get(i));
            if (box != null && box.contains(x, y)) {
                return shapes.get(i);
            }
        }
        return null;
    }

    /** Merkt den Kasten der Auswahl in Pixeln und zeichnet neu. */
    private void measure() {
        Element current = selected;
        selection = current == null || current.getParentNode() == null ? null : screenBox(current);
        canvas.repaint();
    }

    /** Der Kasten eines Elements in Pixeln der Fläche; {@code null}, wenn Batik ihn nicht kennt. */
    private static Rectangle2D screenBox(Element element) {
        if (!(element instanceof SVGLocatable locatable)) {
            return null;
        }
        try {
            SVGRect box = locatable.getBBox();
            SVGMatrix matrix = locatable.getScreenCTM();
            if (box == null || matrix == null) {
                return null;
            }
            return affine(matrix).createTransformedShape(new Rectangle2D.Double(box.getX(), box.getY(),
                    box.getWidth(), box.getHeight())).getBounds2D();
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    /** Ein Punkt in Pixeln der Fläche im Koordinatenraum von {@code parent} (dort, wo seine Kinder liegen). */
    private static Point2D toUser(Element parent, double x, double y) {
        Node at = parent;
        while (at != null && !(at instanceof SVGLocatable)) {
            at = at.getParentNode();
        }
        if (at == null) {
            return new Point2D.Double(x, y);
        }
        try {
            AffineTransform screen = affine(((SVGLocatable) at).getScreenCTM());
            return screen.inverseTransform(new Point2D.Double(x, y), null);
        } catch (Exception singular) {
            return new Point2D.Double(x, y);
        }
    }

    private static AffineTransform affine(SVGMatrix m) {
        return new AffineTransform(m.getA(), m.getB(), m.getC(), m.getD(), m.getE(), m.getF());
    }

    private static Rectangle2D handle(Rectangle2D box) {
        return new Rectangle2D.Double(box.getMaxX() - HANDLE / 2.0, box.getMaxY() - HANDLE / 2.0, HANDLE, HANDLE);
    }

    // ------------------------------------------------------------------ EDT

    private void paintSelection(Graphics graphics) {
        Rectangle2D box = selection;
        if (box == null) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(Comic.ORANGE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f},
                    0f));
            g.draw(box);
            g.setStroke(new BasicStroke(1f));
            Rectangle2D grip = handle(box);
            g.setColor(AppColors.of("app.GraphicCanvas.paintSelection"));
            g.fill(grip);
            g.setColor(Comic.ORANGE);
            g.draw(grip);
        } finally {
            g.dispose();
        }
    }

    /** Rechtsklick: die ganze Grafik oder das Element unter der Maus an den Chat (wie im Artefakt-Menü). */
    private void showMenu(MouseEvent event) {
        int x = event.getX();
        int y = event.getY();
        queue(() -> {
            Element element = document == null ? null : hit(x, y);
            String id = element == null ? null : element.getAttribute("id");
            String line = element == null ? null : SvgElements.list(SvgElements.write(document)).stream()
                    .filter(item -> item.id().equals(id)).map(item -> item.line().strip()).findFirst().orElse(id);
            SwingUtilities.invokeLater(() -> {
                String reference = "Grafik " + graphicId + " „" + title + "“";
                JPopupMenu menu = new JPopupMenu();
                JMenuItem whole = new JMenuItem(TextContextMenu.QUOTE);
                whole.addActionListener(e -> TextContextMenu.quote(this, "Grafik „" + title + "“",
                        reference));
                menu.add(whole);
                if (id != null && !id.isEmpty()) {
                    JMenuItem one = new JMenuItem(ArtifactView.ELEMENT_QUOTE + ": " + line);
                    one.addActionListener(e -> TextContextMenu.quote(this, line, reference + ", Element " + id));
                    menu.add(one);
                }
                menu.show(canvas, x, y);
            });
        });
    }

    /** Läuft im Aktualisierungs-Thread von Batik; vor dem ersten Zeichnen gibt es ihn noch nicht. */
    private void queue(Runnable work) {
        UpdateManager manager = canvas.getUpdateManager();
        if (manager != null && manager.getUpdateRunnableQueue() != null) {
            manager.getUpdateRunnableQueue().invokeLater(work);
        }
    }

    private void queueOrNow(Runnable work) {
        UpdateManager manager = canvas.getUpdateManager();
        if (manager != null && manager.getUpdateRunnableQueue() != null) {
            manager.getUpdateRunnableQueue().invokeLater(work);
        } else {
            work.run();
        }
    }

    private static javax.swing.AbstractAction action(Runnable run) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                run.run();
            }
        };
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, Math.max(size.height, canvas.getPreferredSize().height));
    }

    /** Maus auf der Fläche; Strg und Umschalt bleiben Batik (Zoomen, Schieben). */
    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent event) {
            canvas.requestFocusInWindow();
            if (event.isPopupTrigger()) {
                showMenu(event);
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(event) || event.isControlDown() || event.isShiftDown()) {
                return;
            }
            int x = event.getX();
            int y = event.getY();
            if (tool == Tool.TEXT) {
                String text = JOptionPane.showInputDialog(GraphicCanvas.this, TEXT_PROMPT, TIPS.get(Tool.TEXT),
                        JOptionPane.PLAIN_MESSAGE);
                if (text != null && !text.isBlank()) {
                    queue(() -> addText(x, y, text.strip()));
                }
                return;
            }
            queue(() -> press(x, y));
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event) || event.isControlDown() || event.isShiftDown()) {
                return;
            }
            int x = event.getX();
            int y = event.getY();
            queue(() -> drag(x, y));
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (event.isPopupTrigger()) {
                showMenu(event);
                return;
            }
            if (SwingUtilities.isLeftMouseButton(event) && tool != Tool.TEXT) {
                queue(GraphicCanvas.this::release);
            }
        }
    }

    /** Die Batik-Fläche ohne Skripte und ohne Nachladen von außen (nur eingebettete data:-Bilder). */
    private static final class Canvas extends JSVGCanvas {
        Canvas() {
            super(null, true, false);
        }

        @Override
        protected UserAgent createUserAgent() {
            return new BridgeUserAgent() {
                @Override
                public void checkLoadScript(String scriptType, ParsedURL scriptURL, ParsedURL docURL) {
                    throw new SecurityException("Skripte sind in Grafiken gesperrt.");
                }

                @Override
                public void checkLoadExternalResource(ParsedURL resourceURL, ParsedURL docURL) {
                    if (resourceURL == null || !"data".equalsIgnoreCase(resourceURL.getProtocol())) {
                        throw new SecurityException("Grafiken laden nichts nach.");
                    }
                }
            };
        }
    }
}
