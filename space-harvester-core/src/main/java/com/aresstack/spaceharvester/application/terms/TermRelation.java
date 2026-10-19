package com.aresstack.spaceharvester.application.terms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Eine typisierte Beziehung zwischen zwei Begriffen (#120, ADR 0019). Begriffe sind Wortlaute, keine Konzepte; eine
 * Beziehung braucht keine Fundstelle im Bestand.
 *
 * @param id           stabile, URL-sichere Kennung, abgeleitet aus {@link #key}; derselbe Schlüssel ergibt dieselbe
 *                     Beziehung, darum gibt es keine Dubletten
 * @param fromTerm     Begriff im Wortlaut, bei {@link RelationType#ABBREVIATION} die Abkürzung
 * @param toTerm       Begriff im Wortlaut, bei {@link RelationType#ABBREVIATION} die Langform
 * @param type         Art
 * @param scope        Geltungsbereich
 * @param origin       Herkunft; ändert sich nie
 * @param status       Prüfstatus
 * @param evidence     Belege, bei Vorschlägen die Stellen, aus denen sie stammen
 * @param note         Notiz des Bearbeiters oder {@code null}
 * @param generation   bei Vorschlägen der Lauf, der sie zuletzt bestätigt hat, sonst {@code null}
 * @param evidenceStale ob ein bestätigter Vorschlag im letzten Lauf seiner Herkunft nicht mehr belegt war
 * @param createdBy    wer sie angelegt hat ({@code AccessContext#jobOwner()} oder Kennung des Laufs)
 * @param createdAt    wann
 * @param reviewedBy   wer zuletzt bestätigt, abgelehnt oder wiederhergestellt hat, oder {@code null}
 * @param reviewedAt   wann, oder {@code null}
 * @param revision     zählt jede Änderung, für optimistisches Sperren
 */
public record TermRelation(String id, String fromTerm, String toTerm, RelationType type, RelationScope scope,
                           RelationOrigin origin, ReviewStatus status, List<RelationEvidence> evidence, String note,
                           String generation, boolean evidenceStale, String createdBy, Instant createdAt,
                           String reviewedBy, Instant reviewedAt, long revision) {

    public TermRelation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromTerm, "fromTerm");
        Objects.requireNonNull(toTerm, "toTerm");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        evidence = List.copyOf(evidence);
    }

    /**
     * Identität einer Beziehung: normalisierte Endpunkte, Art und Geltungsbereich. Bei ungerichteten Arten zählen die
     * Endpunkte ohne Reihenfolge. Eine Sperre gilt für genau diesen Schlüssel, also nie für andere Bedeutungen
     * derselben Abkürzung oder andere Geltungsbereiche.
     */
    public static String key(String fromTerm, String toTerm, RelationType type, RelationScope scope) {
        String from = TermText.normalize(fromTerm);
        String to = TermText.normalize(toTerm);
        if (!type.directed() && from.compareTo(to) > 0) {
            String swap = from;
            from = to;
            to = swap;
        }
        return from + '\u0000' + to + '\u0000' + type.id() + '\u0000' + scope.key();
    }

    /** Kennung zum Schlüssel: {@code r} und 24 Hexadezimalziffern von SHA-256. */
    public static String idOf(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "r" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    public String key() {
        return key(fromTerm, toTerm, type, scope);
    }

    /** Ob die Beziehung den Begriff an einem Ende hat (verglichen über den Suchwert). */
    public boolean touches(String normalized) {
        return TermText.normalize(fromTerm).equals(normalized) || TermText.normalize(toTerm).equals(normalized);
    }

    /** Der andere Endpunkt zum Suchwert {@code normalized}. */
    public String other(String normalized) {
        return TermText.normalize(fromTerm).equals(normalized) ? toTerm : fromTerm;
    }

    TermRelation with(ReviewStatus status, String reviewedBy, Instant reviewedAt) {
        return new TermRelation(id, fromTerm, toTerm, type, scope, origin, status, evidence, note, generation,
                evidenceStale, createdBy, createdAt, reviewedBy, reviewedAt, revision + 1);
    }

    TermRelation proposedAgain(List<RelationEvidence> evidence, String generation) {
        return new TermRelation(id, fromTerm, toTerm, type, scope, origin, status, evidence, note, generation, false,
                createdBy, createdAt, reviewedBy, reviewedAt, revision + 1);
    }

    TermRelation stale(boolean stale) {
        return new TermRelation(id, fromTerm, toTerm, type, scope, origin, status, evidence, note, generation, stale,
                createdBy, createdAt, reviewedBy, reviewedAt, revision + 1);
    }
}
