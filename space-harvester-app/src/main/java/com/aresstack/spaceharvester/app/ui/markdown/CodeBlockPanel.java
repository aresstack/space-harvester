package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** Zeigt einen Codeblock mit Sprachmarke und Kopieraktion (aus askai-java8 {@code CodeBlockPanel}). */
final class CodeBlockPanel extends JPanel {

    CodeBlockPanel(String language, final String code, MarkdownTheme theme) {
        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(theme.getCodeBackground());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel languageLabel = new JLabel(language == null || language.trim().isEmpty() ? "code" : language.trim());
        // Die Sprachmarke stammt aus der Modellantwort: nie als Swing-HTML deuten (ein „<html>…“ bliebe Text).
        languageLabel.putClientProperty("html.disable", Boolean.TRUE);
        languageLabel.setForeground(theme.getMutedForeground());
        languageLabel.setHorizontalAlignment(SwingConstants.LEFT);
        MarkdownActionButton copyButton = new MarkdownActionButton(
                new MarkdownActionButton.CopyIcon(), "Code kopieren", theme.getMutedForeground(),
                () -> copy(code));
        header.add(languageLabel, BorderLayout.WEST);
        header.add(copyButton, BorderLayout.EAST);

        JTextArea textArea = new JTextArea(code == null ? "" : code);
        textArea.setEditable(false);
        textArea.setFont(theme.getCodeFont());
        textArea.setForeground(theme.getForeground());
        textArea.setBackground(theme.getCodeBackground());
        textArea.setSelectionColor(theme.getQuoteBorder());
        textArea.setSelectedTextColor(theme.getForeground());
        textArea.setCaretColor(theme.getForeground());
        textArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
        textArea.setLineWrap(false);

        // Display every line and reserve space for horizontal scrolling only.
        MarkdownScrollPane scrollPane = new MarkdownScrollPane(textArea, 200, 8, theme);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(text == null ? "" : text), null);
    }
}
