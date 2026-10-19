package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.SkyClouds;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Der Navigationsbanner über dem Dokument im Lesebereich: der Wolkenhimmel aus askai-java8 arch
 * {@code ResearchOutOfScopeSky} (dieselbe Wolkenzeichnung, derselbe durchsichtige Verlauf, dieselbe Durchlässigkeit
 * und Höhengrenze über {@link SkyClouds}), hier mit den Hauptkapiteln als EINE waagrechte Wolkenreihe statt
 * umbrechender Wolken.
 *
 * <p>Kopie von {@code DocumentNavigationSky} aus dem Enterprise AI Client; nur das Datenmodell ist das der App
 * ({@link Navigation}), Verhalten und Texte sind unverändert.</p>
 *
 * <ul>
 *   <li>Das aktive Kapitel steht waagrecht in der Mitte (FA-08); links die vorigen, rechts die folgenden. Vor dem
 *       ersten und nach dem letzten Kapitel ist so viel Rand, dass auch sie mittig stehen können; kein Umlauf.</li>
 *   <li>Das Mausrad über dem Banner verschiebt die Reihe waagrecht und rollt nie das Dokument (FA-09). Danach
 *       zentriert erst ein tatsächlicher Kapitelwechsel wieder.</li>
 *   <li>Ein Klick auf ein Kapitel meldet es dem {@link Listener} (FA-10).</li>
 *   <li>Der Pfeil links klappt den Himmel nach unten auf (FA-11): darunter die Gliederung NUR des aktiven Kapitels,
 *       Originalunterüberschriften und erkannte Themenabschnitte, je anklickbar (FA-12). Jede Ebene des aktiven Pfads
 *       ist eine eigene waagrechte Wolkenreihe mit eigenem Bildlauf (#110): unter dem Kapitel seine direkten Kinder,
 *       darunter die Kinder des aktiven Kindes usw.; das Mausrad rollt nur die Reihe unter dem Zeiger. Höchstens
 *       {@link ResearchUiMetrics#SKY_EXPANDED_MAX_PERCENT} % der Höhe, darüber rollen die Reihen senkrecht (FA-13).</li>
 *   <li>Lange Bezeichnungen werden mit „…“ gekürzt, der Tooltip zeigt sie ganz; die Breite einer Wolke richtet sich
 *       nach der verfügbaren Breite, so passen in schmalen Fenstern weniger Nachbarn (FA-14).</li>
 *   <li>Automatisch erkannte Abschnitte stehen kursiv in einer gestrichelten Wolke; Originalüberschriften gerade in
 *       einer durchgezogenen (FA-04).</li>
 * </ul>
 *
 * <p>{@link #contains(int, int)} beansprucht nur die bedienbare Zone; darunter, im Auslauf des Verlaufs, bleibt das
 * Dokument klick- und rollbar. Den nötigen Platz oben meldet der Banner über {@link #TOP_INSET_PROPERTY}.</p>
 */
public final class DocumentNavigationSky extends JPanel {

    /** Client-Property: so viel Platz braucht der Banner oben im Dokument (Pixel). */
    public static final String TOP_INSET_PROPERTY = "enterpriseai.readerTopInset";
    static final String GENERATED_HINT = "automatisch erkannter Abschnitt";
    static final int WHEEL_STEP = 48;
    private static final float FONT_SIZE = 12.5f;
    private static final int CHEVRON_SIZE = 22;
    private static final int ROW_HEIGHT = ResearchUiMetrics.CLOUD_GAP_V + ResearchUiMetrics.CLOUD_CHIP_HEIGHT;

    /** Was der Banner meldet; auf dem EDT. */
    public interface Listener {
        /** Ein Kapitel oder Unterabschnitt wurde angeklickt. */
        void sectionSelected(Navigation.Section section);
    }

    private final SectionStrip strip;
    private final ExpandChevron chevron = new ExpandChevron();
    private final HierarchyRows rows = new HierarchyRows();
    private final JScrollPane rowsScroll;
    private Listener listener;
    private Navigation navigation;
    private List<Navigation.Section> chapters = Collections.emptyList();
    private String activeChapterId;
    private String activeSectionId;
    private boolean expanded;
    private int contentBottom;
    private boolean revealDeepestRow;

    public DocumentNavigationSky() {
        super(null);
        setOpaque(false);
        setName("reader.navigationSky");
        strip = new SectionStrip(0, null, chapters);
        rowsScroll = new JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rowsScroll.setBorder(BorderFactory.createEmptyBorder());
        rowsScroll.setOpaque(false);
        rowsScroll.getViewport().setOpaque(false);
        com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI.install(rowsScroll.getVerticalScrollBar());
        // Each row consumes the wheel for itself; only the free scroll bar moves the rows vertically, never the document.
        rowsScroll.setVisible(false);
        add(strip);
        add(chevron);
        add(rowsScroll);
        // Rad über dem Himmel (Luft oder Wolkenreihe): waagrecht durch die Kapitel, das Dokument bleibt stehen.
        addMouseWheelListener(strip::wheel);
        // Wie arch: ein Klick in die Luft des aufgeklappten Himmels klappt ihn wieder ein.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (expanded) {
                    setExpanded(false);
                }
            }
        });
        setVisible(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Zeigt die Navigation (leer: kein Banner). Das aktive Kapitel bleibt, wenn es noch existiert. EDT. */
    public void setNavigation(Navigation value) {
        navigation = value;
        chapters = value == null ? Collections.<Navigation.Section>emptyList() : value.chapters();
        boolean keep = false;
        for (Navigation.Section chapter : chapters) {
            keep |= chapter.id().equals(activeChapterId);
        }
        if (!keep) {
            activeChapterId = chapters.isEmpty() ? null : chapters.get(0).id();
            strip.manual = false;
        }
        strip.sections = chapters;
        strip.rebuild();
        rows.sync(false);
        setVisible(!chapters.isEmpty());
        if (chapters.isEmpty()) {
            publishTopInset(0);
        }
        revalidate();
        repaint();
    }

    public Navigation navigation() {
        return navigation;
    }

    /**
     * Das Kapitel, in dem das Dokument gerade steht, und darin der tiefste sichtbare Abschnitt (oder {@code null}).
     * Ein Kapitelwechsel zentriert die Reihe neu, auch nach manuellem Verschieben; derselbe Wert ändert nichts. Die
     * Unterreihen folgen dem aktiven Pfad; eine Reihe mit unverändertem Elternknoten behält ihren Bildlauf.
     */
    public void setActive(String chapterId, String sectionId) {
        boolean chapterChanged = chapterId != null && !chapterId.equals(activeChapterId);
        boolean sectionChanged = sectionId == null ? activeSectionId != null : !sectionId.equals(activeSectionId);
        if (chapterChanged) {
            activeChapterId = chapterId;
            strip.manual = false;
            strip.centerOnActive(true);
        }
        if (chapterChanged || sectionChanged) {
            activeSectionId = sectionId;
            rows.sync(true);
            strip.repaint();
            revalidate();
        }
    }

    public String activeChapterId() {
        return activeChapterId;
    }

    public String activeSectionId() {
        return activeSectionId;
    }

    public void setExpanded(boolean value) {
        if (expanded != value) {
            expanded = value;
            rows.sync(false);
            revalidate();
            repaint();
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** Unterkante der bedienbaren Zone: ein angesprungener Abschnitt muss darunter stehen (FA-10). */
    public int coveredHeight() {
        return isVisible() ? contentBottom : 0;
    }

    // Für Tests (gleiches Paket).
    int stripOffsetForTest() {
        return (int) Math.round(strip.offset);
    }

    int targetOffsetForTest(String chapterId) {
        return (int) Math.round(strip.targetOffsetOf(chapterId));
    }

    boolean manualForTest() {
        return strip.manual;
    }

    void wheelForTest(MouseWheelEvent event) {
        strip.wheel(event);
    }

    JComponent stripForTest() {
        return strip;
    }

    /** The sections of each sub row, top to bottom. */
    List<List<Navigation.Section>> rowSectionsForTest() {
        List<List<Navigation.Section>> result = new ArrayList<List<Navigation.Section>>();
        for (SectionStrip row : rows.rows) {
            result.add(row.sections);
        }
        return result;
    }

    /** Row at {@code depth}: 0 the chapters, 1 the first sub row. */
    JComponent rowForTest(int depth) {
        return rowAt(depth);
    }

    int rowOffsetForTest(int depth) {
        return (int) Math.round(rowAt(depth).offset);
    }

    boolean rowManualForTest(int depth) {
        return rowAt(depth).manual;
    }

    /** Bounds of a cloud in the coordinates of its row, as painted now. */
    Rectangle chipBoundsForTest(int depth, int index) {
        SectionStrip row = rowAt(depth);
        row.layoutChips();
        Rectangle chip = row.chips.get(index);
        return new Rectangle((int) Math.round(row.shift()) + chip.x, chip.y, chip.width, chip.height);
    }

    double anchorForTest(int depth) {
        return rowAt(depth).anchorX();
    }

    String activeIdForTest(int depth) {
        return rowAt(depth).activeId();
    }

    void clickRowForTest(int depth, int index) {
        rowAt(depth).select(index);
    }

    void clickChapterForTest(int index) {
        strip.select(index);
    }

    String chipTextForTest(int index) {
        return strip.chipText(index);
    }

    JScrollPane rowsScrollForTest() {
        return rowsScroll;
    }

    // ------------------------------------------------------------------ Treffer, Malen, Layout

    @Override
    public boolean contains(int x, int y) {
        return isVisible() && y >= 0 && y <= Math.min(contentBottom, getHeight());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (contentBottom <= 0) {
            return;
        }
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            int skyHeight = Math.min(getHeight(), contentBottom + ResearchUiMetrics.SKY_FADE_TAIL);
            SkyClouds.paintSky(g2, getWidth(), contentBottom, skyHeight);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public void doLayout() {
        if (getWidth() <= 0 || getHeight() <= 0 || chapters.isEmpty()) {
            contentBottom = 0;
            publishTopInset(0);
            return;
        }
        int padH = ResearchUiMetrics.SKY_PADDING_H;
        int top = ResearchUiMetrics.SKY_PADDING_TOP;
        boolean expandable = !activeChildren().isEmpty();
        chevron.setVisible(expandable);
        chevron.setBounds(padH, top + (ResearchUiMetrics.CLOUD_CHIP_HEIGHT - CHEVRON_SIZE) / 2, CHEVRON_SIZE,
                CHEVRON_SIZE);
        int stripLeft = padH + CHEVRON_SIZE + 6;
        strip.setBounds(stripLeft, top, Math.max(0, getWidth() - stripLeft - padH),
                ResearchUiMetrics.CLOUD_CHIP_HEIGHT);
        strip.layoutChips();
        int bottom = top + ResearchUiMetrics.CLOUD_CHIP_HEIGHT + 8;
        boolean showRows = expanded && expandable && rows.rowCount() > 0;
        rowsScroll.setVisible(showRows);
        if (showRows) {
            // Each row carries its gap to the row above, so the rows start right below the chapters.
            int rowsTop = top + ResearchUiMetrics.CLOUD_CHIP_HEIGHT;
            int natural = rows.rowCount() * ROW_HEIGHT;
            int cap = Math.max(ROW_HEIGHT * 2,
                    getHeight() * ResearchUiMetrics.SKY_EXPANDED_MAX_PERCENT / 100 - rowsTop);
            int height = Math.min(natural, cap);
            rowsScroll.setBounds(stripLeft, rowsTop, Math.max(0, getWidth() - stripLeft - padH), height);
            rowsScroll.validate();
            if (revealDeepestRow) {
                revealDeepestRow = false;
                rowsScroll.getViewport().setViewPosition(new java.awt.Point(0, Math.max(0, natural - height)));
            }
            bottom = rowsTop + height + 8;
        }
        contentBottom = bottom;
        publishTopInset(contentBottom);
    }

    private void publishTopInset(int pixels) {
        putClientProperty(TOP_INSET_PROPERTY, Integer.valueOf(pixels));
    }

    private Navigation.Section activeChapter() {
        for (Navigation.Section chapter : chapters) {
            if (chapter.id().equals(activeChapterId)) {
                return chapter;
            }
        }
        return null;
    }

    /** Chapter, then each section down to the active one; just the chapter while no subsection is active. */
    private List<Navigation.Section> activePath() {
        List<Navigation.Section> path = new ArrayList<Navigation.Section>();
        Navigation.Section chapter = activeChapter();
        if (chapter == null) {
            return path;
        }
        path.add(chapter);
        if (activeSectionId != null && !activeSectionId.equals(chapter.id())) {
            pathTo(chapter.children(), activeSectionId, path);
        }
        return path;
    }

    private static boolean pathTo(List<Navigation.Section> sections, String id, List<Navigation.Section> path) {
        for (Navigation.Section section : sections) {
            path.add(section);
            if (section.id().equals(id) || pathTo(section.children(), id, path)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /** Row at {@code depth} (0 the chapters), or {@code null}. */
    private SectionStrip rowAt(int depth) {
        if (depth == 0) {
            return strip;
        }
        return depth - 1 < rows.rows.size() ? rows.rows.get(depth - 1) : null;
    }

    /** The row above {@code depth}, whose active cloud is the parent of that row. */
    private SectionStrip parentStrip(int depth) {
        return depth <= 0 ? null : rowAt(depth - 1);
    }

    private List<Navigation.Section> activeChildren() {
        Navigation.Section chapter = activeChapter();
        return chapter == null ? Collections.<Navigation.Section>emptyList() : chapter.children();
    }

    private void fireSelected(Navigation.Section section) {
        if (listener != null && section != null) {
            listener.sectionSelected(section);
        }
    }

    static Font fontFor(Navigation.Section section) {
        Font font = ResearchUiTypography.regular(FONT_SIZE);
        return section.isGenerated() ? font.deriveFont(Font.ITALIC) : font;
    }

    static String tooltipFor(Navigation.Section section) {
        return section.isGenerated() ? section.label() + " (" + GENERATED_HINT + ")" : section.label();
    }

    /** Text gekürzt auf {@code width} Pixel, mit „…“. */
    static String elide(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end).trim() + ellipsis) > width) {
            end--;
        }
        return end == 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
    }

    private static Stroke borderFor(Navigation.Section section, boolean active) {
        float width = active ? 1.8f : 1.2f;
        return section.isGenerated()
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 3f}, 0f)
                : new BasicStroke(width);
    }

    /** Eine Wolke mit Bezeichnung, wie die Wolken des Himmels; aktiv mit kräftigem Rand. */
    private static void paintCloud(Graphics2D g2, Navigation.Section section, String text, int width, int height,
                                   boolean hovered, boolean active) {
        SkyClouds.paintCloudBase(g2, width, height, hovered || active, borderFor(section, active));
        if (active) {
            g2.setColor(ResearchUiPalette.ACCENT_BLUE);
            g2.setStroke(borderFor(section, true));
            g2.draw(SkyClouds.cloudShape(width, height));
        }
        g2.setFont(fontFor(section));
        FontMetrics metrics = g2.getFontMetrics();
        g2.setColor(ResearchUiPalette.CLOUD_TEXT);
        g2.drawString(text, ResearchUiMetrics.CLOUD_CHIP_PADDING_H, SkyClouds.cloudTextY(metrics, height));
    }

    // ------------------------------------------------------------------ Wolkenreihen

    /**
     * One horizontal row of clouds with its own scroll state: the chapters (depth 0) or the direct children of one
     * node on the active path (depth 1 and deeper). The wheel over the row moves only this row and never the document.
     */
    private final class SectionStrip extends JComponent {

        private final int depth;
        private final List<Rectangle> chips = new ArrayList<Rectangle>();
        private final List<String> texts = new ArrayList<String>();
        private List<Navigation.Section> sections;
        private String parentId;
        private String shownActiveId;
        private double offset;
        private double target;
        private boolean manual;
        private boolean pendingCenter;
        private int hovered = -1;
        private int laidOutWidth = -1;
        private final Timer animation = new Timer(15, event -> step());

        SectionStrip(int depth, String parentId, List<Navigation.Section> sections) {
            this.depth = depth;
            this.parentId = parentId;
            this.sections = sections;
            setOpaque(false);
            setName(depth == 0 ? "reader.navigationChapters" : "reader.navigationRow" + depth);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            ToolTipSupport.register(this);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    int index = indexAt(event.getX(), event.getY());
                    if (index != hovered) {
                        hovered = index;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = -1;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    int index = indexAt(event.getX(), event.getY());
                    if (index >= 0) {
                        select(index);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(this::wheel);
        }

        /** Free space above the clouds of a sub row; the link to the parent cloud is drawn there. */
        int top() {
            return depth == 0 ? 0 : ResearchUiMetrics.CLOUD_GAP_V;
        }

        String activeId() {
            if (depth == 0) {
                return activeChapterId;
            }
            List<Navigation.Section> path = activePath();
            return depth < path.size() ? path.get(depth).id() : null;
        }

        /** Lay out anew for the chapters of a new navigation; keep a manual position. */
        void rebuild() {
            laidOutWidth = -1;
            layoutChips();
            centerOnActive(false);
            repaint();
        }

        /** Replace the shown sections; a new parent starts a fresh scroll state, the same parent keeps it. */
        void show(String parent, List<Navigation.Section> value, boolean animate) {
            boolean newParent = parent == null ? parentId != null : !parent.equals(parentId);
            parentId = parent;
            sections = value;
            laidOutWidth = -1;
            if (newParent) {
                manual = false;
                shownActiveId = activeId();
                animation.stop();
                layoutChips();
                centerOnActive(false);
            } else {
                layoutChips();
                followActive(animate);
            }
            repaint();
        }

        /** Recenter when the active entry changed, unless the user moved the row and the entry is still in view. */
        void followActive(boolean animate) {
            String active = activeId();
            boolean changed = active == null ? shownActiveId != null : !active.equals(shownActiveId);
            shownActiveId = active;
            if (!changed) {
                return;
            }
            if (manual && !activeVisible()) {
                manual = false;
            }
            centerOnActive(animate);
        }

        /** Breiten aus der verfügbaren Breite: in schmalen Fenstern schmalere Wolken, weniger Nachbarn (FA-14). */
        void layoutChips() {
            int width = getWidth();
            if (width == laidOutWidth && chips.size() == sections.size()) {
                return;
            }
            boolean resized = laidOutWidth >= 0 && laidOutWidth != width;
            laidOutWidth = width;
            chips.clear();
            texts.clear();
            if (width <= 0) {
                return;
            }
            int maxChip = Math.max(80, Math.min(240, (int) (width * 0.42)));
            int height = ResearchUiMetrics.CLOUD_CHIP_HEIGHT;
            int x = 0;
            for (Navigation.Section section : sections) {
                FontMetrics metrics = getFontMetrics(fontFor(section));
                int pad = ResearchUiMetrics.CLOUD_CHIP_PADDING_H;
                String text = elide(section.label(), metrics, maxChip - 2 * pad);
                int chipWidth = Math.min(maxChip, metrics.stringWidth(text) + 2 * pad);
                chips.add(new Rectangle(x, top(), chipWidth, height));
                texts.add(text);
                x += chipWidth + ResearchUiMetrics.CLOUD_GAP_H;
            }
            if (pendingCenter || (resized && !manual)) {
                centerOnActive(false);
            }
        }

        /** Rand links: so viel, dass der erste Eintrag mittig stehen kann. */
        private int lead() {
            return chips.isEmpty() ? 0 : getWidth() / 2 - chips.get(0).width / 2;
        }

        private int totalWidth() {
            if (chips.isEmpty()) {
                return 0;
            }
            Rectangle last = chips.get(chips.size() - 1);
            return last.x + last.width;
        }

        private double minOffset() {
            // A sub row may also sit wholly inside the row, flush right, for example below a parent at the right edge.
            return depth == 0 || chips.isEmpty() ? 0 : Math.min(0, lead() - (getWidth() - totalWidth()));
        }

        private double maxOffset() {
            if (chips.isEmpty()) {
                return 0;
            }
            Rectangle last = chips.get(chips.size() - 1);
            // Rand rechts entsprechend: der letzte Eintrag kann ebenfalls mittig stehen.
            double centerLast = last.x + last.width / 2.0 + lead() - getWidth() / 2.0;
            return depth == 0 ? Math.max(0, centerLast) : Math.max(lead(), centerLast);
        }

        private double clamp(double value) {
            return Math.max(minOffset(), Math.min(maxOffset(), value));
        }

        /** X of the content origin inside this row for the current offset. */
        private double shift() {
            return lead() - offset;
        }

        /** Center of the active cloud in row coordinates, or {@code -1}. */
        double activeCenterX() {
            layoutChips();
            int index = indexOf(activeId());
            if (index < 0) {
                return -1;
            }
            Rectangle chip = chips.get(index);
            return shift() + chip.x + chip.width / 2.0;
        }

        private int indexOf(String id) {
            for (int i = 0; i < sections.size() && i < chips.size(); i++) {
                if (sections.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        private boolean activeVisible() {
            int index = indexOf(activeId());
            if (index < 0) {
                return true;
            }
            Rectangle chip = chips.get(index);
            double x = shift() + chip.x;
            return x >= 0 && x + chip.width <= getWidth();
        }

        double targetOffsetOf(String id) {
            int index = indexOf(id);
            if (depth == 0) {
                if (index < 0) {
                    return offset;
                }
                Rectangle chip = chips.get(index);
                // Versatz so, dass die Mitte der Wolke in der Mitte der Reihe steht.
                return clamp(lead() + chip.x + chip.width / 2.0 - getWidth() / 2.0);
            }
            return clamp(lead() - targetShift(index));
        }

        /**
         * Where a sub row starts: a row that fits stands as a group below the parent cloud; a wider row puts its
         * active entry in the middle, or without one the group again below the parent.
         */
        private double targetShift(int activeIndex) {
            int width = getWidth();
            int total = totalWidth();
            double anchor = anchorX();
            if (anchor < 0) {
                anchor = width / 2.0;
            }
            if (total <= width) {
                return Math.max(0, Math.min(width - total, anchor - total / 2.0));
            }
            if (activeIndex >= 0) {
                Rectangle chip = chips.get(activeIndex);
                return width / 2.0 - (chip.x + chip.width / 2.0);
            }
            return anchor - total / 2.0;
        }

        /** Center of the parent cloud in the row above, in row coordinates, or {@code -1}. */
        double anchorX() {
            SectionStrip parent = parentStrip(depth);
            return parent == null ? -1 : parent.activeCenterX();
        }

        void centerOnActive(boolean animate) {
            if (manual || (depth == 0 && activeChapterId == null)) {
                return;
            }
            if (getWidth() <= 0 || chips.isEmpty()) {
                pendingCenter = true;
                return;
            }
            pendingCenter = false;
            target = depth == 0 ? targetOffsetOf(activeChapterId) : targetOffsetOf(activeId());
            if (!animate || !isShowing()) {
                offset = target;
                animation.stop();
                repaintSky();
            } else if (!animation.isRunning()) {
                animation.start();
            }
        }

        private void step() {
            double delta = target - offset;
            if (Math.abs(delta) < 0.75) {
                offset = target;
                animation.stop();
            } else {
                offset += delta * 0.25;
            }
            repaintSky();
        }

        void wheel(MouseWheelEvent event) {
            double rotation = event.getPreciseWheelRotation();
            if (rotation == 0) {
                rotation = event.getWheelRotation();
            }
            layoutChips();
            animation.stop();
            pendingCenter = false;
            manual = true;
            offset = clamp(offset + rotation * WHEEL_STEP);
            target = offset;
            event.consume(); // nie zugleich das Dokument rollen
            repaintSky();
        }

        /** Repaint this row and the link of the row below, which follows the active cloud of this one. */
        private void repaintSky() {
            repaint();
            SectionStrip child = rowAt(depth + 1);
            if (child != null) {
                child.repaint();
            }
        }

        void select(int index) {
            if (index < 0 || index >= sections.size()) {
                return;
            }
            Navigation.Section section = sections.get(index);
            if (depth == 0) {
                setActive(section.id(), null);
            } else {
                setActive(activeChapterId, section.id());
            }
            // Settle the rows now: the jump must land below the banner as tall as it is after this click.
            DocumentNavigationSky.this.doLayout();
            fireSelected(section);
        }

        String chipText(int index) {
            return index >= 0 && index < texts.size() ? texts.get(index) : null;
        }

        private int indexAt(int x, int y) {
            if (y < top()) {
                return -1;
            }
            double contentX = x - shift();
            for (int i = 0; i < chips.size(); i++) {
                Rectangle chip = chips.get(i);
                if (contentX >= chip.x && contentX < chip.x + chip.width) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = indexAt(event.getX(), event.getY());
            return index < 0 || index >= sections.size() ? null : tooltipFor(sections.get(index));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            layoutChips();
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                paintLink(g2);
                int shift = (int) Math.round(shift());
                String active = activeId();
                for (int i = 0; i < chips.size() && i < sections.size(); i++) {
                    Rectangle chip = chips.get(i);
                    int x = chip.x + shift;
                    if (x + chip.width < 0 || x > getWidth()) {
                        continue;
                    }
                    Graphics2D cloud = (Graphics2D) g2.create(x, chip.y, chip.width, chip.height);
                    try {
                        Navigation.Section section = sections.get(i);
                        paintCloud(cloud, section, texts.get(i), chip.width, chip.height, i == hovered,
                                section.id().equals(active));
                    } finally {
                        cloud.dispose();
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        /** A short, quiet stroke from the parent cloud down to this row; no tree connector. */
        private void paintLink(Graphics2D g2) {
            if (depth == 0) {
                return;
            }
            double anchor = anchorX();
            if (anchor < 0 || anchor > getWidth()) {
                return;
            }
            int x = (int) Math.round(anchor);
            g2.setColor(ResearchUiPalette.CLOUD_BORDER);
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x, 1, x, top() - 1);
        }
    }

    /** The sub rows of the expanded sky: one row per node on the active path that has children. */
    private final class HierarchyRows extends JPanel implements Scrollable {

        private final List<SectionStrip> rows = new ArrayList<SectionStrip>();

        HierarchyRows() {
            super(null);
            setOpaque(false);
            setName("reader.navigationRows");
        }

        /** Match the rows to the active path; rows whose parent stays keep their scroll position. */
        void sync(boolean animate) {
            List<Navigation.Section> parents = new ArrayList<Navigation.Section>();
            if (expanded) {
                List<Navigation.Section> path = activePath();
                for (Navigation.Section node : path) {
                    if (node.children().isEmpty()) {
                        break;
                    }
                    parents.add(node);
                }
            }
            boolean changed = rows.size() != parents.size();
            for (int i = 0; i < parents.size(); i++) {
                Navigation.Section parent = parents.get(i);
                if (i < rows.size()) {
                    SectionStrip row = rows.get(i);
                    boolean same = parent.id().equals(row.parentId);
                    row.show(parent.id(), parent.children(), animate);
                    changed |= !same;
                } else {
                    SectionStrip row = new SectionStrip(i + 1, parent.id(), parent.children());
                    row.shownActiveId = row.activeId();
                    row.pendingCenter = true;
                    rows.add(row);
                    add(row);
                }
            }
            while (rows.size() > parents.size()) {
                SectionStrip row = rows.remove(rows.size() - 1);
                row.animation.stop();
                remove(row);
            }
            if (changed) {
                revealDeepestRow = true;
                revalidate();
            }
            repaint();
        }

        int rowCount() {
            return rows.size();
        }

        @Override
        public void doLayout() {
            int y = 0;
            for (SectionStrip row : rows) {
                row.setBounds(0, y, getWidth(), ROW_HEIGHT);
                row.layoutChips();
                y += ROW_HEIGHT;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Math.max(1, getWidth()), rows.size() * ROW_HEIGHT);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return ROW_HEIGHT / 2;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ------------------------------------------------------------------ Aufklappen

    /** Der kleine Pfeil links: klappt die Gliederung des aktiven Kapitels auf und zu. */
    private final class ExpandChevron extends JComponent {

        private boolean hovered;

        ExpandChevron() {
            setName("reader.navigationExpand");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    setExpanded(!expanded);
                }
            });
        }

        @Override
        public String getToolTipText() {
            return expanded ? "Unterabschnitte einklappen" : "Unterabschnitte des Kapitels zeigen";
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                if (hovered) {
                    g2.setColor(ResearchUiPainter.mix(ResearchUiPalette.SKY_BAR_SURFACE, java.awt.Color.WHITE,
                            0.35f));
                    g2.fillOval(0, 0, getWidth(), getHeight());
                }
                java.awt.Color color = hovered ? ResearchUiPalette.CLOUD_TEXT : ResearchUiPalette.SKY_CAPTION;
                if (expanded) {
                    ResearchUiPainter.paintChevronUp(g2, getWidth() / 2, getHeight() / 2, 5, color);
                } else {
                    ResearchUiPainter.paintChevronDown(g2, getWidth() / 2, getHeight() / 2, 5, color);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Tooltips für Komponenten, die {@code getToolTipText(MouseEvent)} je Stelle beantworten. */
    private static final class ToolTipSupport {
        static void register(JComponent component) {
            javax.swing.ToolTipManager.sharedInstance().registerComponent(component);
        }
    }
}
