package com.aresstack.spaceharvester.application.deadlines;

import java.util.regex.Pattern;

/**
 * Woran ein Antrag und eine Genehmigung im Wortlaut erkennbar sind (ADR 0035: ein Antrag ist nie eine Genehmigung).
 * Regex-Stufe und Validierung lesen dieselben Wörter; die Verneinung prüft der Aufrufer.
 */
public final class ApprovalWording {

    private static final Pattern GRANTED = Pattern.compile(
            "(?i)\\b(?:genehmigt|bewilligt|zugestimmt|eingewilligt|gewährt|erteilt)\\b");
    private static final Pattern REQUESTED = Pattern.compile("(?i)\\b(?:beantragt\\p{L}*|antrag\\p{L}*)\\b");

    private ApprovalWording() {
    }

    /** Ob ein Genehmigungswort im Text steht (genehmigt, bewilligt, zugestimmt …). */
    public static boolean namesGrant(String text) {
        return text != null && GRANTED.matcher(text).find();
    }

    /** Ob ein Antragswort im Text steht (beantragt, Antrag …). */
    public static boolean namesRequest(String text) {
        return text != null && REQUESTED.matcher(text).find();
    }
}
