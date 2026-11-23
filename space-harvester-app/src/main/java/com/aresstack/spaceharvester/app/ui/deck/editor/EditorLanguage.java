package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax;
import java.util.List;
import java.util.Optional;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Eintrag der Sprachwahl unten rechts im Editor. In MainframeMate saß hier die Satzarten-Auswahl, deren Gruppe
 * „Sprachen“ Hervorhebung und Parser der Gliederung steuerte; übernommen ist nur diese Parser-Steuerung (Angelo,
 * 23.11.2026), die Satzarten selbst nicht.
 *
 * @param label  Anzeige
 * @param style  RSyntaxTextArea-Stil
 * @param parser Parser der Gliederung, leer ohne Gliederung
 */
public record EditorLanguage(String label, String style, Optional<SourceLanguage> parser) {

    /** „Automatisch“: an Endung und Inhalt erkennen (wie MainframeMate ohne gewählte Satzart). */
    public static final EditorLanguage AUTO = new EditorLanguage("Automatisch", null, Optional.empty());

    public static final List<EditorLanguage> ALL = List.of(
            AUTO,
            mainframe(SourceLanguage.JCL, MainframeSyntax.JCL),
            mainframe(SourceLanguage.COBOL, MainframeSyntax.COBOL),
            mainframe(SourceLanguage.NATURAL, MainframeSyntax.NATURAL),
            mainframe(SourceLanguage.DDM, MainframeSyntax.NATURAL),
            plain("Java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            plain("Python", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            plain("JavaScript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            plain("TypeScript", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            plain("JSON", SyntaxConstants.SYNTAX_STYLE_JSON),
            plain("XML", SyntaxConstants.SYNTAX_STYLE_XML),
            plain("HTML", SyntaxConstants.SYNTAX_STYLE_HTML),
            plain("SQL", SyntaxConstants.SYNTAX_STYLE_SQL),
            plain("YAML", SyntaxConstants.SYNTAX_STYLE_YAML),
            plain("Shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            plain("Batch", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            plain("Groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            plain("Markdown", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            plain("Properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            plain("Text", SyntaxConstants.SYNTAX_STYLE_NONE));

    static {
        MainframeSyntax.register();
    }

    /** Was „Automatisch“ für diese Datei bedeutet: Großrechner-Sprache an Endung oder Inhalt, sonst die Endung. */
    public static EditorLanguage detect(String name, String text) {
        Optional<SourceLanguage> mainframe = SourceLanguage.detect(name, text);
        if (mainframe.isPresent()) {
            return of(mainframe.get());
        }
        String style = SyntaxStyles.forExtension(extension(name));
        for (EditorLanguage language : ALL) {
            if (language != AUTO && style.equals(language.style())) {
                return language;
            }
        }
        return new EditorLanguage("Text", style, Optional.empty());
    }

    public static EditorLanguage of(SourceLanguage language) {
        return ALL.stream().filter(l -> l.parser().equals(Optional.of(language))).findFirst().orElseThrow();
    }

    @Override
    public String toString() {
        return label;
    }

    private static EditorLanguage mainframe(SourceLanguage language, String style) {
        return new EditorLanguage(language.label(), style, Optional.of(language));
    }

    private static EditorLanguage plain(String label, String style) {
        return new EditorLanguage(label, style, Optional.empty());
    }

    static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }
}
