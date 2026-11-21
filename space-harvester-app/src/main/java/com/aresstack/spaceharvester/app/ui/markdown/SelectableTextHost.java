package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.text.JTextComponent;
import java.util.List;

/**
 * A rendered block whose visible text lives in text components that are not permanently part of the component tree
 * (for example table cells, which are inserted only while the user selects in them). {@link MarkdownMessageView}
 * watches these components like any other text so that selecting and quoting work the same for every block type.
 */
interface SelectableTextHost {

    /** Return every text component of the block whose selection can be quoted. */
    List<JTextComponent> selectableTexts();
}
