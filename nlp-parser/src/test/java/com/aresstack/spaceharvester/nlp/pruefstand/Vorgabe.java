package com.aresstack.spaceharvester.nlp.pruefstand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handvorgabe eines Textsatzes ({@code vorgabe.tsv}, ADR 0032): je erwartete Aussage Verb-Grundform, Handelnder,
 * Betroffenes, Empfänger und die Merkmale Verneinung, Verbindlichkeit, Zeitbezug. Mehrere Zeilen mit derselben ID
 * sind alternative Lesarten; eine davon genügt.
 */
record Vorgabe(List<Erwartung> erwartungen) {

    /** Rollen, deren Besetzung „vollständig richtig“ entscheidet. Umstände, Zeit, Mittel zählen nicht. */
    enum Rolle {
        HANDELNDER,
        BETROFFENES,
        EMPFAENGER
    }

    /**
     * Eine erwartete Aussage mit ihren Lesarten.
     *
     * @param id     Kennung ({@code B07})
     * @param text   Text des Satzes ({@code B})
     * @param satz   Originalsatz ab 1
     * @param lesarten mindestens eine Lesart
     */
    record Erwartung(String id, String text, int satz, List<Lesart> lesarten) {
    }

    /**
     * Eine Lesart einer erwarteten Aussage.
     *
     * @param verb            erwartete Grundform des Vollverbs mit Partikel
     * @param rollen          erwartete Beteiligte je Rolle (leere Liste = nichts ausgedrückt)
     * @param verneint        die Aussage ist verneint
     * @param verbindlichkeit {@code -}, {@code MUSS}, {@code SOLL}, {@code KANN}, {@code DARF}
     * @param zeitbezug       {@code GILT}, {@code GEPLANT}, {@code FRUEHER}, {@code VERWORFEN}
     * @param bemerkung       Erläuterung, frei
     */
    record Lesart(String verb, Map<Rolle, List<Beteiligter>> rollen, boolean verneint, String verbindlichkeit,
                  String zeitbezug, String bemerkung) {
    }

    /**
     * Ein erwarteter Beteiligter: gleichbedeutende Formen ({@code A|B}), vertretbar ergänzt ({@code ?X}).
     *
     * @param formen      Formen in Kleinschreibung; {@code §v} steht für einen Teilsatz mit Kopf {@code v},
     *                    {@code ~X} für „darf auch als andere Ergänzung stehen“
     * @param vertretbar  {@code ?X}: darf fehlen; „streng“ verlangt, dass er fehlt
     */
    record Beteiligter(List<String> formen, boolean vertretbar) {

        /** Ob {@code name} (klein) eine der Formen ist; {@code ~X} zählt hier als {@code X}. */
        boolean passt(String name) {
            for (String form : formen) {
                String bare = form.startsWith("~") ? form.substring(1) : form;
                if (bare.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /** Formen, die auch als beliebige andere Ergänzung stehen dürfen ({@code ~X}), ohne Tilde. */
        List<String> auchAlsErgaenzung() {
            return formen.stream().filter(form -> form.startsWith("~")).map(form -> form.substring(1)).toList();
        }
    }

    static Vorgabe lesen(String tsv) {
        Map<String, Erwartung> byId = new LinkedHashMap<>();
        for (String line : tsv.split("\r?\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 10) {
                throw new IllegalArgumentException("Vorgabe braucht 10 Spalten: " + line);
            }
            Map<Rolle, List<Beteiligter>> rollen = new LinkedHashMap<>();
            rollen.put(Rolle.HANDELNDER, beteiligte(f[4]));
            rollen.put(Rolle.BETROFFENES, beteiligte(f[5]));
            rollen.put(Rolle.EMPFAENGER, beteiligte(f[6]));
            Lesart lesart = new Lesart(f[3].trim(), rollen, "ja".equals(f[7].trim()), f[8].trim(), f[9].trim(),
                    f.length > 10 ? f[10].trim() : "");
            String id = f[0].trim();
            int satz = Integer.parseInt(f[2].trim());
            Erwartung vorhanden = byId.get(id);
            if (vorhanden == null) {
                byId.put(id, new Erwartung(id, f[1].trim(), satz, new ArrayList<>(List.of(lesart))));
            } else {
                if (vorhanden.satz() != satz || !vorhanden.text().equals(f[1].trim())) {
                    throw new IllegalArgumentException("Lesarten von " + id + " in verschiedenen Sätzen");
                }
                vorhanden.lesarten().add(lesart);
            }
        }
        return new Vorgabe(byId.values().stream()
                .map(e -> new Erwartung(e.id(), e.text(), e.satz(), List.copyOf(e.lesarten()))).toList());
    }

    List<Erwartung> fuer(String text) {
        return erwartungen.stream().filter(e -> e.text().equals(text)).toList();
    }

    private static List<Beteiligter> beteiligte(String feld) {
        String value = feld.trim();
        if (value.isEmpty() || "-".equals(value)) {
            return List.of();
        }
        List<Beteiligter> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String p = part.trim();
            boolean vertretbar = p.startsWith("?");
            if (vertretbar) {
                p = p.substring(1);
            }
            List<String> formen = new ArrayList<>();
            for (String form : p.split("\\|")) {
                formen.add(form.trim().toLowerCase(Locale.ROOT));
            }
            result.add(new Beteiligter(List.copyOf(formen), vertretbar));
        }
        return List.copyOf(result);
    }
}
