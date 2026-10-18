package com.aresstack.spaceharvester.application.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rechte für Endnutzer mit durchgereichter Anmeldung (#24, Teil C); alle anderen Aufrufer entscheidet die Basisrichtlinie
 * (in der Regel {@link CorpusReleasePolicy}). Ein Endnutzer darf nur, was beide zugleich erlauben:
 *
 * <ul>
 *   <li>die Korpusfreigabe des durchreichenden Clients für diesen Scope und diese Art und</li>
 *   <li>die Quelle selbst, gefragt mit dem Token des Endnutzers ({@link DelegatedSourceAccess#canSee}).</li>
 * </ul>
 *
 * <p>Die Anmeldeart schränkt keine Funktion ein (ADR 0010): Ein Endnutzer bekommt jede Art ({@link AccessKind}), die
 * die Korpusfreigabe des Clients erlaubt, auch Export und Abgleich, und sieht dabei genau die Spaces und Seiten, die
 * die Quelle ihm zeigt. Ein Export eines Endnutzers enthält deshalb nur seine lesbaren Seiten. Das Token gilt nur für
 * seine Quelle; jeder Scope einer anderen Quelle wird verweigert, ohne die Quelle zu fragen.
 *
 * <p>Antworten der Quelle werden je Token und Scope für {@code decisionTtl} gehalten, damit Listen und Bäume nicht jede
 * Seite bei jeder Anfrage erneut prüfen. Entzogene Rechte wirken also spätestens nach dieser Zeit. Unklare Antworten
 * werden nicht gehalten.
 */
public final class DelegatedAccessPolicy implements AccessPolicy {

    private final AccessPolicy base;
    private final Map<String, DelegatedSourceAccess> sources;
    private final Duration decisionTtl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<Key, Cached> decisions = new ConcurrentHashMap<>();

    /**
     * @param sources    Prüfung je Quellen-ID
     * @param maxEntries Obergrenze gehaltener Antworten; darüber wird der Zwischenspeicher geleert
     */
    public DelegatedAccessPolicy(AccessPolicy base, Map<String, DelegatedSourceAccess> sources, Duration decisionTtl,
                                 int maxEntries, Clock clock) {
        this.base = Objects.requireNonNull(base, "base");
        this.sources = Map.copyOf(sources);
        this.decisionTtl = Objects.requireNonNull(decisionTtl, "decisionTtl");
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
        if (context.kind() != AccessContext.PrincipalKind.END_USER) {
            return base.decide(context, scope, kind);
        }
        UserCredential credential = context.credential();
        DelegatedSourceAccess source = sources.get(scope.sourceId());
        if (source == null || !credential.sourceId().equals(scope.sourceId())) {
            return AccessDecision.DENY;
        }
        AccessDecision corpus = base.decide(AccessContext.technicalClient(context.via()), scope, kind);
        if (corpus != AccessDecision.ALLOW || scope.isSource()) {
            return corpus;
        }
        return cached(source, credential, scope);
    }

    /**
     * Ein Endnutzer sieht bei der Quelle nur, was sein eigenes Token zeigt; was die Zugangsdaten der Quelle sehen,
     * erhält er nie. Andere Aufrufer entscheidet die Basis.
     */
    @Override
    public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
        return context.kind() == AccessContext.PrincipalKind.END_USER ? AccessDecision.DENY
                : base.decideSourceWide(context, sourceId, kind);
    }

    private AccessDecision cached(DelegatedSourceAccess source, UserCredential credential, ResourceScope scope) {
        Key key = new Key(credential.fingerprint(), scope);
        Instant now = clock.instant();
        Cached hit = decisions.get(key);
        if (hit != null && now.isBefore(hit.expires())) {
            return hit.decision();
        }
        AccessDecision decision = source.canSee(credential, scope);
        if (decision == null) {
            return AccessDecision.UNDETERMINED;
        }
        if (decision != AccessDecision.UNDETERMINED && !decisionTtl.isZero()) {
            if (decisions.size() >= maxEntries) {
                decisions.values().removeIf(entry -> !now.isBefore(entry.expires()));
                if (decisions.size() >= maxEntries) {
                    decisions.clear();
                }
            }
            decisions.put(key, new Cached(decision, now.plus(decisionTtl)));
        }
        return decision;
    }

    private record Key(String fingerprint, ResourceScope scope) {
    }

    private record Cached(AccessDecision decision, Instant expires) {
    }
}
