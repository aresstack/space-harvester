package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.enrichment.PageText;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

/**
 * Der Lesetext einer Seite mit den Akronymen aus dem Core-Ergebnis {@code acronyms} (#115). Markiert wird nur, was
 * {@link PageAcronyms} für genau diesen Text liefert; die App erkennt selbst nichts. Wird außerhalb des EDT gebaut und
 * dort nur noch angezeigt. Der Text bleibt unverändert und selektierbar; Ausschreibungen sind reiner Text.
 */
final class ReaderDocument {

    static final String NO_PREVIEW = "Für dieses Format gibt es keine Lesevorschau. Die Originaldaten zeigen den "
            + "vollständigen Inhalt.";

    private static final Color UNKNOWN_MARK = new Color(0x9E, 0x9E, 0x9E, 60);

    private final StyledDocument document;
    private final PageText text;
    private final PageAcronyms acronyms;
    private final List<PageAcronyms.Mark> marks;

    private ReaderDocument(StyledDocument document, PageText text, PageAcronyms acronyms) {
        this.document = document;
        this.text = text;
        this.acronyms = acronyms;
        this.marks = acronyms.marks();
    }

    /**
     * Baut den Lesetext.
     *
     * @param text     Projektion der angezeigten Payload oder {@code null}, wenn es keine gibt
     * @param acronyms das Core-Ergebnis für genau diesen Text ({@link PageAcronyms#read})
     * @param font     Grundschrift des Lesebereichs
     */
    static ReaderDocument build(PageText text, PageAcronyms acronyms, Font font) {
        StyleContext styles = new StyleContext();
        Style base = styles.getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(base, font.getFamily());
        StyleConstants.setFontSize(base, font.getSize());
        DefaultStyledDocument document = new DefaultStyledDocument(styles);
        try {
            if (text == null) {
                document.insertString(0, NO_PREVIEW, null);
                return new ReaderDocument(document, null, acronyms);
            }
            document.insertString(0, text.text(), null);
            for (PageText.Section section : text.sections()) {
                SimpleAttributeSet heading = new SimpleAttributeSet();
                StyleConstants.setBold(heading, true);
                StyleConstants.setFontSize(heading, Math.max(14, 22 - 2 * section.level()));
                StyleConstants.setForeground(heading, Comic.INK);
                document.setCharacterAttributes(section.start(), Math.min(section.heading().length(),
                        document.getLength() - section.start()), heading, false);
            }
            SimpleAttributeSet known = mark(ResearchUiPalette.ACRONYM_MARK);
            SimpleAttributeSet unknown = mark(UNKNOWN_MARK);
            for (PageAcronyms.Mark mark : acronyms.marks()) {
                PageAcronyms.Occurrence occurrence = mark.occurrence();
                document.setCharacterAttributes(occurrence.start(), occurrence.end() - occurrence.start(),
                        mark.acronym().expansion() == null ? unknown : known, false);
            }
        } catch (BadLocationException e) {
            // Abschnitte und Fundstellen stammen aus demselben Text (PageAcronyms prüft den Eingabe-Hash).
        }
        return new ReaderDocument(document, text, acronyms);
    }

    StyledDocument document() {
        return document;
    }

    PageText text() {
        return text;
    }

    PageAcronyms acronyms() {
        return acronyms;
    }

    /** Die markierten Fundstellen in Textreihenfolge. */
    List<PageAcronyms.Mark> marks() {
        return marks;
    }

    /** Die Fundstelle, die {@code offset} enthält, oder {@code null}. */
    PageAcronyms.Mark markAt(int offset) {
        int low = 0;
        int high = marks.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            PageAcronyms.Occurrence occurrence = marks.get(middle).occurrence();
            if (offset < occurrence.start()) {
                high = middle - 1;
            } else if (offset >= occurrence.end()) {
                low = middle + 1;
            } else {
                return marks.get(middle);
            }
        }
        return null;
    }

    /** Die erste Fundstelle, die nach {@code offset} beginnt, oder {@code null}. */
    PageAcronyms.Mark next(int offset) {
        for (PageAcronyms.Mark mark : marks) {
            if (mark.occurrence().start() > offset) {
                return mark;
            }
        }
        return null;
    }

    /** Die letzte Fundstelle, die vor {@code offset} beginnt, oder {@code null}. */
    PageAcronyms.Mark previous(int offset) {
        PageAcronyms.Mark found = null;
        for (PageAcronyms.Mark mark : marks) {
            if (mark.occurrence().start() >= offset) {
                break;
            }
            found = mark;
        }
        return found;
    }

    /** Kurztext für den Tooltip; beginnt nie mit {@code <html>}, die Ausschreibung bleibt reiner Text. */
    static String tooltip(PageAcronyms.Acronym acronym) {
        return acronym.acronym() + ": " + (acronym.expansion() == null
                ? "Ausschreibung unbekannt (steht nicht auf dieser Seite)" : acronym.expansion());
    }

    /** Ausführliche Zeile unter dem Lesetext. */
    static String detail(PageAcronyms.Acronym acronym) {
        return tooltip(acronym) + " · " + acronym.count() + "× auf dieser Seite"
                + (acronym.section() == null ? "" : " · zuerst in „" + acronym.section() + "“");
    }

    /**
     * Hinweis über dem Lesetext: woher die Markierung kommt oder warum es keine gibt.
     *
     * @param live ob die angezeigte Fassung live geladen wurde und nicht im Bestand liegt
     */
    String hint(boolean live) {
        if (text == null) {
            return "Ohne Lesevorschau keine Akronyme.";
        }
        String source = acronyms.result() == null ? "" : " · Core-Analyse „" + acronyms.result().processorId()
                + "“ Version " + acronyms.result().processorVersion();
        if (live && acronyms.state() != PageAcronyms.State.CURRENT) {
            return "Die angezeigte Live-Fassung ist noch nicht analysiert; Akronyme werden nicht markiert. Die "
                    + "Analyse folgt mit dem nächsten Abgleich.";
        }
        return switch (acronyms.state()) {
            case CURRENT -> {
                if (acronyms.acronyms().isEmpty()) {
                    yield "Keine Akronyme gefunden" + source;
                }
                long known = acronyms.acronyms().stream().filter(a -> a.expansion() != null).count();
                yield acronyms.acronyms().size() + " Akronyme (" + known + " mit Ausschreibung), " + marks.size()
                        + " Fundstellen markiert" + source + " · Alt+→/Alt+← springt"
                        + (acronyms.warnings().isEmpty() ? "" : " · " + String.join("; ", acronyms.warnings()));
            }
            case STALE -> "Die Akronym-Analyse gehört zu einer anderen Fassung oder Prozessorversion und wird nicht "
                    + "markiert. „Analyse neu berechnen“ aktualisiert sie.";
            case FAILED -> "Die Akronym-Analyse ist fehlgeschlagen; nichts markiert. „Analyse neu berechnen“ "
                    + "versucht es erneut.";
            case MISSING -> "Für diese Seite gibt es noch keine Akronym-Analyse. Sie entsteht beim Abgleich oder über "
                    + "„Analyse neu berechnen“.";
        };
    }

    private static SimpleAttributeSet mark(Color background) {
        SimpleAttributeSet mark = new SimpleAttributeSet();
        StyleConstants.setUnderline(mark, true);
        StyleConstants.setBackground(mark, background);
        return mark;
    }
}
