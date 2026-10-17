package com.aresstack.spaceharvester.nlp.training;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein CoNLL-U-Satz: Kommentarzeilen und alle Token-Zeilen in Dateireihenfolge, einschließlich Mehrwort-Zeilen
 * ({@code 6-7}) und leerer Knoten ({@code 3.1}). Spalten nach https://universaldependencies.org/format.html.
 */
public record ConlluSentence(List<String> comments, List<String[]> lines) {

    public static final int ID = 0;
    public static final int FORM = 1;
    public static final int LEMMA = 2;
    public static final int UPOS = 3;
    public static final int XPOS = 4;
    public static final int FEATS = 5;
    public static final int HEAD = 6;
    public static final int DEPREL = 7;
    public static final int DEPS = 8;
    public static final int MISC = 9;
    public static final int COLUMNS = 10;

    public ConlluSentence {
        comments = List.copyOf(comments);
        lines = List.copyOf(lines);
    }

    /** Nur die syntaktischen Wörter (ganzzahlige ID), ohne Mehrwort-Zeilen und leere Knoten. */
    public List<String[]> words() {
        return lines.stream().filter(ConlluSentence::isWord).toList();
    }

    public static boolean isWord(String[] columns) {
        return columns[ID].chars().allMatch(Character::isDigit);
    }

    public static boolean isMultiwordToken(String[] columns) {
        return columns[ID].contains("-");
    }

    public static boolean isEmptyNode(String[] columns) {
        return columns[ID].contains(".");
    }

    public static List<ConlluSentence> read(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<ConlluSentence> read(BufferedReader reader, String source) throws IOException {
        List<ConlluSentence> sentences = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        List<String[]> lines = new ArrayList<>();
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (number == 1 && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line.isBlank()) {
                // Ein Kommentarblock ohne Satz (Dateikopf) gehört zu keinem Satz und fällt weg
                if (!lines.isEmpty()) {
                    sentences.add(new ConlluSentence(comments, lines));
                }
                comments = new ArrayList<>();
                lines = new ArrayList<>();
            } else if (line.startsWith("#")) {
                if (!lines.isEmpty()) {
                    throw new ConlluException(source + ":" + number + ": Kommentar innerhalb eines Satzes");
                }
                comments.add(line);
            } else {
                String[] columns = line.split("\t", -1);
                if (columns.length != COLUMNS) {
                    throw new ConlluException(source + ":" + number + ": " + columns.length + " statt 10 Spalten");
                }
                lines.add(columns);
            }
        }
        if (!lines.isEmpty()) {
            sentences.add(new ConlluSentence(comments, lines));
        }
        return sentences;
    }

    public static void write(Path file, List<ConlluSentence> sentences) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (ConlluSentence sentence : sentences) {
                    writer.write(sentence.format());
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Der Satz als CoNLL-U-Block, jede Zeile mit {@code \n} abgeschlossen, ohne die trennende Leerzeile. */
    public String format() {
        StringBuilder text = new StringBuilder();
        comments.forEach(comment -> text.append(comment).append('\n'));
        lines.forEach(columns -> text.append(String.join("\t", columns)).append('\n'));
        return text.toString();
    }

    public String sentenceId() {
        return comments.stream()
                .filter(comment -> comment.startsWith("# sent_id = "))
                .map(comment -> comment.substring("# sent_id = ".length()).trim())
                .findFirst()
                .orElse("");
    }

    /** Ungültige CoNLL-U-Datei. */
    public static final class ConlluException extends RuntimeException {
        public ConlluException(String message) {
            super(message);
        }
    }
}
