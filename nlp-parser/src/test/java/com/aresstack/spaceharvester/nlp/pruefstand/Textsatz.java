package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein Textsatz des Prüfstands (ADR 0032): Ordner {@code pruefstand/<name>/} mit {@code texte.tsv} (Text, Textart),
 * {@code texte/<T>.txt}, {@code vorgabe.tsv}, {@code varianten.tsv} und je Variante {@code varianten/<v>/<T>.conllu}
 * (gepinnte Ausgabe der echten Kette) sowie bei Vorstufen {@code <T>.vorstufe} (eine Zeile je vereinfachtem Satz:
 * {@code <Originalsatz ab 1>|<Kennzeichen>|<Satz>}). Fehlt einer Vorstufen-Variante ein Text, gilt dafür „heute“.
 *
 * @param name      Ordnername
 * @param rolle     {@code entwicklung} (Regeln werden daran entwickelt) oder {@code kontrolle} (nur gemessen)
 * @param texte     Texte in Reihenfolge
 * @param varianten Varianten, „heute“ zuerst
 * @param vorgabe   Handvorgabe
 */
record Textsatz(String name, String rolle, List<Text> texte, List<Variante> varianten, Vorgabe vorgabe) {

    static final String BASIS = "/com/aresstack/spaceharvester/nlp/pruefstand/";
    static final String HEUTE = "heute";

    record Text(String name, String art, String inhalt) {
    }

    /**
     * Eine Variante. Zusammengesetzte Varianten (dritte Spalte {@code gezielt:<Vorstufe>} oder {@code immer:<Vorstufe>})
     * haben keine eigenen Pins: sie führen die Analyse des Originals mit der einer Vorstufe je Originalsatz zusammen
     * (ADR 0032, FL3); {@code aus} ist dann die Vorstufe.
     */
    record Variante(String name, String beschreibung, String art, String aus) {

        Variante(String name, String beschreibung) {
            this(name, beschreibung, null, null);
        }

        boolean vorstufe() {
            return !HEUTE.equals(name);
        }

        boolean zusammengesetzt() {
            return aus != null;
        }

        /** Variante, deren Pins und Vorstufen-Zeilen gelten. */
        String pins() {
            return aus == null ? name : aus;
        }
    }

    /** Eine Zeile einer Vorstufe. */
    record VorstufenSatz(int originalsatz, String kennzeichen, String satz) {
    }

    /** Alle Textsätze aus {@code saetze.tsv}. */
    static List<Textsatz> alle() {
        List<Textsatz> result = new ArrayList<>();
        for (String[] row : tsv(resource(BASIS + "saetze.tsv"))) {
            result.add(laden(row[0], row[1]));
        }
        return result;
    }

    static Textsatz laden(String name, String rolle) {
        String dir = BASIS + name + "/";
        List<Text> texte = new ArrayList<>();
        for (String[] row : tsv(resource(dir + "texte.tsv"))) {
            texte.add(new Text(row[0], row[1], resource(dir + "texte/" + row[0] + ".txt")));
        }
        List<Variante> varianten = new ArrayList<>();
        for (String[] row : tsv(resource(dir + "varianten.tsv"))) {
            String[] zusammen = row.length > 2 && !row[2].isBlank() ? row[2].split(":", 2) : null;
            varianten.add(new Variante(row[0], row.length > 1 ? row[1] : "", zusammen == null ? null : zusammen[0],
                    zusammen == null ? null : zusammen[1]));
        }
        if (varianten.isEmpty() || !HEUTE.equals(varianten.get(0).name())) {
            throw new IllegalStateException(name + ": erste Variante muss „heute“ sein");
        }
        return new Textsatz(name, rolle, List.copyOf(texte), List.copyOf(varianten),
                Vorgabe.lesen(resource(dir + "vorgabe.tsv")));
    }

    /** Vorstufen-Zeilen eines Textes in einer Variante oder {@code null}, wenn die Variante den Text nicht umformt. */
    List<VorstufenSatz> vorstufe(Variante variante, Text text) {
        if (!variante.vorstufe()) {
            return null;
        }
        String content = optionalResource(BASIS + name + "/varianten/" + variante.pins() + "/" + text.name()
                + ".vorstufe");
        if (content == null) {
            return null;
        }
        List<VorstufenSatz> result = new ArrayList<>();
        for (String line : content.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\\|", 3);
            result.add(new VorstufenSatz(Integer.parseInt(f[0].trim()), f[1].trim(), f[2].trim()));
        }
        return List.copyOf(result);
    }

    /** Eingabe der Kette für einen Text in einer Variante: Originaltext oder die vereinfachten Sätze je Zeile. */
    String eingabe(Variante variante, Text text) {
        List<VorstufenSatz> saetze = vorstufe(variante, text);
        if (saetze == null) {
            return text.inhalt().stripTrailing();
        }
        return String.join("\n", saetze.stream().map(VorstufenSatz::satz).toList());
    }

    /** Pfad der gepinnten CoNLL-U relativ zum Textsatz ({@code varianten/heute/A.conllu}). */
    String pinPfad(Variante variante, Text text) {
        String v = vorstufe(variante, text) == null ? HEUTE : variante.pins();
        return "varianten/" + v + "/" + text.name() + ".conllu";
    }

    /** Die gepinnte Analyse eines Textes in einer Variante. */
    ParsedDocument analyse(Variante variante, Text text) {
        String pfad = pinPfad(variante, text);
        String conllu = resource(BASIS + name + "/" + pfad);
        return ConllU.read(new DocumentRef("pruefstand", name + "/" + text.name(), variante.name()), conllu,
                List.of(Component.gold("dependencies", pfad)));
    }

    static String resource(String path) {
        String content = optionalResource(path);
        if (content == null) {
            throw new IllegalStateException("Fehlt: " + path);
        }
        return content;
    }

    private static String optionalResource(String path) {
        try (InputStream input = Textsatz.class.getResourceAsStream(path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String[]> tsv(String content) {
        List<String[]> rows = new ArrayList<>();
        for (String line : content.split("\r?\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }
}
