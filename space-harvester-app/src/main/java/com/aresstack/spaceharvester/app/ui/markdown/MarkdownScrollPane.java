package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;
import com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;

/** Display the complete block height and scroll only horizontally when necessary. */
class MarkdownScrollPane extends JScrollPane implements WidthAwareHeight {

    private final int preferredWidth;
    private final int extraHeight;

    MarkdownScrollPane(Component view, int preferredWidth, int extraHeight, MarkdownTheme theme) {
        super(view);
        this.preferredWidth = preferredWidth;
        this.extraHeight = extraHeight;
        ComicScrollBarUI.install(getHorizontalScrollBar(), theme.getMutedForeground(), theme.getForeground());
        setOpaque(false);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_NEVER);
        NestedScrollWheelRouter.install(this);
    }

    @Override
    public int preferredHeightForWidth(int width) {
        int height = contentHeight();
        if (needsHorizontalBar(width)) {
            height += getHorizontalScrollBar().getPreferredSize().height;
        }
        return height;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        return new Dimension(preferredWidth, preferredHeightForWidth(width));
    }

    private int contentHeight() {
        Component view = getViewport().getView();
        int content = view == null ? 0 : view.getPreferredSize().height;
        JViewport columnHeader = getColumnHeader();
        if (columnHeader != null && columnHeader.getView() != null) {
            content += columnHeader.getView().getPreferredSize().height;
        }
        Insets insets = getInsets();
        return content + extraHeight + insets.top + insets.bottom;
    }

    private boolean needsHorizontalBar(int width) {
        Component view = getViewport().getView();
        if (view == null || width <= 0) {
            return false;
        }
        Insets insets = getInsets();
        return view.getPreferredSize().width > width - insets.left - insets.right;
    }
}
