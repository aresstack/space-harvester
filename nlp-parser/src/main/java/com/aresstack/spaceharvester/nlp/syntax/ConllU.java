package com.aresstack.spaceharvester.nlp.syntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CoNLL-U lesen und schreiben (https://universaldependencies.org/format.html), als Austausch- und Prüfformat: Export
 * einer Analyse zum Nachsehen und Einlesen von Hand annotierter Sätze für Tests. Leere Knoten ({@code 3.1}) werden
 * beim Lesen übersprungen; Mehrworttokens bleiben erhalten.
 */
public final class ConllU {

    private ConllU() {
    }

    /** Die Analyse als CoNLL-U, ein Block je Satz, mit {@code # sent_id} und {@code # text}. */
    public static String write(ParsedDocument document) {
        StringBuilder out = new StringBuilder();
        out.append("# newdoc id = ").append(document.ref().key()).append('\n');
        for (Sentence sentence : document.sentences()) {
            out.append("# sent_id = ").append(sentence.index() + 1).append('\n');
            out.append("# text = ").append(sentence.text().replace('\n', ' ')).append('\n');
            for (Token token : sentence.tokens()) {
                MultiwordToken multiword = sentence.multiwordToken(token.id());
                if (multiword != null && multiword.first() == token.id()) {
                    out.append(multiword.first()).append('-').append(multiword.last()).append('\t')
                            .append(multiword.form()).append("\t_\t_\t_\t_\t_\t_\t_\t")
                            .append(misc(document.text(), multiword.end())).append('\n');
                }
                out.append(token.id()).append('\t')
                        .append(token.form()).append('\t')
                        .append(field(token.lemma())).append('\t')
                        .append(field(token.upos())).append('\t')
                        .append(field(token.xpos())).append('\t')
                        .append(token.featsText()).append('\t')
                        .append(token.head() == Token.NO_HEAD ? "_" : String.valueOf(token.head())).append('\t')
                        .append(field(token.deprel())).append("\t_\t")
                        .append(multiword != null ? "_" : misc(document.text(), token.end())).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Liest CoNLL-U-Sätze zu einem Dokument. Der Dokumenttext entsteht aus den {@code # text}-Zeilen (ohne sie aus
     * den Formen und {@code SpaceAfter=No}), Sätze durch ein Leerzeichen getrennt; daraus ergeben sich die Spannen.
     */
    public static ParsedDocument read(DocumentRef ref, String conllu, List<Component> components) {
        StringBuilder text = new StringBuilder();
        List<Sentence> sentences = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : (conllu + "\n").split("\r?\n", -1)) {
            if (line.isBlank()) {
                if (!block.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    sentences.add(sentence(sentences.size(), block, text));
                    block.clear();
                }
            } else {
                block.add(line);
            }
        }
        return new ParsedDocument(ref, text.toString(), sentences, components);
    }

    private static Sentence sentence(int index, List<String> lines, StringBuilder documentText) {
        String declared = null;
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("#")) {
                if (line.startsWith("# text = ")) {
                    declared = line.substring("# text = ".length());
                }
            } else {
                String[] columns = line.split("\t", -1);
                if (columns.length != 10) {
                    throw new IllegalArgumentException("CoNLL-U-Zeile braucht 10 Spalten: " + line);
                }
                if (!columns[0].contains(".")) {
                    rows.add(columns);
                }
            }
        }
        String sentenceText = declared != null ? declared : reconstruct(rows);
        int sentenceStart = documentText.length();
        documentText.append(sentenceText);
        List<Token> tokens = new ArrayList<>();
        List<MultiwordToken> multiwords = new ArrayList<>();
        int cursor = 0;
        int rangeEnd = 0;
        int rangeStart = 0;
        int rangeFinish = 0;
        for (String[] row : rows) {
            if (row[0].contains("-")) {
                String[] range = row[0].split("-");
                int[] span = locate(sentenceText, row[1], cursor);
                cursor = span[1];
                rangeEnd = Integer.parseInt(range[1]);
                rangeStart = sentenceStart + span[0];
                rangeFinish = sentenceStart + span[1];
                multiwords.add(new MultiwordToken(Integer.parseInt(range[0]), rangeEnd, row[1], rangeStart,
                        rangeFinish));
                continue;
            }
            int id = Integer.parseInt(row[0]);
            int start;
            int end;
            if (id <= rangeEnd) {
                start = rangeStart;
                end = rangeFinish;
            } else {
                int[] span = locate(sentenceText, row[1], cursor);
                cursor = span[1];
                start = sentenceStart + span[0];
                end = sentenceStart + span[1];
            }
            tokens.add(new Token(id, row[1], start, end, value(row[2]), value(row[3]), value(row[4]),
                    feats(row[5]), "_".equals(row[6]) ? Token.NO_HEAD : Integer.parseInt(row[6]), value(row[7])));
        }
        return new Sentence(index, sentenceStart, sentenceStart + sentenceText.length(), sentenceText, tokens,
                multiwords);
    }

    /** Spanne von {@code form} ab {@code from}; fehlt sie im Text, bleibt sie leer an der aktuellen Stelle. */
    private static int[] locate(String text, String form, int from) {
        int at = text.indexOf(form, from);
        return at < 0 ? new int[]{from, from} : new int[]{at, at + form.length()};
    }

    private static String reconstruct(List<String[]> rows) {
        StringBuilder text = new StringBuilder();
        int rangeEnd = 0;
        for (String[] row : rows) {
            if (row[0].contains("-")) {
                rangeEnd = Integer.parseInt(row[0].split("-")[1]);
            } else if (Integer.parseInt(row[0]) <= rangeEnd) {
                continue;
            }
            text.append(row[1]);
            if (!row[9].contains("SpaceAfter=No")) {
                text.append(' ');
            }
        }
        return text.toString().strip();
    }

    private static Map<String, String> feats(String column) {
        Map<String, String> feats = new LinkedHashMap<>();
        if (!"_".equals(column)) {
            for (String pair : column.split("\\|")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    feats.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return feats;
    }

    private static String value(String column) {
        return "_".equals(column) ? null : column;
    }

    private static String field(String value) {
        return value == null || value.isEmpty() ? "_" : value;
    }

    private static String misc(String text, int end) {
        return end < text.length() && !Character.isWhitespace(text.charAt(end)) ? "SpaceAfter=No" : "_";
    }
}
