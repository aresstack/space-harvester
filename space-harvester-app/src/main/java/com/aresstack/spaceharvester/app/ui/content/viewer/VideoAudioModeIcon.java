package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Draw a compact headset glyph without relying on platform fonts. */
final class VideoAudioModeIcon implements Icon {

    private final boolean active;

    VideoAudioModeIcon(boolean active) {
        this.active = active;
    }

    @Override
    public int getIconWidth() {
        return 20;
    }

    @Override
    public int getIconHeight() {
        return 20;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.translate(x, y);
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setColor(active ? AppColors.of("app.VideoAudioModeIcon.paintIcon") : AppColors.of("app.VideoAudioModeIcon.paintIcon2"));
            canvas.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            canvas.drawArc(3, 2, 14, 14, 0, 180);
            canvas.drawRoundRect(2, 10, 4, 7, 2, 2);
            canvas.drawRoundRect(14, 10, 4, 7, 2, 2);
            if (active) {
                canvas.drawLine(7, 17, 13, 17);
            }
        } finally {
            canvas.dispose();
        }
    }
}
