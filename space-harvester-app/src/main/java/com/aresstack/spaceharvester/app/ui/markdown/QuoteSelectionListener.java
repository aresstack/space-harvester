package com.aresstack.spaceharvester.app.ui.markdown;

/** Empfängt Textauswahlen einer {@link MarkdownMessageView} (Zitieren); gerufen auf dem Swing-EDT. */
public interface QuoteSelectionListener {

    /** Der Nutzer hat in einem Block Text markiert und die Maustaste losgelassen. */
    void selectionMade(QuoteSelection selection);

    /** Es gibt keine Markierung mehr (Klick ohne Auswahl, Auswahl aufgehoben). */
    void selectionCleared();
}
