package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Das Fachglossar aus Sicht der App (#122 Stufe 2, Spezifikation Abschnitt 2): dauerhaft gepflegte Glossareinträge
 * mit Herkunft (manuell oder KI) und Prüfstand (vorgeschlagen, bestätigt, abgelehnt), unabhängig voneinander, dazu
 * der manuell gestartete KI-Lauf „Fachbegriffe ermitteln“ als abbrechbarer Auftrag. Die Schnittstelle folgt dem
 * REST-Vertrag {@code /api/v1/glossary} des Dienstes; die App leitet nichts ab, der Core prüft Rechte, Revision und
 * Schutzregeln (manuelle bleiben, bestätigte bleiben bestätigt, abgelehnte kommen nicht wieder).
 *
 * <p>Alle Aufrufe außerhalb des EDT; jede Methode darf eine {@link RuntimeException} des Core werfen.
 */
public interface GlossaryAccess {

    /** Höchstens so viele Einträge je Abfrage. */
    int MAX_LIMIT = 200;

    /**
     * Ein Glossareintrag.
     *
     * @param id            stabile Kennung
     * @param term          Begriff im Wortlaut
     * @param normalized    normalisierte Schreibweise
     * @param sourceId      Geltungsbereich Quelle oder {@code null} für alle
     * @param spaceKey      Geltungsbereich Space oder {@code null}
     * @param origin        Herkunft: {@link RelationOrigin#MANUAL} oder {@link RelationOrigin#AI}
     * @param status        Prüfstand
     * @param evidence      Belegstellen im Begriffsindex
     * @param evidenceStale ob sich Fundstellen seit dem Beleg geändert haben („Beleg veraltet“)
     * @param explanation   fachliche Erläuterung oder {@code null}
     * @param generation    Modell-/Promptversion bei KI-Erzeugung, sonst {@code null}
     * @param reviewedAt    Zeitpunkt der letzten Benutzerentscheidung oder {@code null}
     * @param revision      Stand für das optimistische Sperren
     */
    record Entry(String id, String term, String normalized, String sourceId, String spaceKey, RelationOrigin origin,
                 ReviewStatus status, List<RelationEvidence> evidence, boolean evidenceStale, String explanation,
                 String generation, Instant createdAt, Instant updatedAt, Instant reviewedAt, long revision) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(status, "status");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Eine Abfrage; {@code null} heißt überall „ohne Einschränkung“.
     *
     * @param q          Suchtext über Begriff und Schreibweise
     * @param startsWith Anfangsbuchstabe
     * @param limit      1 bis {@link #MAX_LIMIT}
     * @param cursor     {@code nextCursor} der vorigen Seite oder {@code null}
     */
    record Search(String q, ReviewStatus status, RelationOrigin origin, String sourceId, String spaceKey,
                  String startsWith, int limit, String cursor) {
    }

    /**
     * Eine Seite Einträge.
     *
     * @param nextCursor  zum Weiterlesen oder {@code null}
     * @param aiAvailable ob ein KI-Dienst für „Fachbegriffe ermitteln“ eingerichtet ist; ohne bleibt die Pflege von
     *                    Hand
     */
    record Page(List<Entry> entries, String nextCursor, boolean aiAvailable) {

        public Page {
            entries = List.copyOf(entries);
        }
    }

    /** Ein manuell angelegter Fachbegriff; er gilt sofort als bestätigt. */
    record NewEntry(String term, String sourceId, String spaceKey, String explanation) {

        public NewEntry {
            Objects.requireNonNull(term, "term");
        }
    }

    /** Der Eintrag wurde inzwischen geändert (Revision passt nicht): neu laden. */
    class ConflictException extends RuntimeException {

        public ConflictException(String id) {
            super("Der Eintrag " + id + " wurde inzwischen geändert");
        }
    }

    /** Kein KI-Dienst eingerichtet; „Fachbegriffe ermitteln“ geht nicht. */
    class AiUnavailableException extends RuntimeException {

        public AiUnavailableException() {
            super("Kein KI-Dienst eingerichtet");
        }
    }

    /** Die Quelle ist nicht für externe Verarbeitung freigegeben. */
    class NotAllowedException extends RuntimeException {

        public NotAllowedException(String sourceId) {
            super("Die Quelle " + sourceId + " ist nicht für die Verarbeitung durch den KI-Dienst freigegeben");
        }
    }

    /** Einträge nach Begriff, von {@code cursor} an. */
    Page search(Search search);

    /** Legt einen manuellen, bestätigten Eintrag an ({@code POST}). */
    Entry add(NewEntry entry);

    /** Ändert die Erläuterung ({@code PUT /{id}/explanation}); {@link ConflictException} bei fremdem Stand. */
    Entry explanation(String id, String explanation, long revision);

    /** Bestätigen; ein bestätigter KI-Vorschlag bleibt als KI-erzeugt erkennbar. */
    Entry confirm(String id, long revision);

    /** Ablehnen; bleibt als abgelehnt sichtbar und kommt bei keinem KI-Lauf wieder. */
    Entry reject(String id, long revision);

    /** Nimmt eine Ablehnung bewusst zurück: ein KI-Vorschlag ist wieder vorgeschlagen, ein manueller bestätigt. */
    Entry restore(String id, long revision);

    /** Entfernen: ein manueller Eintrag ist danach weg (leer), ein KI-Eintrag abgelehnt (der Eintrag). */
    Optional<Entry> remove(String id, long revision);

    /**
     * Startet „Fachbegriffe ermitteln“ für alle berechtigten Quellen ({@code null, null}), eine Quelle oder einen Space:
     * je Quelle ein Auftrag.
     *
     * @throws AiUnavailableException ohne KI-Dienst
     * @throws NotAllowedException    wenn keine Quelle im Umfang freigegeben ist
     */
    List<JobStatus> classify(String sourceId, String spaceKey);

    /** Stand eines Auftrags; {@code progress} zählt gefragte und beantwortete Kandidaten und geschriebene Einträge. */
    JobStatus classification(String jobId);

    /** Bricht den Lauf ab; was schon vorgeschlagen ist, bleibt, Benutzerentscheidungen ändert er nie. */
    JobStatus cancelClassification(String jobId);

    /**
     * Beobachter: meldet jede Änderung des Glossars, auch die Teilergebnisse eines laufenden „Fachbegriffe ermitteln“,
     * aus beliebigem Thread.
     *
     * @return meldet den Beobachter wieder ab
     */
    default Runnable observe(Runnable onChange) {
        return () -> {
        };
    }

    /**
     * Beobachter für die Aufträge „Fachbegriffe ermitteln“ (ADR 0027): meldet jeden neuen Stand eines Auftrags aus
     * beliebigem Thread, statt dass der Reiter ihn in Abständen liest. Erst anmelden, dann {@link #classification}.
     *
     * @return meldet den Beobachter wieder ab
     */
    default Runnable observeJobs(java.util.function.Consumer<JobStatus> onChange) {
        return () -> {
        };
    }

    /** Kontext einer Belegstelle, leer wenn die Seite fehlt, nicht sichtbar oder inzwischen anders ist. */
    Optional<TermQueries.Snippet> snippet(RelationEvidence evidence, int chars);

    /** Titel der Seite eines Belegs, wenn sie im Cache liegt und sichtbar ist. */
    Optional<String> pageTitle(String sourceId, String pageId);
}
