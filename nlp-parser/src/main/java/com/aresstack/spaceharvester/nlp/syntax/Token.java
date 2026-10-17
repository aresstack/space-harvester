package com.aresstack.spaceharvester.nlp.syntax;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Ein Wort (syntaktisches Token im Sinne von UD) eines Satzes. Felder, die keine Schicht geliefert hat, sind
 * {@code null} oder leer und werden nie geraten.
 *
 * @param id     Position im Satz ab 1, stabil innerhalb des Satzes (CoNLL-U {@code ID})
 * @param form   Wortform; bei Teilen eines Mehrworttokens die Teilform ({@code an}, {@code dem})
 * @param start  Zeichenposition im Dokumenttext (inklusive); Teile eines Mehrworttokens tragen dessen Spanne
 * @param end    Zeichenposition im Dokumenttext (exklusive)
 * @param lemma  Grundform oder {@code null}
 * @param upos   universelle Wortart (UD UPOS) oder {@code null}
 * @param xpos   sprachspezifische Wortart (STTS) oder {@code null}
 * @param feats  morphologische Merkmale ({@code Case=Dat}), leer wenn keine Schicht sie liefert
 * @param head   Kopf ({@code 0} = Wurzel) oder {@link #NO_HEAD} vor dem Parsen
 * @param deprel UD-Relation ({@code nsubj}, {@code obl:arg}) oder {@code null} vor dem Parsen
 */
public record Token(int id, String form, int start, int end, String lemma, String upos, String xpos,
                    Map<String, String> feats, int head, String deprel) {

    /** Kopf eines noch nicht geparsten Tokens. */
    public static final int NO_HEAD = -1;

    public Token {
        Objects.requireNonNull(form, "form");
        if (id < 1) {
            throw new IllegalArgumentException("Token-ID beginnt bei 1: " + id);
        }
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Ungültige Spanne " + start + ".." + end);
        }
        feats = feats == null ? Map.of() : Map.copyOf(new TreeMap<>(feats));
    }

    /** Ein Token vor Wortart und Parse. */
    public static Token word(int id, String form, int start, int end) {
        return new Token(id, form, start, end, null, null, null, Map.of(), NO_HEAD, null);
    }

    public Token withTags(String lemma, String upos, String xpos, Map<String, String> feats) {
        return new Token(id, form, start, end, lemma, upos, xpos, feats, head, deprel);
    }

    public Token withDependency(int head, String deprel) {
        return new Token(id, form, start, end, lemma, upos, xpos, feats, head, deprel);
    }

    public boolean parsed() {
        return head != NO_HEAD && deprel != null;
    }

    /** Merkmal {@code name} ({@code Case}) oder {@code null}. */
    public String feature(String name) {
        return feats.get(name);
    }

    /** Hauptrelation ohne Untertyp: {@code obl} für {@code obl:arg}. */
    public String baseRelation() {
        if (deprel == null) {
            return null;
        }
        int colon = deprel.indexOf(':');
        return colon < 0 ? deprel : deprel.substring(0, colon);
    }

    public boolean hasUpos(String... values) {
        for (String value : values) {
            if (value.equals(upos)) {
                return true;
            }
        }
        return false;
    }

    /** Merkmale im CoNLL-U-Format ({@code Case=Dat|Number=Sing}) oder {@code _}. */
    public String featsText() {
        if (feats.isEmpty()) {
            return "_";
        }
        StringBuilder text = new StringBuilder();
        new TreeMap<>(feats).forEach((key, value) -> {
            if (!text.isEmpty()) {
                text.append('|');
            }
            text.append(key).append('=').append(value);
        });
        return text.toString();
    }
}
