package com.aresstack.enterpriseai.browser.api;

/** What {@link ChatBrowser#act} does with a control. */
public enum BrowserAction {
    /** Clicks a button, menu entry, tab, toggle, checkbox or other clickable element. */
    CLICK,
    /** Types a value into a text or search field, without submitting. */
    FILL,
    /** Types the value (if given) into a field and submits it like Enter: the site's own search. */
    SUBMIT,
    /** Chooses an option of a select box by its visible text or value. */
    SELECT
}
