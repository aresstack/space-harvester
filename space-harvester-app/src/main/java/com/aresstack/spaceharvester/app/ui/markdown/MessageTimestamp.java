package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleTimestamps;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Font;

/** Use one compact timestamp format in both message headers and footers. */
public final class MessageTimestamp extends JLabel {

    public MessageTimestamp(Color foreground) {
        setForeground(foreground);
        setFont(getFont().deriveFont(Font.PLAIN, Math.max(10f, getFont().getSize2D() - 2f)));
        setName("transcript.timestamp");
    }

    public void setTime(long epochMillis) {
        setText(BubbleTimestamps.timeText(epochMillis) + " · " + BubbleTimestamps.dateText(epochMillis));
        setToolTipText(BubbleTimestamps.tooltip(epochMillis));
    }
}
