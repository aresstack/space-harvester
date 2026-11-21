package com.aresstack.enterpriseai.astu.acropolis.speech;

/**
 * Macht aus dem Markdown einer Antwort oder Seite den Text, der gesprochen wird. Die Oberfläche übergibt dieselbe
 * Deutung des Markdowns, mit der sie es anzeigt, damit nichts gesprochen wird, was nicht zu sehen ist; ohne sie gilt
 * {@link SpeechText#plainText}. Absätze bleiben durch Leerzeilen getrennt.
 */
public interface SpeechTextPreparation {

    /** Markdown → vorlesbarer Text; nie {@code null}. */
    String spokenText(String markdown);
}
