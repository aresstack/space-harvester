package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleTimestamps;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Use one compact timestamp format in both message headers and footers. */
public final class MessageTimestamp extends JLabel {

    public MessageTimestamp(Color foreground) {
        setForeground(foreground);
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        float headerSize = Math.max(10f, base.getSize2D() - 1f);
        setFont(base.deriveFont(Font.PLAIN, Math.max(6f, headerSize * 0.5f)));
        setHorizontalAlignment(SwingConstants.RIGHT);
        setName("transcript.timestamp");
    }

    public void setTime(long epochMillis) {
        setText(BubbleTimestamps.stackedHtml(epochMillis));
        setToolTipText(BubbleTimestamps.tooltip(epochMillis));
    }
}
