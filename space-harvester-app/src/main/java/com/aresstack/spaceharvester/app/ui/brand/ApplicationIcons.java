package com.aresstack.spaceharvester.app.ui.brand;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.Window;
import java.awt.image.BaseMultiResolutionImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Das Anwendungssymbol (das freigestellte Raumschiff) für Fenster, Taskleiste und Alt+Tab, nach dem Vorbild von
 * {@code ApplicationIcons} im Enterprise AI Client. Windows nimmt das Taskleistensymbol aus dem Fenstersymbol und wählt
 * je DPI-Stufe die passende Größe; deshalb werden alle Größen gesetzt.
 */
public final class ApplicationIcons {

    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    static final int TITLE_SIZE = 24;

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationIcons.class);

    private ApplicationIcons() {
    }

    /** Alle Größen, klein nach groß. */
    public static List<Image> images() {
        List<Image> images = new ArrayList<>(SIZES.length);
        for (int size : SIZES) {
            images.add(BrandImages.icon(size));
        }
        return List.copyOf(images);
    }

    /** Setzt das Symbol auf das Fenster; vor {@code setVisible(true)} aufrufen. Ohne Bild bleibt das Java-Logo. */
    public static void apply(Window window) {
        try {
            window.setIconImages(images());
        } catch (RuntimeException e) {
            LOG.warn("Anwendungssymbol nicht gesetzt: {}", e.getMessage());
        }
    }

    /** Das kleine Logo für die eigene Titelleiste; bei 150 % oder 200 % Skalierung in voller Schärfe. */
    public static Icon titleLogo() {
        return new ImageIcon(new BaseMultiResolutionImage(BrandImages.icon(TITLE_SIZE),
                BrandImages.icon(TITLE_SIZE * 3 / 2), BrandImages.icon(TITLE_SIZE * 2)));
    }
}
