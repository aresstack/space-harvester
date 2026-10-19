package com.aresstack.spaceharvester.app.ui.content;

import javax.swing.JLayeredPane;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Dokument im Lesebereich mit dem Navigationsbanner darüber: die Bildlauffläche liegt unten, der
 * {@link DocumentNavigationSky} als durchsichtige Ebene darüber (wie der Himmel über dem Chatverlauf). Der Banner
 * verkleinert die Breite des Dokuments nie (FA-07); oben bekommt das Dokument so viel Platz, wie der Banner meldet.
 *
 * <p>Quellpositionen (FA-05) werden auf die Ansicht abgebildet: die Startposition ist eine Zeichenposition im
 * angezeigten Klartext, ihre Zeile liefert {@link JTextComponent#modelToView2D(int)}; passt der Text an der Stelle
 * nicht zum Anker, sucht die Seite den Anker im ganzen Text. Beim Rollen meldet die Seite dem Banner das Kapitel und
 * den tiefsten Abschnitt an der Oberkante unter dem Banner (FA-08); ein Klick im Banner rollt so, dass der Abschnitt
 * direkt unter dem Banner steht (FA-10, FA-12). Das Dokument selbst wird nie verändert.</p>
 *
 * <p>Kopie von {@code NavigableDocumentPage} aus dem Enterprise AI Client; dort ist die Seite eine Markdown- oder
 * HTML-Ansicht mit Blöcken, hier der Lesetext ({@code JTextPane}) selbst, der in der Bildlauffläche liegt.</p>
 */
final class NavigableDocumentPage extends JLayeredPane {

    /** Abstand unter dem Banner, an dem ein angesprungener Abschnitt steht und ab dem er als aktiv gilt. */
    static final int TARGET_GAP = 8;

    private final JScrollPane scroll;
    /** Der Lesetext; zugleich die Seite in der Bildlauffläche. */
    private final JTextComponent page;
    private final int basePaddingTop;
    private final DocumentNavigationSky sky = new DocumentNavigationSky();
    /** Abschnitt → y in Seitenkoordinaten; leer = neu berechnen. */
    private final Map<String, Integer> positions = new LinkedHashMap<String, Integer>();
    /** Größe der Seite, für die {@link #positions} gemessen wurden. */
    private java.awt.Dimension positionsSize;
    private int topInset;
    /** Zuletzt angesprungener Abschnitt und die Rollposition danach. */
    private Navigation.Section jumped;
    private int jumpedViewY = -1;

    /**
     * @param page           der Lesetext in der Bildlauffläche
     * @param basePaddingTop oberer Innenabstand des Lesetexts ohne Banner
     */
    NavigableDocumentPage(JScrollPane scroll, JTextComponent page, int basePaddingTop) {
        this.scroll = scroll;
        this.page = page;
        this.basePaddingTop = basePaddingTop;
        setLayout(null);
        add(scroll, JLayeredPane.DEFAULT_LAYER);
        add(sky, JLayeredPane.PALETTE_LAYER);
        sky.addPropertyChangeListener(DocumentNavigationSky.TOP_INSET_PROPERTY, event -> applyTopInset());
        sky.setListener(this::scrollTo);
        scroll.getViewport().addChangeListener(event -> syncActive());
        page.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                syncActive(); // Umbruch oder Inhalt geändert: positionOf misst neu
            }
        });
    }

    DocumentNavigationSky sky() {
        return sky;
    }

    JScrollPane scroll() {
        return scroll;
    }

    void setNavigation(Navigation navigation) {
        positions.clear();
        jumped = null;
        sky.setNavigation(navigation);
        syncActive();
    }

    /** Der Lesetext hat einen neuen Inhalt bekommen (gleiche Größe möglich): Positionen neu messen. */
    void contentChanged() {
        positions.clear();
        positionsSize = null;
        jumped = null;
        syncActive();
    }

    @Override
    public void doLayout() {
        scroll.setBounds(0, 0, getWidth(), getHeight());
        sky.setBounds(0, 0, getWidth(), getHeight());
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        return scroll.getPreferredSize();
    }

    private void applyTopInset() {
        Object value = sky.getClientProperty(DocumentNavigationSky.TOP_INSET_PROPERTY);
        int inset = value instanceof Integer ? Math.max(0, (Integer) value) : 0;
        if (inset == topInset) {
            return;
        }
        topInset = inset;
        java.awt.Insets insets = page.getInsets();
        page.setBorder(javax.swing.BorderFactory.createEmptyBorder(basePaddingTop + topInset, insets.left,
                insets.bottom, insets.right));
        positions.clear();
        page.revalidate();
    }

    // ------------------------------------------------------------------ Springen und Mitlaufen

    /** Rollt so, dass der Abschnitt direkt unter dem Banner steht. */
    void scrollTo(Navigation.Section section) {
        if (!page.isValid()) {
            // Lay out first when the banner just changed its height: the top padding moves every heading. Lay out the
            // viewport and the page directly as well, validate() skips a tree that is not displayable yet.
            scroll.validate();
            scroll.getViewport().doLayout();
            page.doLayout();
        }
        Integer y = positionOf(section);
        if (y == null) {
            return;
        }
        JViewport viewport = scroll.getViewport();
        int target = y - sky.coveredHeight() - TARGET_GAP;
        int max = Math.max(0, page.getHeight() - viewport.getExtentSize().height);
        Point to = new Point(0, Math.max(0, Math.min(max, target)));
        jumped = section;
        jumpedViewY = to.y;
        viewport.setViewPosition(to);
        syncActive();
    }

    /**
     * Treffer unter dem Wolkenbanner der Dokumentnavigation anzeigen, nicht dahinter: steht die Zeile von
     * {@code offset} nach dem Markieren hinter dem Banner, rollt die Seite sie direkt darunter.
     */
    void revealBelowSky(int offset) {
        int covered = sky.coveredHeight();
        if (covered <= 0 || offset < 0 || offset > page.getDocument().getLength()) {
            return;
        }
        Rectangle rect = rectOf(offset);
        if (rect == null) {
            return;
        }
        JViewport viewport = scroll.getViewport();
        int top = rect.y - viewport.getViewPosition().y;
        if (top >= covered && top < viewport.getExtentSize().height) {
            return;
        }
        int max = Math.max(0, page.getHeight() - viewport.getExtentSize().height);
        int target = rect.y - covered - TARGET_GAP;
        viewport.setViewPosition(new Point(viewport.getViewPosition().x, Math.max(0, Math.min(max, target))));
    }

    /** Kapitel und tiefster Abschnitt an der Oberkante unter dem Banner. */
    void syncActive() {
        Navigation navigation = sky.navigation();
        if (navigation == null || navigation.isEmpty() || page.getHeight() <= 0) {
            return;
        }
        int line = scroll.getViewport().getViewPosition().y + sky.coveredHeight() + TARGET_GAP + 4;
        String chapterId = navigation.chapters().get(0).id();
        String sectionId = null;
        for (Navigation.Section chapter : navigation.chapters()) {
            Integer y = positionOf(chapter);
            if (y != null && y <= line) {
                chapterId = chapter.id();
            }
        }
        Navigation.Section active = navigation.find(chapterId);
        if (active != null) {
            for (Navigation.Section section : descendants(active)) {
                Integer y = positionOf(section);
                if (y != null && y <= line) {
                    sectionId = section.id();
                }
            }
        }
        if (jumped != null && scroll.getViewport().getViewPosition().y == jumpedViewY) {
            // Nach einem Sprung gilt das gewählte Ziel, auch wenn es die Oberkante nicht erreichen kann (Dokumentende),
            // bis wieder gerollt wird.
            Navigation.Section chapter = chapterOf(navigation, jumped);
            if (chapter != null) {
                chapterId = chapter.id();
                sectionId = chapter.id().equals(jumped.id()) ? null : jumped.id();
            }
        }
        sky.setActive(chapterId, sectionId);
    }

    private static Navigation.Section chapterOf(Navigation navigation, Navigation.Section section) {
        for (Navigation.Section chapter : navigation.chapters()) {
            if (chapter.id().equals(section.id()) || descendants(chapter).contains(section)) {
                return chapter;
            }
        }
        return null;
    }

    private static List<Navigation.Section> descendants(Navigation.Section chapter) {
        List<Navigation.Section> all = new ArrayList<Navigation.Section>();
        collect(chapter.children(), all);
        return all;
    }

    private static void collect(List<Navigation.Section> sections, List<Navigation.Section> into) {
        for (Navigation.Section section : sections) {
            into.add(section);
            collect(section.children(), into);
        }
    }

    /** y des Abschnitts in Seitenkoordinaten oder {@code null}, wenn die Seite noch nicht gelegt ist. */
    Integer positionOf(Navigation.Section section) {
        if (page.getWidth() <= 0) {
            return null;
        }
        if (!page.getSize().equals(positionsSize)) {
            positions.clear(); // anderer Umbruch: neu messen
            positionsSize = page.getSize();
        }
        Integer known = positions.get(section.id());
        if (known == null) {
            known = locate(section);
            if (page.isValid()) {
                positions.put(section.id(), known); // nur fertig gelegten Text merken (Viewport meldet sich mitten im Layout)
            }
        }
        return known;
    }

    /** Zeile der Startposition; passt der Text dort nicht zum Anker, die Zeile des Ankers im ganzen Text. */
    private int locate(Navigation.Section section) {
        String content;
        try {
            content = page.getDocument().getText(0, page.getDocument().getLength());
        } catch (BadLocationException e) {
            return 0;
        }
        String anchor = normalize(section.anchor());
        int start = Math.min(section.start(), content.length());
        if (anchor.isEmpty() || startsWithAnchor(content, start, anchor)) {
            Rectangle rect = rectOf(start);
            return rect == null ? 0 : rect.y;
        }
        Integer y = anchorY(content, anchor);
        if (y != null) {
            return y;
        }
        Rectangle rect = rectOf(start);
        return rect == null ? 0 : rect.y;
    }

    private static boolean startsWithAnchor(String content, int start, String anchor) {
        String probe = anchor.length() > 24 ? anchor.substring(0, 24) : anchor;
        StringBuilder normalized = new StringBuilder();
        for (int i = start; i < content.length() && normalized.length() < probe.length(); i++) {
            char c = content.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString().equals(probe);
    }

    /** y der Zeile, in der der Anker im Lesetext beginnt. */
    private Integer anchorY(String content, String anchor) {
        String probe = anchor.length() > 24 ? anchor.substring(0, 24) : anchor;
        int[] map = new int[content.length()];
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                map[normalized.length()] = i;
                normalized.append(Character.toLowerCase(c));
            }
        }
        int hit = normalized.indexOf(probe);
        if (hit < 0) {
            return null;
        }
        Rectangle rect = rectOf(map[hit]);
        return rect == null ? null : rect.y;
    }

    private Rectangle rectOf(int offset) {
        try {
            Rectangle2D rect = page.modelToView2D(offset);
            return rect == null ? null : rect.getBounds();
        } catch (BadLocationException e) {
            return null;
        }
    }

    static String normalize(String value) {
        StringBuilder out = new StringBuilder();
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    out.append(Character.toLowerCase(c));
                }
            }
        }
        return out.toString();
    }
}
