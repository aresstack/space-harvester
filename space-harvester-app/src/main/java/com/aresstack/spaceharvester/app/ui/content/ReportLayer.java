package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Objects;

/**
 * Der Bericht einer Zusammenfassung mit seinen Ebenen (ADR 0034, Paket 5a): unten der Inhalt (Bildlauf samt
 * BusyOverlay), darüber rechts oben die Kugel des Podcasts neben der Bildlaufleiste und, nur wo es Updates gibt, die
 * Wolkenreihe als durchsichtiger Himmel wie über dem Lesetext der Seiten ({@link NavigableDocumentPage}). Mit Himmel
 * bekommt der Inhalt oben so viel Platz, wie die Reihe meldet, und die Kugel steht auf Höhe der Wolken.
 */
final class ReportLayer extends JLayeredPane {

    /** Abstand des Podcast-Knopfs zum Rand, wie der Vorlese-Orb im Enterprise AI Client. */
    static final int ORB_INSET = 10;

    private final JComponent content;
    private final JComponent orb;
    private final JScrollPane scroll;
    private DocumentNavigationSky sky;

    /**
     * @param content der Inhalt, etwa der Bildlauf im BusyOverlay
     * @param orb     die Kugel des Podcasts, {@code null} ohne
     * @param scroll  der Bildlauf des Inhalts; die Kugel bleibt links neben seiner Leiste
     */
    ReportLayer(JComponent content, JComponent orb, JScrollPane scroll) {
        this.content = Objects.requireNonNull(content, "content");
        this.orb = orb;
        this.scroll = Objects.requireNonNull(scroll, "scroll");
        setLayout(null);
        add(content, JLayeredPane.DEFAULT_LAYER);
        if (orb != null) {
            add(orb, JLayeredPane.MODAL_LAYER);
            // Bildlaufleiste erscheint oder verschwindet: der Knopf bleibt links daneben.
            scroll.getViewport().addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    doLayout();
                }
            });
        }
    }

    /**
     * Legt den Himmel über den Inhalt.
     *
     * @param padded bekommt oben den Abstand, den die Wolkenreihe meldet
     */
    ReportLayer sky(DocumentNavigationSky sky, JComponent padded) {
        this.sky = Objects.requireNonNull(sky, "sky");
        add(sky, JLayeredPane.PALETTE_LAYER);
        sky.addPropertyChangeListener(DocumentNavigationSky.TOP_INSET_PROPERTY, event -> {
            Object value = sky.getClientProperty(DocumentNavigationSky.TOP_INSET_PROPERTY);
            int inset = value instanceof Integer pixels ? Math.max(0, pixels) : 0;
            padded.setBorder(BorderFactory.createEmptyBorder(inset, 0, 0, 0));
            padded.revalidate();
        });
        return this;
    }

    @Override
    public void doLayout() {
        content.setBounds(0, 0, getWidth(), getHeight());
        if (sky != null) {
            sky.setBounds(0, 0, getWidth(), getHeight());
        }
        if (orb != null) {
            int bar = scroll.getVerticalScrollBar().isVisible() ? scroll.getVerticalScrollBar().getWidth() : 0;
            int top = sky == null ? ORB_INSET : ResearchUiMetrics.SKY_PADDING_TOP
                    + (ResearchUiMetrics.CLOUD_CHIP_HEIGHT - PodcastOrb.SIZE) / 2;
            orb.setBounds(getWidth() - bar - ORB_INSET - PodcastOrb.SIZE, Math.max(2, top), PodcastOrb.SIZE,
                    PodcastOrb.SIZE);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return content.getPreferredSize();
    }
}
