package com.aresstack.spaceharvester.application.deadlines;

import java.util.regex.Pattern;

/**
 * Woran ein Antrag und eine Genehmigung im Wortlaut erkennbar sind (ADR 0035: ein Antrag ist nie eine Genehmigung).
 * Regex-Stufe und Validierung lesen dieselben Wörter; die Verneinung prüft der Aufrufer.
 *
 * <p>Eine Genehmigung braucht kein Genehmigungswort (Angelo, 23.11.2026): Spricht das Dokument die Verlängerung selbst
 * verbindlich aus („wird die Frist … verlängert“), zählt das wie „genehmigt“. Nicht dagegen eine bloße Möglichkeit
 * („kann verlängert werden“, „ist möglich“) oder ein Satz, der zugleich einen Antrag nennt. Ob der Aussteller dafür
 * zuständig ist, prüft der {@link DeadlineRuleEvaluator}, nicht der Wortlaut.
 */
public final class ApprovalWording {

    private static final Pattern GRANTED = Pattern.compile(
            "(?i)\\b(?:genehmigt|bewilligt|zugestimmt|eingewilligt|gewährt|erteilt)\\b");
    private static final Pattern REQUESTED = Pattern.compile("(?i)\\b(?:beantragt\\p{L}*|antrag\\p{L}*)\\b");
    /** Verbindlich ausgesprochene Verlängerung: „wird/werden … verlängert“, „verlängere ich/wir“; Datumspunkte zählen. */
    private static final Pattern BINDING_EXTENSION = Pattern.compile(
            "(?i)\\b(?:wird|werden)\\b(?:[^.;:]|(?<=\\d)\\.){0,160}?\\bverlängert\\b|\\bverlänger(?:e|n)\\s+(?:ich|wir)\\b");
    /** Nur eine Möglichkeit oder ein Soll, keine Entscheidung. */
    private static final Pattern POSSIBILITY = Pattern.compile(
            "(?i)\\b(?:kann|können|könnte|könnten|darf|dürfen|dürfte|möglich|soll|sollen|sollte|sollten)\\b");

    private ApprovalWording() {
    }

    /**
     * Ob der Text eine Genehmigung nennt: ein Genehmigungswort (genehmigt, bewilligt, zugestimmt …) oder eine
     * verbindlich ausgesprochene Verlängerung ohne Möglichkeitswort und ohne Antrag.
     */
    public static boolean namesGrant(String text) {
        if (text == null) {
            return false;
        }
        return GRANTED.matcher(text).find() || (BINDING_EXTENSION.matcher(text).find()
                && !POSSIBILITY.matcher(text).find() && !namesRequest(text));
    }

    /** Ob ein Antragswort im Text steht (beantragt, Antrag …). */
    public static boolean namesRequest(String text) {
        return text != null && REQUESTED.matcher(text).find();
    }
}
