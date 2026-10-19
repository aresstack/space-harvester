package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manuelle und geprüfte Fakten (#182), nach dem Ablauf des Faktenspeichers im Enterprise AI Client: Eine Person legt
 * Fakten zu einer Seite an, bestätigt oder verwirft automatische und manuelle Fakten und entfernt manuelle wieder.
 * Kein Prozessorlauf berührt manuelle Fakten; geprüfte automatische bleiben mit alter Herkunft stehen, wenn ihr Ergebnis
 * neu berechnet wird ({@link EditedFactStore}). Jede Änderung steht im Protokoll.
 *
 * <p>Rechte: Lesen wie das Faktenblatt ({@code metadata} und {@code content} an der Seite, Abruf wie
 * {@link PageQueries#page}); Ändern verlangt zusätzlich {@code sync} an der Seite. Optimistisches Sperren über die
 * Revision des Eintrags ({@code 0}, solange niemand den Fakt geprüft hat); Bearbeiter ist
 * {@link AccessContext#jobOwner()}.
 */
public class FactEdits {

    /** Prozessorkennung in der Herkunft manueller Fakten. */
    public static final String MANUAL_PROCESSOR = "manual";
    /** Längste Payload eines manuellen Fakts in Zeichen. */
    public static final int MAX_PAYLOAD_CHARS = 16_384;
    /** Höchstens so viele Argumente hat ein manueller Fakt. */
    public static final int MAX_ARGUMENTS = 32;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AccessGuard access;
    private final PageQueries pages;
    private final Enrichments enrichments;
    private final FactStore facts;
    private final FactEditStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param enrichments für den Eingabe-Hash manueller Fakten oder {@code null}
     * @param facts       die Fakten mit Prüfablage ({@link EditedFactStore} über {@code store})
     */
    public FactEdits(AccessGuard access, PageQueries pages, Enrichments enrichments, FactStore facts,
                     FactEditStore store, Clock clock) {
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.enrichments = enrichments;
        this.facts = Objects.requireNonNull(facts, "facts");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Die Revision eines Fakts; {@code 0}, solange er nicht in der Prüfablage steht. */
    public long revision(String factId) {
        return store.find(factId).map(FactEdit::revision).orElse(0L);
    }

    /**
     * Legt einen manuellen Fakt an, ungeprüft, gegen den aktuellen Text der Seite.
     *
     * @throws InvalidQueryException bei unbekanntem Typ, ungültiger Payload, Rolle oder Argumenten
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code sync}
     */
    public synchronized Edited add(AccessContext caller, String sourceId, String pageId, NewFact fact) {
        Objects.requireNonNull(fact, "fact");
        DeliveredPage page = writable(caller, sourceId, pageId);
        String type = fact.type() == null ? null : fact.type().strip();
        if (!FactTypes.ALL.contains(type)) {
            throw new InvalidQueryException("type kennt nur " + String.join(", ", FactTypes.ALL));
        }
        String payload = payload(fact.payloadJson());
        List<MetadataFact.Argument> arguments = arguments(fact.arguments());
        String input = enrichments == null ? null : enrichments.inspect(page.snapshot()).inputSha256();
        Instant now = clock.instant();
        MetadataFact created;
        try {
            created = new MetadataFact(newId(), page.header().key(), type, fact.role(), payload, arguments,
                    new MetadataFact.Provenance(MANUAL_PROCESSOR, "", input == null ? "" : input,
                            page.snapshot().revision(), now), fact.anchor(), FactOrigin.MANUAL,
                    FactReview.UNREVIEWED);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException(e.getMessage());
        }
        String actor = caller.jobOwner();
        FactEdit edit = new FactEdit(created, 1, actor, now, null, null);
        store.write(List.of(edit), List.of(),
                List.of(new FactEditStore.AuditEntry(created.id(), created.page(), "add", actor, now,
                        created.review(), 1)));
        return new Edited(created, 1);
    }

    /**
     * Setzt den Prüfstatus eines Fakts der Seite. {@link FactReview#UNREVIEWED} nimmt einen automatischen Fakt wieder
     * aus der Prüfablage.
     *
     * @param review           neuer Status; {@code null} gilt nach der Rechteprüfung als ungültige Anfrage
     * @param expectedRevision die zuletzt gelesene Revision oder {@code null} ohne Prüfung
     * @throws UnknownFactException    ohne diesen Fakt an der Seite
     * @throws FactConflictException   wenn die Revision nicht passt
     */
    public synchronized Edited review(AccessContext caller, String sourceId, String pageId, String factId,
                                      FactReview review, Long expectedRevision) {
        DeliveredPage page = writable(caller, sourceId, pageId);
        if (review == null) {
            throw new InvalidQueryException("review kennt nur UNREVIEWED, CONFIRMED, REJECTED");
        }
        MetadataFact current = find(page, factId);
        Optional<FactEdit> stored = store.find(factId);
        long revision = stored.map(FactEdit::revision).orElse(0L);
        check(expectedRevision, revision);
        String actor = caller.jobOwner();
        Instant now = clock.instant();
        MetadataFact reviewed = current.withReview(review);
        if (reviewed.origin() == FactOrigin.AUTOMATIC && review == FactReview.UNREVIEWED) {
            if (stored.isPresent()) {
                store.write(List.of(), List.of(factId),
                        List.of(new FactEditStore.AuditEntry(factId, current.page(), "review", actor, now, review,
                                revision)));
            }
            return new Edited(reviewed, 0);
        }
        long next = revision + 1;
        FactEdit edit = new FactEdit(reviewed, next, stored.map(FactEdit::createdBy).orElse(actor),
                stored.map(FactEdit::createdAt).orElse(now), actor, now);
        store.write(List.of(edit), List.of(),
                List.of(new FactEditStore.AuditEntry(factId, current.page(), "review", actor, now, review,
                        next)));
        return new Edited(reviewed, next);
    }

    /**
     * Entfernt einen manuellen Fakt oder die geprüfte Kopie eines automatischen; automatische Fakten selbst lassen sich
     * nur verwerfen.
     *
     * @throws UnknownFactException  ohne diesen Fakt an der Seite
     * @throws InvalidQueryException bei einem ungeprüften automatischen Fakt
     * @throws FactConflictException wenn die Revision nicht passt
     */
    public synchronized void remove(AccessContext caller, String sourceId, String pageId, String factId,
                                    Long expectedRevision) {
        DeliveredPage page = writable(caller, sourceId, pageId);
        find(page, factId);
        FactEdit stored = store.find(factId).orElseThrow(() -> new InvalidQueryException(
                "Automatische Fakten lassen sich nur verwerfen (review=REJECTED), nicht entfernen"));
        check(expectedRevision, stored.revision());
        store.write(List.of(), List.of(factId), List.of(new FactEditStore.AuditEntry(factId, stored.fact().page(),
                "remove", caller.jobOwner(), clock.instant(), stored.fact().review(), stored.revision())));
    }

    /**
     * Das Protokoll eines Fakts der Seite, auch nachdem er entfernt wurde. Rechte wie das Faktenblatt.
     *
     * @throws UnknownFactException wenn es weder den Fakt noch Einträge im Protokoll gibt
     */
    public List<FactEditStore.AuditEntry> history(AccessContext caller, String sourceId, String pageId,
                                                  String factId) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, false);
        List<FactEditStore.AuditEntry> entries = store.history(factId).stream()
                .filter(entry -> entry.page().equals(page.header().key())).toList();
        if (entries.isEmpty() && facts.factsOf(page.header().key()).stream()
                .noneMatch(fact -> fact.id().equals(factId))) {
            throw new UnknownFactException();
        }
        return entries;
    }

    private DeliveredPage writable(AccessContext caller, String sourceId, String pageId) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.page(sourceId, page.header().metadata().partitionKey(), pageId),
                AccessKind.SYNC);
        return page;
    }

    private MetadataFact find(DeliveredPage page, String factId) {
        if (factId == null) {
            throw new UnknownFactException();
        }
        return facts.factsOf(page.header().key()).stream().filter(fact -> fact.id().equals(factId)).findFirst()
                .orElseThrow(UnknownFactException::new);
    }

    private static void check(Long expected, long revision) {
        if (expected != null && expected != revision) {
            throw new FactConflictException(revision);
        }
    }

    private static String payload(String payloadJson) {
        String text = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson.strip();
        if (text.length() > MAX_PAYLOAD_CHARS) {
            throw new InvalidQueryException("payload höchstens " + MAX_PAYLOAD_CHARS + " Zeichen");
        }
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new InvalidQueryException("payload ist kein JSON");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidQueryException("payload muss ein JSON-Objekt sein");
        }
        return JSON.writeValueAsString(node);
    }

    private static List<MetadataFact.Argument> arguments(List<NewArgument> given) {
        List<NewArgument> list = given == null ? List.of() : given;
        if (list.size() > MAX_ARGUMENTS) {
            throw new InvalidQueryException("höchstens " + MAX_ARGUMENTS + " Argumente");
        }
        Map<String, Integer> ordinals = new HashMap<>();
        List<MetadataFact.Argument> arguments = new ArrayList<>();
        try {
            for (NewArgument argument : list) {
                String role = Objects.requireNonNull(argument.role(), "role").strip();
                String value = FactTypes.normalize(Objects.requireNonNull(argument.value(), "value"));
                int ordinal = ordinals.merge(role, 1, Integer::sum) - 1;
                arguments.add(new MetadataFact.Argument(role, ordinal, value));
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidQueryException("Ungültiges Argument: " + e.getMessage());
        }
        return arguments;
    }

    private String newId() {
        byte[] bytes = new byte[16];
        String id;
        do {
            random.nextBytes(bytes);
            id = HexFormat.of().formatHex(bytes);
        } while (store.find(id).isPresent());
        return id;
    }

    /**
     * Ein neuer manueller Fakt.
     *
     * @param type        {@link FactTypes#ALL}
     * @param role        {@code DEFINES}, {@code MENTIONS} oder {@code null}
     * @param payloadJson JSON-Objekt oder {@code null} für {@code {}}
     * @param arguments   suchbare Argumente; Werte werden normalisiert, Ordnungszahlen je Rolle vergeben
     * @param anchor      Fundstelle im aktuellen Text oder {@code null}
     */
    public record NewFact(String type, String role, String payloadJson, List<NewArgument> arguments,
                          MetadataFact.Anchor anchor) {

        public NewFact {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }

    /** Ein Argument eines neuen Fakts. */
    public record NewArgument(String role, String value) {
    }

    /** Ein Fakt nach der Änderung mit seiner Revision ({@code 0} außerhalb der Prüfablage). */
    public record Edited(MetadataFact fact, long revision) {
    }
}
