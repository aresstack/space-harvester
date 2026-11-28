package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Draw the familiar four-corner full-screen and restore symbols. */
final class VideoFullScreenIcon implements Icon {

    private static final int SIZE = 17;
    private final boolean restore;

    VideoFullScreenIcon(boolean restore) {
        this.restore = restore;
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.translate(x, y);
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paint.setColor(component.isEnabled() ? AppColors.of("app.VideoFullScreenIcon.glyph") : AppColors.of("app.VideoFullScreenIcon.disabledGlyph"));
            if (restore) {
                paint.drawLine(6, 2, 6, 6);
                paint.drawLine(2, 6, 6, 6);
                paint.drawLine(11, 2, 11, 6);
                paint.drawLine(11, 6, 15, 6);
                paint.drawLine(2, 11, 6, 11);
                paint.drawLine(6, 11, 6, 15);
                paint.drawLine(11, 11, 15, 11);
                paint.drawLine(11, 11, 11, 15);
            } else {
                paint.drawLine(2, 6, 2, 2);
                paint.drawLine(2, 2, 6, 2);
                paint.drawLine(11, 2, 15, 2);
                paint.drawLine(15, 2, 15, 6);
                paint.drawLine(2, 11, 2, 15);
                paint.drawLine(2, 15, 6, 15);
                paint.drawLine(11, 15, 15, 15);
                paint.drawLine(15, 11, 15, 15);
            }
        } finally {
            paint.dispose();
        }
    }
}
