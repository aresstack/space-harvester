package com.aresstack.spaceharvester.application.enrichment;

/**
 * Gliederung einer Seite: Überschriften mit Ebene, Position und Wortzahl ihres Abschnitts, dazu die Wortzahl der Seite.
 * Ergebnis {@code {"words":n,"sections":[{"heading","level","charStart","charEnd","words"}]}}.
 */
public class OutlineProcessor implements EnrichmentProcessor {

    @Override
    public String id() {
        return "outline";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        StringBuilder json = new StringBuilder("{\"words\":").append(words(text.text())).append(",\"sections\":[");
        boolean first = true;
        for (PageText.Section section : text.sections()) {
            json.append(first ? "" : ",")
                    .append("{\"heading\":").append(JsonText.quote(section.heading()))
                    .append(",\"level\":").append(section.level())
                    .append(",\"charStart\":").append(section.start())
                    .append(",\"charEnd\":").append(section.end())
                    .append(",\"words\":").append(words(text.text().substring(section.start(), section.end())))
                    .append('}');
            first = false;
        }
        return EnrichmentOutput.of(json.append("]}").toString());
    }

    static int words(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean letter = Character.isLetterOrDigit(text.charAt(i));
            if (letter && !inWord) {
                words++;
            }
            inWord = letter;
        }
        return words;
    }
}
