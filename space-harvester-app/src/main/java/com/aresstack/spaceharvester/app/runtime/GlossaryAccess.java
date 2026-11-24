package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import com.aresstack.spaceharvester.application.glossary.GlossaryOverview;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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
     * @param reference     geprüftes Glossar, das den globalen Begriff nennt, oder {@code null}
     */
    record Entry(String id, String term, String normalized, String sourceId, String spaceKey, RelationOrigin origin,
                 ReviewStatus status, List<RelationEvidence> evidence, boolean evidenceStale, String explanation,
                 String generation, Instant createdAt, Instant updatedAt, Instant reviewedAt, long revision,
                 Reference reference) {

        /** Ohne Quelle aus einem geprüften Glossar. */
        public Entry(String id, String term, String normalized, String sourceId, String spaceKey,
                     RelationOrigin origin, ReviewStatus status, List<RelationEvidence> evidence,
                     boolean evidenceStale, String explanation, String generation, Instant createdAt,
                     Instant updatedAt, Instant reviewedAt, long revision) {
            this(id, term, normalized, sourceId, spaceKey, origin, status, evidence, evidenceStale, explanation,
                    generation, createdAt, updatedAt, reviewedAt, revision, null);
        }

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(status, "status");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Quelle eines Begriffs aus einem geprüften Glossar (ADR 0019, Nachtrag 23.11.2026).
     *
     * @param title   Titel der Quelle
     * @param url     Fundstelle
     * @param wording Begriff im Wortlaut der Quelle
     * @param page    Seite der Quelle
     */
    record Reference(String title, String url, String wording, int page) {
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
    default List<JobStatus> classify(String sourceId, String spaceKey) {
        return classify(sourceId, spaceKey, null);
    }

    /**
     * Wie {@link #classify(String, String)}, mit {@code pageId} nur über diese Seite samt Unterseiten (D4.2): der Lauf
     * ergänzt nur, Einträge gelten weiter für Quelle und Space der Seite.
     */
    List<JobStatus> classify(String sourceId, String spaceKey, String pageId);

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
     * Beobachter für alle sichtbaren Aufträge dieser App (ADR 0027): jeder neue Stand und jedes Ereignis aus beliebigem
     * Thread, statt dass der Reiter in Abständen liest. Der Reiter liest neu, wenn „Fachbegriffe ermitteln“, „Aussagen
     * analysieren“ oder „Aussagen prüfen“ endet, und meldet die bewusst ausgelassene Modellphase
     * ({@code GLOSSARY_MODEL_SKIPPED}). Erst anmelden, dann {@link #classification}.
     *
     * @return meldet den Beobachter wieder ab
     */
    default Runnable watchJobs(com.aresstack.spaceharvester.application.jobs.JobListener listener) {
        return () -> {
        };
    }

    /**
     * Eine Zeile der fertigen Sicht des Reiters ({@code GlossaryOverview.ViewRow}): der Eintrag mit seiner Wichtigkeit
     * in der Auswahl ({@code null}, wenn die Ableitung ihn dort nicht kennt) und seiner Einordnung.
     */
    record Row(Entry entry, GlossaryDerivation.Importance importance, GlossaryDerivation.Category category,
               com.aresstack.spaceharvester.application.glossary.GlossaryEntry.Classification classification,
               RelationEvidence definition) {

        public Row {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(category, "category");
        }

        /** Ohne Begründung der Einordnung und ohne Vorschlag zur Begriffsbestimmung. */
        public Row(Entry entry, GlossaryDerivation.Importance importance, GlossaryDerivation.Category category) {
            this(entry, importance, category, null, null);
        }
    }

    /** Eine Zeile im Protokoll eines Eintrags ({@code Glossary#history}): was, wer, wann, mit welchem Status. */
    record History(String action, String actor, Instant at, ReviewStatus status) {
    }

    /** Ordnet den Eintrag von Hand ein; danach ändert kein Lauf die Einordnung mehr. */
    Entry categorize(String id, GlossaryDerivation.Category category, long revision);

    /** Das Protokoll des Eintrags, älteste zuerst. */
    List<History> history(String id);

    /**
     * Einzelheiten eines Begriffs aus der gehaltenen Sicht ({@code View#term}), ohne neues Rechnen; leer, wenn die
     * Ableitung ihn in der Auswahl nicht kennt.
     */
    default Optional<GlossaryDerivation.Term> term(Overview overview, String normalized) {
        return overview == null || overview.held() == null ? Optional.empty() : overview.held().term(normalized);
    }

    /**
     * Die fertige Sicht des Reiters ({@code GlossaryOverview.View}): sortiert, gefiltert und gezählt vom Core.
     *
     * @param held die Sicht des Core zum Verfeinern und für die Einzelheiten je Begriff; {@code null} in Tests
     */
    record Overview(List<Row> rows, Map<GlossaryDerivation.Category, Integer> byCategory,
                    Map<ReviewStatus, Integer> byStatus, int pages, int stalePages, GlossaryOverview.View held) {

        public Overview {
            rows = List.copyOf(rows);
            byCategory = Map.copyOf(byCategory);
            byStatus = Map.copyOf(byStatus);
        }
    }

    /**
     * Volle Sicht über die links gewählte Auswahl ({@code GlossaryOverview#view} mit {@code PageSelection}): bei neuer
     * Auswahl und am Ende eines Auftrags. {@code selection} {@code null} heißt alle Quellen.
     *
     * @throws java.util.concurrent.CancellationException sobald {@code cancelled} zutrifft
     */
    Overview overview(Selection selection, GlossaryOverview.Query query, BooleanSupplier cancelled);

    /**
     * Verfeinern mit der gehaltenen Sicht ({@code GlossaryOverview#view} mit voriger Sicht): andere Suche, anderer
     * Filter oder eine Meldung des Glossars; die Ableitung wird nicht neu gerechnet.
     */
    Overview refine(Overview previous, GlossaryOverview.Query query, BooleanSupplier cancelled);

    /** Kontext einer Belegstelle, leer wenn die Seite fehlt, nicht sichtbar oder inzwischen anders ist. */
    Optional<TermQueries.Snippet> snippet(RelationEvidence evidence, int chars);

    /** Titel der Seite eines Belegs, wenn sie im Cache liegt und sichtbar ist. */
    Optional<String> pageTitle(String sourceId, String pageId);
}
