package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.deck.editor.TextSearch;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;

/**
 * Zeilenfilter wie {@code RegexFoldParser} in MainframeMate: alle Zeilen ohne Treffer werden zugeklappt, die erste
 * Zeile bleibt sichtbar. Ohne Ausdruck oder ohne Treffer bleibt der Text offen.
 */
final class RegexFolds {

    private RegexFolds() {
    }

    /** Klappt die Zeilen ohne Treffer zu; gibt die Zahl der Zeilen mit Treffer zurück. */
    static int apply(RSyntaxTextArea area, String regex) {
        List<Integer> hits = TextSearch.matchingLines(area.getText(), regex);
        List<Fold> folds = new ArrayList<>();
        if (!hits.isEmpty()) {
            try {
                int lineCount = area.getLineCount();
                int lastVisible = 0;
                for (int hit : hits) {
                    if (hit - lastVisible > 1) {
                        fold(area, folds, lastVisible, hit - 2);
                    }
                    lastVisible = hit;
                }
                if (lastVisible < lineCount - 1) {
                    fold(area, folds, lastVisible, lineCount - 2);
                }
            } catch (BadLocationException e) {
                folds.clear();
            }
        }
        area.getFoldManager().setFolds(folds);
        area.revalidate();
        area.repaint();
        return hits.size();
    }

    /** Nimmt den Filter weg: Faltungen wieder nach der Sprache. */
    static void clear(RSyntaxTextArea area) {
        area.getFoldManager().setFolds(new ArrayList<>());
        area.getFoldManager().reparse();
        area.revalidate();
        area.repaint();
    }

    private static void fold(RSyntaxTextArea area, List<Fold> folds, int startLine, int endLine)
            throws BadLocationException {
        if (startLine > endLine) {
            return;
        }
        Fold fold = new Fold(FoldType.CODE, area, area.getLineStartOffset(startLine));
        fold.setEndOffset(area.getLineEndOffset(endLine));
        fold.setCollapsed(true);
        folds.add(fold);
    }
}
